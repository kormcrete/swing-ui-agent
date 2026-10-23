package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.journal.RunFolder;
import io.github.kormcrete.swingui.journal.TestJournal;

import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Entry point: {@code assertThat(ui.button("Save")).isDisabled()}. Every assertion retries until its wait ends. */
public final class UiAssertions {
    private static final long EVENTUALLY_POLL_MS = 100;
    /** The {@link #eventually} polling on this thread; null when none. */
    private static final ThreadLocal<Poll> POLLING = new ThreadLocal<Poll>();

    private UiAssertions() {}

    public static TargetAssert assertThat(Target target) {
        return new TargetAssert(target);
    }

    /**
     * Polls {@code read} until {@code ok} accepts its value and returns that value: the retrying form of a
     * read for a check no single assertion expresses. Not found / ambiguous is "not yet"; any other failure
     * propagates at once. The run journal gets one check line for it, not a read line per poll; nothing when
     * {@code read} reads no Client. An eventually inside another polls as part of the outer one.
     */
    public static <T> T eventually(String what, Duration within, Supplier<T> read, Predicate<? super T> ok) {
        long wait = Target.millis(within);
        long start = System.currentTimeMillis();
        if (POLLING.get() != null) return poll(what, wait, start, read, ok);
        Poll poll = new Poll();
        POLLING.set(poll);
        Throwable failure = null;
        try {
            return poll(what, wait, start, read, ok);
        } catch (RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            POLLING.remove();
            poll.journal(what, start, failure);
        }
    }

    private static <T> T poll(String what, long wait, long start, Supplier<T> read, Predicate<? super T> ok) {
        Object last = "(nothing read)";
        while (true) {
            try {
                T value = read.get();
                if (ok.test(value)) return value;
                last = value;
            } catch (UiActionFailed e) {
                if (e.reason() != Reason.NOT_FOUND && e.reason() != Reason.AMBIGUOUS) throw e;
                last = e.getMessage();
            }
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed >= wait) {
                throw new UiAssertionError("Expected " + what + "\nlast read: " + last + "\nwaited: " + elapsed + " ms",
                        null, null);
            }
            try {
                Thread.sleep(EVENTUALLY_POLL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for " + what, ie);
            }
        }
    }

    /** The {@link #eventually} polling on this thread, which takes the Client reads made on it; null when none. */
    static Poll polling() {
        return POLLING.get();
    }

    /** What one {@link #eventually} saw: the journal, client, target and value of its last Client read. */
    static final class Poll {
        private TestJournal journal;
        private int client;
        private Locator target;
        private Object value;

        /** A Client read polled: {@code value} null when it failed. */
        void saw(TestJournal journal, int client, Locator target, Object value) {
            this.journal = journal;
            this.client = client;
            this.target = target == null ? null : target.copy();
            this.value = value;
        }

        /** The one check line, to the journal of the last read; nothing when no Client read ran. */
        void journal(String what, long startedAt, Throwable failure) {
            if (journal == null) return;
            try {
                journal.eventually(client, what, target, value, startedAt, failure);
            } catch (RuntimeException e) {
                RunFolder.warnOff(e);   // the journal never fails a check
            }
        }
    }
}
