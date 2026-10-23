package io.github.kormcrete.swingui.junit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kormcrete.swingui.agent.InJvmAgent;
import io.github.kormcrete.swingui.client.AgentClient;
import io.github.kormcrete.swingui.journal.RunFolder;
import io.github.kormcrete.swingui.testing.FixtureClass;
import io.github.kormcrete.swingui.testing.RecordingVerbs;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** The JUnit recorded test runner against an in-JVM agent over recording verbs. */
class RecordedTestsTest {

    static volatile AgentClient agentUnderTest;
    static volatile Path folder;
    static volatile Path runs;

    @FixtureClass
    static class Recorded {
        @RegisterExtension
        static final UiTestExtension EXT = new UiTestExtension(c -> UiPool.of(agentUnderTest), cls -> runs);

        @TestFactory
        Stream<DynamicTest> recorded(Client ui) {
            return RecordedTests.of(ui, folder);
        }
    }

    /** Only recorded tests, as a UI test class of them run alone: no UI test is known when the plan starts. */
    @FixtureClass
    @Tag("ui")
    static class RecordedAlone {
        @RegisterExtension
        static final UiTestExtension EXT = new UiTestExtension(c -> UiPool.of(agentUnderTest), cls -> runs);

        @TestFactory
        Stream<DynamicTest> recorded(Client ui) {
            return RecordedTests.of(ui, folder);
        }
    }

    @TempDir Path dir;

    private static final String PASS = "{\"version\":2,\"start\":\"reset\",\"steps\":[{\"checks\":[{"
            + "\"target\":{\"role\":\"button\",\"name\":\"OK\"},\"text\":\"OK\"}]}]}";
    private static final String FAIL = "{\"version\":2,\"start\":\"reset\",\"steps\":[{\"action\":\"loginAs\","
            + "\"as\":\"customer\"}]}";

    private void write(String name, String json) throws IOException {
        Files.write(dir.resolve(name), json.getBytes(StandardCharsets.UTF_8));
    }

    private TestExecutionSummary launch(RecordingVerbs served) {
        InJvmAgent agent = InJvmAgent.start(served, null);
        try {
            agentUnderTest = new AgentClient("127.0.0.1", agent.port());
            folder = dir;
            runs = dir.resolve("ui-runs");
            SummaryGeneratingListener listener = new SummaryGeneratingListener();
            LauncherFactory.create().execute(LauncherDiscoveryRequestBuilder.request()
                    .selectors(selectClass(Recorded.class)).configurationParameter("swingui.fixtures", "run").build(),
                    listener);
            return listener.getSummary();
        } finally {
            agentUnderTest = null;
            RunFolder.forget(runs);
            agent.stop();
        }
    }

    @Test
    void oneTestPerRecordedTestAndAFailingOneFailsWithItsReport() throws IOException {
        write("pass.json", PASS);
        write("fail.json", FAIL);
        TestExecutionSummary s = launch(new RecordingVerbs());
        assertThat(s.getTestsFoundCount()).isEqualTo(2);
        assertThat(s.getTestsSucceededCount()).isEqualTo(1);
        assertThat(s.getFailures()).hasSize(1);
        Throwable failure = s.getFailures().get(0).getException();
        assertThat(failure).isInstanceOf(AssertionError.class).hasMessageContaining("FAIL").hasMessageContaining("loginAs");
        Path txt = ModuleTarget.of(RecordedTestsTest.class).toAbsolutePath()
                .resolve("failsafe-reports").resolve("recorded-test-failures").resolve("fail.txt");
        assertThat(txt).isRegularFile();
        assertThat(failure.getMessage()).contains("UI evidence: " + txt);
        // a file without a name runs under its file's base name, like its test and its report file
        assertThat(failure.getMessage()).contains("Recorded test 'fail' failed");
    }

