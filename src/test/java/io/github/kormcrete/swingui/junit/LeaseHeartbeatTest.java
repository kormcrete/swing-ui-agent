package io.github.kormcrete.swingui.junit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LeaseHeartbeatTest {

    @Test
    void renewsEveryPeriodUntilStopped() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LeaseHeartbeat h = new LeaseHeartbeat(() -> { calls.incrementAndGet(); return true; }, 20, "hb-test");
        h.start();
        awaitAtLeast(calls, 3);
        h.stop();
        int seen = calls.get();
        assertThat(seen).isGreaterThanOrEqualTo(3);
        Thread.sleep(80);
        assertThat(calls.get()).isEqualTo(seen);
        assertThat(h.isRunning()).isFalse();
        assertThat(h.lost()).isNull();
    }

    @Test
    void aRenewThatSaysTheLeaseIsGoneStopsAndIsReported() throws Exception {
        LeaseHeartbeat h = new LeaseHeartbeat(() -> false, 10, "hb-test");
        h.start();
        long deadline = System.currentTimeMillis() + 5_000;
        while (h.isRunning() && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertThat(h.isRunning()).isFalse();
        assertThat(h.lost()).contains("lease was lost");
    }

    @Test
    void anUnreachableAgentIsRetriedNotFatal() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LeaseHeartbeat h = new LeaseHeartbeat(() -> {
            if (calls.incrementAndGet() < 3) throw new IllegalStateException("connection refused");
            return true;
        }, 10, "hb-test");
        h.start();
        awaitAtLeast(calls, 3);
        h.stop();
        assertThat(calls.get()).isGreaterThanOrEqualTo(3);
        assertThat(h.lost()).isNull();
    }

    /** Waits for {@code n} renews, up to 5 s: a slow or loaded machine runs fewer periods in a fixed sleep. */
    private static void awaitAtLeast(AtomicInteger calls, int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (calls.get() < n && System.currentTimeMillis() < deadline) Thread.sleep(10);
    }
}
