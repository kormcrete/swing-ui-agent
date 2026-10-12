package io.github.kormcrete.swingui.driver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class LaunchModeTest {

    private String saved;

    @BeforeEach
    void save() {
        saved = System.getProperty(LaunchMode.UI_ELEMENT);
        System.clearProperty(LaunchMode.UI_ELEMENT);
    }

    @AfterEach
    void restore() {
        if (saved == null) System.clearProperty(LaunchMode.UI_ELEMENT);
        else System.setProperty(LaunchMode.UI_ELEMENT, saved);
    }

    @Test
    void normalIsTheDefaultAndSetsNothing() {
        assertThat(LaunchMode.apply(null)).isEqualTo(LaunchMode.NORMAL);
        assertThat(LaunchMode.apply("  ")).isEqualTo(LaunchMode.NORMAL);
        assertThat(LaunchMode.apply("Normal")).isEqualTo(LaunchMode.NORMAL);
        assertThat(System.getProperty(LaunchMode.UI_ELEMENT)).isNull();
    }

    @Test
    void backgroundSetsUiElement() {
        assertThat(LaunchMode.apply(" background ")).isEqualTo(LaunchMode.BACKGROUND);
        assertThat(System.getProperty(LaunchMode.UI_ELEMENT)).isEqualTo("true");
    }

    @Test
    void anUnknownModeNamesTheProperty() {
        DriverException e = catchThrowableOfType(() -> LaunchMode.apply("headless"), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("swingui.mode").contains("headless");
    }

    @Test
    void normalModeDefaults() {
        DriverSettings s = DriverSettings.defaults(LaunchMode.NORMAL);
        assertThat(s.settleMillis).isEqualTo(30_000);
        assertThat(s.jobActionMillis).isEqualTo(120_000);
        assertThat(s.expectMillis).isEqualTo(10_000);
        assertThat(s.maxNodes).isEqualTo(400);
        assertThat(s.maxRows).isEqualTo(20);
    }
}
