package io.github.kormcrete.swingui.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kormcrete.swingui.driver.DomainStepFailed;
import io.github.kormcrete.swingui.driver.Driver;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.DriverSettings;
import io.github.kormcrete.swingui.driver.EdtClock;
import io.github.kormcrete.swingui.driver.LaunchMode;
import io.github.kormcrete.swingui.driver.PendingDialog;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.StepCall;
import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.outcome.Outcomes;
import io.github.kormcrete.swingui.driver.outcome.UiReading;
import io.github.kormcrete.swingui.driver.platform.Watch;
import io.github.kormcrete.swingui.flow.refs.Refs;
import io.github.kormcrete.swingui.flow.refs.Resolution;
import io.github.kormcrete.swingui.flow.refs.RunValues;
import io.github.kormcrete.swingui.flow.verb.RememberVerb;
import io.github.kormcrete.swingui.flow.verb.VerbAction;
import io.github.kormcrete.swingui.spi.StepHost;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Runs a Flow's steps against a {@link StepHost}, collecting a FlowResult. Stops at the first failed step.
 * A verb runs through the host's driver; {@code navigate}, {@code close}, {@code loginAs} and {@code do} through the
 * host. A {@code do} step's result is its filled sentence, with one child result per step its body ran
 * ({@link StepResult#steps}). Every step is timed (wall time and its {@link EdtClock} split); with
 * {@link #diagnoseFailures} a failed step also carries {@link StepDiagnostics}, a failed {@code do} step on its failed
 * child.
 *
 * <p>With {@link #outcomes}, a run of a recorded test file, a step may use values of earlier steps ({@link Refs}): each
 * step that a later one refers to by its {@code id} is read before and after it ran, and what it changed is kept
 * ({@link Outcomes#diff}); a remember step keeps its value; every step's references are filled in just before it runs
 * ({@link Resolution#fill}), waiting for a value that comes late, and {@link StepResult#resolved} says what they stood
 * for. Without one (the live session's own steps, whose text is never escaped) the steps run as they are.
 */
public class FlowExecutor {

    /** How often a reference without a value is tried again while it waits. */
    public static final long LINK_POLL_MS = 100;

    /** What the app shows now, for the outcome of a step: the driver's {@code readUi}. */
    public interface OutcomeSource {
        UiReading read();
    }

    private FailureDiagnostics diagnostics;
    private OutcomeSource outcomes;
    private RunValues runValues;
    private Long linkWaitMs;

    /** Diagnose each failed step with {@code d} - for a replay, never for the live MCP session. */
    public FlowExecutor diagnoseFailures(FailureDiagnostics d) {
        this.diagnostics = d;
        return this;
    }

    /** Fills in references from earlier steps, reading the steps they name from {@code source} (see the class comment). */
    public FlowExecutor outcomes(OutcomeSource source) {
        this.outcomes = source;
        return this;
    }

    /** The values references use; by default each run gets its own, with a new {@code run.id}. */
    public FlowExecutor runValues(RunValues values) {
        this.runValues = values;
        return this;
    }

    /** How long a reference may wait for its value; by default the driver's expect timeout. */
    FlowExecutor linkWait(long millis) {
        this.linkWaitMs = millis;
        return this;
    }

    private long linkWait() {
        return linkWaitMs != null ? linkWaitMs : defaultLinkWait();
    }

    /** How long a reference waits for its value by default: the driver's expect timeout. */
    public static long defaultLinkWait() {
        Driver driver = Driver.current();
        return driver != null ? driver.settings().expectMillis : DriverSettings.defaults(LaunchMode.NORMAL).expectMillis;
    }

    /** A Verbs that cannot report capabilities (a recording or remote one) leaves them out. */
    private static List<String> capabilitiesOrNull(Verbs verbs) {
        try {
            return verbs.capabilities().lines();
        } catch (DriverException e) {
            if (e.reason() == Reason.CAPABILITY_GAP) return null;
            throw e;
        }
    }

    /** A Verbs that cannot watch real input (a recording or remote one) runs its steps unwatched. */
    private static Watch.Mark markOrNull(Verbs verbs) {
        try {
            return verbs.watch().mark();
        } catch (DriverException e) {
            if (e.reason() == Reason.CAPABILITY_GAP) return null;
            throw e;
        }
    }

    public FlowResult run(Flow flow, StepHost host) {
        FlowResult result = new FlowResult();
        result.flowName = flow.name;
        Verbs verbs = host.verbs();
        if (verbs != null) result.capabilities = capabilitiesOrNull(verbs);
        Links links = outcomes == null ? null : new Links(flow,
                runValues != null ? runValues : new RunValues(RunValues.newRunId(), LocalDateTime.now()));
        for (int i = 0; i < flow.steps.size(); i++) {
            Action recorded = flow.steps.get(i);
            Action action = recorded;
            Map<String, String> resolved = null;
            Watch.Mark mark = verbs == null ? null : markOrNull(verbs);
            EdtClock.Reading edtBefore = EdtClock.read();
            long started = System.nanoTime();
            StepResult step;
            try {
                refuseWhileADialogWaits(recorded, host);
                if (links != null) {
                    action = links.fill(recorded);
                    resolved = orNull(Resolution.resolved(recorded, links.values));
                    links.before(action);
                }
                VerbOutcome outcome = execute(action, host);
                if (links != null) links.after(i, action, outcome);
                step = StepResult.pass(i, action.describe());
                if (outcome != null) {
                    step.outcome = outcome;
                    step.pending = outcome.pending;
                    step.via = outcome.via;
                    step.warning = outcome.warning;
                    if (outcome.note != null) step.message = outcome.note;
                    domainStep(step, outcome);
                }
            } catch (AssertionError | RuntimeException e) {
                step = StepResult.fail(i, action.describe(), e.getMessage());
                if (links != null) links.forget();
                step.reason = reasonOf(e);
                if (e instanceof DriverException && !((DriverException) e).details().isEmpty()) {
                    step.details = ((DriverException) e).details();
                }
                if (e instanceof DomainStepFailed) domainStep(step, ((DomainStepFailed) e).outcome());
            }
            step.resolved = resolved;
            step.ms = (System.nanoTime() - started) / 1_000_000L;
            EdtClock.Reading edt = EdtClock.read().since(edtBefore);
            step.edtMs = edt.runMs();
            step.edtWaitMs = edt.waitMs();
            if (mark != null) {
                step.realInput = orNull(verbs.watch().realInputSince(mark));
                step.activations = orNull(verbs.watch().activationsSince(mark));
            }
            if (!step.passed && diagnostics != null) {
                final Action ran = action;
                StepResult child = failedChild(step);
                String label = StepNumbers.label(flow.steps, i);
                if (child == null) {
                    step.diagnostics = diagnose(() -> diagnostics.diagnose(ran, verbs, flow.name, label));
                } else {
                    String childLabel = label + "-" + (child.index + 1);
                    child.diagnostics = diagnose(() -> diagnostics.diagnose(targetsOf(child), verbs, flow.name, childLabel));
                }
            }
            result.steps.add(step);
            if (!step.passed) break; // stop at first failure
        }
        return result;
    }

    /**
     * One run's references: the values so far ({@link RunValues}) and, for each step a later one names, the reading
     * before it, kept until its last use so a value that comes late can be read again.
     */
    private final class Links {
        final Flow flow;
        final RunValues values;
        /** The ids some step refers to, each with the index of the last step that does. */
        final Map<String, Integer> lastUse = new HashMap<String, Integer>();
        final Map<String, UiReading> before = new HashMap<String, UiReading>();
        /** The reading before the step running now, when a later step names it. */
        UiReading pending;

        Links(Flow flow, RunValues values) {
            this.flow = flow;
            this.values = values;
            for (int i = 0; i < flow.steps.size(); i++) {
                try {
                    for (Refs.Ref r : Refs.of(flow.steps.get(i))) lastUse.put(r.id, i);
                } catch (IllegalArgumentException malformed) {
                    // the step fails on it when it fills its values
                }
            }
        }

        /** The step with its references filled in, waiting for a late value ({@link #fill(Action, RunValues, Map, OutcomeSource, long, Flow)}). */
        Action fill(Action a) {
            if (a instanceof RememberVerb && ((RememberVerb) a).as != null) {
                String problem = RecordedTestFiles.idProblem(((RememberVerb) a).as);
                if (problem != null) throw new DriverException(Reason.BAD_INPUT, "remember as '" + ((RememberVerb) a).as + "': " + problem);
            }
            return FlowExecutor.fill(a, values, before, outcomes, linkWait(), flow);
        }

        /** Reads the app before a step that a later step names. */
        void before(Action a) {
            pending = a.id != null && lastUse.containsKey(a.id) ? outcomes.read() : null;
        }

        /** Keeps what the step changed or remembered, and lets go of readings no later step needs. */
        void after(int index, Action a, VerbOutcome out) {
            if (pending != null) {
                before.put(a.id, pending);
                values.put(a.id, outcome(pending, outcomes.read(), out));
                pending = null;
            }
            if (a instanceof RememberVerb && out != null && out.note != null) values.remember(((RememberVerb) a).as, out.note);
            for (Map.Entry<String, Integer> e : lastUse.entrySet()) {
                if (e.getValue() == index) before.remove(e.getKey());
            }
        }

        void forget() {
            pending = null;
        }
    }

    /**
     * {@code a} with its references filled in from {@code values} ({@link Resolution#fill}). A reference to a step's
     * outcome that has no value yet is read again every {@link #LINK_POLL_MS} - what changed since the reading taken
     * before that step ({@code before}, by id; a step without one is not waited for) - until it has one or
     * {@code waitMillis} is over; then the step fails (NOT_FOUND), saying in words what the earlier step of
     * {@code steps} did not do. Never the value the recording saw.
     */
    public static Action fill(Action a, RunValues values, Map<String, UiReading> before, OutcomeSource source,
                              long waitMillis, Flow steps) {
        Long deadline = null;
        while (true) {
            try {
                return Resolution.fill(a, values);
            } catch (RunValues.UnresolvedReference e) {
                UiReading was = before.get(e.ref.id);
                long now = System.nanoTime();
                if (deadline == null) deadline = now + waitMillis * 1_000_000L;
                if (was == null || now - deadline >= 0) {
                    throw new DriverException(Reason.NOT_FOUND, Resolution.inWords(e.ref, steps));
                }
                try {
                    Thread.sleep(LINK_POLL_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new DriverException(Reason.TIMEOUT, "interrupted while waiting for " + e.ref.source);
                }
                // read again, what the step's own body returned stays
                JsonNode made = values.get(e.ref.id) == null ? null : values.get(e.ref.id).get(STEP);
                ObjectNode again = outcome(was, source.read(), null);
                if (made != null) again.set(STEP, made);
                values.put(e.ref.id, again);
            }
        }
    }

    /** Where a domain step's own values go in its outcome: {@code ${placeOrder.step.number}}. */
    public static final String STEP = "step";

    /**
     * What a step changed ({@link Outcomes#diff} of the readings {@code before} and {@code after} it) as JSON, with what a
     * domain step's body returned ({@link VerbOutcome#made} of {@code out}, null for none) under {@link #STEP}.
     */
    public static ObjectNode outcome(UiReading before, UiReading after, VerbOutcome out) {
        ObjectNode outcome = FlowJson.mapper().valueToTree(Outcomes.diff(before, after));
        if (out != null && out.made != null) outcome.set(STEP, FlowJson.mapper().valueToTree(out.made));
        return outcome;
    }

    private static <K, V> Map<K, V> orNull(Map<K, V> m) {
        return m.isEmpty() ? null : m;
    }

    private static StepDiagnostics diagnose(Supplier<StepDiagnostics> diagnosis) {
        try {
            return diagnosis.get();
        } catch (RuntimeException e) {
            StepDiagnostics d = new StepDiagnostics();
            d.problems.add("diagnosis failed: " + e);
            return d;
        }
    }

    /** A domain step's result: its filled sentence for its label, and a child result per step its body ran. */
    private static void domainStep(StepResult step, VerbOutcome outcome) {
        if (outcome.sentence != null) step.action = outcome.sentence;
        if (outcome.calls == null) return;
        step.steps = new ArrayList<StepResult>();
        for (int k = 0; k < outcome.calls.size(); k++) {
            StepCall c = outcome.calls.get(k);
            StepResult r = c.passed ? StepResult.pass(k, c.action) : StepResult.fail(k, c.action, c.message);
            r.json = c.json;
            r.ms = c.ms;
            if (c.reason != null) r.reason = c.reason.name();
            if (c.details != null && !c.details.isEmpty()) r.details = c.details;
            if (c.outcome != null) {
                r.outcome = c.outcome;
                r.pending = c.outcome.pending;
                r.via = c.outcome.via;
                r.warning = c.outcome.warning;
                if (c.outcome.note != null) r.message = c.outcome.note;
            }
            step.steps.add(r);
        }
    }

    /** The child of a failed {@code do} step that failed it (its last, when that one failed); else null. */
    private static StepResult failedChild(StepResult step) {
        if (step.steps == null || step.steps.isEmpty()) return null;
        StepResult last = step.steps.get(step.steps.size() - 1);
        return last.passed ? null : last;
    }

    /** A child's targets, read from its step JSON; none for an app step or a step that does not parse. */
    private static Map<String, Locator> targetsOf(StepResult child) {
        try {
            Action a = FlowJson.mapper().readValue(child.json, Action.class);
            if (a instanceof VerbAction) return ((VerbAction) a).targets();
        } catch (java.io.IOException | RuntimeException unreadable) {
            // nothing to diagnose by
        }
        return Collections.emptyMap();
    }

    /** The reason a failure is reported with. A host's refusal is a missing capability. */
    static String reasonOf(Throwable e) {
        if (e instanceof DriverException) return ((DriverException) e).reason().name();
        if (e instanceof UnsupportedOperationException) return Reason.CAPABILITY_GAP.name();
        return null;
    }

    private static List<String> orNull(List<String> entries) {
        return entries.isEmpty() ? null : entries;
    }

    /**
     * An app step cannot run while a message or confirmation waits for an answer: it would act behind a
     * modal a user could not get past, and pass for it. A verb checks its own target against the modal
     * that blocks it, and may be the step that answers it.
     */
    private static void refuseWhileADialogWaits(Action action, StepHost host) {
        if (isAppStep(action)) refuseAppStepWhileADialogWaits(host);
    }

    /**
     * Refuses an app step (navigate, close, loginAs) while {@code host} has a dialog waiting for an answer, as every
     * step of a recorded test is refused; a domain step's body asks it too before its own app steps.
     */
    public static void refuseAppStepWhileADialogWaits(StepHost host) {
        PendingDialog waiting = host.openDialog();
        if (waiting != null) {
            throw new AssertionError("a dialog is waiting for an answer: " + waiting.summary()
                    + " - answer it with the 'answer' verb before the next step");
        }
    }

    private static boolean isAppStep(Action action) {
        return action instanceof NavigateAction || action instanceof CloseAction || action instanceof LoginAsAction;
    }

    private static VerbOutcome execute(Action action, StepHost host) {
        if (action instanceof VerbAction) {
            Verbs verbs = host.verbs();
            if (verbs == null) {
                throw new DriverException(Reason.CAPABILITY_GAP, "no UI driver is installed for " + action.describe());
            }
            return ((VerbAction) action).run(verbs);
        }
        if (action instanceof NavigateAction) {
            host.navigate(((NavigateAction) action).target);
        } else if (action instanceof CloseAction) {
            host.closeActiveTab();
        } else if (action instanceof LoginAsAction) {
            host.loginAs(((LoginAsAction) action).as);
        } else if (action instanceof DoAction) {
            DoAction d = (DoAction) action;
            if (d.step == null || d.step.trim().isEmpty()) {
                throw new DriverException(Reason.BAD_INPUT, "a do step needs 'step': the id of a plugin's domain step");
            }
            return host.runStep(d.step, d.params == null ? Collections.<String, String>emptyMap() : d.params);
        } else {
            throw new DriverException(Reason.BAD_INPUT, "unknown step type: " + action.getClass().getName()
                    + "; the step types are " + FlowJson.stepTypes());
        }
        return null;
    }
}
