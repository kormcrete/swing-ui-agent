package io.github.kormcrete.swingui.flow;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.EdtClock;
import io.github.kormcrete.swingui.driver.PendingDialog;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.platform.Watch;
import io.github.kormcrete.swingui.flow.verb.VerbAction;
import io.github.kormcrete.swingui.spi.StepHost;

import java.util.List;

/**
 * Runs a Flow's steps against a {@link StepHost}, collecting a FlowResult. Stops at the first failed step.
 * A verb runs through the host's driver; {@code navigate}, {@code close} and {@code loginAs} through the host.
 * Every step is timed (wall time and its {@link EdtClock} split); with {@link #diagnoseFailures} a
 * failed step also carries {@link StepDiagnostics}.
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
                }
            } catch (AssertionError | RuntimeException e) {
                step = StepResult.fail(i, action.describe(), e.getMessage());
                step.reason = reasonOf(e);
                if (e instanceof DriverException && !((DriverException) e).details().isEmpty()) {
                    step.details = ((DriverException) e).details();
                }
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
                try {
                    step.diagnostics = diagnostics.diagnose(action, verbs, flow.name, StepNumbers.label(flow.steps, i));
                } catch (RuntimeException e) {
                    step.diagnostics = new StepDiagnostics();
                    step.diagnostics.problems.add("diagnosis failed: " + e);
                }
            }
            result.steps.add(step);
            if (!step.passed) break; // stop at first failure
        }
        return result;
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
        if (!isAppStep(action)) return;
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
        } else {
            throw new DriverException(Reason.BAD_INPUT, "unknown step type: " + action.getClass().getName()
                    + "; the step types are " + FlowJson.stepTypes());
        }
        return null;
    }
}
