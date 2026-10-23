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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kormcrete.swingui.flow.UiUsers;
import io.github.kormcrete.swingui.journal.RunFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

class UiTestExtensionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** What the test types with {@code ui.secret("ada", "pin")}; never in a journal. */
    private static final String PIN = "pin-7319-zq";

    /** The agent the inner test class binds to; set before each launch. */
    static volatile AgentClient agentUnderTest;
    /** The verbs behind {@link #agentUnderTest} for the journal fixtures. */
    static volatile RecordingVerbs servedUnderTest;
    /** The pool of the header fixture: a checked agent, so its session info is known. */
    static volatile UiPool poolUnderTest;
    /** The ui-runs directory the journal fixtures write to. */
    static volatile Path runsUnderTest;
    static final List<String> cleanUps = new ArrayList<String>();

    @TempDir Path tmp;
    private InJvmAgent journalAgent;

    @FixtureClass
    static class Passing {
        @RegisterExtension
        static final UiTestExtension EXT = new UiTestExtension(c -> UiPool.of(agentUnderTest), cls -> runsUnderTest);

        @Test
        void runs(Client ui) {
            ui.button("Add to cart").click();
            UiAssertions.assertThat(ui.label("Total")).hasText("€7.32");
        }
    }

    @FixtureClass
    static class FailsAtTheSecondClick {
        @RegisterExtension
        static final UiTestExtension EXT = new UiTestExtension(c -> UiPool.of(agentUnderTest), cls -> runsUnderTest);

        @Test
        void runs(Client ui) {
            ui.button("Add to cart").click();
            servedUnderTest.failNext = new DriverException(Reason.NOT_FOUND, "no button 'Place order'");
            ui.button("Place order").click();
            ui.button("Close").click();
        }
    }

    @FixtureClass
    @Tag("ui")   // a run report runs too: its report.txt goes to the same run folder
    static class TypesSecrets {
        @RegisterExtension
        static final UiTestExtension EXT = new UiTestExtension(c -> UiPool.of(agentUnderTest), cls -> runsUnderTest);

        @Test
        void runs(Client ui) {
            String pin = ui.secret("ada", "pin");
            ui.field("PIN").type(pin);
            ui.password("PIN").type(pin);
            ui.password("Password").type("pa55word");
            servedUnderTest.failNext = new DriverException(Reason.NOT_FOUND,
                    "no button 'Pay' near '" + pin + "' and 'pa55word'");
            ui.button("Pay").click();
        }
    }

    @FixtureClass
    static class TwoPassing {
        @RegisterExtension
        static final UiTestExtension EXT = new UiTestExtension(c -> UiPool.of(agentUnderTest), cls -> runsUnderTest);

        @Test void first(Client ui) { ui.button("Add to cart").click(); }

        @Test void second(Client ui) { ui.button("Add to cart").click(); }
    }

    @FixtureClass
    static class NoRunsFolder {
        @RegisterExtension
        static final UiTestExtension EXT = new UiTestExtension(c -> UiPool.of(agentUnderTest), cls -> {
            throw new IllegalStateException("no target folder for " + cls.getSimpleName());
        });

        @Test void first(Client ui) { ui.button("Add to cart").click(); }

        @Test void second(Client ui) { ui.button("Add to cart").click(); }
    }

    @FixtureClass
    @LoginAs("ada")
    static class AsAda {
        @RegisterExtension
        static final UiTestExtension EXT = new UiTestExtension(c -> poolUnderTest, cls -> runsUnderTest);

        @Test void runs(Client ui) { ui.button("Add to cart").click(); }
    }

    static final class OrderDesk implements AppHooks {
        @Override public void reset(ResetSpec spec) { }
        @Override public void login(ResetSpec who, boolean force) { }
        @Override public AppInfo info() { return new AppInfo("Order Desk", "2.4.1"); }
    }

    private void startJournalAgent() {
        servedUnderTest = new RecordingVerbs();
        journalAgent = InJvmAgent.start(servedUnderTest,
                new PluginParts(new RecordingHost(servedUnderTest), null, new OrderDesk(), null, null, null));
        agentUnderTest = new AgentClient("127.0.0.1", journalAgent.port());
        runsUnderTest = tmp.resolve("ui-runs");
    }

    @AfterEach
    void stopJournalAgent() {
        if (journalAgent != null) journalAgent.stop();
        if (runsUnderTest != null) RunFolder.forget(runsUnderTest);
        RunFolder.appVersion(null);
        journalAgent = null;
        agentUnderTest = null;
        servedUnderTest = null;
        poolUnderTest = null;
        runsUnderTest = null;
        System.clearProperty(UiUsers.FILE_PROPERTY);
    }

    /** Launches {@code fixture} with the given configuration parameters (name, value, ...): each test's failure or null. */
    private static List<Throwable> launch(Class<?> fixture, String... parameters) {
        return launch(fixture, new TestExecutionListener[0], parameters);
    }

    /** {@link #launch(Class, String...)} with {@code also} listening too. */
    private static List<Throwable> launch(Class<?> fixture, TestExecutionListener[] also, String... parameters) {
        LauncherDiscoveryRequestBuilder request = LauncherDiscoveryRequestBuilder.request().selectors(selectClass(fixture))
                .configurationParameter("swingui.fixtures", "run");
        for (int i = 0; i + 1 < parameters.length; i += 2) request.configurationParameter(parameters[i], parameters[i + 1]);
        final List<Throwable> results = new ArrayList<Throwable>();
        TestExecutionListener[] listeners = new TestExecutionListener[also.length + 1];
        listeners[0] = new TestExecutionListener() {
            @Override public void executionFinished(TestIdentifier id, TestExecutionResult result) {
                if (id.isTest()) results.add(result.getThrowable().orElse(null));
            }
        };
        System.arraycopy(also, 0, listeners, 1, also.length);
        LauncherFactory.create().execute(request.build(), listeners);
        return results;
    }

    private static Path runFolder() throws IOException {
        String id = new String(Files.readAllBytes(runsUnderTest.resolve("latest-run.txt")), StandardCharsets.UTF_8).trim();
        return runsUnderTest.resolve(id);
    }

    private static Path stepsFile(String test) throws IOException {
        return runFolder().resolve("steps").resolve(test + ".jsonl");
    }

    private static List<JsonNode> lines(String test) throws IOException {
        List<JsonNode> out = new ArrayList<JsonNode>();
        for (String line : Files.readAllLines(stepsFile(test), StandardCharsets.UTF_8)) out.add(JSON.readTree(line));
        return out;
    }

    private static List<String> kinds(List<JsonNode> lines) {
        List<String> out = new ArrayList<String>();
        for (JsonNode l : lines) out.add(l.path("kind").asText());
        return out;
    }

    @Test
    void aPassingTestLeavesHeaderStepsAndAPassedEnd() throws IOException {
        startJournalAgent();

        assertThat(launch(Passing.class)).containsExactly((Throwable) null);

        List<JsonNode> lines = lines("UiTestExtensionTest$Passing#runs");
        assertThat(kinds(lines)).containsExactly("test", "action", "check", "end");
        assertThat(lines.get(0).path("test").asText()).isEqualTo("UiTestExtensionTest$Passing#runs");
        assertThat(lines.get(1).path("text").asText()).isEqualTo("Click Add to cart");
        assertThat(lines.get(1).path("outcome").asText()).isEqualTo("ok");
        assertThat(lines.get(2).path("verb").asText()).isEqualTo("expect");
        assertThat(lines.get(3).path("outcome").asText()).isEqualTo("passed");
        assertThat(lines.get(3).has("message")).isFalse();
        assertThat(lines.get(3).has("evidence")).isFalse();
        // the engine's end wrote the run's finish, as no run report ran
        assertThat(JSON.readTree(runFolder().resolve("run.json").toFile()).has("finished")).isTrue();
    }

    @Test
    void aFailingTestsJournalEndsAtTheFailedStep() throws IOException {
        startJournalAgent();

        List<Throwable> results = launch(FailsAtTheSecondClick.class);

        assertThat(results).hasSize(1);
        assertThat(results.get(0)).isInstanceOf(UiActionFailed.class);
        List<JsonNode> lines = lines("UiTestExtensionTest$FailsAtTheSecondClick#runs");
        assertThat(kinds(lines)).containsExactly("test", "action", "action", "end");
        JsonNode failed = lines.get(2);
        assertThat(failed.path("outcome").asText()).isEqualTo("failed");
        assertThat(failed.path("reason").asText()).isEqualTo("NOT_FOUND");
        assertThat(failed.path("message").asText()).isEqualTo("no button 'Place order'");
        JsonNode end = lines.get(3);
        assertThat(end.path("outcome").asText()).isEqualTo("failed");
        assertThat(end.path("message").asText()).isEqualTo(results.get(0).getMessage().split("\\n", 2)[0]);
        assertThat(end.path("evidence").asText()).endsWith("FailsAtTheSecondClick" + java.io.File.separator + "runs");
        assertThat(Files.isDirectory(java.nio.file.Paths.get(end.path("evidence").asText()))).isTrue();
    }

    @Test
    void aSecretNeverReachesTheJournal() throws IOException {
        startJournalAgent();
        Path users = tmp.resolve("ui-users.properties");
        Files.write(users, ("ada=ada:x\nada.pin=" + PIN + "\n").getBytes(StandardCharsets.UTF_8));
        System.setProperty(UiUsers.FILE_PROPERTY, users.toString());

        List<Throwable> results = launch(TypesSecrets.class,
                new TestExecutionListener[] {new UiRunReport(runsUnderTest.resolve("latest.txt"))});

        assertThat(results).hasSize(1);
        assertThat(results.get(0)).isNotNull();
        // the test's own failure says them on its first line, which the end line keeps
        assertThat(results.get(0).getMessage().split("\\n", 2)[0]).contains(PIN).contains("pa55word");
        String text = new String(Files.readAllBytes(stepsFile("UiTestExtensionTest$TypesSecrets#runs")),
                StandardCharsets.UTF_8);
        assertThat(text).doesNotContain(PIN).doesNotContain("pa55word").contains("***");
        List<JsonNode> lines = lines("UiTestExtensionTest$TypesSecrets#runs");
        assertThat(kinds(lines)).containsExactly("test", "action", "action", "action", "action", "end");
        assertThat(lines.get(1).path("text").asText()).isEqualTo("Type *** into PIN");
        assertThat(lines.get(2).path("text").asText()).isEqualTo("Type *** into PIN");
        assertThat(lines.get(3).path("text").asText()).isEqualTo("Type *** into Password");
        assertThat(lines.get(4).path("message").asText()).isEqualTo("no button 'Pay' near '***' and '***'");
        assertThat(lines.get(5).path("message").asText()).endsWith("no button 'Pay' near '***' and '***'");
        // nor any other file of the run folder: its report.txt says the failure masked too
        List<Path> files;
        try (Stream<Path> walk = Files.walk(runFolder())) {
            files = walk.filter(Files::isRegularFile).collect(Collectors.toList());
        }
        assertThat(files).contains(runFolder().resolve("report.txt"));
        for (Path f : files) {
            assertThat(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).as(f.toString())
                    .doesNotContain(PIN).doesNotContain("pa55word");
        }
        assertThat(new String(Files.readAllBytes(runFolder().resolve("report.txt")), StandardCharsets.UTF_8))
                .contains("FAIL UiTestExtensionTest$TypesSecrets#runs").contains("near '***' and '***'");
    }

    @Test
    void anUnwritableRunsFolderWarnsOnceAndTheTestStillPasses() throws IOException {
        startJournalAgent();
        RunFolder.forgetWarning();
        Files.write(runsUnderTest, "in the way".getBytes(StandardCharsets.UTF_8));
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream both = new PrintStream(captured, true);
        List<Throwable> results;
        System.setOut(both);
        System.setErr(both);
        try {
            results = launch(TwoPassing.class);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }

        assertThat(results).containsExactly(null, null);
        String printed = new String(captured.toByteArray(), StandardCharsets.UTF_8);
        assertThat(printed.split("run journal off for this run", -1)).as(printed).hasSize(2);
        assertThat(runsUnderTest).isRegularFile();
    }

    @Test
    void noRunsFolderAtAllWarnsOnceAndTheTestsStillPass() {
        startJournalAgent();
        RunFolder.forgetWarning();
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream both = new PrintStream(captured, true);
        List<Throwable> results;
        System.setOut(both);
        System.setErr(both);
        try {
            results = launch(NoRunsFolder.class);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }

        assertThat(results).containsExactly(null, null);
        String printed = new String(captured.toByteArray(), StandardCharsets.UTF_8);
        assertThat(printed.split("run journal off for this run", -1)).as(printed).hasSize(2);
        assertThat(printed).contains("[ui-warning] run journal off for this run: java.lang.IllegalStateException: "
                + "no target folder for NoRunsFolder");
    }

    @Test
    void withTheJournalOffNoStepsFileIsWritten() throws IOException {
        startJournalAgent();

        assertThat(launch(Passing.class, "swingui.journal", "off")).containsExactly((Throwable) null);

        List<Path> journals;
        if (!Files.exists(runsUnderTest)) {
            journals = Collections.emptyList();
        } else {
            try (Stream<Path> walk = Files.walk(runsUnderTest)) {
                journals = walk.filter(p -> p.toString().endsWith(".jsonl")).collect(Collectors.toList());
            }
        }
        assertThat(journals).isEmpty();
    }

    @Test
    void theHeaderNamesEachClientsAgentAndAlias() throws IOException {
        startJournalAgent();
        UiAgents checked = new UiAgents("127.0.0.1", journalAgent.port());
        poolUnderTest = new UiPool(Collections.singletonList(checked), 0, "127.0.0.1:" + journalAgent.port());

        assertThat(launch(AsAda.class)).containsExactly((Throwable) null);

        JsonNode header = lines("UiTestExtensionTest$AsAda#runs").get(0);
        assertThat(header.path("clients")).hasSize(1);
        JsonNode client = header.path("clients").get(0);
        assertThat(client.path("client").asInt()).isEqualTo(1);
        assertThat(client.path("agent").asText()).isEqualTo("127.0.0.1:" + journalAgent.port());
        assertThat(client.path("alias").asText()).isEqualTo("ada");
        assertThat(client.path("agentVersion").asText()).isEqualTo(checked.info().agentVersion).isNotEmpty();
        assertThat(client.path("app").path("name").asText()).isEqualTo("Order Desk");
        assertThat(client.path("app").path("version").asText()).isEqualTo("2.4.1");
        JsonNode run = JSON.readTree(runFolder().resolve("run.json").toFile());
        assertThat(run.path("clients").get(0).path("agent").asText()).isEqualTo("127.0.0.1:" + journalAgent.port());
        assertThat(run.path("clients").get(0).path("alias").asText()).isEqualTo("ada");
    }

    @Test
    void anAppVersionTheTestNamesBeatsTheAgentsAndAnAgentATestMadeHasNoGuessedFields() throws IOException {
        startJournalAgent();
        UiRunJournal.appVersion("2.5.0-rc1");

        assertThat(launch(Passing.class)).containsExactly((Throwable) null);

        JsonNode client = lines("UiTestExtensionTest$Passing#runs").get(0).path("clients").get(0);
        assertThat(client.path("agent").asText()).isEqualTo("in-jvm:0");
        assertThat(client.has("alias")).isFalse();
        assertThat(client.has("agentVersion")).isFalse();
        assertThat(client.path("app").has("name")).isFalse();
        assertThat(client.path("app").path("version").asText()).isEqualTo("2.5.0-rc1");
    }

    /** A test whose reset fails (a stubborn modal), with an @AfterEach that takes a Client. */
    @FixtureClass
    static class ResetFailsWithAClientCleanUp {
        @RegisterExtension
        static final UiTestExtension EXT = new UiTestExtension(c -> UiPool.of(agentUnderTest), cls -> runsUnderTest);

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
    void aClientCleanUpAfterAFailedBeginGetsTheSameFailureWithoutLeasingAgain() throws IOException {
        RecordingVerbs served = new RecordingVerbs();
        StubbornModalApp app = new StubbornModalApp();
        InJvmAgent agent = InJvmAgent.start(served, new PluginParts(new RecordingHost(served), null, app, null, null, null));
        String waitBefore = System.getProperty(UiRun.LEASE_WAIT_PROPERTY);
        System.clearProperty(UiRun.LEASE_WAIT_PROPERTY); // the default wait: a second lease would retry ~15 s
        try {
            AgentClient client = new AgentClient("127.0.0.1", agent.port());
            agentUnderTest = client;
            runsUnderTest = tmp.resolve("ui-runs");
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
            // the journal says the test failed, with no step: the reset is not one
            List<JsonNode> lines = lines("UiTestExtensionTest$ResetFailsWithAClientCleanUp#runs");
            assertThat(kinds(lines)).containsExactly("test", "end");
            assertThat(lines.get(1).path("outcome").asText()).isEqualTo("failed");
            assertThat(lines.get(1).path("message").asText()).contains("a modal dialog would not close");
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
        Path root = UiTestExtension.screenshotRoot(UiTestExtensionTest.class);
        assertThat(root.getFileName().toString()).isEqualTo("ui-screenshots");
        assertThat(root.getParent().getParent().resolve("pom.xml")).isRegularFile();
    }
}
