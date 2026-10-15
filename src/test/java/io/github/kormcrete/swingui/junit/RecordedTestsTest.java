package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.agent.InJvmAgent;
import io.github.kormcrete.swingui.client.AgentClient;
import io.github.kormcrete.swingui.testing.FixtureClass;
import io.github.kormcrete.swingui.testing.RecordingVerbs;
import org.junit.jupiter.api.DynamicTest;
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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** The JUnit recorded test runner against an in-JVM agent over recording verbs. */
class RecordedTestsTest {

    static volatile AgentClient agentUnderTest;
    static volatile Path folder;

    @FixtureClass
    static class Recorded {
        @RegisterExtension
        static final UiTestExtension EXT = new UiTestExtension(c -> UiPool.of(agentUnderTest));

        @TestFactory
        Stream<DynamicTest> recorded(Client ui) {
            return RecordedTests.of(ui, folder);
        }
    }

    @TempDir Path dir;

    private static final String PASS = "{\"version\":1,\"start\":\"reset\",\"steps\":[{\"action\":\"expect\","
            + "\"target\":{\"role\":\"button\",\"name\":\"OK\"},\"text\":\"OK\"}]}";
    private static final String FAIL = "{\"version\":1,\"start\":\"reset\",\"steps\":[{\"action\":\"loginAs\","
            + "\"as\":\"customer\"}]}";

    private void write(String name, String json) throws IOException {
        Files.write(dir.resolve(name), json.getBytes(StandardCharsets.UTF_8));
    }

    private TestExecutionSummary launch(RecordingVerbs served) {
        InJvmAgent agent = InJvmAgent.start(served, null);
        try {
            agentUnderTest = new AgentClient("127.0.0.1", agent.port());
            folder = dir;
            SummaryGeneratingListener listener = new SummaryGeneratingListener();
            LauncherFactory.create().execute(LauncherDiscoveryRequestBuilder.request()
                    .selectors(selectClass(Recorded.class)).configurationParameter("swingui.fixtures", "run").build(),
                    listener);
            return listener.getSummary();
        } finally {
            agentUnderTest = null;
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
        write("broken.json", "{\"version\":2,\"start\":\"reset\",\"steps\":[]}");
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
}
