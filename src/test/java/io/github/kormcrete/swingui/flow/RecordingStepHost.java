package io.github.kormcrete.swingui.flow;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.PendingDialog;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.mcp.FakeVerbs;
import io.github.kormcrete.swingui.spi.StepHost;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A {@link StepHost} that records the three app steps it is asked for ({@code "navigate Orders"},
 * {@code "close"}, {@code "loginAs customer"}) and answers {@link #openDialog()} with {@link #waiting}; a {@code do}
 * step answers {@link #stepOutcome} or throws {@link #stepFailure}.
 * {@link #verbs} is null (no driver installed) unless a test sets it.
 */
public class RecordingStepHost implements StepHost {

    public FakeVerbs verbs;
    public PendingDialog waiting;
    public final List<String> calls = new ArrayList<String>();
    /** A navigate to this target fails as a step (TIMEOUT), not as a tool error. */
    public String failNavigateTo;
    /** What a {@code do} step returns; with neither this nor {@link #stepFailure}, runStep is StepHost's own refusal. */
    public VerbOutcome stepOutcome;
    /** What a {@code do} step throws. */
    public RuntimeException stepFailure;

    public RecordingStepHost() {}

    public RecordingStepHost(FakeVerbs verbs) {
        this.verbs = verbs;
    }

    @Override public Verbs verbs() { return verbs; }

    @Override public void navigate(String target) {
        calls.add("navigate " + target);
        if (target != null && target.equals(failNavigateTo)) {
            throw new DriverException(Reason.TIMEOUT, "no tab opened for '" + target + "'");
        }
    }

    @Override public void closeActiveTab() { calls.add("close"); }

    @Override public void loginAs(String alias) { calls.add("loginAs " + alias); }

    @Override public PendingDialog openDialog() { return waiting; }

    @Override public VerbOutcome runStep(String id, Map<String, String> params) {
        if (stepOutcome == null && stepFailure == null) return StepHost.super.runStep(id, params);
        calls.add("do " + id + " " + params);
        if (stepFailure != null) throw stepFailure;
        return stepOutcome;
    }
}
