package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.client.AgentClient;
import io.github.kormcrete.swingui.client.RemoteApp;
import io.github.kormcrete.swingui.client.RemoteVerbs;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.Verbs;

import java.nio.file.Path;

/** One test's hold on the client: lease, reset, a bound Client; release at the end, whatever happened. */
final class UiRun {

    static final long TTL_MS = 120_000L;
    /** How long begin retries a BUSY lease: just past the agent's 15 s takeover, so a rerun after Stop gets in. */
    static final String LEASE_WAIT_PROPERTY = "swingui.leaseWaitMs";
    static final long DEFAULT_LEASE_WAIT_MS = 16_000L;
    private static final long RETRY_MS = 500L;

    /** A third of the agent's 15 s takeover idle: two missed renews are still safe. */
    static final long HEARTBEAT_MS = 5_000L;

    /** How long a stopped test JVM's exit waits for the agent to take its lease back: best effort, never a hang. */
    static final long HOOK_RELEASE_MS = 2_000L;

    /** The JVM's shutdown hooks; a seam so tests record instead of registering. */
    interface ShutdownHooks {
        void add(Thread hook);
        void remove(Thread hook);
    }

    static final ShutdownHooks RUNTIME = new ShutdownHooks() {
        @Override public void add(Thread hook) { Runtime.getRuntime().addShutdownHook(hook); }
        @Override public void remove(Thread hook) { Runtime.getRuntime().removeShutdownHook(hook); }
    };

    private final AgentClient agent;
    private final String owner;
    private final Path screenshotDir;
    private final ShutdownHooks hooks;
    private final long heartbeatMs;
    private AgentClient leased;
    private Thread hook;
    private LeaseHeartbeat heartbeat;

    UiRun(AgentClient agent, String owner) {
        this(agent, owner, null, RUNTIME);
    }

    /** {@code screenshotDir}: where the Client puts a relative screenshot path; null for {@link Client}'s default. */
    UiRun(AgentClient agent, String owner, Path screenshotDir) {
        this(agent, owner, screenshotDir, RUNTIME);
    }

    UiRun(AgentClient agent, String owner, Path screenshotDir, ShutdownHooks hooks) {
        this(agent, owner, screenshotDir, hooks, HEARTBEAT_MS);
    }

    UiRun(AgentClient agent, String owner, Path screenshotDir, ShutdownHooks hooks, long heartbeatMs) {
        this.heartbeatMs = heartbeatMs;
        this.agent = agent;
        this.owner = owner;
        this.screenshotDir = screenshotDir;
        this.hooks = hooks;
    }

    Client begin(String alias, String runId) {
        final String token = lease().get("token").asText();
        leased = agent.withLease(token);
        hook = releaseOnExit(token);
        heartbeat = new LeaseHeartbeat(new LeaseHeartbeat.Renew() {
            @Override public boolean renew() {
                return agent.callJson("session", agent.args().put("op", "renew").put("token", token))
                        .path("renewed").asBoolean(false);
            }
        }, heartbeatMs, "swingui-lease-heartbeat " + owner);
        heartbeat.start();
        if (alias == null) leased.callJson("session", leased.args().put("op", "reset"));
        else leased.callJson("session", leased.args().put("op", "reset").put("alias", alias));
        return new Client(new RemoteVerbs(leased), new RemoteApp(leased), runId, screenshotDir);
    }

    /**
     * A JVM stopped mid-test (IntelliJ's Stop) runs no afterEach: this hook gives its lease back, so a rerun gets the
     * client at once instead of waiting out the agent's idle takeover. Null when the JVM refuses a hook.
     */
    private Thread releaseOnExit(final String token) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    releaseWithin(token, HOOK_RELEASE_MS);
                } catch (Throwable ignored) {
                    // never let the exit fail or hang on the lease
                }
            }
        }, "swingui-release-lease");
        try {
            hooks.add(t);
            return t;
        } catch (RuntimeException e) {
            return null; // shutting down already, or not permitted
        }
    }

    /** Releases {@code token} on a daemon thread, waiting at most {@code timeoutMs}; swallows every error. */
    private void releaseWithin(final String token, long timeoutMs) throws InterruptedException {
        Thread call = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    agent.callJson("session", agent.args().put("op", "release").put("token", token));
                } catch (Throwable ignored) {
                    // best effort: the agent's idle takeover is the fallback
                }
            }
        }, "swingui-release-lease-call");
        call.setDaemon(true);
        call.start();
        call.join(timeoutMs);
    }

    /** The registered shutdown hook while a lease is held; null otherwise. For tests. */
    Thread shutdownHook() {
        return hook;
    }

    private com.fasterxml.jackson.databind.JsonNode lease() {
        long deadline = System.currentTimeMillis() + Long.getLong(LEASE_WAIT_PROPERTY, DEFAULT_LEASE_WAIT_MS);
        while (true) {
            try {
                return agent.callJson("session", agent.args().put("op", "lease").put("owner", owner).put("ttlMs", TTL_MS));
            } catch (DriverException e) {
                if (e.reason() != Reason.BUSY || System.currentTimeMillis() >= deadline) throw e;
            }
            try {
                Thread.sleep(RETRY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new DriverException(Reason.BUSY, "interrupted while waiting for the client's lease");
            }
        }
    }

    /** The verbs evidence is taken with; leased when begin got that far, else unleased (reads need no lease). */
    Verbs verbs() {
        return new RemoteVerbs(leased != null ? leased : agent);
    }

    String edtStack() {
        return agent.callJson("session", agent.args().put("op", "edt")).path("stack").asText();
    }

    /** Why the heartbeat stopped finding the lease, or null. */
    String leaseLost() {
        return heartbeat == null ? null : heartbeat.lost();
    }

    void end() {
        if (heartbeat != null) heartbeat.stop();
        removeHook();
        if (leased == null) return;
        String token = leased.lease();
        leased = null;
        try {
            agent.callJson("session", agent.args().put("op", "release").put("token", token));
        } catch (RuntimeException e) {
            System.err.println("[ui-warning] releasing the lease of " + owner + " failed: " + e.getMessage());
        }
    }

    private void removeHook() {
        Thread h = hook;
        hook = null;
        if (h == null) return;
        try {
            hooks.remove(h);
        } catch (RuntimeException e) {
            // the JVM is already shutting down: the hook runs, and its release is harmless
        }
    }
}
