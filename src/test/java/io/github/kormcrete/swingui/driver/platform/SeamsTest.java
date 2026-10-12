package io.github.kormcrete.swingui.driver.platform;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class SeamsTest {

    private final FakeSeam seam = new FakeSeam();
    private final List<Platform> asked = new ArrayList<Platform>();
    private final Function<Platform, Seam> factory = p -> {
        asked.add(p);
        return seam;
    };

    @Test
    void macWindowsAndLinuxGetTheSeamForTheirPlatformOnAnyJava() {
        assertThat(Seams.select("Mac OS X", "1.8.0_291", factory)).isSameAs(seam);
        assertThat(Seams.select("Windows 11", "21.0.10", factory)).isSameAs(seam);
        assertThat(Seams.select("Windows Server 2022", "1.8.0_482", factory)).isSameAs(seam);
        assertThat(Seams.select("Linux", "25", factory)).isSameAs(seam);
        assertThat(asked).containsExactly(Platform.MAC, Platform.WINDOWS, Platform.WINDOWS, Platform.LINUX);
    }

    @Test
    void anotherOsIsNotSupportedYet() {
        DriverException e = catchThrowableOfType(() -> Seams.select("FreeBSD", "17.0.5", factory), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.CAPABILITY_GAP);
        assertThat(e.getMessage()).contains("FreeBSD on Java 17.0.5").contains("not supported yet")
                .contains("macOS, Windows and Linux");
        assertThat(asked).isEmpty();
    }
}
