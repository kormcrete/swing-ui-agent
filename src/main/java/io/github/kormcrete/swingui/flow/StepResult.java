package io.github.kormcrete.swingui.flow;

import io.github.kormcrete.swingui.driver.PendingDialog;
import io.github.kormcrete.swingui.driver.VerbOutcome;

import java.util.List;

public class StepResult {
    public int index;
    public String action;
    public boolean passed;
    public String message;

    /** The driver's reason for a failure; null when passed or unclassified. */
    public String reason;
    public List<String> details;
    /** OS input that reached the client during this step; null when none. */
    public List<String> realInput;
    /** App activations after launch during this step; null when none. */
    public List<String> activations;
    public PendingDialog pending;
    /** Set when the action ran but the app did not settle afterwards within the wait; the step still passed. */
    public String warning;
    public String via;
    /** The verb's outcome, for recording; null for app steps and failed steps. */
    public VerbOutcome outcome;
    /** Wall time of the step, and the part of it spent running on / waiting for the EDT ({@code EdtClock}). */
    public long ms;
    public long edtMs;
    public long edtWaitMs;
    /** Why a replay step failed, beyond its message - set only when the executor diagnoses failures. */
    public StepDiagnostics diagnostics;

    public static StepResult pass(int index, String action) {
        StepResult r = new StepResult();
        r.index = index; r.action = action; r.passed = true; r.message = "";
        return r;
    }

    public static StepResult fail(int index, String action, String message) {
        StepResult r = new StepResult();
        r.index = index; r.action = action; r.passed = false; r.message = (message != null) ? message : "";
        return r;
    }
}
