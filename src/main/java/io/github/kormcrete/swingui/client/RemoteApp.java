package io.github.kormcrete.swingui.client;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.junit.ClientApp;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** App calls out of process: app steps through do_action, user switches through session login, hooks through plugin_call. */
public final class RemoteApp implements ClientApp {

    private final AgentClient agent;
    private final RemoteVerbs steps;

    public RemoteApp(AgentClient agent) {
        this.agent = agent;
        this.steps = new RemoteVerbs(agent);
    }

    @Override public void navigate(String target) {
        ObjectNode s = agent.args();
        s.put("action", "navigate");
        s.put("target", target);
        steps.run(s);
    }

    @Override public void closeActiveTab() {
        steps.run(agent.args().put("action", "close"));
    }

    @Override public void loginAs(String alias) {
        agent.callJson("session", agent.args().put("op", "login").put("alias", alias));
    }

    @Override public void loginAs(String user, String password) {
        agent.callJson("session", agent.args().put("op", "login").put("user", user).put("password", password));
    }

    @Override public void relogin() {
        agent.callJson("session", agent.args().put("op", "login").put("relogin", true));
    }

    @Override public <T> T hooks(Class<T> type) {
        return RemoteHooks.of(agent, type, null);
    }

    @Override public String runRecordedTest(String testJson, String evidenceDir) {
        ObjectNode a = agent.args();
        try {
            a.set("test", agent.json().readTree(testJson));
        } catch (java.io.IOException e) {
            throw new DriverException(Reason.BAD_INPUT, "the recorded test is not JSON: " + e.getMessage());
        }
        if (evidenceDir != null) a.put("evidence", evidenceDir);
        return AgentClient.firstText(agent.call("run_recorded_test", a));
    }
}
