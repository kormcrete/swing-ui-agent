package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.flow.UiUsers;

/**
 * What a Client asks of the app beyond verbs: app steps, user switches, and the app's typed hooks. Out of process
 * this is client.RemoteApp; unit tests use a recording one.
 */
public interface ClientApp {
    void navigate(String target);
    void closeActiveTab();
    void loginAs(String alias);
    void loginAs(String user, String password);
    void relogin();
    <T> T hooks(Class<T> type);

    /**
     * The secret {@code name} of {@code alias}, as {@link Client#secret} reads it: from
     * .swing-ui-agent/ui-users.properties ({@link UiUsers#secret}). A client that only traces a step body refuses it,
     * so a secret never lands in the steps it keeps.
     */
    default String secret(String alias, String name) {
        return UiUsers.secret(alias, name);
    }

    /**
     * Runs a recorded test file's JSON on the app under this client's lease, as {@code run_recorded_test}; returns run_recorded_test's JSON
     * ({@code passed}, {@code ran}, {@code ms}, {@code report}, ...). {@code evidenceDir} receives the failing step's picture.
     */
    default String runRecordedTest(String testJson, String evidenceDir) {
        throw new UnsupportedOperationException("this client cannot run recorded test files");
    }
}
