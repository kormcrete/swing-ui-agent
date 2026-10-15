package io.github.kormcrete.swingui.junit;

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
     * Runs a recorded test file's JSON on the app under this client's lease, as {@code run_recorded_test}; returns run_recorded_test's JSON
     * ({@code passed}, {@code ran}, {@code ms}, {@code report}, ...). {@code evidenceDir} receives the failing step's picture.
     */
    default String runRecordedTest(String testJson, String evidenceDir) {
        throw new UnsupportedOperationException("this client cannot run recorded test files");
    }
}
