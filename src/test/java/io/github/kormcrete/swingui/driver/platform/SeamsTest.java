package io.github.kormcrete.swingui.driver.platform;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class SeamsTest {

    private final FakeSeam mac = new FakeSeam();

    @Test
    void macOnJava8GetsTheMacSeam() {
        assertThat(Seams.select("Mac OS X", "1.8.0_291", () -> mac)).isSameAs(mac);
    }

    @Test
    void laterJavaIsNotSupportedYet() {
        DriverException e = catchThrowableOfType(() -> Seams.select("Mac OS X", "17.0.5", () -> mac), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.CAPABILITY_GAP);
        assertThat(e.getMessage()).contains("Mac OS X on Java 17.0.5").contains("not supported yet")
                .contains("macOS with Java 8");
    }

    @Test
    void windowsAndLinuxAreNotSupportedYet() {
        assertThat(catchThrowableOfType(() -> Seams.select("Windows 10", "1.8.0_291", () -> mac), DriverException.class)
                .getMessage()).contains("Windows 10 on Java 1.8.0_291").contains("not supported yet");
        assertThat(catchThrowableOfType(() -> Seams.select("Linux", "1.8.0_291", () -> mac), DriverException.class)
                .getMessage()).contains("Linux on Java 1.8.0_291").contains("not supported yet");
    }
}
