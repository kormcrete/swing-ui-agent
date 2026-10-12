package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.testing.FixtureClass;
import io.github.kormcrete.swingui.agent.InJvmAgent;
import io.github.kormcrete.swingui.client.AgentClient;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.spi.AppHooks;
import io.github.kormcrete.swingui.spi.AppInfo;
import io.github.kormcrete.swingui.spi.PluginParts;
import io.github.kormcrete.swingui.spi.ResetSpec;
import io.github.kormcrete.swingui.testing.RecordingHost;
import io.github.kormcrete.swingui.testing.RecordingVerbs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

class UiTestMultiClientTest {

    /** Records the resets of one in-JVM client, tagged with its name. */
    static class NamedApp implements AppHooks {   // not final: one test overrides reset
        final String name;
        NamedApp(String name) { this.name = name; }
        @Override public void reset(ResetSpec spec) { EVENTS.add(name + " reset " + spec.alias); }
        @Override public void login(ResetSpec who, boolean force) { }
        @Override public AppInfo info() { return new AppInfo("fixture", "1"); }
    }

    static final List<String> EVENTS = Collections.synchronizedList(new ArrayList<String>());
    static volatile UiPool poolUnderTest;
    static volatile Client seenX, seenY, seenBefore, seenAfter;

    @FixtureClass
    static class TwoClients {
        @RegisterExtension static final UiTestExtension EXT = new UiTestExtension(c -> poolUnderTest);

        @BeforeEach void before(Client ui) { seenBefore = ui; }

        @Test void both(@As("user1") Client x, @As("user2") Client y) {
            seenX = x;
            seenY = y;
            EVENTS.add("body");
        }

        @AfterEach void after(@As("user2") Client ui) { seenAfter = ui; }
    }

    @FixtureClass
    static class TwoClientsFailing {
        @RegisterExtension static final UiTestExtension EXT = new UiTestExtension(c -> poolUnderTest);

        @Test void fails(@As("user1") Client x, @As("user2") Client y) {
            throw new AssertionError("on purpose");
        }
    }

    @FixtureClass
    static class AfterEachUnknownAlias {
        @RegisterExtension static final UiTestExtension EXT = new UiTestExtension(c -> poolUnderTest);

        @Test void runs(@As("user1") Client x, @As("user2") Client y) { }

        @AfterEach void after(@As("nobody") Client ui) { }
    }

    /** Leaves a warning on each client, so afterEach prints before it ends the runs and releases the pool. */
    @FixtureClass
    static class TwoClientsWithWarnings {
        @RegisterExtension static final UiTestExtension EXT = new UiTestExtension(c -> poolUnderTest);

        @Test void warns(@As("user1") Client x, @As("user2") Client y) {
            x.warn("first");
            y.warn("second");
        }
    }

    private InJvmAgent a, b;
    private AgentClient ca, cb;

    /** Stands in for an app until the agents' ports are known; then it delegates to the one for its port. */
    static final class LateApp implements AppHooks {
        volatile AppHooks app;
        @Override public void recoverSession() { app.recoverSession(); }
        @Override public void reset(ResetSpec spec) { app.reset(spec); }
        @Override public void login(ResetSpec who, boolean force) { app.login(who, force); }
        @Override public AppInfo info() { return app.info(); }
    }

    /** {@code low} runs on the agent with the lower port, which the pool binds first; {@code high} on the other. */
    private void startTwo(AppHooks low, AppHooks high) {
        RecordingVerbs va = new RecordingVerbs(), vb = new RecordingVerbs();
        LateApp appA = new LateApp(), appB = new LateApp();
        a = InJvmAgent.start(va, new PluginParts(new RecordingHost(va), null, appA, null, null, null));
        b = InJvmAgent.start(vb, new PluginParts(new RecordingHost(vb), null, appB, null, null, null));
        // the OS picks the ports: UiPool.of keeps the given order, so put the lower port first, as a parsed spec would
        boolean aFirst = a.port() < b.port();
        appA.app = aFirst ? low : high;
        appB.app = aFirst ? high : low;
        ca = new AgentClient("127.0.0.1", aFirst ? a.port() : b.port());
        cb = new AgentClient("127.0.0.1", aFirst ? b.port() : a.port());
        poolUnderTest = UiPool.of(ca, cb);
        EVENTS.clear();
        seenX = seenY = seenBefore = seenAfter = null;
    }

