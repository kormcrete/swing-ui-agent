package io.github.kormcrete.swingui.testing;

import io.github.kormcrete.swingui.driver.PendingDialog;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.spi.StepHost;

/** A StepHost over a RecordingVerbs: app steps are recorded as "hook …" lines in the same call list. */
public final class RecordingHost implements StepHost {
    private final RecordingVerbs verbs;

    public RecordingHost(RecordingVerbs verbs) {
        this.verbs = verbs;
    }

    @Override public Verbs verbs() { return verbs; }
    @Override public void navigate(String target) { verbs.calls.add("hook navigate " + target); }
    @Override public void closeActiveTab() { verbs.calls.add("hook closeActiveTab"); }
    @Override public void loginAs(String alias) { verbs.calls.add("hook loginAs " + alias); }
    @Override public PendingDialog openDialog() { return null; }
}