    /** The lines of each journal of the run, by file name; every one is in the run folder's steps/. */
    private Map<String, List<JsonNode>> journals() throws IOException {
        Map<String, List<JsonNode>> out = new TreeMap<String, List<JsonNode>>();
        ObjectMapper json = new ObjectMapper();
        List<Path> files = new ArrayList<Path>();
        try (Stream<Path> s = Files.walk(runs)) {
            s.filter(p -> p.toString().endsWith(".jsonl")).forEach(files::add);
        }
        for (Path f : files) {
            assertThat(f.getParent().getFileName().toString()).isEqualTo("steps");
            List<JsonNode> lines = new ArrayList<JsonNode>();
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) lines.add(json.readTree(line));
            out.put(f.getFileName().toString(), lines);
        }
        return out;
    }

    private static JsonNode last(List<JsonNode> lines) {
        return lines.get(lines.size() - 1);
    }

    @Test
    void eachRecordedTestWritesItsOwnJournalAndTheFactoryNone() throws IOException {
        write("pass.json", PASS);
        write("fail.json", FAIL);
        TestExecutionSummary s = launch(new RecordingVerbs());
        assertThat(s.getTestsSucceededCount()).isEqualTo(1);
        assertThat(s.getFailures()).hasSize(1);

        Map<String, List<JsonNode>> journals = journals();
        // the factory's own journal is dropped: its dynamic tests have theirs
        assertThat(journals.keySet()).containsExactly("RecordedTestsTest$Recorded#recorded[fail].jsonl",
                "RecordedTestsTest$Recorded#recorded[pass].jsonl");

        List<JsonNode> pass = journals.get("RecordedTestsTest$Recorded#recorded[pass].jsonl");
        assertThat(pass.get(0).path("test").asText()).isEqualTo("RecordedTestsTest$Recorded#recorded[pass]");
        // test, the check, end: the agent ran the check, the client wrote no line of its own for the run
        assertThat(pass).hasSize(3);
        assertThat(pass.get(1).path("kind").asText()).isEqualTo("check");
        assertThat(pass.get(1).path("outcome").asText()).isEqualTo("ok");
        assertThat(last(pass).path("outcome").asText()).isEqualTo("passed");

        List<JsonNode> fail = journals.get("RecordedTestsTest$Recorded#recorded[fail].jsonl");
        assertThat(fail).hasSize(3);
        assertThat(fail.get(1).path("verb").asText()).isEqualTo("loginAs");
        assertThat(fail.get(1).path("outcome").asText()).isEqualTo("failed");
        JsonNode end = last(fail);
        assertThat(end.path("kind").asText()).isEqualTo("end");
        assertThat(end.path("outcome").asText()).isEqualTo("failed");
        assertThat(end.path("message").asText()).startsWith("Recorded test 'fail' failed");
        Path txt = ModuleTarget.of(RecordedTestsTest.class).toAbsolutePath()
                .resolve("failsafe-reports").resolve("recorded-test-failures").resolve("fail.txt");
        assertThat(end.path("evidence").asText()).isEqualTo(txt.toString());
    }

    @Test
    void theRunReportNamesARecordedTestByItsJournalsId() throws IOException {
        write("pass.json", PASS);
        write("fail.json", FAIL);
        InJvmAgent agent = InJvmAgent.start(new RecordingVerbs(), null);
        try {
            agentUnderTest = new AgentClient("127.0.0.1", agent.port());
            folder = dir;
            runs = dir.resolve("ui-runs");
            LauncherFactory.create().execute(LauncherDiscoveryRequestBuilder.request()
                    .selectors(selectClass(RecordedAlone.class))
                    .configurationParameter("swingui.fixtures", "run").build(),
                    new UiRunReport(runs.resolve("latest.txt")));
        } finally {
            agentUnderTest = null;
            RunFolder.forget(runs);
            agent.stop();
        }

        // the same ids as the journal files, in latest.txt and in the run folder's report.txt
        assertThat(journals().keySet()).containsExactly("RecordedTestsTest$RecordedAlone#recorded[fail].jsonl",
                "RecordedTestsTest$RecordedAlone#recorded[pass].jsonl");
        String runId = new String(Files.readAllBytes(runs.resolve("latest-run.txt")), StandardCharsets.UTF_8).trim();
        for (Path report : new Path[] {runs.resolve("latest.txt"), runs.resolve(runId).resolve("report.txt")}) {
            List<String> lines = Files.readAllLines(report, StandardCharsets.UTF_8);
            assertThat(lines).as(report.toString())
                    .anyMatch(l -> l.startsWith("PASS RecordedTestsTest$RecordedAlone#recorded[pass] "))
                    .anyMatch(l -> l.startsWith("FAIL RecordedTestsTest$RecordedAlone#recorded[fail] "))
                    .noneMatch(l -> l.startsWith("PASS pass ") || l.startsWith("FAIL fail "));
        }
    }

    @Test
    void aRecordedTestThatCannotRunOrBeReadStillEndsItsJournalFailed() throws IOException {
        // the agent refuses a do step without a plugin: the call throws, no reply comes back
        write("needs-plugin.json", "{\"version\":4,\"start\":\"reset\",\"requires\":[\"order-desk\"],\"steps\":["
                + "{\"action\":\"do\",\"step\":\"order-desk:setQty\",\"params\":{\"qty\":\"3\"}}]}");
        write("broken.json", "{\"version\":5,\"start\":\"reset\",\"steps\":[]}");
        TestExecutionSummary s = launch(new RecordingVerbs());
        assertThat(s.getTestsFailedCount()).isEqualTo(2);

        Map<String, List<JsonNode>> journals = journals();
        assertThat(journals.keySet()).containsExactly("RecordedTestsTest$Recorded#recorded[broken].jsonl",
                "RecordedTestsTest$Recorded#recorded[needs-plugin].jsonl");
        for (List<JsonNode> lines : journals.values()) {
            // test and end: no step lines
            assertThat(lines).hasSize(2);
            assertThat(last(lines).path("kind").asText()).isEqualTo("end");
            assertThat(last(lines).path("outcome").asText()).isEqualTo("failed");
            assertThat(last(lines).has("evidence")).isFalse();
        }
        assertThat(last(journals.get("RecordedTestsTest$Recorded#recorded[needs-plugin].jsonl")).path("message").asText())
                .contains("plugin");
        assertThat(last(journals.get("RecordedTestsTest$Recorded#recorded[broken].jsonl")).path("message").asText())
                .contains("version");
    }

    @Test
    void aGroupedTestSurvivesTheRunnersRewrite() throws IOException {
        // no name: the runner rewrites the file's text with the name set, and the group must come through
        write("grouped.json", "{\"version\":2,\"start\":\"reset\",\"steps\":[{\"say\":\"Two things\",\"steps\":["
                + "{\"action\":\"loginAs\",\"as\":\"customer\"},{\"action\":\"loginAs\",\"as\":\"customer\"}]}]}");
        TestExecutionSummary s = launch(new RecordingVerbs());
        assertThat(s.getFailures()).hasSize(1);
        String message = s.getFailures().get(0).getException().getMessage();
        assertThat(message).contains("Recorded test 'grouped' failed").contains("\u2014 Two things").contains("[1] FAIL");
    }

    @Test
    void aFailureLeavesItsReportInRecordedTestFailures() throws IOException {
        write("fail.json", FAIL);
        launch(new RecordingVerbs());
        Path txt = ModuleTarget.of(RecordedTestsTest.class).toAbsolutePath()
                .resolve("failsafe-reports").resolve("recorded-test-failures").resolve("fail.txt");
        assertThat(txt).isRegularFile();
    }

    @Test
    void anEmptySelectionFailsTheRun() {
        TestExecutionSummary s = launch(new RecordingVerbs());
        assertThat(s.getTestsFoundCount()).isZero();
        assertThat(s.getFailures()).hasSize(1);
        assertThat(s.getFailures().get(0).getException()).hasMessageContaining("no recorded test files");
    }

    @Test
    void aBrokenFileIsAFailedTestNotASkippedOne() throws IOException {
        write("broken.json", "{\"version\":5,\"start\":\"reset\",\"steps\":[]}");
        TestExecutionSummary s = launch(new RecordingVerbs());
        assertThat(s.getTestsFoundCount()).isEqualTo(1);
        assertThat(s.getTestsFailedCount()).isEqualTo(1);
        assertThat(s.getTestsSkippedCount()).isZero();
        assertThat(s.getTestsAbortedCount()).isZero();
        assertThat(s.getFailures().get(0).getException()).isInstanceOf(AssertionError.class)
                .hasMessageContaining("version");
    }

    @Test
    void dashDRecordedRunsJustThatOne() throws IOException {
        assertThat(io.github.kormcrete.swingui.flow.RecordedTestSelection.PROPERTY).isEqualTo("recorded");
        write("pass.json", PASS);
        write("fail.json", FAIL);
        String before = System.getProperty("recorded");
        System.setProperty("recorded", "pass");
        try {
            TestExecutionSummary s = launch(new RecordingVerbs());
            assertThat(s.getTestsFoundCount()).isEqualTo(1);
            assertThat(s.getTestsSucceededCount()).isEqualTo(1);
            assertThat(s.getFailures()).isEmpty();
        } finally {
            if (before == null) System.clearProperty("recorded"); else System.setProperty("recorded", before);
        }
    }

    @Test
    void everyRecordedTestStartsWithAReset() throws IOException {
        write("a.json", PASS);
        write("b.json", PASS);
        RecordingVerbs served = new RecordingVerbs();
        TestExecutionSummary s = launch(served);
        assertThat(s.getFailures()).isEmpty();
        List<String> calls = new ArrayList<String>(served.calls);
        long resets = calls.stream().filter("resetPointer"::equals).count();
        assertThat(resets).as("the lease's begin plus one per recorded test: " + calls).isEqualTo(3);
        int first = calls.indexOf("resetPointer");
        int second = calls.subList(first + 1, calls.size()).indexOf("resetPointer") + first + 1;
        int third = calls.subList(second + 1, calls.size()).indexOf("resetPointer") + second + 1;
        assertThat(calls.subList(second, third).stream().anyMatch(c -> c.startsWith("expect"))).isTrue();
        assertThat(calls.subList(third, calls.size()).stream().anyMatch(c -> c.startsWith("expect"))).isTrue();
    }

    @Test
    void folderPrecedence() throws IOException {
        // argument, then -Dswingui.recordedDir, then <module>/uitest
        Path viaProp = java.nio.file.Files.createDirectories(dir.resolve("prop"));
        Path viaArg = java.nio.file.Files.createDirectories(dir.resolve("arg"));
        Files.write(viaProp.resolve("from-prop.json"), PASS.getBytes(StandardCharsets.UTF_8));
        Files.write(viaArg.resolve("from-arg.json"), PASS.getBytes(StandardCharsets.UTF_8));
        String before = System.getProperty("swingui.recordedDir");
        System.setProperty("swingui.recordedDir", viaProp.toString());
        try {
            assertThat(names(RecordedTests.of(null, viaArg))).containsExactly("from-arg");
            assertThat(names(RecordedTests.of(null))).containsExactly("from-prop");
        } finally {
            if (before == null) System.clearProperty("swingui.recordedDir"); else System.setProperty("swingui.recordedDir", before);
        }
        // neither: <module>/uitest, the module being the project this test class is built in
        Path module = ModuleTarget.of(RecordedTestsTest.class).toAbsolutePath().getParent();
        Path uitest = module.resolve("uitest");
        boolean existed = Files.isDirectory(uitest);
        Path tier3 = Files.createDirectories(uitest).resolve("zz-tier-three.json");
        Files.write(tier3, PASS.getBytes(StandardCharsets.UTF_8));
        String saved = System.getProperty("swingui.recordedDir");
        System.clearProperty("swingui.recordedDir");
        try {
            assertThat(names(RecordedTests.of(null))).contains("zz-tier-three");
        } finally {
            Files.deleteIfExists(tier3);
            if (!existed) Files.deleteIfExists(uitest);
            if (saved != null) System.setProperty("swingui.recordedDir", saved);
        }
    }

    @Test
    void aNullCallerFallsBackToTheWorkingDir() throws Exception {
        java.lang.reflect.Method of = RecordedTests.class.getDeclaredMethod("of", Client.class, String.class, Path.class);
        of.setAccessible(true);
        // a relative folder resolves under the working directory when no caller tells the module
        Path relative = Paths.get("target", "null-caller-recorded");
        Path absolute = Files.createDirectories(Paths.get("").toAbsolutePath().resolve(relative));
        Files.write(absolute.resolve("only.json"), PASS.getBytes(StandardCharsets.UTF_8));
        try {
            @SuppressWarnings("unchecked")
            Stream<DynamicTest> tests = (Stream<DynamicTest>) of.invoke(null, null, null, relative);
            assertThat(names(tests)).containsExactly("only");
        } finally {
            Files.deleteIfExists(absolute.resolve("only.json"));
            Files.deleteIfExists(absolute);
        }
    }

    private static List<String> names(Stream<DynamicTest> tests) {
        List<String> out = new ArrayList<String>();
        tests.forEach(t -> out.add(t.getDisplayName()));
        return out;
    }
}
