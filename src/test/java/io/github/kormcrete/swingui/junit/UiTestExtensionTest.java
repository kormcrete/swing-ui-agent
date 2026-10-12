package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.testing.FixtureClass;
import io.github.kormcrete.swingui.agent.InJvmAgent;
import io.github.kormcrete.swingui.client.AgentClient;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.spi.AppHooks;
import io.github.kormcrete.swingui.spi.AppInfo;
import io.github.kormcrete.swingui.spi.PluginParts;
import io.github.kormcrete.swingui.spi.ResetSpec;
import io.github.kormcrete.swingui.testing.RecordingHost;
import io.github.kormcrete.swingui.testing.RecordingVerbs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

class UiTestExtensionTest {

    /** The agent the inner test class binds to; set before each launch. */
    static volatile AgentClient agentUnderTest;
    static final List<String> cleanUps = new ArrayList<String>();

    /** A test whose reset fails (a stubborn modal), with an @AfterEach that takes a Client. */
    @FixtureClass
    static class ResetFailsWithAClientCleanUp {
        @RegisterExtension
        static final UiTestExtension EXT = new UiTestExtension(c -> UiPool.of(agentUnderTest));

        @Test
        void runs(Client ui) {
            cleanUps.add("test body ran");
        }

        @AfterEach
        void cleanUp(Client ui) {
            cleanUps.add("cleanUp ran");
        }
    }

    static final class StubbornModalApp implements AppHooks {
        final AtomicInteger resets = new AtomicInteger();
        @Override public void reset(ResetSpec spec) {
            resets.incrementAndGet();
            throw new DriverException(Reason.APP_ERROR,
                    "reset: a modal dialog would not close after Escape and a window close: Error");
        }
        @Override public void login(ResetSpec who, boolean force) { }
        @Override public AppInfo info() { return new AppInfo("fixture", "1"); }
    }

    @Test
    void aClientCleanUpAfterAFailedBeginGetsTheSameFailureWithoutLeasingAgain() {
        RecordingVerbs served = new RecordingVerbs();
        StubbornModalApp app = new StubbornModalApp();
        InJvmAgent agent = InJvmAgent.start(served, new PluginParts(new RecordingHost(served), null, app, null, null, null));
        String waitBefore = System.getProperty(UiRun.LEASE_WAIT_PROPERTY);
        System.clearProperty(UiRun.LEASE_WAIT_PROPERTY); // the default wait: a second lease would retry ~15 s
        try {
            AgentClient client = new AgentClient("127.0.0.1", agent.port());
            agentUnderTest = client;
            cleanUps.clear();
            final List<Throwable> failures = new ArrayList<Throwable>();
            long t0 = System.currentTimeMillis();
            LauncherFactory.create().execute(
                    LauncherDiscoveryRequestBuilder.request().selectors(selectClass(ResetFailsWithAClientCleanUp.class)).configurationParameter("swingui.fixtures", "run").build(),
                    new TestExecutionListener() {
                        @Override
                        public void executionFinished(TestIdentifier id, TestExecutionResult result) {
                            if (id.isTest()) failures.add(result.getThrowable().orElse(null));
                        }
                    });
            long ms = System.currentTimeMillis() - t0;

            assertThat(failures).hasSize(1);
            Throwable failure = failures.get(0);
            assertThat(failure).isInstanceOf(ParameterResolutionException.class)
                    .hasMessageContaining("a modal dialog would not close after Escape and a window close: Error");
            // the @AfterEach resolution rethrew that very failure: JUnit adds no second one
            for (Throwable s : failure.getSuppressed()) assertThat(s).isNotInstanceOf(ParameterResolutionException.class);
            assertThat(cleanUps).isEmpty();
            assertThat(app.resets.get()).as("resets: one lease, one reset").isEqualTo(1);
            assertThat(ms).as("no second lease waiting out the takeover").isLessThan(8_000L);
            // the test's own lease was released by afterEach
            assertThat(client.callJson("session", client.args().put("op", "info")).get("lease").isNull()).isTrue();
        } finally {
            if (waitBefore != null) System.setProperty(UiRun.LEASE_WAIT_PROPERTY, waitBefore);
            agentUnderTest = null;
            agent.stop();
        }
    }

    @Test
    void screenshotsGoUnderTheTestModulesTarget() {
        assertThat(UiTestExtension.screenshotRoot(UiTestExtensionTest.class))
                .isEqualTo(ModuleTarget.of(UiTestExtensionTest.class).resolve("ui-screenshots"));
        assertThat(UiTestExtension.screenshotRoot(UiTestExtensionTest.class).toString())
                .endsWith("swing-ui-agent/target/ui-screenshots");
    }
}
