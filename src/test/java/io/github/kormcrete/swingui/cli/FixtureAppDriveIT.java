package io.github.kormcrete.swingui.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kormcrete.swingui.fixture.FixtureApp;
import io.github.kormcrete.swingui.flow.RecordedTestFiles;
import io.github.kormcrete.swingui.junit.Client;
import io.github.kormcrete.swingui.junit.RecordedTests;
import io.github.kormcrete.swingui.junit.UiTestExtension;
import io.github.kormcrete.swingui.testing.FixtureClass;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * The whole product on this OS and Java: FixtureApp starts in its own JVM with the packaged agent jar
 * ({@code -javaagent}, so on Java 9+ the agent opens java.desktop itself), and the CLI drives it the way a coding
 * agent does - typing, clicking, keys that follow focus the app moved itself, Tab, a modal dialog, a drop and a
 * context menu - with the person's palette shown in the window, as it is by default. Run by failsafe after
 * {@code package}; on every OS CI covers, this is what makes the platform green.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FixtureAppDriveIT {

    private static final Pattern LISTENING = Pattern.compile("swing-ui-agent listening on http://127\\.0\\.0\\.1:(\\d+)/mcp");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Process app;
    private static int port;
    private static final StringBuffer appOutput = new StringBuffer();
    private static Path testsDir;

    @BeforeAll
    static void startTheAppWithTheAgent() throws Exception {
        Path agentJar = Paths.get(System.getProperty("swingui.agentJar", "missing: set swingui.agentJar"));
        assertThat(Files.isRegularFile(agentJar)).as("agent jar %s (run through mvn verify)", agentJar).isTrue();
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        // only the fixture's own classes: the agent must come from the packaged jar, not from target/classes
        String fixtureClasses = Paths.get(FixtureApp.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
        app = new ProcessBuilder(java, "-javaagent:" + agentJar + "=port=0", "-cp", fixtureClasses,
                FixtureApp.class.getName()).redirectErrorStream(true).start();
        CountDownLatch listening = new CountDownLatch(1);
        Thread drain = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(app.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) {
                    appOutput.append(line).append('\n');
                    Matcher m = LISTENING.matcher(line);
                    if (m.find()) {
                        port = Integer.parseInt(m.group(1));
                        listening.countDown();
                    }
                }
            } catch (Exception ignored) {
                // the app stopped
            }
        }, "fixture-app-output");
        drain.setDaemon(true);
        drain.start();
        assertThat(listening.await(60, TimeUnit.SECONDS)).as("the agent never listened:\n" + appOutput).isTrue();
    }

    @AfterAll
    static void stopTheApp() throws Exception {
        if (testsDir != null) {
            try (Stream<Path> all = Files.walk(testsDir)) {
                all.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        if (app == null) return;
        app.destroy();
        if (!app.waitFor(10, TimeUnit.SECONDS)) app.destroyForcibly();
    }

    @Test
    @Order(1)
    void theDriverStartsWithEveryRequiredCapability() {
        assertThat(appOutput.toString()).contains("UI driver installed").doesNotContain("MISSING");
    }

    @Test
    @Order(2)
    void thePaletteIsOnByDefaultAndTheAgentClicksUnderIt() throws Exception {
        assertThat(call("session", "{'op':'info'}").path("palette").asBoolean()).isTrue();
        // the window is up: the palette is put in at the end of this step (never while the agent acts)
        step("expect", "{'target':{'name':'status'},'text':'idle'}");
        // Archive sits where the palette's bar sits by default: the bar steps off it for the agent's click
        step("click", "{'target':{'name':'archive'}}");
        step("expect", "{'target':{'name':'status'},'text':'archived'}");
    }

    @Test
    @Order(3)
    void typeAndClick() throws Exception {
        step("type", "{'target':{'name':'customer'},'text':'ACME'}");
        step("click", "{'target':{'name':'save'}}");
        step("expect", "{'target':{'name':'status'},'text':'saved ACME'}");
    }

    @Test
    @Order(4)
    void keysFollowTheFocusTheAppMovedItselfAndTab() throws Exception {
        step("click", "{'target':{'name':'new'}}");
        step("press", "{'keys':'5'}");
        step("expect", "{'target':{'name':'customer'},'value':'5'}");
        step("press", "{'keys':'Tab'}");
        step("press", "{'keys':'7'}");
        step("expect", "{'target':{'name':'notes'},'value':'7'}");
    }

    @Test
    @Order(5)
    void aModalDialogAndTypingAfterIt() throws Exception {
        step("click", "{'target':{'name':'delete'}}");
        step("answer", "{'button':'Yes'}");
        step("expect", "{'target':{'name':'status'},'text':'deleted'}");
        step("type", "{'target':{'name':'notes'},'text':'after the dialog'}");
        step("expect", "{'target':{'name':'notes'},'value':'after the dialog'}");
    }

    @Test
    @Order(6)
    void aDropAndAContextMenu() throws Exception {
        step("drop", "{'target':{'name':'attachments'},'text':'ORD-1640.pdf'}");
        step("expect", "{'target':{'name':'status'},'text':'attached ORD-1640.pdf'}");
        step("context_menu", "{'target':{'name':'attachments'},'path':['Clear attachments']}");
        step("expect", "{'target':{'name':'status'},'text':'attachments cleared'}");
    }

    @Test
    @Order(7)
    void typeIntoASpinnerCellAndAValueOutOfRangeKeepsTheLastValidOne() throws Exception {
        step("type", "{'target':{'name':'lines','row':{'Product':'Paper'},'column':'Qty'},'text':'3','commit':true}");
        step("expect", "{'target':{'name':'lines'},'cell':{'row':{'Product':'Paper'},'column':'Qty','expected':'3'}}");
        step("expect", "{'target':{'name':'status'},'text':'qty 3 code 10'}");
        // the status moves on, so "qty 3" again can only come from the edit of the next step ending
        step("click", "{'target':{'name':'save'}}");
        step("expect", "{'target':{'name':'status'},'text':'saved 5'}");
        step("type", "{'target':{'name':'lines','row':{'Product':'Paper'},'column':'Qty'},'text':'0','commit':true}");
        step("expect", "{'target':{'name':'status'},'text':'qty 3 code 10'}");
        step("expect", "{'target':{'name':'lines'},'cell':{'row':{'Product':'Paper'},'column':'Qty','expected':'3'}}");
    }

    @Test
    @Order(8)
    void aRefusedEditIsCancelledAndTheCellKeepsItsValue() throws Exception {
        step("type", "{'target':{'name':'lines','row':{'Product':'Paper'},'column':'Code'},'text':'abc','commit':true}");
        step("expect", "{'target':{'name':'lines'},'cell':{'row':{'Product':'Paper'},'column':'Code','expected':'10'}}");
        step("expect", "{'target':{'name':'status'},'text':'code cancelled'}");
        // the editor is gone: a second type starts a fresh edit and this one is accepted
        step("type", "{'target':{'name':'lines','row':{'Product':'Paper'},'column':'Code'},'text':'42','commit':true}");
        step("expect", "{'target':{'name':'lines'},'cell':{'row':{'Product':'Paper'},'column':'Code','expected':'42'}}");
    }

    @Test
    @Order(9)
    void aRefusalTheAppExplainsInADialogIsLeftToTheTest() throws Exception {
        step("type", "{'target':{'name':'lines','row':{'Product':'Paper'},'column':'Ref'},'text':'x1','commit':true}");
        // one refusal: the driver asked the editor no second time under the app's dialog
        step("expect", "{'target':{'name':'status'},'text':'ref refused 1'}");
        step("answer", "{'button':'OK'}");
        step("expect", "{'target':{'name':'lines'},'cell':{'row':{'Product':'Paper'},'column':'Ref','expected':'20'}}");
        // typing into another cell: the click asks Ref's editor to stop, and its second refusal is left to the test
        refused("type", "{'target':{'name':'lines','row':{'Product':'Paper'},'column':'Code'},'text':'5','commit':true}",
                "blocked by dialog Ref");
        step("expect", "{'target':{'name':'status'},'text':'ref refused 2'}");
        step("answer", "{'button':'OK'}");
        // the edit is still open: typing again goes into its field, without asking the editor to stop first
        step("type", "{'target':{'name':'lines','row':{'Product':'Paper'},'column':'Ref'},'text':'7','commit':true}");
        step("expect", "{'target':{'name':'lines'},'cell':{'row':{'Product':'Paper'},'column':'Ref','expected':'7'}}");
        step("expect", "{'target':{'name':'status'},'text':'qty 3 code 42'}");
    }

    @Test
    @Order(10)
    void anEditorThatKeepsItsEditOpenNeverTakesTheTextOfAnotherCell() throws Exception {
        step("type", "{'target':{'name':'lines','row':{'Product':'Paper'},'column':'Code'},'text':'11','commit':true}");
        step("type", "{'target':{'name':'lines','row':{'Product':'Paper'},'column':'Code'},'text':'abc'}");
        // Code's editor refuses to stop, silently: Qty's edit cannot start, and Code's field must not take the 5
        refused("type", "{'target':{'name':'lines','row':{'Product':'Paper'},'column':'Qty'},'text':'5','commit':true}",
                "column 'Code' is still being edited");
        step("expect", "{'target':{'name':'lines'},'cell':{'row':{'Product':'Paper'},'column':'Code','expected':'11'}}");
        step("type", "{'target':{'name':'lines','row':{'Product':'Paper'},'column':'Code'},'text':'12','commit':true}");
        step("expect", "{'target':{'name':'lines'},'cell':{'row':{'Product':'Paper'},'column':'Code','expected':'12'}}");
    }

    @Test
    @Order(11)
    void aRecordedRecordedTestIsExportedAndRunsAgain() throws Exception {
        testsDir = Files.createTempDirectory("swingui-recorded-it");
        String dir = testsDir.toString().replace('\\', '/');
        call("session", "{'op':'reset'}");
        step("click", "{'target':{'name':'new'}}");
        step("type", "{'target':{'name':'customer'},'text':'Recorded test 1'}");
        step("click", "{'target':{'name':'save'}}");
        step("expect", "{'target':{'name':'status'},'text':'saved Recorded test 1'}");
        JsonNode exported = call("export_recorded_test", "{'name':'save-customer','dir':'" + dir + "'}");
        Path file = Paths.get(exported.path("savedTo").asText());
        assertThat(file).as(exported.path("why").asText()).isRegularFile();
        assertThat(exported.path("steps").asInt()).as(exported.path("why").asText()).isEqualTo(4);
        assertThat(exported.path("startWarning").asText()).as(exported.path("why").asText()).isNotEmpty();
        assertThat(RecordedTestFiles.read(file).steps).hasSize(4);

        JsonNode ran = call("run_recorded_test", "{'path':'" + file.toString().replace('\\', '/') + "'}");
        assertThat(ran.path("passed").asBoolean()).as(ran.path("why").asText()).isTrue();
        assertThat(ran.path("ran").asInt()).as(ran.path("why").asText()).isEqualTo(4);
    }

    @Test
    @Order(12)
    void aFailingRecordedTestExitsFourWithItsDiagnosis() throws Exception {
        Path evidence = Files.createDirectory(testsDir.resolve("evidence"));
        JsonNode result = callExpecting(4, "run_recorded_test", "{'test':{'version':2,'name':'never-saved','start':'reset',"
                + "'steps':[{'checks':[{'target':{'name':'status'},'text':'never','timeout':500}]}]},"
                + "'evidence':'" + evidence.toString().replace('\\', '/') + "'}");
        String why = result.path("why").asText();
        assertThat(result.path("passed").asBoolean()).as(why).isFalse();
        JsonNode first = result.path("steps").path(0);
        assertThat(first.path("passed").asBoolean()).as(why).isFalse();
        Path png = Paths.get(first.path("diagnostics").path("screenshot").asText());
        assertThat(png).as(why).isRegularFile();
        assertThat(png.getFileName().toString()).endsWith(".png");
        assertThat(result.path("stderr").asText()).as(why).contains("FAIL");
    }

    /** The folder holds only the exported recorded test when this runs: the failing one of the step before was inline. */
    @FixtureClass
    static class Runner {
        @RegisterExtension
        static final UiTestExtension EXT = new UiTestExtension();

        @TestFactory
        Stream<DynamicTest> recorded(Client ui) {
            return RecordedTests.of(ui, testsDir);
        }
    }

    @Test
    @Order(13)
    void theJUnitRunnerRunsTheExportedRecordedTest() {
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(Runner.class))
                .configurationParameter("swingui.fixtures", "run")
                .configurationParameter("swingui.agents", "127.0.0.1:" + port).build(), listener);
        TestExecutionSummary summary = listener.getSummary();
        assertThat(summary.getFailures()).as("failures: %s\napp output:\n%s", summary.getFailures(), appOutput).isEmpty();
        assertThat(summary.getTestsFoundCount()).isEqualTo(1);
        assertThat(summary.getTestsSucceededCount()).isEqualTo(1);
    }

    @Test
    @Order(14)
    void anAgentStartedRecordingIsEditedReplayedAndExported() throws Exception {
        Path folder = Files.createDirectories(testsDir.resolve("together"));
        String dir = folder.toString().replace('\\', '/');
        String expectSaved = "{'at':'3.1','step':{'action':'expect','target':{'name':'status'},'text':'saved Rec 1'}}";
        JsonNode started = call("recording", "{'op':'start','reset':true}");
        try {
            String why = started.path("why").asText();
            assertThat(started.path("state").asText()).as(why).isEqualTo("recording");
            assertThat(started.path("shared").asBoolean()).as(why).isTrue();
            // a recording is running: the session cannot be reset under it
            CliResult busy = cli("session", "{'op':'reset'}");
            assertThat(busy.exit).as(busy.err).isEqualTo(1);
            assertThat(busy.err).contains("recording");

            step("click", "{'target':{'name':'new'}}");
            step("type", "{'target':{'name':'customer'},'text':'Rec 1'}");
            step("type", "{'target':{'name':'notes'},'text':'scratch'}");
            step("click", "{'target':{'name':'save'}}");
            JsonNode list = call("recording", "{'op':'list'}");
            why = list.path("why").asText();
            assertThat(list.path("steps")).as(why).hasSize(4);
            for (JsonNode s : list.path("steps")) {
                assertThat(s.path("author").asText()).as(why).isEqualTo("agent");
                assertThat(s.path("java").asText()).as(why).isNotEmpty();
            }

            JsonNode deleted = call("recording", "{'op':'delete','n':3,'rev':" + list.path("rev").asLong() + "}");
            assertThat(deleted.path("steps")).as(deleted.path("why").asText()).hasSize(3);
            JsonNode inserted = call("recording", "{'op':'insert'," + expectSaved.substring(1));
            why = inserted.path("why").asText();
            assertThat(inserted.path("steps")).as(why).hasSize(3);   // the check sits under step 3
            assertThat(inserted.path("steps").path(2).path("checks").path(0).path("edited").asBoolean()).as(why).isTrue();
            JsonNode undone = call("recording", "{'op':'undo'}");
            assertThat(undone.path("steps")).as(undone.path("why").asText()).hasSize(3);
            assertThat(undone.path("steps").path(2).path("checks")).as(undone.path("why").asText()).isEmpty();
            call("recording", "{'op':'insert'," + expectSaved.substring(1));

            JsonNode replayed = call("recording", "{'op':'replay'}");
            why = replayed.path("why").asText();
            assertThat(replayed.path("passed").asBoolean()).as(why).isTrue();
            assertThat(replayed.path("ran").asInt()).as(why).isEqualTo(4);
            assertThat(replayed.path("reset").asBoolean()).as(why).isTrue();
            // a passing replay clears the edit marks
            JsonNode after = call("recording", "{'op':'list'}");
            for (JsonNode s : after.path("steps")) {
                assertThat(s.path("edited").asBoolean()).as(after.path("why").asText()).isFalse();
                for (JsonNode c : s.path("checks")) {
                    assertThat(c.path("edited").asBoolean()).as(after.path("why").asText()).isFalse();
                }
            }
            call("recording", "{'op':'stop'}");
        } finally {
            stopIfStillRecording();
        }

        JsonNode exported = call("recording", "{'op':'export','as':'recorded','name':'together','dir':'" + dir + "'}");
        String why = exported.path("why").asText();
        Path file = Paths.get(exported.path("savedTo").asText());
        assertThat(file).as(why).isRegularFile();
        assertThat(RecordedTestFiles.read(file).steps).as(why).hasSize(4);
        JsonNode ran = call("run_recorded_test", "{'path':'" + file.toString().replace('\\', '/') + "'}");
        assertThat(ran.path("passed").asBoolean()).as(ran.path("why").asText()).isTrue();
        assertThat(ran.path("ran").asInt()).as(ran.path("why").asText()).isEqualTo(4);
    }

    /** Cleanup that never replaces the assertion that failed: stops only a recording still running. */
    private static void stopIfStillRecording() {
        try {
            if ("recording".equals(call("recording", "{'op':'list'}").path("state").asText())) {
                call("recording", "{'op':'stop'}");
            }
        } catch (Throwable ignored) {
            // the test's own failure is the one to report
        }
    }

    /** Exit code and stderr of a CLI call that is refused as a tool error (empty stdout). */
    private static final class CliResult {
        int exit;
        String err;
    }

    private static CliResult cli(String tool, String args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliResult r = new CliResult();
        r.exit = UiCli.run(new String[] {"--port", String.valueOf(port), tool, args.replace('\'', '"')},
                new PrintStream(out, true, "UTF-8"), new PrintStream(err, true, "UTF-8"));
        r.err = new String(err.toByteArray(), StandardCharsets.UTF_8);
        return r;
    }

    /** Runs one CLI call, {@code args} in single quotes for readability, and requires it to pass. */
    private static void step(String tool, String args) throws Exception {
        JsonNode result = call(tool, args);
        assertThat(result.path("passed").asBoolean()).as(result.path("why").asText()).isTrue();
    }

    /** Runs one CLI call and requires it to be refused as NOT_ACTIONABLE with a message holding {@code because}. */
    private static void refused(String tool, String args, String because) throws Exception {
        JsonNode result = call(tool, args);
        String why = result.path("why").asText();
        assertThat(result.path("passed").asBoolean()).as(why).isFalse();
        assertThat(result.path("reason").asText()).as(why).isEqualTo("NOT_ACTIONABLE");
        assertThat(result.path("message").asText()).as(why).contains(because);
    }

    /** The CLI's answer, which must exit 0, with a "why" for assertion messages. */
    private static JsonNode call(String tool, String args) throws Exception {
        return callExpecting(0, tool, args);
    }

    /** Like {@link #call}, for a tool whose CLI exit code is {@code exit}; the report on stderr is "stderr". */
    private static JsonNode callExpecting(int expected, String tool, String args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = UiCli.run(new String[] {"--port", String.valueOf(port), tool, args.replace('\'', '"')},
                new PrintStream(out, true, "UTF-8"), new PrintStream(err, true, "UTF-8"));
        String answer = new String(out.toByteArray(), StandardCharsets.UTF_8);
        String why = tool + " " + args + " -> exit " + exit + ": " + answer + new String(err.toByteArray(),
                StandardCharsets.UTF_8) + "\napp output:\n" + appOutput;
        assertThat(exit).as(why).isEqualTo(expected);
        ObjectNode result = (ObjectNode) JSON.readTree(answer);
        result.put("stderr", new String(err.toByteArray(), StandardCharsets.UTF_8));
        return result.put("why", why);
    }
}
