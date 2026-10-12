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
}
