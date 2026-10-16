package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.vocab.Matcher;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs a recording's folding ({@link Matcher#fold}) on a thread of its own, off the EDT and without the session's lock:
 * the session hands over a {@link Job} (its records as of one revision) under its lock, the matcher thread matches, and
 * the result is applied under the lock again ({@link Apply}), where the session drops it when the recording changed
 * meanwhile. A newer job replaces one not started yet. Lock order: the session's monitor, then this one; the matcher
 * thread never holds this monitor while it waits for the session's.
 */
final class Folding {

    private static final Logger LOG = Logger.getLogger(Folding.class.getName());

    /** The time all matches of one job have together. */
    static final long BUDGET_MS = 2000;
    /** The matcher thread's stack: a match nests its fits in the call stack (see {@code Pattern}). */
    private static final long STACK_BYTES = 32L << 20;
    /** An idle matcher thread ends after this long; the next job starts a new one. */
    private static final long IDLE_MS = 30_000;

    /** One look at the recording, as of revision {@link #rev}. */
    static final class Job {
        final int rev;
        final List<Matcher.Item> items;
        final List<Matcher.Candidate> vocabulary;
        /** The highest record id among the items: every record up to it has been looked at once this job is applied. */
        final int newest;

        Job(int rev, List<Matcher.Item> items, List<Matcher.Candidate> vocabulary, int newest) {
            this.rev = rev;
            this.items = items;
            this.vocabulary = vocabulary;
            this.newest = newest;
        }
    }

    /** Applies a job's result; called with the session's lock held. */
    interface Apply {
        void apply(Job job, Matcher.Result result);
    }

    private final Object session;
    private final Apply apply;
    private final long budgetNanos;
    // guarded by this
    private Job pending;
    private Job running;
    private Job doneJob;
    private Matcher.Result doneResult;
    /** A finished job is being applied (under the session's lock). */
    private boolean applying;
    private Thread thread;

    Folding(Object session, Apply apply) {
        this(session, apply, BUDGET_MS);
    }

    Folding(Object session, Apply apply, long budgetMs) {
        this.session = session;
        this.apply = apply;
        this.budgetNanos = TimeUnit.MILLISECONDS.toNanos(budgetMs);
    }

    /** Hands {@code job} to the matcher thread (replacing one it has not started), starting the thread when needed. */
    synchronized void schedule(Job job) {
        pending = job;
        if (thread == null) {
            thread = new Thread(null, this::loop, "swing-ui-agent folding", STACK_BYTES);
            thread.setDaemon(true);
            thread.start();
        }
        notifyAll();
    }

    /**
     * Waits until no job is waiting, matching or waiting to be applied, at most {@code maxMs}. With {@code holdingLock}
     * (the caller holds the session's lock, so the matcher thread cannot apply) it applies a finished job itself.
     */
    void settle(long maxMs, boolean holdingLock) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxMs);
        while (true) {
            if (holdingLock) applyDone();
            synchronized (this) {
                boolean idle = pending == null && running == null && (holdingLock || doneJob == null && !applying);
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (idle || left <= 0) return;
                try {
                    wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Applies the finished job, if one waits. The caller holds the session's lock. */
    void applyDone() {
        Job job;
        Matcher.Result result;
        synchronized (this) {
            job = doneJob;
            result = doneResult;
            doneJob = null;
            doneResult = null;
            if (job == null) return;
            applying = true;
        }
        try {
            apply.apply(job, result);
        } catch (RuntimeException e) {
            // a fold that cannot be made leaves the recording as it is; folding goes on with the next steps
            LOG.log(Level.WARNING, "a fold could not be made; the steps stay as they are", e);
        } finally {
            synchronized (this) {
                applying = false;
                notifyAll();
            }
        }
    }

    private void loop() {
        while (true) {
            Job job;
            synchronized (this) {
                long idleSince = System.nanoTime();
                while (pending == null) {
                    long left = IDLE_MS - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - idleSince);
                    if (left <= 0) {
                        thread = null;
                        return;
                    }
                    try {
                        wait(left);
                    } catch (InterruptedException e) {
                        thread = null;
                        return;
                    }
                }
                job = pending;
                pending = null;
                running = job;
            }
            Matcher.Result result;
            try {
                result = Matcher.fold(job.items, job.vocabulary, System.nanoTime() + budgetNanos);
            } catch (RuntimeException | StackOverflowError e) {
                // a matcher that breaks folds nothing; the recording stays as it is
                LOG.log(Level.WARNING, "folding failed; the steps stay as they are", e);
                result = null;
            }
            synchronized (this) {
                running = null;
                doneJob = job;
                doneResult = result;
                notifyAll();
            }
            synchronized (session) {
                applyDone();
            }
        }
    }
}
