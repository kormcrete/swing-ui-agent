package io.github.kormcrete.swingui.spi;

/**
 * What the agent asks of the app plugin around a test ({@code session} ops reset, login and info). Called on an
 * HTTP thread, never on the EDT.
 */
public interface AppHooks {

    /**
     * Signs back in when the app's session has expired behind a modal sign-in dialog of its own. The reset calls
     * this <em>before</em> it escapes modal dialogs, because Escape or a window close on such a dialog may only turn
     * it into an exit question. A no-op (the default) when no session has expired; throws, with the reason, when it
     * cannot sign back in.
     */
    default void recoverSession() {}

    /**
     * Brings the app to one test's start state: its own dialogs closed, every tab closed, logged in as
     * {@code spec}, settled. The agent has already reset the pointer, called {@link #recoverSession} and escaped
     * modal dialogs. Throws, with the reason, when it cannot get there; a dialog that will not close is a finding,
     * not something to work around.
     */
    void reset(ResetSpec spec);

    /**
     * Logs {@code who} in mid-test, closing nothing. A no-op when {@code who} is logged in and no login form shows,
     * unless {@code force}. {@code who} null with {@code force}: log the current user in again.
     */
    void login(ResetSpec who, boolean force);

    AppInfo info();
}
