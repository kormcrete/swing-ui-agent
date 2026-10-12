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
        Thread.sleep(150);
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
        Thread.sleep(80);
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
        Thread.sleep(100);
        h.stop();
        assertThat(calls.get()).isGreaterThanOrEqualTo(3);
        assertThat(h.lost()).isNull();
    }
}
