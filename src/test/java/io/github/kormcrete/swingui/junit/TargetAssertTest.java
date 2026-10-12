package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.testing.RecordingVerbs;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Locator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.github.kormcrete.swingui.junit.Rows.row;
import static io.github.kormcrete.swingui.junit.UiAssertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class TargetAssertTest {

    private final RecordingVerbs verbs = new RecordingVerbs();
    private final Client ui = new Client(verbs, null, "UIT-test");

    @Test
    void eachAssertionIsOneExpectCall() {
        assertThat(ui.button("Save")).isDisabled();
        assertThat(ui.button("Save")).isEnabled();
        assertThat(ui.field("Short")).hasValue("Widget 3");
        assertThat(ui.table("Orders")).hasRowCount("gte", 1).hasCell(row("Order no.", "ORD-1640"), "Customer", "Test");
        String b = Locator.of("button", "Save").toString();
        org.assertj.core.api.Assertions.assertThat(verbs.calls).containsExactly(
                "expect " + b + " state=[disabled] null",
                "expect " + b + " state=[!disabled] null",
                "expect " + Locator.of("text", "Short") + " value=Widget 3 null",
                "expect " + Locator.of("table", "Orders") + " rows=gte 1 null",
                "expect " + Locator.of("table", "Orders") + " cell={Order no.=ORD-1640}/Customer=Test null");
    }

    @Test
    void isVisibleSendsNotHidden() {
        assertThat(ui.label("Status")).isVisible();
        org.assertj.core.api.Assertions.assertThat(verbs.calls.get(0)).contains("state=[!hidden]");
    }

    @Test
    void withinOverridesTheWait() {
        assertThat(ui.button("OK")).within(Duration.ofSeconds(3)).isEnabled();
        org.assertj.core.api.Assertions.assertThat(verbs.calls.get(0)).endsWith(" 3000");
    }

    @Test
    void withinRejectsANonPositiveDuration() {
        IllegalArgumentException e = catchThrowableOfType(
                () -> assertThat(ui.button("OK")).within(Duration.ZERO), IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThat(e).hasMessageContaining("positive");
    }

    @Test
    void anExpectationThatNeverHoldsIsAnAssertionErrorQuotingTheLastValue() {
        verbs.expectFailuresBeforePass = -1;
        UiAssertionError e = catchThrowableOfType(
                () -> assertThat(ui.field("Short")).hasText("X"), UiAssertionError.class);
        org.assertj.core.api.Assertions.assertThat(e).hasMessageContaining("have text 'X'")
                .hasMessageContaining("expected text 'X' but was 'Y'");
        org.assertj.core.api.Assertions.assertThat(e.target()).isEqualTo(Locator.of("text", "Short"));
    }

    @Test
    void aTargetThatNeverAppearsIsAnAssertionErrorToo() {
        verbs.failNext = new DriverException(Reason.NOT_FOUND, "no label 'Status'");
        UiAssertionError e = catchThrowableOfType(() -> assertThat(ui.label("Status")).isVisible(), UiAssertionError.class);
        org.assertj.core.api.Assertions.assertThat(e).hasMessageContaining("no label 'Status'");
    }

    @Test
    void aBadLocatorIsNotAnAssertionFailure() {
        verbs.failNext = new DriverException(Reason.BAD_INPUT, "role 'knob' is not a role");
        UiActionFailed e = catchThrowableOfType(() -> assertThat(ui.role("knob", "x")).isVisible(), UiActionFailed.class);
        org.assertj.core.api.Assertions.assertThat(e).isNotNull();
        org.assertj.core.api.Assertions.assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
    }

    @Test
    void isAbsentPassesOnceTheTargetIsGone() {
        verbs.failNext = new DriverException(Reason.NOT_FOUND, "gone");
        assertThat(ui.clickable("summary.column.ORD-1550")).isAbsent();
        org.assertj.core.api.Assertions.assertThat(verbs.calls).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(verbs.calls.get(0)).startsWith("read ");
    }

    @Test
    void isAbsentFailsWhileTheTargetStaysWithinTheWait() {
        UiAssertionError e = catchThrowableOfType(
                () -> assertThat(ui.clickable("x")).within(Duration.ofMillis(300)).isAbsent(), UiAssertionError.class);
        org.assertj.core.api.Assertions.assertThat(e).hasMessageContaining("be absent");
    }

    @Test
    void severalStatesAreOneExpectation() {
        assertThat(ui.field("Short")).hasStates("editable", "!disabled");
        org.assertj.core.api.Assertions.assertThat(verbs.calls.get(0)).contains("state=[editable, !disabled]");
    }
}
