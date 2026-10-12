package io.github.kormcrete.swingui.junit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UiParallelTest {

    @Test
    void theActionsRunAtTheSameTime() {
        final CountDownLatch both = new CountDownLatch(2);
        Runnable meet = () -> {
            both.countDown();
            try {
                if (!both.await(2, TimeUnit.SECONDS)) throw new AssertionError("the other action never ran alongside");
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        };
        UiParallel.run(meet, meet);
    }

    @Test
    void theyAreReleasedTogetherBehindTheBarrier() {
        final AtomicLong first = new AtomicLong(), second = new AtomicLong();
        UiParallel.run(() -> first.set(System.nanoTime()), () -> second.set(System.nanoTime()));
        assertThat(Math.abs(first.get() - second.get())).isLessThan(TimeUnit.MILLISECONDS.toNanos(50));
    }

    @Test
    void bothFailuresAreReported() {
        assertThatThrownBy(() -> UiParallel.run(
                () -> { throw new IllegalStateException("a refused"); },
                () -> { throw new AssertionError("b refused"); }))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("UiParallel: action(s) 1, 2 of 2 failed")
                .satisfies(t -> assertThat(t.getSuppressed()).hasSize(1));
    }

    @Test
    void oneFailureLeavesTheOtherActionRunToItsEnd() {
        final AtomicLong done = new AtomicLong();
        assertThatThrownBy(() -> UiParallel.run(
                () -> { throw new IllegalStateException("a refused"); },
                () -> { sleep(100); done.set(1); }))
                .hasMessageContaining("action(s) 1 of 2 failed").hasCauseInstanceOf(IllegalStateException.class);
        assertThat(done.get()).isEqualTo(1);
    }

    @Test
    void anActionThatNeverReturnsFailsAtTheTimeoutNamingIt() {
        final CountDownLatch never = new CountDownLatch(1);
        long t0 = System.currentTimeMillis();
        assertThatThrownBy(() -> UiParallel.run(Duration.ofMillis(300),
                () -> { },
                () -> { try { never.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }))
                .isInstanceOf(AssertionError.class)
                .hasMessage("UiParallel: action 2 of 2 still running after 300 ms");
        assertThat(System.currentTimeMillis() - t0).isLessThan(2_000);
    }

    @Test
    void theFailureOfTheOtherActionIsKeptWhenOneNeverReturns() {
        final CountDownLatch never = new CountDownLatch(1);
        assertThatThrownBy(() -> UiParallel.run(Duration.ofMillis(1_000),
                () -> { throw new IllegalStateException("a refused"); },
                () -> { try { never.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }))
                .isInstanceOf(AssertionError.class)
                .hasMessage("UiParallel: action 2 of 2 still running after 1000 ms")
                .satisfies(t -> assertThat(t.getSuppressed()).hasSize(1)
                        .allSatisfy(s -> assertThat(s).isInstanceOf(IllegalStateException.class).hasMessage("a refused")));
    }

    @Test
    void fewerThanTwoActionsIsAMistake() {
        assertThatThrownBy(() -> UiParallel.run(() -> { })).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
