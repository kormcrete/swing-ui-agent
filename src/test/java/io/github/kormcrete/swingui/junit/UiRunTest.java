package io.github.kormcrete.swingui.junit;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UiRunTest {

    static final class App implements AppHooks {
        final List<String> calls = new ArrayList<String>();
        @Override public void reset(ResetSpec spec) { calls.add("reset " + spec.key()); }
        @Override public void login(ResetSpec who, boolean force) { calls.add("login " + who); }
        @Override public AppInfo info() { return new AppInfo("fixture", "1"); }
    }

    private final RecordingVerbs served = new RecordingVerbs();
    private final App app = new App();
    private final InJvmAgent agent = InJvmAgent.start(served,
            new PluginParts(new RecordingHost(served), null, app, null, null, null));
    private final AgentClient client = new AgentClient("127.0.0.1", agent.port());
    private String waitBefore;

    @BeforeEach
    void noWaitForTheTakeover() {
        waitBefore = System.getProperty(UiRun.LEASE_WAIT_PROPERTY);
        System.setProperty(UiRun.LEASE_WAIT_PROPERTY, "0");
    }

    @AfterEach
    void stop() {
        if (waitBefore == null) System.clearProperty(UiRun.LEASE_WAIT_PROPERTY);
        else System.setProperty(UiRun.LEASE_WAIT_PROPERTY, waitBefore);
        agent.stop();
    }

    private com.fasterxml.jackson.databind.JsonNode info() {
        return client.callJson("session", client.args().put("op", "info"));
    }

    @Test
    void beginLeasesAndResetsEndReleases() {
        UiRun run = new UiRun(client, "OrdersSmokeUiIT#opens (UIT-1)");
        Client ui = run.begin("alice", "UIT-1");
        assertThat(app.calls).containsExactly("reset alias:alice");
        assertThat(info().path("lease").path("owner").asText()).isEqualTo("OrdersSmokeUiIT#opens (UIT-1)");
        ui.button("OK").click();
        run.end();
        assertThat(info().get("lease").isNull()).isTrue();
    }

    @Test
    void aSecondOwnerIsBusyWhileTheFirstRuns() {
        UiRun first = new UiRun(client, "A#a (r1)");
        first.begin(null, "r1");
        assertThatThrownBy(() -> new UiRun(client, "B#b (r2)").begin(null, "r2"))
                .isInstanceOf(DriverException.class)
                .hasMessageContaining("busy: leased by A#a (r1)")
                .extracting(e -> ((DriverException) e).reason()).isEqualTo(Reason.BUSY);
        first.end();
    }

    @Test
    void endIsSafeTwiceAndAfterAFailedBegin() {
        UiRun mine = new UiRun(client, "A#a (r1)");
        mine.end(); // before any begin
        UiRun holder = new UiRun(client, "H#h (r0)");
        holder.begin(null, "r0");
        UiRun refused = new UiRun(client, "B#b (r2)");
        assertThatThrownBy(() -> refused.begin(null, "r2")).isInstanceOf(DriverException.class);
        refused.end(); // after a begin that failed on a foreign lease: must not release it
        assertThat(info().path("lease").path("owner").asText()).isEqualTo("H#h (r0)");
        holder.end();
        holder.end();
        assertThat(info().get("lease").isNull()).isTrue();
        mine.begin(null, "r1");
        mine.end();
        mine.end();
        assertThat(info().get("lease").isNull()).isTrue();
    }

    /** Records what a UiRun registers instead of touching the JVM's real shutdown hooks. */
    static final class Hooks implements UiRun.ShutdownHooks {
        final List<Thread> added = new ArrayList<Thread>();
        final List<Thread> removed = new ArrayList<Thread>();
        @Override public void add(Thread hook) { added.add(hook); }
        @Override public void remove(Thread hook) { removed.add(hook); }
    }

    @Test
    void aLeaseRegistersAShutdownHookAndTheReleaseRemovesIt() {
        Hooks hooks = new Hooks();
        UiRun run = new UiRun(client, "A#a (r1)", null, hooks);
        run.begin(null, "r1");
        assertThat(hooks.added).hasSize(1);
        assertThat(run.shutdownHook()).isSameAs(hooks.added.get(0));
        run.end();
        assertThat(hooks.removed).containsExactlyElementsOf(hooks.added);
        assertThat(run.shutdownHook()).isNull();
    }

    @Test
    void theShutdownHookReleasesTheLeaseOfAStoppedTestJvm() throws Exception {
        Hooks hooks = new Hooks();
        UiRun run = new UiRun(client, "A#a (r1)", null, hooks);
        run.begin(null, "r1");
        assertThat(info().path("lease").path("owner").asText()).isEqualTo("A#a (r1)");
        Thread hook = hooks.added.get(0);
        long t0 = System.currentTimeMillis();
        hook.run();                                  // what the JVM does on Stop
        assertThat(System.currentTimeMillis() - t0).isLessThan(UiRun.HOOK_RELEASE_MS + 1_000L);
        assertThat(info().get("lease").isNull()).isTrue();
        run.end();                                   // the late end is harmless
        assertThat(info().get("lease").isNull()).isTrue();
    }

    @Test
    void theShutdownHookSwallowsAnUnreachableAgent() {
        Hooks hooks = new Hooks();
        UiRun run = new UiRun(client, "A#a (r1)", null, hooks);
        run.begin(null, "r1");
        agent.stop();
        hooks.added.get(0).run();                    // must neither throw nor hang
        run.end();                                   // stops the heartbeat thread
    }

    @Test
    void theHeartbeatThreadIsADaemonAndStopsOnEnd() throws Exception {
        UiRun run = new UiRun(client, "UiRunTest#hb", null, new Hooks(), 20);
        run.begin(null, "UIT-1");
        Thread hb = findThread("swingui-lease-heartbeat UiRunTest#hb");
        assertThat(hb).isNotNull();
        assertThat(hb.isDaemon()).isTrue();
        run.end();
        hb.join(1_000);
        assertThat(hb.isAlive()).isFalse();
        assertThat(run.leaseLost()).isNull();
    }

    private static Thread findThread(String name) {
        for (Thread t : Thread.getAllStackTraces().keySet()) if (name.equals(t.getName())) return t;
        return null;
    }

    @Test
    void aBegunClientSavesScreenshotsUnderTheRunsFolder(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        UiRun run = new UiRun(client, "A#a (r1)", dir.resolve("ui-screenshots"), new Hooks());
        Client ui = run.begin(null, "r1");
        ui.saveScreenshot("shots/a.png");
        run.end();
        assertThat(Files.readAllBytes(dir.resolve("ui-screenshots/shots/a.png"))).containsExactly(1, 2, 3);
    }

    @Test
    void theEdtStackComesFromTheClient() {
        assertThat(new UiRun(client, "A#a (r1)").edtStack()).contains("AWT-EventQueue");
    }
}
