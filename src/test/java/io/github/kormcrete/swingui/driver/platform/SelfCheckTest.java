package io.github.kormcrete.swingui.driver.platform;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class SelfCheckTest {

    @Test
    void aCompleteSeamPassesAndReportsEveryCapability() {
        CapabilityReport report = SelfCheck.run(new FakeSeam());
        assertThat(report.missingRequired()).isEmpty();
        assertThat(report.lines()).hasSize(Capability.values().length + 1)
                .contains("seam: FakeSeam", "pin the toolkit's focused window: ok");
    }

    @Test
    void aCapabilityThePlatformNeedsNothingForIsReportedWithItsNote() {
        FakeSeam seam = new FakeSeam();
        seam.notes.put(Capability.PIN_FOCUSED_WINDOW, "not needed: Windows reads the focused window from the OS");
        CapabilityReport report = SelfCheck.run(seam);
        assertThat(report.has(Capability.PIN_FOCUSED_WINDOW)).isTrue();
        assertThat(report.lines())
                .contains("pin the toolkit's focused window: not needed: Windows reads the focused window from the OS");
    }

    @Test
    void aMissingOptionalCapabilityIsReportedAsUnavailable() {
        CapabilityReport report = SelfCheck.run(new FakeSeam().without(Capability.APP_ACTIVE, "no LWCToolkit"));
        assertThat(report.has(Capability.APP_ACTIVE)).isFalse();
        assertThat(report.has(Capability.OS_INPUT)).isTrue();
        assertThat(report.lines()).contains("read whether the app is active: unavailable (no LWCToolkit)");
    }

    @Test
    void aMissingRequiredCapabilityRefusesToStartAndNamesTheFix() {
        DriverException e = catchThrowableOfType(
                () -> SelfCheck.run(new FakeSeam().without(Capability.TYPE_AHEAD, "NoSuchFieldException: typeAheadMarkers")),
                DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.CAPABILITY_GAP);
        assertThat(e.getMessage())
                .contains("FakeSeam")
                .contains("read and cancel queued focus requests")
                .contains("NoSuchFieldException: typeAheadMarkers")
                .contains("Fix: use the real seam");
        assertThat(e.details()).contains("read and cancel queued focus requests: MISSING (NoSuchFieldException: typeAheadMarkers)");
    }
}
