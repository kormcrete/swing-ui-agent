package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.client.AgentClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The clients a test JVM may lease. {@value #AGENTS_PARAMETER} lists them as
 * {@code host:port,host:port}: a JUnit configuration parameter, so a system property, junit-platform.properties or
 * a suite's {@code @ConfigurationParameter}. The default is the one agent on {@code swingui.port} (8765).
 * A test reserves all the clients it needs at once, the lowest free ports first, and waits up to
 * {@value #WAIT_PROPERTY} for this JVM's other tests to give them back. It then leases each on its agent in that
 * same ascending order (UiRun), so two multi-client tests never hold one client each while waiting for the other's.
 */
final class UiPool {

    static final String AGENTS_PARAMETER = "swingui.agents";
    static final String WAIT_PROPERTY = "swingui.poolWaitMs";
    static final long DEFAULT_WAIT_MS = 600_000L;

    private static final Map<String, UiPool> POOLS = new HashMap<String, UiPool>();

    private final List<UiAgents> agents;
    private final long waitMs;
    private final String spec;
    private final Map<UiAgents, String> holders = new IdentityHashMap<UiAgents, String>();

    UiPool(List<UiAgents> agents, long waitMs, String spec) {
        this.agents = Collections.unmodifiableList(new ArrayList<UiAgents>(agents));
        this.waitMs = waitMs;
        this.spec = spec;
    }

    /** One pool per spec in this JVM, so every test class shares its reservations. */
    static synchronized UiPool forSpec(String spec) {
        List<UiAgents> parsed = parse(spec);
        String key = key(parsed);
        UiPool pool = POOLS.get(key);
        if (pool == null) {
            pool = new UiPool(parsed, Long.getLong(WAIT_PROPERTY, DEFAULT_WAIT_MS), key);
            POOLS.put(key, pool);
        }
        return pool;
    }

    static String defaultSpec() {
        return "127.0.0.1:" + Integer.getInteger(UiAgents.PORT_PROPERTY, UiAgents.DEFAULT_PORT);
    }

    /** Agents a test made itself, unchecked; a reservation that cannot be met fails at once. */
    static UiPool of(AgentClient... ready) {
        List<UiAgents> list = new ArrayList<UiAgents>();
        for (int i = 0; i < ready.length; i++) list.add(new UiAgents("in-jvm", i, ready[i]));
        return new UiPool(list, 0, "in-jvm x" + ready.length);
    }

    static List<UiAgents> parse(String spec) {
        if (spec == null || spec.trim().isEmpty()) {
            throw new IllegalArgumentException(AGENTS_PARAMETER + " names no agent: '" + spec + "'");
        }
        List<UiAgents> out = new ArrayList<UiAgents>();
        List<String> seen = new ArrayList<String>();
        for (String raw : spec.split(",", -1)) {
            String entry = raw.trim();
            if (entry.isEmpty()) throw new IllegalArgumentException(AGENTS_PARAMETER + "=" + spec + ": empty entry (a stray comma?)");
            int colon = entry.lastIndexOf(':');
            int port;
            try {
                if (colon <= 0) throw new NumberFormatException();
                String digits = entry.substring(colon + 1);
                if (digits.startsWith("+")) throw new NumberFormatException();
                port = Integer.parseInt(digits);
                if (port < 1 || port > 65535) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(AGENTS_PARAMETER + "=" + spec + ": '" + entry + "' is not host:port");
            }
            String host = entry.substring(0, colon);
            String where = host + ":" + port;
            if (seen.contains(where)) throw new IllegalArgumentException(AGENTS_PARAMETER + "=" + spec + ": " + where + " twice");
            seen.add(where);
            out.add(new UiAgents(host, port));
        }
        Collections.sort(out, new Comparator<UiAgents>() {
            @Override public int compare(UiAgents a, UiAgents b) {
                int byPort = Integer.compare(a.port(), b.port());
                return byPort != 0 ? byPort : a.host().compareTo(b.host());
            }
        });
        return out;
    }

    private static String key(List<UiAgents> sorted) {
        StringBuilder sb = new StringBuilder();
        for (UiAgents a : sorted) sb.append(sb.length() == 0 ? "" : ",").append(a.where());
        return sb.toString();
    }

    int size() { return agents.size(); }

    String spec() { return spec; }

    /** The {@code n} lowest free clients, reserved for {@code owner} in this JVM; waits for them, up to the pool's wait. */
    synchronized List<UiAgents> reserve(int n, String owner) {
        if (n > agents.size()) {
            throw new IllegalStateException("pool exhausted: needs " + n + ", has " + agents.size() + " ("
                    + AGENTS_PARAMETER + "=" + spec + ")");
        }
        long deadline = System.currentTimeMillis() + waitMs;
        while (agents.size() - holders.size() < n) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                throw new IllegalStateException("pool busy: needs " + n + " free of " + agents.size() + ", waited "
                        + waitMs / 1000 + " s; held by " + new ArrayList<String>(holders.values()) + " ("
                        + AGENTS_PARAMETER + "=" + spec + ", " + WAIT_PROPERTY + ")");
            }
            try {
                wait(left);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for " + n + " free clients");
            }
        }
        List<UiAgents> taken = new ArrayList<UiAgents>();
        for (UiAgents a : agents) {
            if (taken.size() == n) break;
            if (!holders.containsKey(a)) {
                holders.put(a, owner);
                taken.add(a);
            }
        }
        return taken;
    }

    synchronized void release(List<UiAgents> taken) {
        if (taken == null) return;
        for (UiAgents a : taken) holders.remove(a);
        notifyAll();
    }
}
