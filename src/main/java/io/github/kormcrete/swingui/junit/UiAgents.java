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
    static final long READY_WAIT_MS = 10_000L;
    static final long READY_POLL_MS = 200L;

    private final String host;
    private final int port;
    private AgentClient agent;
    private RuntimeException broken;
    private long readyWaitMs = READY_WAIT_MS;

    UiAgents(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /** An agent a test made itself: no reachability, plugin or guard check. */
    UiAgents(String host, int port, AgentClient ready) {
        this(host, port);
        this.agent = ready;
    }

    /** How long {@link #shared} waits for a window of the app. For tests. */
    UiAgents readyWait(long ms) {
        this.readyWaitMs = ms;
        return this;
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
            info = awaitReady(a, info);
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

    /** The plugin is usable; while no window of the app shows yet, asks again for a short while. */
    private SessionInfo awaitReady(AgentClient a, SessionInfo info) {
        long deadline = System.currentTimeMillis() + readyWaitMs;
        while (!info.ready && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(READY_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            info = SessionInfo.parse(a.callJson("session", a.args().put("op", "info")));
        }
        if (!info.ready) {
            throw prefix(where(), new IllegalStateException("no window of the app is showing after "
                    + seconds(readyWaitMs) + " - start the app and wait for session ready"));
        }
        return info;
    }

    private static String seconds(long millis) {
        if (millis >= 1_000L) return Math.round(millis / 1_000.0) + " s";
        return String.format(java.util.Locale.ROOT, "%.1f s", millis / 1_000.0);
    }
}
