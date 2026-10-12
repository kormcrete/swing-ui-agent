package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UiAssertionsEventuallyTest {

    @Test
    void returnsTheFirstValueThatHolds() {
        AtomicInteger reads = new AtomicInteger();
        Integer v = UiAssertions.eventually("the count to reach 3", Duration.ofSeconds(5),
                reads::incrementAndGet, n -> n >= 3);
        assertThat(v).isEqualTo(3);
    }

    @Test
    void failsWithTheLastValueAndTheWait() {
        assertThatThrownBy(() -> UiAssertions.eventually("a tab titled 'Report'", Duration.ofMillis(300),
                () -> Collections.singletonList("Orders"), t -> t.contains("Report")))
                .isInstanceOf(UiAssertionError.class)
                .hasMessageContaining("a tab titled 'Report'")
                .hasMessageContaining("[Orders]")
                .hasMessageContaining("waited");
    }

    @Test
    void notFoundIsNotYet() {
        AtomicInteger reads = new AtomicInteger();
        String v = UiAssertions.eventually("the label", Duration.ofSeconds(5), () -> {
            if (reads.incrementAndGet() < 3) {
                throw new UiActionFailed("read", null, new DriverException(Reason.NOT_FOUND, "no label"),
                        Collections.<String>emptyList());
            }
            return "Page 2 of 9";
        }, s -> s.startsWith("Page"));
        assertThat(v).isEqualTo("Page 2 of 9");
    }

    @Test
    void anyOtherFailurePropagatesAtOnce() {
        AtomicInteger reads = new AtomicInteger();
        assertThatThrownBy(() -> UiAssertions.eventually("x", Duration.ofSeconds(5), () -> {
            reads.incrementAndGet();
            throw new UiActionFailed("read", null, new DriverException(Reason.BAD_INPUT, "bad locator"),
                    Collections.<String>emptyList());
        }, o -> true)).isInstanceOf(UiActionFailed.class);
        assertThat(reads.get()).isEqualTo(1);
    }

    @Test
    void aZeroWaitIsRefused() {
        assertThatThrownBy(() -> UiAssertions.eventually("x", Duration.ZERO, () -> 1, o -> true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
