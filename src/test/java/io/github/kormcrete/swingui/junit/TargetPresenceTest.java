package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.testing.RecordingVerbs;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Node;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TargetPresenceTest {
    private final RecordingVerbs verbs = new RecordingVerbs();
    private final Client ui = new Client(verbs, null, "UIT-test");

    @Test void foundIsOne() { assertThat(ui.button("OK").presence()).isEqualTo(Target.Presence.ONE); }

    @Test void notFoundIsNone() {
        verbs.failNext = new DriverException(Reason.NOT_FOUND, "no");
        assertThat(ui.button("OK").exists()).isFalse();
    }

    @Test void ambiguousIsMany() {
        verbs.failNext = new DriverException(Reason.AMBIGUOUS, "two");
        assertThat(ui.dialog().role("button", null).presence()).isEqualTo(Target.Presence.MANY);
    }

    @Test void aBadLocatorIsAnError() {
        verbs.failNext = new DriverException(Reason.BAD_INPUT, "bad");
        assertThatThrownBy(() -> ui.role("knob", "x").exists()).isInstanceOf(UiActionFailed.class);
    }

    @Test void presenceIsOneReadWithoutRowsOrRetry() {
        verbs.failNext = new DriverException(Reason.NOT_FOUND, "no");
        ui.button("OK").presence();
        assertThat(verbs.calls).hasSize(1).allMatch(c -> c.startsWith("read "));
    }

    @Test void loginAsWithoutASessionSwitchIsUnsupported() {
        assertThatThrownBy(() -> ui.loginAs("customer")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void theClientReadsAModalOffTheDialogSnapshot() {
        verbs.modalsShowing = 0;
        assertThat(ui.modalShowing()).isFalse();
        assertThat(ui.describeModal()).isEqualTo("(no modal dialog)");
        verbs.modalsShowing = 1;
        verbs.modalText = "The record is locked";
        assertThat(ui.modalShowing()).isTrue();
        assertThat(ui.describeModal()).isEqualTo("Question: The record is locked");
    }

    @Test void aModalIsFoundOnlyByItsModalFlag() {
        Node app = new Node("app", null);
        app.addChild(new Node("dialog", "Not modal"));
        verbs.readAnswer = app; // what snapshot answers while modalsShowing is -1
        assertThat(ui.modalShowing()).isFalse();
    }
}