    private void stopTwo() {
        a.stop();
        b.stop();
    }

    private static List<Throwable> launch(Class<?> fixture) {
        final List<Throwable> failures = new ArrayList<Throwable>();
        LauncherFactory.create().execute(
                LauncherDiscoveryRequestBuilder.request().selectors(selectClass(fixture))
                        .configurationParameter("swingui.fixtures", "run").build(),
                new TestExecutionListener() {
                    @Override public void executionFinished(TestIdentifier id, TestExecutionResult r) {
                        if (r.getThrowable().isPresent()) failures.add(r.getThrowable().get());
                    }
                });
        return failures;
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(dir)) {
            paths = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        }
        for (Path p : paths) Files.delete(p);
    }

    @Test
    void twoClientsAreBoundByPositionAndEachResetAsItsAlias() {
        startTwo(new NamedApp("low"), new NamedApp("high"));
        try {
            assertThat(launch(TwoClients.class)).isEmpty();
            assertThat(seenX).isNotNull().isNotSameAs(seenY);
            assertThat(EVENTS).contains("low reset user1", "high reset user2", "body");
            assertThat(EVENTS.indexOf("low reset user1")).isLessThan(EVENTS.indexOf("high reset user2"));
            assertThat(seenX.runId()).isNotEqualTo(seenY.runId());
        } finally {
            stopTwo();
        }
    }

    @Test
    void aBeforeEachClientIsClientOneAndAnAfterEachAsPicksByAlias() {
        startTwo(new NamedApp("low"), new NamedApp("high"));
        try {
            assertThat(launch(TwoClients.class)).isEmpty();
            assertThat(seenBefore).isSameAs(seenX);
            assertThat(seenAfter).isSameAs(seenY);
            // the lifecycle parameter came first, yet the lease was sized from the test method: two resets, not one
            assertThat(EVENTS).filteredOn(e -> e.contains(" reset ")).hasSize(2);
        } finally {
            stopTwo();
        }
    }

    @Test
    void bothClientsAreReleasedAfterTheTest() {
        startTwo(new NamedApp("low"), new NamedApp("high"));
        try {
            launch(TwoClients.class);
            assertThat(poolUnderTest.reserve(2, "next")).hasSize(2); // a pool of wait 0 would throw if still held
            ca.callJson("session", ca.args().put("op", "lease").put("owner", "next")); // agent leases released too
            cb.callJson("session", cb.args().put("op", "lease").put("owner", "next"));
        } finally {
            stopTwo();
        }
    }

    @Test
    void aFailureWritesOneEvidenceFolderPerClient() throws Exception {
        startTwo(new NamedApp("low"), new NamedApp("high"));
        try {
            Path dir = UiTestExtension.evidenceRoot(TwoClientsFailing.class)
                    .resolve(TwoClientsFailing.class.getSimpleName()).resolve("fails");
            deleteTree(dir); // a previous run's folders would pass this test on their own
            List<Throwable> failures = launch(TwoClientsFailing.class);
            assertThat(failures).hasSize(1);
            assertThat(Files.isDirectory(dir.resolve("client-1"))).isTrue();
            assertThat(Files.isDirectory(dir.resolve("client-2"))).isTrue();
        } finally {
            stopTwo();
        }
    }

    @Test
    void anAfterEachAsThatNamesNoClientFailsNamingTheAliases() {
        startTwo(new NamedApp("low"), new NamedApp("high"));
        try {
            List<Throwable> failures = launch(AfterEachUnknownAlias.class);
            assertThat(failures).hasSize(1);
            assertThat(failures.get(0)).hasMessageContaining("@As(\"nobody\")").hasMessageContaining("[user1, user2]");
        } finally {
            stopTwo();
        }
    }

    @Test
    void aForeignLeaseOnTheSecondClientFailsAndReleasesTheFirst() throws Exception {
        startTwo(new NamedApp("low"), new NamedApp("high"));
        String waitBefore = System.getProperty(UiRun.LEASE_WAIT_PROPERTY);
        System.setProperty(UiRun.LEASE_WAIT_PROPERTY, "300");
        try {
            cb.callJson("session", cb.args().put("op", "lease").put("owner", "the CLI"));
            Path dir = UiTestExtension.evidenceRoot(TwoClients.class)
                    .resolve(TwoClients.class.getSimpleName()).resolve("both");
            deleteTree(dir);
            List<Throwable> failures = launch(TwoClients.class);
            assertThat(failures).hasSize(1);
            assertThat(failures.get(0)).hasMessageContaining("leased by the CLI");
            assertThat(EVENTS).contains("low reset user1"); // client 1 was leased and reset before client 2 failed
            // a multi-client test writes client-N folders even when client 2 never began
            assertThat(Files.isDirectory(dir.resolve("client-1"))).isTrue();
            assertThat(Files.exists(dir.resolve("client-2").resolve("evidence.txt"))).isTrue();
            // the first client's agent lease is free again, and the pool too
            ca.callJson("session", ca.args().put("op", "lease").put("owner", "next"));
            assertThat(poolUnderTest.reserve(2, "next")).hasSize(2);
        } finally {
            if (waitBefore == null) System.clearProperty(UiRun.LEASE_WAIT_PROPERTY); else System.setProperty(UiRun.LEASE_WAIT_PROPERTY, waitBefore);
            stopTwo();
        }
    }

    @Test
    void anAfterEachAfterAFailedBeginGetsTheSameFailureWithoutLeasingAgain() {
        startTwo(new NamedApp("low"), new NamedApp("high") {
            @Override public void reset(ResetSpec spec) {
                EVENTS.add("high reset " + spec.alias);
                throw new DriverException(io.github.kormcrete.swingui.driver.Reason.APP_ERROR, "reset: stubborn modal");
            }
        });
        try {
            List<Throwable> failures = launch(TwoClients.class);
            assertThat(failures).hasSize(1);
            assertThat(failures.get(0)).hasMessageContaining("stubborn modal");
            assertThat(EVENTS).filteredOn(e -> e.startsWith("high reset")).hasSize(1);
            assertThat(EVENTS).doesNotContain("body");
        } finally {
            stopTwo();
        }
    }

    /**
     * Whatever fails earlier in afterEach (here printing a warning), the runs still end and the
     * pool reservation is still released. One shared pool of wait 0, launched twice: a leaked reservation would fail
     * the second launch at once with "pool busy", a leaked agent lease after 300 ms with "leased by".
     */
    @Test
    void aFailingAfterEachStillEndsTheRunsAndReleasesThePool() {
        startTwo(new NamedApp("low"), new NamedApp("high"));
        String waitBefore = System.getProperty(UiRun.LEASE_WAIT_PROPERTY);
        System.setProperty(UiRun.LEASE_WAIT_PROPERTY, "300");
        final PrintStream realOut = System.out;
        try {
            System.setOut(new PrintStream(new OutputStream() {
                @Override public void write(int x) { realOut.write(x); }
            }) {
                @Override public void println(String s) {
                    if (s != null && s.startsWith("[ui-warning]")) throw new IllegalStateException("stdout refused: " + s);
                    realOut.println(s);
                }
            });
            List<Throwable> first;
            try {
                first = launch(TwoClientsWithWarnings.class);
            } finally {
                System.setOut(realOut);
            }
            assertThat(first).hasSize(1);
            assertThat(first.get(0)).hasMessageContaining("stdout refused");

            assertThat(launch(TwoClients.class)).isEmpty();
        } finally {
            System.setOut(realOut);
            if (waitBefore == null) System.clearProperty(UiRun.LEASE_WAIT_PROPERTY); else System.setProperty(UiRun.LEASE_WAIT_PROPERTY, waitBefore);
            stopTwo();
        }
    }
}
