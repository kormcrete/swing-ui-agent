package io.github.kormcrete.swingui.testing;

import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

class FixtureClassConditionTest {

    @FixtureClass
    static class AFixture {
        @Test void fails() { throw new AssertionError("a fixture ran outside its owning test"); }
    }

    private static SummaryGeneratingListener launch(boolean owned) {
        LauncherDiscoveryRequestBuilder b = LauncherDiscoveryRequestBuilder.request().selectors(selectClass(AFixture.class));
        if (owned) b.configurationParameter(FixtureClassCondition.PARAMETER, FixtureClassCondition.VALUE);
        SummaryGeneratingListener l = new SummaryGeneratingListener();
        LauncherFactory.create().execute(b.build(), l);
        return l;
    }

    @Test
    void aPackageRunSkipsAFixtureClass() {
        assertThat(launch(false).getSummary().getContainersSkippedCount()).isEqualTo(1);
        assertThat(launch(false).getSummary().getTestsFailedCount()).isZero();
    }

    @Test
    void itsOwningTestRunsIt() {
        assertThat(launch(true).getSummary().getTestsFailedCount()).isEqualTo(1);
    }
}
