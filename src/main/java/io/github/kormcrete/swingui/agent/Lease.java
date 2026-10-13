package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;

import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One owner at a time. An owner leases the agent and passes the token with every
 * mutating call; read-only calls need none, but one that carries the holder's token renews the lease. A mutating
 * call without a token runs only while nobody holds the lease (the CLI's one-call lease). A lease ends on release,
 * after {@code ttl} without a call, or when another owner leases an agent idle for {@link #TAKEOVER_IDLE_MS} with
 * no call running: a test JVM stopped mid-test must not lock the client for the whole TTL. A running call keeps
 * the lease alive however long it takes.
 */
final class Lease {

    static final long DEFAULT_TTL_MS = 120_000L;
    static final long TAKEOVER_IDLE_MS = 15_000L;

    interface Clock {
        long now();
    }

    static final class Grant {
        final String token;
        final String owner;
        final long since;
        final long ttlMs;

        Grant(String token, String owner, long since, long ttlMs) {
            this.token = token;
            this.owner = owner;
            this.since = since;
            this.ttlMs = ttlMs;
        }
    }

    private final Clock clock;
    /** Why no owner may lease the agent right now though nobody holds it - a person busy in the app - or null. */
    private volatile Supplier<String> blocker = () -> null;
    /** Told when the lease is taken (true) and when it ends (false): released, expired or never renewed. */
    private volatile Consumer<Boolean> watcher = held -> { };
    private final SecureRandom random = new SecureRandom();
    private Grant held;
    private long lastCall;
    private int inFlight;
    private int tokenlessInFlight;

    Lease(Clock clock) {
        this.clock = clock;
    }

    /** {@code watch} hears when the lease is taken and when it ends. */
    void watchedBy(Consumer<Boolean> watch) {
        watcher = watch == null ? held -> { } : watch;
    }

    /** {@code reason} answers why a lease must wait, or null; asked on every {@link #acquire}. */
    void blockedBy(Supplier<String> reason) {
        blocker = reason == null ? () -> null : reason;
    }

    synchronized Grant acquire(String owner, Long ttlMs) {
        if (owner == null || owner.trim().isEmpty()) throw new DriverException(Reason.BAD_INPUT, "lease needs an 'owner'");
        long now = clock.now();
        expire(now);
        String blocked = blocker.get();
        if (blocked != null) throw new DriverException(Reason.BUSY, "busy: " + blocked);
        if (tokenlessInFlight > 0) {
            throw new DriverException(Reason.BUSY, "busy: a call without a lease is running");
        }
        if (held != null) {
            if (inFlight > 0 || now - lastCall < TAKEOVER_IDLE_MS) throw busy();
            System.err.println("swing-ui-agent: " + owner.trim() + " took over the lease of " + held.owner
                    + ", idle for " + (now - lastCall) / 1000 + " s");
        }
        long ttl = ttlMs == null || ttlMs <= 0 ? DEFAULT_TTL_MS : ttlMs;
        held = new Grant(newToken(), owner.trim(), now, ttl);
        lastCall = now;
        inFlight = 0;
        watcher.accept(true);
        return held;
    }

    synchronized boolean release(String token) {
        if (held == null || !held.token.equals(token)) return false;
        held = null;
        inFlight = 0;
        watcher.accept(false);
        return true;
    }

    /** Admits one mutating call; pair it with {@link #exit} in a finally. */
    synchronized void enter(String token) {
        long now = clock.now();
        expire(now);
        if (token == null) {
            if (held != null) throw busy();
            tokenlessInFlight++;
            return;
        }
        if (held == null) {
            throw new DriverException(Reason.BUSY,
                    "lease " + token + " is not held (released, expired after its ttl, or the client restarted)");
        }
        if (!held.token.equals(token)) {
            throw new DriverException(Reason.BUSY, "lease " + token + " is not the current one; " + holderText());
        }
        inFlight++;
        lastCall = now;
    }

    synchronized void exit(String token) {
        if (token == null) {
            tokenlessInFlight = Math.max(0, tokenlessInFlight - 1);
            return;
        }
        if (held == null || !held.token.equals(token)) return;
        inFlight = Math.max(0, inFlight - 1);
        lastCall = clock.now();
    }

    /**
     * A read-only call carrying the holder's token renews the lease: a test that only reads, or polls between reads,
     * is still running. No token, or a foreign one, renews nothing and is no error.
     */
    synchronized void renew(String token) {
        long now = clock.now();
        expire(now);
        if (token != null && held != null && held.token.equals(token)) lastCall = now;
    }

    /**
     * The test JVM's heartbeat: renews the lease when {@code token} is the live one. False when it is
     * not (released, expired, taken over, or never held), so the heartbeat can stop and say the lease is gone.
     */
    synchronized boolean renewHeld(String token) {
        long now = clock.now();
        expire(now);
        if (token == null || held == null || !held.token.equals(token)) return false;
        lastCall = now;
        return true;
    }

    /** The live lease, or null. */
    synchronized Grant holder() {
        expire(clock.now());
        return held;
    }

    private void expire(long now) {
        if (held != null && inFlight == 0 && now - lastCall >= held.ttlMs) {
            held = null;
            watcher.accept(false);
        }
    }

    private DriverException busy() {
        return new DriverException(Reason.BUSY, holderText());
    }

    private String holderText() {
        return "busy: leased by " + held.owner + " since " + new SimpleDateFormat("HH:mm:ss").format(new Date(held.since));
    }

    private String newToken() {
        byte[] b = new byte[12];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
