package io.github.kormcrete.swingui.driver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A plugin's domain step whose body failed, with what it ran up to the failure ({@link #outcome}: the filled sentence
 * and the steps). Made by {@link #of}: when the last step the body ran failed, that step is the failure, and the
 * message names it; else the body failed on its own (a check of its own, plugin code).
 */
public final class DomainStepFailed extends DriverException {

    private final VerbOutcome outcome;

    private DomainStepFailed(Reason reason, String message, List<String> details, VerbOutcome outcome) {
        super(reason, message, details);
        this.outcome = outcome;
    }

    /** The filled sentence and the steps the body ran, the failed one last when a step failed. */
    public VerbOutcome outcome() {
        return outcome;
    }

    /**
     * The failure of the domain step {@code outcome} describes, after its body threw {@code thrown}. A failed last step
     * gives the reason, details and the message ("Add Gel pen, black to the cart: step 2 (click(...)) failed: ...");
     * otherwise a DriverException keeps its reason, an AssertionError is EXPECTATION and anything else APP_ERROR.
     */
    public static DomainStepFailed of(VerbOutcome outcome, Throwable thrown) {
        List<StepCall> calls = outcome.calls == null ? Collections.<StepCall>emptyList() : outcome.calls;
        StepCall last = calls.isEmpty() ? null : calls.get(calls.size() - 1);
        if (last != null && !last.passed) {
            Reason reason = last.reason != null ? last.reason : thrown instanceof AssertionError ? Reason.EXPECTATION
                    : Reason.APP_ERROR;
            String why = last.reason != null ? DriverException.stripReason(last.message, last.reason) : last.message;
            return new DomainStepFailed(reason, outcome.sentence + ": step " + calls.size() + " (" + last.action
                    + ") failed: " + why, last.details == null ? Collections.<String>emptyList() : last.details, outcome);
        }
        Reason reason;
        String why;
        List<String> details = new ArrayList<String>();
        if (thrown instanceof DriverException) {
            DriverException d = (DriverException) thrown;
            reason = d.reason();
            why = DriverException.stripReason(d.getMessage(), reason);
            details.addAll(d.details());
        } else {
            reason = thrown instanceof AssertionError ? Reason.EXPECTATION : Reason.APP_ERROR;
            why = thrown.getMessage() != null ? thrown.getMessage() : thrown.toString();
        }
        return new DomainStepFailed(reason, outcome.sentence + ": the step's body failed: " + why, details, outcome);
    }
}
