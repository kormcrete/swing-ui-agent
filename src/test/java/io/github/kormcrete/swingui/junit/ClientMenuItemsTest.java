package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.testing.RecordingVerbs;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientMenuItemsTest {
    private final RecordingVerbs verbs = new RecordingVerbs();
    private final Client ui = new Client(verbs, null, "UIT-test");

    @Test
    void readsThePathItNames() {
        verbs.menuItemsAnswer = Arrays.asList("Groups", "Users");
        assertThat(ui.menuItems("Administration", "System")).containsExactly("Groups", "Users");
        assertThat(verbs.calls).containsExactly("menuItems [Administration, System]");
    }

    @Test
    void aDriverFailureIsAUiActionFailed() {
        verbs.failNext = new DriverException(Reason.NOT_FOUND, "no menu 'X'");
        assertThatThrownBy(() -> ui.menuItems("X")).isInstanceOf(UiActionFailed.class).hasMessageContaining("no menu 'X'");
    }
}
