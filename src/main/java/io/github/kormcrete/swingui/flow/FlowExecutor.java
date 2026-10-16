package io.github.kormcrete.swingui.flow;

import io.github.kormcrete.swingui.driver.DomainStepFailed;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.EdtClock;
import io.github.kormcrete.swingui.driver.PendingDialog;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.StepCall;
import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.platform.Watch;
import io.github.kormcrete.swingui.flow.verb.VerbAction;
import io.github.kormcrete.swingui.spi.StepHost;

import java.util.ArrayList;
import java.util.Collections;
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
 */
public class FlowExecutor {

    private FailureDiagnostics diagnostics;

    /** Diagnose each failed step with {@code d} - for a replay, never for the live MCP session. */
    public FlowExecutor diagnoseFailures(FailureDiagnostics d) {
        this.diagnostics = d;
        return this;
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
        for (int i = 0; i < flow.steps.size(); i++) {
            Action action = flow.steps.get(i);
            Watch.Mark mark = verbs == null ? null : markOrNull(verbs);
            EdtClock.Reading edtBefore = EdtClock.read();
            long started = System.nanoTime();
            StepResult step;
            try {
                refuseWhileADialogWaits(action, host);
                VerbOutcome outcome = execute(action, host);
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
                step.reason = reasonOf(e);
                if (e instanceof DriverException && !((DriverException) e).details().isEmpty()) {
                    step.details = ((DriverException) e).details();
                }
                if (e instanceof DomainStepFailed) domainStep(step, ((DomainStepFailed) e).outcome());
            }
            step.ms = (System.nanoTime() - started) / 1_000_000L;
            EdtClock.Reading edt = EdtClock.read().since(edtBefore);
            step.edtMs = edt.runMs();
            step.edtWaitMs = edt.waitMs();
            if (mark != null) {
                step.realInput = orNull(verbs.watch().realInputSince(mark));
                step.activations = orNull(verbs.watch().activationsSince(mark));
            }
            if (!step.passed && diagnostics != null) {
                StepResult child = failedChild(step);
                String label = StepNumbers.label(flow.steps, i);
                if (child == null) {
                    step.diagnostics = diagnose(() -> diagnostics.diagnose(action, verbs, flow.name, label));
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
