package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.client.AgentClient;
import io.github.kormcrete.swingui.driver.Seconds;

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
    /** The session info {@link #shared} checked; null until then, and for an agent a test made itself. */
    private SessionInfo info;
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

    /** The session info {@link #shared} checked; null before that, and for an agent a test made itself. */
    synchronized SessionInfo info() { return info; }

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
            this.info = info;
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
        if (!info.ready && "busy".equals(info.mainWindow)) {
            throw prefix(where(), new IllegalStateException("the app did not answer after " + Seconds.of(readyWaitMs)
                    + " - its event thread is busy; wait until java -jar swing-ui-agent.jar session says ready and retry"));
        }
        if (!info.ready) {
            throw prefix(where(), new IllegalStateException("no window of the app is showing after "
                    + Seconds.of(readyWaitMs) + " - start the app and wait for session ready"));
        }
        return info;
    }
}
