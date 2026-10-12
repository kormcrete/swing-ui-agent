package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.testing.RecordingApp;
import io.github.kormcrete.swingui.testing.RecordingVerbs;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientReloginTest {

    interface Tabs { List<String> openTabTitles(); }

    @Test
    void loginCallsGoToTheAppAndSurviveTimeoutCopies() {
        RecordingApp app = new RecordingApp();
        Client ui = new Client(new RecordingVerbs(), app, "r").timeout(Duration.ofSeconds(3));
        ui.loginAs("customer");
        ui.loginAs("customer1", "pw");
        ui.relogin();
        assertThat(app.calls).containsExactly("app loginAs customer", "app loginAs customer1/pw", "app relogin");
    }

    @Test
    void hooksAreTyped() {
        RecordingApp app = new RecordingApp().register(Tabs.class, () -> Collections.singletonList("Orders"));
        assertThat(new Client(new RecordingVerbs(), app, "r").hooks(Tabs.class).openTabTitles()).containsExactly("Orders");
    }

    @Test
    void withoutAnAppTheSessionCallsAreUnsupported() {
        Client ui = new Client(new RecordingVerbs(), null, "r");
        assertThatThrownBy(() -> ui.relogin()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ui.hooks(Tabs.class)).isInstanceOf(UnsupportedOperationException.class);
    }
}
