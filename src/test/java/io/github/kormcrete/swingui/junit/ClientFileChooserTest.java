package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.testing.RecordingVerbs;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.FileChooserState;
import io.github.kormcrete.swingui.driver.Reason;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientFileChooserTest {
    private final RecordingVerbs verbs = new RecordingVerbs();
    private final Client ui = new Client(verbs, null, "UIT-test");

    @Test
    void readsTheFolderNameAndFocusOfTheShowingChooser() {
        verbs.fileChooserAnswer = new FileChooserState("/tmp/import", "", true, "Metal Look and Feel");
        FileChooser c = ui.timeout(Duration.ofSeconds(3)).fileChooser();
        assertThat(c.folder()).isEqualTo("/tmp/import");
        assertThat(c.name()).isEmpty();
        assertThat(c.nameFocused()).isTrue();
        assertThat(c.lookAndFeel()).contains("Metal");
        assertThat(verbs.calls).containsExactly("fileChooser 3000");
    }

    @Test
    void theShortcutsReadTheFolderAndTheName() {
        verbs.fileChooserAnswer = new FileChooserState("/tmp/import", "orders.xlsx", false, "Metal Look and Feel");
        assertThat(ui.fileChooserFolder()).isEqualTo("/tmp/import");
        assertThat(ui.fileChooserName()).isEqualTo("orders.xlsx");
        assertThat(verbs.calls).containsExactly("fileChooser null", "fileChooser null");
    }

    @Test
    void noChooserShowingIsAUiActionFailed() {
        verbs.failNext = new DriverException(Reason.NOT_FOUND, "no file chooser is showing");
        assertThatThrownBy(ui::fileChooserFolder).isInstanceOf(UiActionFailed.class)
                .hasMessageContaining("no file chooser is showing");
    }

    @Test
    void focusingTheNameFieldIsAnActionWithTheClientsTimeout() {
        ui.timeout(Duration.ofSeconds(5)).focusFileChooserName();
        assertThat(verbs.calls).containsExactly("focusFileChooserName 5000");
    }

    @Test
    void focusingWithNoChooserShowingIsAUiActionFailed() {
        verbs.failNext = new DriverException(Reason.NOT_FOUND, "no file chooser is showing");
        assertThatThrownBy(ui::focusFileChooserName).isInstanceOf(UiActionFailed.class)
                .hasMessageContaining("no file chooser is showing");
    }
}
