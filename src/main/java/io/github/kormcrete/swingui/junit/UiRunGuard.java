package io.github.kormcrete.swingui.junit;

/**
 * An app's check before the first UI test of a run (ServiceLoader, META-INF/services in the test module): is the
 * server up, is the client current. Throw IllegalStateException with what to do; every test of the run then fails
 * with that message.
 */
public interface UiRunGuard {
    void check(SessionInfo info);
}
