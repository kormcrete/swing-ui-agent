package io.github.kormcrete.swingui.junit;

/**
 * Keeps a test's lease alive while the test makes no agent call, for example while it polls a database for two
 * minutes. A daemon thread renews every period; it dies with a stopped test JVM, so the agent's idle
 * takeover still frees the client. A renew that answers "not held" stops it and is reported through {@link #lost}.
 * A renew that throws (agent briefly unreachable) is retried: the test's own next call reports a dead agent.
 */
final class LeaseHeartbeat {

    /** One renew: true while the lease is still held. */
    interface Renew {
        boolean renew();
    }

    private final Renew renew;
    private final long periodMs;
    private final String threadName;
    private volatile String lost;
    private Thread thread;

    LeaseHeartbeat(Renew renew, long periodMs, String threadName) {
        this.renew = renew;
        this.periodMs = periodMs;
        this.threadName = threadName;
    }

    synchronized void start() {
        if (thread != null) return;
        thread = new Thread(new Runnable() {
            @Override public void run() {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(periodMs);
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        if (!renew.renew()) {
                            lost = "the lease was lost (taken over, expired, or the client restarted)";
                            return;
                        }
                    } catch (RuntimeException e) {
                        // unreachable for a moment: try again next period
                    }
                }
            }
        }, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        Thread t = thread;
        if (t == null) return;
        t.interrupt();
        try {
            t.join(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    synchronized boolean isRunning() {
        return thread != null && thread.isAlive();
    }

    /** Why the lease is gone, or null while it is held. */
    String lost() {
        return lost;
    }
}
