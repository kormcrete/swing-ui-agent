package io.github.kormcrete.swingui.junit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

/**
 * Releases one final action per client at the same moment: each on its own thread, all waiting on one
 * barrier, so their requests reach the server within milliseconds of each other. Which one wins is not controlled:
 * a simultaneous test asserts an invariant (exactly one wins, the data is consistent), never a winner. Prepare each
 * client step by step first, and pass only the final action, one per client.
 * <p>
 * A timed-out action's HTTP call stays blocked up to the client read timeout. The agent serialises driver calls, so
 * nothing interleaves, but the failing test's evidence and the next test's reset may wait that long, and a hung case
 * gets no EDT stack.
 */
public final class UiParallel {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private UiParallel() {}

    public static void run(Runnable... actions) {
        run(DEFAULT_TIMEOUT, actions);
    }

    public static void run(Duration timeout, Runnable... actions) {
        if (actions == null || actions.length < 2) {
            throw new IllegalArgumentException("UiParallel.run needs at least two actions, got "
                    + (actions == null ? 0 : actions.length));
        }
        final int n = actions.length;
        final CyclicBarrier barrier = new CyclicBarrier(n);
        final Throwable[] failures = new Throwable[n];
        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            final int k = i;
            final Runnable action = actions[i];
            threads[i] = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        barrier.await();
                        action.run();
                    } catch (BrokenBarrierException e) {
                        failures[k] = new AssertionError("UiParallel: action " + (k + 1) + " never started: another one broke the barrier");
                    } catch (Throwable t) {
                        failures[k] = t;
                    }
                }
            }, "ui-parallel-" + (i + 1));
            threads[i].setDaemon(true);
            threads[i].start();
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        for (int i = 0; i < n; i++) {
            long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            try {
                if (left > 0) threads[i].join(left);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interruptAll(threads);
                throw new AssertionError("UiParallel: interrupted while waiting for the actions");
            }
            if (threads[i].isAlive()) {
                interruptAll(threads);
                AssertionError hung = new AssertionError("UiParallel: action " + (i + 1) + " of " + n
                        + " still running after " + timeout.toMillis() + " ms");
                // keep the failures of the actions that did end (a thread still alive may yet write its slot)
                for (int j = 0; j < n; j++) {
                    if (j != i && !threads[j].isAlive() && failures[j] != null) hung.addSuppressed(failures[j]);
                }
                throw hung;
            }
        }
        List<Integer> failed = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) if (failures[i] != null) failed.add(i + 1);
        if (failed.isEmpty()) return;
        Throwable first = failures[failed.get(0) - 1];
        StringBuilder which = new StringBuilder();
        for (Integer f : failed) which.append(which.length() == 0 ? "" : ", ").append(f);
        AssertionError error = new AssertionError("UiParallel: action(s) " + which + " of " + n + " failed: "
                + first.getMessage(), first);
        for (int i = 1; i < failed.size(); i++) error.addSuppressed(failures[failed.get(i) - 1]);
        throw error;
    }

    private static void interruptAll(Thread[] threads) {
        for (Thread t : threads) t.interrupt();
    }
}
