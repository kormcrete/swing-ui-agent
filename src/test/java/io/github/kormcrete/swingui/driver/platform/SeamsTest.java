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
    void macOnLaterJavaGetsTheMacSeamToo() {
        assertThat(Seams.select("Mac OS X", "11.0.19", () -> mac)).isSameAs(mac);
        assertThat(Seams.select("Mac OS X", "17.0.9", () -> mac)).isSameAs(mac);
        assertThat(Seams.select("Mac OS X", "25", () -> mac)).isSameAs(mac);
    }

    @Test
    void windowsAndLinuxAreNotSupportedYet() {
        assertThat(catchThrowableOfType(() -> Seams.select("Windows 10", "1.8.0_291", () -> mac), DriverException.class)
                .getMessage()).contains("Windows 10 on Java 1.8.0_291").contains("not supported yet");
        assertThat(catchThrowableOfType(() -> Seams.select("Linux", "1.8.0_291", () -> mac), DriverException.class)
                .getMessage()).contains("Linux on Java 1.8.0_291").contains("not supported yet");
    }
}
