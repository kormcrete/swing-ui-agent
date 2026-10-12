package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.client.AgentClient;

import java.util.ServiceLoader;

/**
 * The agent a test JVM talks to, checked once per JVM. The checks: it answers, its app plugin (if any) is ready, and every
 * {@link UiRunGuard} passes. A failure is kept: every later test fails at once with it.
 */
final class UiAgents {

    static final String PORT_PROPERTY = "swingui.port";
    static final int DEFAULT_PORT = 8765;

    private final String host;
    private final int port;
    private AgentClient agent;
    private RuntimeException broken;

    UiAgents(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /** An agent a test made itself: no reachability, plugin or guard check. */
    UiAgents(String host, int port, AgentClient ready) {
        this(host, port);
        this.agent = ready;
    }

    String host() { return host; }
    int port() { return port; }
    String where() { return host + ":" + port; }

    /** A guard's failure, prefixed with the agent it is about: with several clients the message must say which. */
    static IllegalStateException prefix(String where, IllegalStateException e) {
        IllegalStateException named = new IllegalStateException(where + ": " + e.getMessage(), e.getCause());
        named.setStackTrace(e.getStackTrace());
        return named;
    }

    synchronized AgentClient shared() {
        if (broken != null) throw broken;
        if (agent != null) return agent;
        try {
            AgentClient a = new AgentClient(host, port);
            SessionInfo info = SessionInfo.parse(a.callJson("session", a.args().put("op", "info")));
            if (!info.appPluginUsable()) {
                throw prefix(where(), new IllegalStateException("the app plugin is " + info.pluginState + ": "
                        + info.pluginDetail + " - wait for 'ready' (java -jar swing-ui-agent.jar session) and retry"));
            }
            try {
                for (UiRunGuard guard : ServiceLoader.load(UiRunGuard.class)) guard.check(info);
            } catch (IllegalStateException e) {
                throw prefix(where(), e);
            }
            System.out.println("[ui] " + where() + " " + info.headline());
            agent = a;
            return a;
        } catch (RuntimeException e) {
            broken = e;
            throw e;
        }
    }
}
