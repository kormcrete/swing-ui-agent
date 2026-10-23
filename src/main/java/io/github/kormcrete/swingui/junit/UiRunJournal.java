package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.journal.RunFolder;

/**
 * The run journal's setup calls. Every {@link UiTest} writes its steps and checks to
 * {@code target/ui-runs/<run id>/steps/<Class>#<method>.jsonl} on its own; nothing here is needed for that.
 */
public final class UiRunJournal {

    private UiRunJournal() {}

    /**
     * Names the app version this run tests, e.g. {@code UiRunJournal.appVersion(OrderDesk.VERSION)} in a
     * {@code @BeforeAll} or a static initializer. It goes into the run's {@code run.json} and each test's client list,
     * and beats {@code -Dswingui.appVersion} and the version the app reports; {@code null} or blank forgets it.
     */
    public static void appVersion(String version) {
        RunFolder.appVersion(version);
    }
}
