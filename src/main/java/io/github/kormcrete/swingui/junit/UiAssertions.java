package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.driver.Reason;

import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Entry point: {@code assertThat(ui.button("Save")).isDisabled()}. Every assertion retries until its wait ends. */
public final class UiAssertions {
    private static final long EVENTUALLY_POLL_MS = 100;

    private UiAssertions() {}

    public static TargetAssert assertThat(Target target) {
        return new TargetAssert(target);
    }

    /**
     * Polls {@code read} until {@code ok} accepts its value and returns that value: the retrying form of a
     * read for a check no single assertion expresses. Not found / ambiguous is "not yet"; any other failure
     * propagates at once.
     */
    public static <T> T eventually(String what, Duration within, Supplier<T> read, Predicate<? super T> ok) {
        long wait = Target.millis(within);
        long start = System.currentTimeMillis();
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
}
