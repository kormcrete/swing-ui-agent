package io.github.kormcrete.swingui.spi;

import io.github.kormcrete.swingui.driver.PendingDialog;
import io.github.kormcrete.swingui.driver.Verbs;

/** What steps run against, live and in replay: the driver's verbs, and the three app steps. */
public interface StepHost {
    /** The installed driver's verbs; null when none is installed. */
    Verbs verbs();
    /** The {@code navigate} step: opens what an entry-point id or a {@link UiInventory#navTargets} name names. */
    void navigate(String target);
    /** The {@code close} step: closes the active tab, answering its "close anyway?" confirm. */
    void closeActiveTab();
    /** The {@code loginAs} step: logs out and in as a user alias ({@link io.github.kormcrete.swingui.flow.UiUsers}). */
    void loginAs(String alias);
    /**
     * The message or confirmation (an option pane) waiting for an answer now, or null. App steps refuse to run
     * while one waits.
     */
    PendingDialog openDialog();
}
