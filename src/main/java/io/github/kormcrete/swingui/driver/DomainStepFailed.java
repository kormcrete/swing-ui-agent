package io.github.kormcrete.swingui.driver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;

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

    /** {@link #of(VerbOutcome, Throwable, boolean, UnaryOperator)} for a body that failed with its last step, if that one failed. */
    public static DomainStepFailed of(VerbOutcome outcome, Throwable thrown) {
        return of(outcome, thrown, true, UnaryOperator.<String>identity());
    }

    /**
     * The failure of the domain step {@code outcome} describes, after its body threw {@code thrown}. When {@code byLastStep}
     * (the body threw the failure of its last step on, as is or as a cause) and that step failed, it gives the reason,
     * details and the message ("Add Gel pen, black to the cart: step 2 (click(...)) failed: ..."); otherwise the body failed
     * on its own, e.g. after getting past a failed step: a DriverException keeps its reason, an AssertionError is
     * EXPECTATION and anything else APP_ERROR, its message and details passed through {@code mask} (secrets the body read).
     */
    public static DomainStepFailed of(VerbOutcome outcome, Throwable thrown, boolean byLastStep, UnaryOperator<String> mask) {
        List<StepCall> calls = outcome.calls == null ? Collections.<StepCall>emptyList() : outcome.calls;
        StepCall last = calls.isEmpty() ? null : calls.get(calls.size() - 1);
        if (byLastStep && last != null && !last.passed) {
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
            for (String line : d.details()) details.add(mask.apply(line));
        } else {
            reason = thrown instanceof AssertionError ? Reason.EXPECTATION : Reason.APP_ERROR;
            why = thrown.getMessage() != null ? thrown.getMessage() : thrown.toString();
        }
        return new DomainStepFailed(reason, outcome.sentence + ": the step's body failed: " + mask.apply(why), details, outcome);
    }
}
