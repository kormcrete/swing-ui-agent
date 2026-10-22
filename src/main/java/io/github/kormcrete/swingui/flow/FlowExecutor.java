package io.github.kormcrete.swingui.flow;

import com.fasterxml.jackson.databind.JsonNode;
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
import io.github.kormcrete.swingui.flow.refs.JsonPaths;
import io.github.kormcrete.swingui.flow.refs.Refs;
import io.github.kormcrete.swingui.flow.refs.Resolution;
import io.github.kormcrete.swingui.flow.refs.RunValues;
import io.github.kormcrete.swingui.flow.verb.RememberVerb;
import io.github.kormcrete.swingui.flow.verb.VerbAction;
import io.github.kormcrete.swingui.spi.StepHost;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * ({@link Outcomes#diff}), waiting right after it for a value a later step uses that comes late ({@link #settled}); a
 * remember step keeps its value; every step's references are filled in just before it runs ({@link Resolution#fill}),
 * and {@link StepResult#resolved} says what they stood for. Without one (the live session's own steps, whose text is never escaped) the steps run as they are.
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
        /** The paths later steps use of each id some step refers to. */
        final Map<String, Set<String>> paths = new HashMap<String, Set<String>>();
        /** The reading before the step running now, when a later step names it. */
        UiReading pending;

        Links(Flow flow, RunValues values) {
            this.flow = flow;
            this.values = values;
            for (int i = 0; i < flow.steps.size(); i++) {
                try {
                    for (Refs.Ref r : Refs.of(flow.steps.get(i))) {
                        if (!paths.containsKey(r.id)) paths.put(r.id, new LinkedHashSet<String>());
                        paths.get(r.id).add(r.path);
                    }
                } catch (IllegalArgumentException malformed) {
                    // the step fails on it when it fills its values
                }
            }
        }

        /** The step with its references filled in; one without a value fails it ({@link #fill(Action, RunValues, Flow)}). */
        Action fill(Action a) {
            if (a instanceof RememberVerb && ((RememberVerb) a).as != null) {
                String problem = RecordedTestFiles.idProblem(((RememberVerb) a).as);
                if (problem != null) throw new DriverException(Reason.BAD_INPUT, "remember as '" + ((RememberVerb) a).as + "': " + problem);
            }
            return FlowExecutor.fill(a, values, flow, outcomes);
        }

        /** Reads the app before a step that a later step names. */
        void before(Action a) {
            pending = a.id != null && paths.containsKey(a.id) ? outcomes.read() : null;
            // what the step makes this run is not known: no value of an earlier run stands in for it
            if (pending == null && a.id != null) values.remove(a.id);
        }

        /**
         * Keeps what the step changed - waiting, before the next step runs, for a value a later step uses that comes late
         * ({@link #settled}) - or what it remembered.
         */
        void after(int index, Action a, VerbOutcome out) {
            if (pending != null) {
                UiReading now = outcomes.read();
                if (now == null) {
                    values.remove(a.id);
                } else {
                    Map<String, Object> diff = settled(pending, now, out == null ? null : out.made, paths.get(a.id),
                            outcomes, linkWait());
                    values.put(a.id, FlowJson.mapper().valueToTree(diff));
                }
                pending = null;
            }
            if (a instanceof RememberVerb && out != null && out.note != null) values.remember(((RememberVerb) a).as, out.note);
        }

        void forget() {
            pending = null;
        }
    }

    /**
     * {@code a} with its references filled in from {@code values} ({@link Resolution#fill}). A reference without a value
     * fails the step (NOT_FOUND), saying in words what the earlier step of {@code steps} did not do: a value that comes
     * late was waited for right after its step ({@link #settled}). Never the value the recording saw.
     */
    public static Action fill(Action a, RunValues values, Flow steps) {
        return fill(a, values, steps, null);
    }

    /**
     * {@link #fill(Action, RunValues, Flow)}; for a row an earlier step did not add, the message goes on with the
     * filled text fields beside that table, read from {@code now} ({@link Outcomes#filterHint}).
     */
    public static Action fill(Action a, RunValues values, Flow steps, OutcomeSource now) {
        try {
            return Resolution.fill(a, values);
        } catch (RunValues.UnresolvedReference e) {
            String message = Resolution.inWords(e.ref, steps);
            String hint = now == null ? null : filterHint(e.ref.path, now);
            throw new DriverException(Reason.NOT_FOUND, hint == null ? message : message + "; " + hint);
        }
    }

    /** The filter hint for a path {@code .rows.<table>.added...}, else null. */
    private static String filterHint(String path, OutcomeSource now) {
        List<String> keys;
        try {
            keys = path.isEmpty() ? Collections.<String>emptyList() : JsonPaths.keys("$" + path);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        if (keys.size() < 3 || !"rows".equals(keys.get(0)) || !"added".equals(keys.get(2))) return null;
        try {
            return Outcomes.filterHint(now.read(), keys.get(1));
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * What a step changed from {@code before} to {@code after} ({@link Outcomes#diff}, with a domain step's own values
     * {@code made} under {@link #STEP}), once each of {@code paths} - the paths later steps use of it - holds one value:
     * until then the app is read again from {@code source} every {@link #LINK_POLL_MS}, up to {@code waitMillis}, and
     * the last reading counts. A value that comes late (an app that saves in the background) is waited for right after
     * its step, before any later step runs: what a later step changes, such as a row it adds to the same table, is never
     * taken for this step's. A source that cannot be read ends the wait.
     */
    public static Map<String, Object> settled(UiReading before, UiReading after, Object made, Collection<String> paths,
                                              OutcomeSource source, long waitMillis) {
        Map<String, Object> diff = diffOf(before, after, made);
        if (paths == null || paths.isEmpty()) return diff;
        long deadline = System.nanoTime() + waitMillis * 1_000_000L;
        while (!holdsAll(diff, paths) && System.nanoTime() - deadline < 0) {
            try {
                Thread.sleep(LINK_POLL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return diff;
            }
            UiReading now;
            try {
                now = source.read();
            } catch (RuntimeException unreadable) {
                return diff;
            }
            if (now == null) return diff;
            diff = diffOf(before, now, made);
        }
        return diff;
    }

    private static Map<String, Object> diffOf(UiReading before, UiReading after, Object made) {
        Map<String, Object> diff = Outcomes.diff(before, after);
        if (made != null) diff.put(STEP, made);
        return diff;
    }

    /** Whether each of {@code paths} names one value in {@code diff}; an empty path (the whole outcome) never waits. */
    private static boolean holdsAll(Map<String, Object> diff, Collection<String> paths) {
        JsonNode tree = FlowJson.mapper().valueToTree(diff);
        for (String path : paths) {
            if (path.isEmpty()) continue;
            List<JsonNode> hits;
            try {
                hits = JsonPaths.select(tree, "$" + path);
            } catch (IllegalArgumentException malformed) {
                continue;
            }
            if (hits.size() != 1 || !hits.get(0).isValueNode() || hits.get(0).isNull()) return false;
        }
        return true;
    }

    /** Where a domain step's own values go in its outcome: {@code ${placeOrder.step.number}}. */
    public static final String STEP = "step";

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
