package io.github.kormcrete.swingui.driver;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class DriverExceptionTest {

    @Test
    void theMessageStartsWithTheReason() {
        DriverException e = new DriverException(Reason.NOT_ACTIONABLE, "disabled: Save");
        assertThat(e.getMessage()).isEqualTo("NOT_ACTIONABLE: disabled: Save");
        assertThat(e.reason()).isEqualTo(Reason.NOT_ACTIONABLE);
        assertThat(e.details()).isEmpty();
    }

    @Test
    void detailsAreKeptAndCannotBeChanged() {
        DriverException e = new DriverException(Reason.AMBIGUOUS, "2 match", Arrays.asList("a", "b"));
        assertThat(e.details()).containsExactly("a", "b");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> e.details().add("c"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void onlyGapsBadInputAndBusyAreToolErrors() {
        for (Reason r : Reason.values()) {
            assertThat(r.isToolError()).as(r.name())
                    .isEqualTo(r == Reason.CAPABILITY_GAP || r == Reason.BAD_INPUT || r == Reason.BUSY);
        }
    }
}
