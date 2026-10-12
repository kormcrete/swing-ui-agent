package io.github.kormcrete.swingui.driver;

/**
 * What one thread has spent on the event dispatch thread since its last {@link #reset}: time a task
 * RAN there, time the caller WAITED for it to start (the queue), and how many tasks it handed over.
 *
 * <p>Per calling thread, so the MCP request thread measures its own request only. Kept by
 * {@link Edt#call}; EDT work that does not route through it (e.g. an {@code invokeLater} a verb then
 * waits on through a latch) is not counted and lands in the caller's off-EDT remainder. A call made
 * on the EDT runs inline and is not counted itself; its time is already inside the enclosing task's
 * run time, so nothing is counted twice.
 */
public final class EdtClock {

    private static final ThreadLocal<long[]> COUNTERS = new ThreadLocal<long[]>() {
        @Override protected long[] initialValue() {
            return new long[3];
        }
    };

    private static final int RUN = 0;
    private static final int WAIT = 1;
    private static final int CALLS = 2;

    private EdtClock() {}

    /** Starts this thread's count afresh. */
    public static void reset() {
        long[] c = COUNTERS.get();
        c[RUN] = 0;
        c[WAIT] = 0;
        c[CALLS] = 0;
    }

    /** Adds one hand-over: {@code blockedNanos} the caller waited in total, {@code runNanos} of it running. */
    public static void add(long blockedNanos, long runNanos) {
        long[] c = COUNTERS.get();
        c[RUN] += runNanos;
        c[WAIT] += Math.max(0, blockedNanos - runNanos);
        c[CALLS]++;
    }

    /** A copy of this thread's counters so far. */
    public static Reading read() {
        long[] c = COUNTERS.get();
        return new Reading(c[RUN], c[WAIT], c[CALLS]);
    }

    /** Run and wait time in nanoseconds, and the number of hand-overs. */
    public static final class Reading {
        public final long runNanos;
        public final long waitNanos;
        public final long calls;

        public Reading(long runNanos, long waitNanos, long calls) {
            this.runNanos = runNanos;
            this.waitNanos = waitNanos;
            this.calls = calls;
        }

        /** What was spent between {@code earlier} and this reading, on the same thread. */
        public Reading since(Reading earlier) {
            return new Reading(runNanos - earlier.runNanos, waitNanos - earlier.waitNanos, calls - earlier.calls);
        }

        public long runMs() {
            return runNanos / 1_000_000L;
        }

        public long waitMs() {
            return waitNanos / 1_000_000L;
        }
    }
}
