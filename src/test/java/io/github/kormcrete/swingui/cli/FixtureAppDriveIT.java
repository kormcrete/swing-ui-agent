package io.github.kormcrete.swingui.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kormcrete.swingui.fixture.FixtureApp;
import io.github.kormcrete.swingui.flow.RecordedTestFiles;
import io.github.kormcrete.swingui.journal.RunFolderFormat;
import io.github.kormcrete.swingui.junit.Client;
import io.github.kormcrete.swingui.junit.RecordedTests;
import io.github.kormcrete.swingui.junit.UiAssertions;
import io.github.kormcrete.swingui.junit.UiTestExtension;
import io.github.kormcrete.swingui.testing.FixtureClass;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
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
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
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
 * agent does - typing, clicking, keys that follow focus the app moved itself, Tab, a modal dialog, a drop, a
 * context menu and a row moved by the app's own drag and drop - with the person's palette shown in the window, as it is
 * by default. In the Register window the agent records tests whose steps use what earlier steps made (a copied record,
 * a new tag and folder) and a text unique per run; a new app and agent replay them from their files alone, as recorded
 * tests and as Java tests. UI tests launched against it leave a run folder of the documented format: a journal line of
 * every kind, a masked checkpoint, a failing test's journal ending at its failed check, and two checkpoints of the same
 * screen that are the same picture. Run by failsafe after {@code package}; on every OS CI covers, this is what makes the
 * platform green.
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
        startApp();
    }

    /** Starts FixtureApp with the packaged agent jar and waits until the agent listens ({@link #port}). */
    private static void startApp() throws Exception {
        Path agentJar = Paths.get(System.getProperty("swingui.agentJar", "missing: set swingui.agentJar"));
        assertThat(Files.isRegularFile(agentJar)).as("agent jar %s (run through mvn verify)", agentJar).isTrue();
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        // only the fixture's own classes: the agent must come from the packaged jar, not from target/classes
        String fixtureClasses = Paths.get(FixtureApp.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
        Process started = new ProcessBuilder(java, "-javaagent:" + agentJar + "=port=0", "-cp", fixtureClasses,
                FixtureApp.class.getName()).redirectErrorStream(true).start();
        app = started;
        CountDownLatch listening = new CountDownLatch(1);
        Thread drain = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
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
        try {
            if (testsDir != null) {
                try (Stream<Path> all = Files.walk(testsDir)) {
                    all.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
        } finally {
            // the app this class started last: the first one, or the one a case started in its place
            stopApp();
        }
    }

    private static void stopApp() throws Exception {
        if (app == null) return;
        app.destroy();
        // gone before the next app starts or the build cleans: a live JVM keeps the agent jar open on Windows
        if (!app.waitFor(10, TimeUnit.SECONDS)) app.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        app = null;
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
        step("expect", "{'target':{'name':'status'},'text':'idle'}");
        // the window is up; the palette comes in between the agent's steps, never during one (so not while an expect
        // waits for it): reads are no steps, and the fixture enables Palette over Archive once the palette is there
        awaitEnabled("paletteOverArchive");
        // the palette's default spot covers no button, so the person drags it over Archive (their spot wins there);
        // the bar steps off it for the agent's click
        step("click", "{'target':{'name':'paletteOverArchive'}}");
        step("expect", "{'target':{'name':'status'},'text':'palette over archive'}");
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
        // the row's item is enabled only while a row is selected: the right-click selects the row it is aimed at
        step("expect", "{'target':{'name':'lines'},'selected':[]}");
        step("context_menu", "{'target':{'name':'lines','row':{'Product':'Paper'}},'path':['Copy product']}");
        step("expect", "{'target':{'name':'status'},'text':'copied Paper'}");
        long started = System.currentTimeMillis();
        JsonNode wrong = call("context_menu", "{'target':{'name':'lines','row':{'Product':'Paper'}},'path':['Delete product']}");
        assertThat(wrong.path("passed").asBoolean()).as(wrong.path("why").asText()).isFalse();
        assertThat(wrong.path("reason").asText()).isEqualTo("NOT_FOUND");
        assertThat(wrong.path("message").asText()).contains("no item 'Delete product' in the popup; items are [").contains("Copy product");
        assertThat(System.currentTimeMillis() - started).isLessThan(5_000);
        step("expect", "{'target':{'name':'lines'},'selected':[0]}");
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
    void aRefusedEditFailsTheStepIsCancelledAndTheCellKeepsItsValue() throws Exception {
        // the typed value is lost: the step must not pass, and it says what the cell kept
        refused("type", "{'target':{'name':'lines','row':{'Product':'Paper'},'column':'Code'},'text':'abc','commit':true}",
                "refused the value; the edit was cancelled and the cell keeps \"10\"");
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
        assertThat(exported.path("steps").asInt()).as(exported.path("why").asText()).isEqualTo(3);
        assertThat(exported.path("checks").asInt()).as(exported.path("why").asText()).isEqualTo(1);
        assertThat(exported.path("startWarning").asText()).as(exported.path("why").asText()).isNotEmpty();
        assertThat(RecordedTestFiles.read(file).steps).hasSize(4);

        JsonNode ran = call("run_recorded_test", "{'path':'" + file.toString().replace('\\', '/') + "'}");
        assertThat(ran.path("passed").asBoolean()).as(ran.path("why").asText()).isTrue();
        assertThat(ran.path("ran").asInt()).as(ran.path("why").asText()).isEqualTo(3);
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
            assertThat(replayed.path("ran").asInt()).as(why).isEqualTo(3);   // steps: the check sits under step 3
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
        assertThat(ran.path("ran").asInt()).as(ran.path("why").asText()).isEqualTo(3);
    }

    @Test
    @Order(15)
    void aTableRowStepIsNamedByItsValueOrItsPosition() throws Exception {
        try {
            assertThat(call("recording", "{'op':'start','reset':true}").path("state").asText()).isEqualTo("recording");
            step("click", "{'target':{'name':'lines','row':{'Product':'Paper'},'column':'Product'}}");
            step("expect", "{'target':{'name':'lines'},'cell':{'row':{'Product':'Paper'},'column':'Product','expected':'Paper'}}");
            JsonNode list = call("recording", "{'op':'list'}");
            String why = list.path("why").asText();
            JsonNode row = list.path("steps").path(0).path("row");
            assertThat(row.path("by").asText()).as(why).isEqualTo("value");
            assertThat(row.path("index").asInt()).as(why).isEqualTo(0);
            assertThat(list.path("steps").path(0).path("checks").path(0).path("row").path("index").asInt()).as(why).isEqualTo(0);

            JsonNode byPosition = call("recording", "{'op':'row','n':1,'by':'position'}");
            assertThat(byPosition.path("steps").path(0).path("step").path("target").path("row").toString())
                    .as(byPosition.path("why").asText()).isEqualTo("{\"index\":0}");
            JsonNode replayed = call("recording", "{'op':'replay'}");
            assertThat(replayed.path("passed").asBoolean()).as(replayed.path("why").asText()).isTrue();
            call("recording", "{'op':'row','n':'1.1','by':'position'}");
            replayed = call("recording", "{'op':'replay'}");
            assertThat(replayed.path("passed").asBoolean()).as(replayed.path("why").asText()).isTrue();
            call("recording", "{'op':'stop'}");
        } finally {
            stopIfStillRecording();
        }
    }

    @Test
    @Order(16)
    void oneNumberingInListReportAndFile() throws Exception {
        Path folder = Files.createDirectories(testsDir.resolve("described"));
        String dir = folder.toString().replace('\\', '/');
        try {
            assertThat(call("recording", "{'op':'start','reset':true}").path("state").asText()).isEqualTo("recording");
            // a check before any action is step 1; the save button is not disabled whatever earlier cases left in the status
            step("expect", "{'target':{'name':'save'},'state':['!disabled']}");
            step("click", "{'target':{'name':'new'}}");
            step("type", "{'target':{'name':'customer'},'text':'Said 1'}");
            step("click", "{'target':{'name':'save'}}");
            step("expect", "{'target':{'name':'status'},'text':'saved Said 1'}");
            call("recording", "{'op':'edit','n':2,'say':'Start a new customer'}");
            call("recording", "{'op':'edit','n':'4.1','say':'It is saved'}");
            call("recording", "{'op':'group','from':3,'to':4,'say':'Save Said 1'}");

            JsonNode list = call("recording", "{'op':'list'}");
            String why = list.path("why").asText();
            assertThat(list.path("steps")).as(why).hasSize(4);
            assertThat(list.path("steps").path(0).path("checks").path(0).path("n").asText()).as(why).isEqualTo("1.1");
            assertThat(list.path("steps").path(3).path("checks").path(0).path("n").asText()).as(why).isEqualTo("4.1");
            assertThat(list.path("steps").path(3).path("checks").path(0).path("say").asText()).as(why).isEqualTo("It is saved");
            assertThat(list.path("groups")).as(why).hasSize(1);
            assertThat(list.path("groups").path(0).path("from").asInt()).as(why).isEqualTo(3);
            assertThat(list.path("groups").path(0).path("to").asInt()).as(why).isEqualTo(4);
            call("recording", "{'op':'stop'}");
        } finally {
            stopIfStillRecording();
        }

        JsonNode exported = call("recording", "{'op':'export','as':'recorded','name':'said','dir':'" + dir + "'}");
        String why = exported.path("why").asText();
        Path file = Paths.get(exported.path("savedTo").asText());
        assertThat(file).as(why).isRegularFile();
        JsonNode json = JSON.readTree(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertThat(json.path("version").asInt()).as(why).isEqualTo(2);
        // the check-only step first, then a step, then the group holding two steps
        assertThat(json.path("steps")).as(why).hasSize(3);
        assertThat(json.path("steps").path(0).path("checks")).as(why).hasSize(1);
        JsonNode group = json.path("steps").path(2);
        assertThat(group.path("say").asText()).as(why).isEqualTo("Save Said 1");
        assertThat(group.path("steps")).as(why).hasSize(2);
        assertThat(group.path("steps").path(1).path("checks").path(0).path("say").asText()).as(why).isEqualTo("It is saved");

        JsonNode ran = call("run_recorded_test", "{'path':'" + file.toString().replace('\\', '/') + "'}");
        why = ran.path("why").asText();
        assertThat(ran.path("passed").asBoolean()).as(why).isTrue();
        String report = ran.path("report").asText();
        assertThat(report).as(why).contains("[1.1]").contains("[4.1]").contains("Start a new customer").contains("\u2014 Save Said 1");
        assertThat(ran.path("ran").asInt()).as(why).isEqualTo(4);   // steps: ran and total do not count checks
        assertThat(ran.path("total").asInt()).as(why).isEqualTo(4);
        JsonNode steps = ran.path("steps");
        assertThat(steps).as(why).hasSize(5);
        assertThat(steps.path(0).path("n").asText()).as(why).isEqualTo("1.1");
        assertThat(steps.path(1).path("n").asInt()).as(why).isEqualTo(2);
        assertThat(steps.path(2).path("n").asInt()).as(why).isEqualTo(3);
        assertThat(steps.path(3).path("n").asInt()).as(why).isEqualTo(4);
        assertThat(steps.path(4).path("n").asText()).as(why).isEqualTo("4.1");
    }

    @Test
    @Order(17)
    void aValueLabelIsCheckedAndRecordedByItsCaption() throws Exception {
        step("click", "{'target':{'name':'new'}}");
        step("expect", "{'target':{'role':'label','name':'Total'},'text':'€0.00'}");
        step("click", "{'target':{'name':'save'}}");
        step("expect", "{'target':{'role':'label','name':'Total'},'text':'€7.32'}");
        try {
            assertThat(call("recording", "{'op':'start','reset':true}").path("state").asText()).isEqualTo("recording");
            JsonNode read = call("read", "{'target':{'role':'label','name':'Total'}}");
            String ref = read.path("node").path("ref").asText();
            assertThat(ref).as(read.path("why").asText()).isNotEmpty();
            JsonNode checked = call("expect", "{'target':{'ref':'" + ref + "'},'text':'€7.32'}");
            String why = checked.path("why").asText();
            assertThat(checked.path("passed").asBoolean()).as(why).isTrue();
            JsonNode target = checked.path("recorded").path("target");
            assertThat(target.path("role").asText()).as(why).isEqualTo("label");
            assertThat(target.path("name").asText()).as(why).isEqualTo("Total");
            JsonNode list = call("recording", "{'op':'list'}");
            assertThat(list.path("steps").path(0).path("checks").path(0).path("java").asText()).as(list.path("why").asText())
                    .contains("label(\"Total\")");
            call("recording", "{'op':'stop'}");
        } finally {
            stopIfStillRecording();
        }
    }

    @Test
    @Order(18)
    void anElementIsShownWhileItsDialogIsOpenAndAbsentOnceItIsAnswered() throws Exception {
        step("click", "{'target':{'name':'delete'}}");
        step("expect", "{'target':{'role':'button','name':'Yes'},'shown':true}");
        JsonNode stillThere = call("expect", "{'target':{'role':'button','name':'Yes'},'absent':true,'timeout':300}");
        assertThat(stillThere.path("passed").asBoolean()).as(stillThere.path("why").asText()).isFalse();
        assertThat(stillThere.path("message").asText()).contains("absent");
        step("answer", "{'button':'Yes'}");
        step("expect", "{'target':{'role':'button','name':'Yes'},'absent':true}");
    }

    @Test
    @Order(19)
    void aRecordedTestChecksThatAClosedTabIsAbsent() throws Exception {
        JsonNode ran = call("run_recorded_test", "{'test':{'version':2,'name':'close-the-receipt','start':'reset','steps':["
                + "{'checks':[{'target':{'role':'tab','name':'ORD-1640'},'shown':true}]},"
                + "{'action':'click','target':{'role':'button','name':'Close tab'},'checks':["
                + "{'target':{'role':'tab','name':'ORD-1640'},'absent':true},"
                + "{'target':{'name':'status'},'text':'tab closed'}]}]}}");
        assertThat(ran.path("passed").asBoolean()).as(ran.path("why").asText()).isTrue();
        assertThat(ran.path("ran").asInt()).isEqualTo(2);
    }

    /** Where the Register window's elements are: its own window, beside the main one. */
    private static final String REGISTER = "'window':'window:Register'";

    /** The copy recorded test of {@link #aRecordingThatCopiesARowReplaysTwiceEachOnItsOwnCopy}, for the broken link after it. */
    private static Path copyTest;

    @Test
    @Order(20)
    void aRecordingThatCopiesARowReplaysTwiceEachOnItsOwnCopy() throws Exception {
        Path folder = Files.createDirectories(testsDir.resolve("copied"));
        Path javaRoot = Files.createDirectories(testsDir.resolve("copied-java"));
        try {
            assertThat(call("recording", "{'op':'start','reset':true}").path("state").asText()).isEqualTo("recording");
            step("menu", "{'path':['Window','Register']}");
            step("click", "{'target':{" + REGISTER + ",'name':'Register','row':{'Number':'REG-1001'},'column':'Number'}}");
            step("click", "{'target':{" + REGISTER + ",'role':'button','name':'Copy'}}");
            // the copy comes in 150 ms after the click: the agent looks for it before it clicks it, and it is what Copy
            // changed (read again until the agent's next action)
            awaitFound("{" + REGISTER + ",'name':'Register','row':{'Number':'REG-1003'}}");
            step("click", "{'target':{" + REGISTER + ",'name':'Register','row':{'Number':'REG-1003'},'column':'Number'}}");
            // a check of the copy's own number: new on every run, so linked to what Copy added
            step("expect", "{'target':{" + REGISTER + ",'name':'Register'},'cell':{'row':{'Number':'REG-1003'},"
                    + "'column':'Number','expected':'REG-1003'}}");
            JsonNode remembered = call("recording", "{'op':'remember','target':{" + REGISTER + ",'name':'Register',"
                    + "'column':'Number'},'selected':true,'as':'copyNumber'}");
            assertThat(remembered.path("did").asText()).as(remembered.path("why").asText())
                    .isEqualTo("remembered REG-1003 as copyNumber (check 4.2)");
            step("type", "{'target':{" + REGISTER + ",'role':'text','name':'Note'},'text':'Copied note'}");
            JsonNode unique = call("recording", "{'op':'unique','n':5,'where':'end'}");
            assertThat(unique.path("did").asText()).as(unique.path("why").asText())
                    .isEqualTo("made the text of step 5 unique per run");
            step("click", "{'target':{" + REGISTER + ",'role':'button','name':'Save note'}}");
            step("expect", "{'target':{" + REGISTER + ",'name':'Register'},'cell':{'row':{'Number':'REG-1003'},"
                    + "'column':'Note','expected':'Copied note'}}");

            // the person's words for each step, and the note's two steps under one sentence
            String[][] said = {{"1", "Open the register"}, {"2", "Pick the first record"}, {"3", "Copy it"},
                    {"4", "Open the copy"}, {"'4.1'", "It has the new number"}, {"5", "Write a note of this run"}, {"6", "Save the note"},
                    {"'6.1'", "The copy keeps the note"}};
            for (String[] say : said) call("recording", "{'op':'edit','n':" + say[0] + ",'say':'" + say[1] + "'}");
            call("recording", "{'op':'group','from':5,'to':6,'say':'Note the copy'}");

            JsonNode list = call("recording", "{'op':'list'}");
            String why = list.path("why").asText();
            JsonNode steps = list.path("steps");
            assertThat(steps).as(why).hasSize(6);
            // the copy's number came from what Copy changed: linked at once, as the click on it was recorded
            String copied = "${copy.rows.Register.added[0].Number}";
            assertThat(steps.path(3).path("step").path("target").path("row").path("Number").asText()).as(why)
                    .isEqualTo(copied);
            JsonNode own = steps.path(3).path("checks").path(0).path("step").path("cell");
            assertThat(own.path("row").path("Number").asText()).as(why).isEqualTo(copied);
            assertThat(own.path("expected").asText()).as(why).isEqualTo(copied);
            assertThat(steps.path(4).path("step").path("text").asText()).as(why).isEqualTo("Copied note ${run.id}");
            JsonNode check = steps.path(5).path("checks").path(0).path("step");
            // the check names the row by the remembered number and expects the unique note
            assertThat(check.path("cell").path("row").path("Number").asText()).as(why).isEqualTo("${copyNumber}");
            assertThat(check.path("cell").path("expected").asText()).as(why).isEqualTo("Copied note ${run.id}");
            call("recording", "{'op':'stop'}");
        } finally {
            stopIfStillRecording();
        }

        JsonNode exported = call("recording", "{'op':'export','as':'recorded','name':'copy-a-row','dir':'"
                + slashes(folder) + "'}");
        String why = exported.path("why").asText();
        copyTest = Paths.get(exported.path("savedTo").asText());
        assertThat(copyTest).as(why).isRegularFile();
        String file = new String(Files.readAllBytes(copyTest), StandardCharsets.UTF_8);
        JsonNode json = JSON.readTree(file);
        assertThat(json.path("version").asInt()).as(file).isEqualTo(4);
        // everything the agent built is in the file: sentences, the group, the links, the remembered name, the run's id
        assertThat(file).as(file).contains("\"Open the register\"", "\"Pick the first record\"", "\"Copy it\"",
                "\"Open the copy\"", "\"It has the new number\"", "\"Write a note of this run\"", "\"Save the note\"", "\"The copy keeps the note\"",
                "\"Note the copy\"", "\"id\" : \"copy\"", "\"${copy.rows.Register.added[0].Number}\"",
                "\"as\" : \"copyNumber\"", "\"${copyNumber}\"", "\"Copied note ${run.id}\"")
                .doesNotContain("REG-1003");
        JsonNode javaExport = call("recording", "{'op':'export','as':'java','class':'CopyARowUiIT','package':'copied',"
                + "'dir':'" + slashes(javaRoot) + "'}");
        Path javaFile = Paths.get(javaExport.path("savedTo").asText());
        assertThat(javaFile).as(javaExport.path("why").asText()).isRegularFile();

        // nothing of the recording session is left: a new app with a new agent, only the file
        stopApp();
        startApp();
        assertThat(call("recording", "{'op':'list'}").path("steps")).isEmpty();
        JsonNode first = runCopy();
        JsonNode second = runCopy();
        String number1 = resolved(first, "${copy.rows.Register.added[0].Number}");
        String number2 = resolved(second, "${copy.rows.Register.added[0].Number}");
        String id1 = resolved(first, "${run.id}");
        String id2 = resolved(second, "${run.id}");
        // each run copied a record of its own, under its own number, and noted it with its own id
        assertThat(number1).matches("REG-\\d{4}");
        assertThat(number2).matches("REG-\\d{4}").isNotEqualTo(number1);
        assertThat(id1).matches("[a-z0-9]{8}");
        assertThat(id2).matches("[a-z0-9]{8}").isNotEqualTo(id1);
        assertThat(second.path("report").asText()).as(second.path("why").asText()).contains(number2, id2);
        assertThat(resolved(second, "${copyNumber}")).isEqualTo(number2);

        String source = new String(Files.readAllBytes(javaFile), StandardCharsets.UTF_8);
        assertThat(source).as(source).contains("Outcome copy = ui.outcomeOf(", "ui.run().id()", ".selectedText()");
        runExportedJava(javaRoot, javaFile, "copied.CopyARowUiIT");
    }

    /** Runs the copy recorded test from its file and requires it to pass; its result. */
    private static JsonNode runCopy() throws Exception {
        JsonNode ran = call("run_recorded_test", "{'path':'" + slashes(copyTest) + "'}");
        String why = ran.path("why").asText();
        assertThat(ran.path("passed").asBoolean()).as(why).isTrue();
        assertThat(ran.path("ran").asInt()).as(why).isEqualTo(6);
        return ran;
    }

    @Test
    @Order(21)
    void aListItemAndATreeItemAddedByAStepAreClickedThroughALink() throws Exception {
        Path folder = Files.createDirectories(testsDir.resolve("entries"));
        Path javaRoot = Files.createDirectories(testsDir.resolve("entries-java"));
        try {
            assertThat(call("recording", "{'op':'start','reset':true}").path("state").asText()).isEqualTo("recording");
            step("menu", "{'path':['Window','Register']}");
            // what each button added, as the agent learns it: the outcome of its click
            JsonNode tagAdded = call("click", "{'target':{" + REGISTER + ",'role':'button','name':'Add tag'},'outcome':true}");
            String tag = tagAdded.path("outcome").path("items").path("Tags").path("added").path(0).asText();
            assertThat(tag).as(tagAdded.path("why").asText()).matches("TAG-\\d+");
            step("click", "{'target':{" + REGISTER + ",'name':'Tags','item':['" + tag + "']}}");
            JsonNode folderAdded = call("click", "{'target':{" + REGISTER + ",'role':'button','name':'New folder'},"
                    + "'outcome':true}");
            String path = folderAdded.path("outcome").path("items").path("Folders").path("added").path(0).asText();
            assertThat(path).as(folderAdded.path("why").asText()).matches("Folders > Folder \\d+");
            String folderName = path.substring("Folders > ".length());
            step("click", "{'target':{" + REGISTER + ",'name':'Folders','item':['Folders','" + folderName + "']}}");

            JsonNode list = call("recording", "{'op':'list'}");
            String why = list.path("why").asText();
            JsonNode steps = list.path("steps");
            assertThat(steps).as(why).hasSize(5);
            assertThat(steps.path(2).path("step").path("target").path("item").toString()).as(why)
                    .isEqualTo("[\"${addTag.items.Tags.added[0]}\"]");
            assertThat(steps.path(4).path("step").path("target").path("item").toString()).as(why)
                    .isEqualTo("[\"${newFolder.items.Folders.added[0]}\"]");
            call("recording", "{'op':'stop'}");
        } finally {
            stopIfStillRecording();
        }

        JsonNode exported = call("recording", "{'op':'export','as':'recorded','name':'click-the-new-entries','dir':'"
                + slashes(folder) + "'}");
        Path file = Paths.get(exported.path("savedTo").asText());
        assertThat(file).as(exported.path("why").asText()).isRegularFile();
        JsonNode javaExport = call("recording", "{'op':'export','as':'java','class':'NewEntriesUiIT','package':'entries',"
                + "'dir':'" + slashes(javaRoot) + "'}");
        Path javaFile = Paths.get(javaExport.path("savedTo").asText());
        assertThat(javaFile).as(javaExport.path("why").asText()).isRegularFile();

        JsonNode first = call("run_recorded_test", "{'path':'" + slashes(file) + "'}");
        assertThat(first.path("passed").asBoolean()).as(first.path("why").asText()).isTrue();
        JsonNode second = call("run_recorded_test", "{'path':'" + slashes(file) + "'}");
        assertThat(second.path("passed").asBoolean()).as(second.path("why").asText()).isTrue();
        String tag1 = resolved(first, "${addTag.items.Tags.added[0]}");
        String tag2 = resolved(second, "${addTag.items.Tags.added[0]}");
        assertThat(tag1).matches("TAG-\\d+");
        assertThat(tag2).matches("TAG-\\d+").isNotEqualTo(tag1);
        String folder1 = resolved(first, "${newFolder.items.Folders.added[0]}");
        String folder2 = resolved(second, "${newFolder.items.Folders.added[0]}");
        assertThat(folder1).matches("Folders > Folder \\d+");
        assertThat(folder2).matches("Folders > Folder \\d+").isNotEqualTo(folder1);
        assertThat(second.path("report").asText()).as(second.path("why").asText()).contains(tag2, folder2);
        runExportedJava(javaRoot, javaFile, "entries.NewEntriesUiIT");
    }

    @Test
    @Order(22)
    void aBrokenLinkFailsInWords() throws Exception {
        // the step Copy was recorded on now clicks Save note, which adds no record: the copy it opens is not there.
        // The link waits the driver's whole expect timeout (10 s) for it: a run has no shorter wait of its own.
        assertThat(copyTest).as("the copy recorded test of aRecordingThatCopiesARowReplaysTwiceEachOnItsOwnCopy, "
                + "which did not get as far as its export").isNotNull();
        ObjectNode test = (ObjectNode) JSON.readTree(new String(Files.readAllBytes(copyTest), StandardCharsets.UTF_8));
        ObjectNode copy = stepWithId(test.path("steps"), "copy");
        assertThat(copy).as(test.toString()).isNotNull();
        ((ObjectNode) copy.path("target")).put("name", "Save note");
        Path broken = Files.createDirectories(testsDir.resolve("broken")).resolve("copy-a-row.json");
        Files.write(broken, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(test));

        JsonNode ran = callExpecting(4, "run_recorded_test", "{'path':'" + slashes(broken) + "'}");
        String why = ran.path("why").asText();
        assertThat(ran.path("passed").asBoolean()).as(why).isFalse();
        JsonNode failed = null;
        for (JsonNode s : ran.path("steps")) if (!s.path("passed").asBoolean()) failed = s;
        assertThat(failed).as(why).isNotNull();
        assertThat(failed.path("n").asText()).as(why).isEqualTo("4");
        assertThat(failed.path("message").asText()).as(why).contains("Step 3 (Save note) added no row to Register");
        assertThat(ran.path("stderr").asText()).as(why).contains("Step 3 (Save note) added no row to Register");
    }

    @Test
    @Order(23)
    void aRecordOpenedInItsOwnWindowIsFoundByItsNumberOnEveryRun() throws Exception {
        Path folder = Files.createDirectories(testsDir.resolve("opened"));
        Path javaRoot = Files.createDirectories(testsDir.resolve("opened-java"));
        String number = "${copy.rows.Register.added[0].Number}";
        try {
            assertThat(call("recording", "{'op':'start','reset':true}").path("state").asText()).isEqualTo("recording");
            step("menu", "{'path':['Window','Register']}");
            int records = registerNumbers().size();
            step("click", "{'target':{" + REGISTER + ",'name':'Register','row':{'Number':'REG-1001'},'column':'Number'}}");
            step("click", "{'target':{" + REGISTER + ",'role':'button','name':'Copy'}}");
            // the copy comes in late: its number is the Register's last one once it is there
            List<String> numbers = registerNumbers();
            for (long end = System.currentTimeMillis() + 10_000; numbers.size() == records; numbers = registerNumbers()) {
                assertThat(System.currentTimeMillis()).as("no copy in the Register").isLessThan(end);
                Thread.sleep(50);
            }
            String n = numbers.get(numbers.size() - 1);
            assertThat(n).matches("REG-\\d{4}");
            String record = "'window':'window:" + n + "'";
            step("click", "{'target':{" + REGISTER + ",'name':'Register','row':{'Number':'" + n + "'},'column':'Number'}}");
            step("click", "{'target':{" + REGISTER + ",'role':'button','name':'Open'}}");
            step("click", "{'target':{" + record + ",'role':'tab','name':'History'}}");
            step("click", "{'target':{" + record + ",'role':'tab','name':'" + n + "'}}");
            step("expect", "{'target':{" + record + ",'scope':['tab:" + n + "'],'role':'label','name':'" + n + "'},"
                    + "'shown':true}");
            step("close_window", "{" + record + "}");

            JsonNode list = call("recording", "{'op':'list'}");
            String why = list.path("why").asText();
            JsonNode steps = list.path("steps");
            assertThat(steps).as(why).hasSize(8);
            // the window's title is the copy's number: found by what Copy added, not by the title Open showed
            JsonNode history = steps.path(5).path("step").path("target");
            assertThat(history.path("window").asText()).as(why).isEqualTo("window:" + number);
            assertThat(history.path("name").asText()).as(why).isEqualTo("History");
            JsonNode check = steps.path(6).path("checks").path(0).path("step").path("target");
            assertThat(check.path("scope").path(0).asText()).as(why).isEqualTo("tab:" + number);
            assertThat(check.path("name").asText()).as(why).isEqualTo(number);
            assertThat(steps.path(7).path("step").path("window").asText()).as(why).isEqualTo("window:" + number);
            call("recording", "{'op':'stop'}");

            JsonNode exported = call("recording", "{'op':'export','as':'recorded','name':'open-a-record','dir':'"
                    + slashes(folder) + "'}");
            Path file = Paths.get(exported.path("savedTo").asText());
            assertThat(file).as(exported.path("why").asText()).isRegularFile();
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            assertThat(text).as(text).contains("\"window:" + number + "\"", "\"tab:" + number + "\"").doesNotContain(n);
            JsonNode javaExport = call("recording", "{'op':'export','as':'java','class':'OpenARecordUiIT','package':'opened',"
                    + "'dir':'" + slashes(javaRoot) + "'}");
            Path javaFile = Paths.get(javaExport.path("savedTo").asText());
            assertThat(javaFile).as(javaExport.path("why").asText()).isRegularFile();

            JsonNode first = call("run_recorded_test", "{'path':'" + slashes(file) + "'}");
            assertThat(first.path("passed").asBoolean()).as(first.path("why").asText()).isTrue();
            JsonNode second = call("run_recorded_test", "{'path':'" + slashes(file) + "'}");
            assertThat(second.path("passed").asBoolean()).as(second.path("why").asText()).isTrue();
            String number1 = resolved(first, number);
            String number2 = resolved(second, number);
            assertThat(number1).matches("REG-\\d{4}").isNotEqualTo(n);
            assertThat(number2).matches("REG-\\d{4}").isNotEqualTo(number1);
            runExportedJava(javaRoot, javaFile, "opened.OpenARecordUiIT");
        } finally {
            stopIfStillRecording();
        }
    }

    @Test
    @Order(24)
    void aRowDraggedOntoAnotherMovesThroughTheAppsOwnDragAndDrop() throws Exception {
        String window = "'window':'window:Line order','name':'orderLines'";
        step("menu", "{'path':['Window','Line order']}");
        // the table moves rows with AWT drag and drop (a gesture recognizer and a drop target): the agent's drag
        // starts the app's drag, and the app's drop target moves the row
        JsonNode dragged = call("drag", "{'from':{" + window + ",'row':{'Product':'Milk'},'column':'Product'},"
                + "'to':{" + window + ",'row':{'Product':'Coffee'},'column':'Product'}}");
        String why = dragged.path("why").asText();
        assertThat(dragged.path("passed").asBoolean()).as(why).isTrue();
        assertThat(dragged.path("message").asText()).as(why).contains("accepted the drop (move)").contains("completed");
        step("expect", "{'target':{'window':'window:Line order','name':'lineStatus'},'text':'moved Milk to line 1'}");
        String[] order = {"Milk", "Coffee", "Tea"};
        for (int i = 0; i < order.length; i++) {
            step("expect", "{'target':{" + window + "},'cell':{'row':{'index':" + i + "},'column':'Product','expected':'"
                    + order[i] + "'}}");
        }
    }

    @Test
    @Order(25)
    void aDropThatAsksFirstAndAcceptsNothingIsLeftToTheAnswerAndMovesTheRow() throws Exception {
        String window = "'window':'window:Line order','name':'packLines'";
        step("menu", "{'path':['Window','Line order']}");
        // its drop asks first, and then moves the row without telling the toolkit it took the drop
        JsonNode dragged = call("drag", "{'from':{" + window + ",'row':{'Product':'Jam'},'column':'Product'},"
                + "'to':{" + window + ",'row':{'Product':'Bread'},'column':'Product'}}");
        String why = dragged.path("why").asText();
        assertThat(dragged.path("passed").asBoolean()).as(why).isTrue();
        assertThat(dragged.path("pending").path("message").asText()).as(why).contains("Move Jam to line 1?");
        step("answer", "{'button':'Yes'}");
        step("expect", "{'target':{'window':'window:Line order','name':'lineStatus'},'text':'moved Jam to line 1'}");
        String[] order = {"Jam", "Bread", "Butter"};
        for (int i = 0; i < order.length; i++) {
            step("expect", "{'target':{" + window + "},'cell':{'row':{'index':" + i + "},'column':'Product','expected':'"
                    + order[i] + "'}}");
        }
        // the drag and drop ended once the drop returned: the next one starts
        JsonNode again = call("drag", "{'from':{'window':'window:Line order','name':'orderLines','row':{'Product':'Tea'},"
                + "'column':'Product'},'to':{'window':'window:Line order','name':'orderLines','row':{'Product':'Milk'},"
                + "'column':'Product'}}");
        assertThat(again.path("passed").asBoolean()).as(again.path("why").asText()).isTrue();
        step("expect", "{'target':{'window':'window:Line order','name':'lineStatus'},'text':'moved Tea to line 1'}");
    }

    @Test
    @Order(26)
    void aListEntryDraggedOntoAnotherMovesThroughSwingsOwnDragAndDrop() throws Exception {
        String window = "'window':'window:Line order','name':'pickList'";
        step("menu", "{'path':['Window','Line order']}");
        // Swing's drag and drop (drag enabled, a TransferHandler) runs in the app like the AWT one: no native drag
        JsonNode dragged = call("drag", "{'from':{" + window + ",'item':['Plums']},'to':{" + window + ",'item':['Apples']}}");
        String why = dragged.path("why").asText();
        assertThat(dragged.path("passed").asBoolean()).as(why).isTrue();
        assertThat(dragged.path("message").asText()).as(why).contains("accepted the drop (move)").contains("completed");
        step("expect", "{'target':{'window':'window:Line order','name':'lineStatus'},'text':'moved Plums to pick 1'}");
        JsonNode read = call("read", "{'target':{" + window + "}}");
        assertThat(read.path("node").toString()).as(read.path("why").asText()).containsSubsequence("Plums", "Apples", "Pears");
        step("close_window", "{'window':'window:Line order'}");
    }

    /** A UI test that leaves a line of every kind in its run journal, and two checkpoints, one with a mask. */
    @FixtureClass
    @Tag("ui")   // as @UiTest would: the run report writes latest.txt for the launch
    static class JournalConformance {
        @RegisterExtension
        static final UiTestExtension EXT = new UiTestExtension();

        @Test
        void placesAnOrder(Client ui) {
            ui.step("Fill the order");
            ui.byId("customer").fill("Ada");
            ui.byId("save").click();
            UiAssertions.assertThat(ui.label("Total")).hasText("€7.32");   // the value label FixtureApp captions "Total"
            assertThat(ui.label("Total").shownText()).isEqualTo("€7.32");   // a read line
            ui.step("Look at the lines");
            ui.byId("lines").row("Product", "Paper").cell("Qty").checkpoint("paper-qty");
            ui.checkpoint("order-form", ui.byId("status"));
        }
    }

    /** The same screen twice, once with the pointer over Save and a caret in Customer, once without. */
    @FixtureClass
    @Tag("ui")
    static class CheckpointTwice {
        @RegisterExtension
        static final UiTestExtension EXT = new UiTestExtension();

        @Test
        void sameScreenSamePicture(Client ui) throws Exception {
            ui.byId("customer").click();   // focus and a blinking caret in Customer
            ui.byId("save").hover();       // Save rolled over
            ui.checkpoint("first");
            ui.byId("save").unhover();
            Thread.sleep(700);             // past one caret blink
            ui.checkpoint("second");
        }
    }

    /** A UI test whose check fails: its journal ends at that check. */
    @FixtureClass
    @Tag("ui")
    static class JournalFailing {
        @RegisterExtension
        static final UiTestExtension EXT = new UiTestExtension();

        @Test
        void failsAtTheStatus(Client ui) {
            ui.byId("save").click();
            UiAssertions.assertThat(ui.byId("status")).within(Duration.ofSeconds(1)).hasText("never shown");
            ui.byId("new").click();   // never runs
        }
    }

    @Test
    @Order(27)
    void aUiTestLeavesAJournalOfEveryKindAndAMaskedCheckpoint() throws Exception {
        Path folder = launchUiFixture(JournalConformance.class, true);
        assertThat(RunFolderFormat.check(folder)).as("run folder %s", folder).isEmpty();
        List<JsonNode> lines = journal(folder, "FixtureAppDriveIT$JournalConformance#placesAnOrder");
        List<String> kinds = new java.util.ArrayList<String>();
        for (JsonNode l : lines) kinds.add(l.path("kind").asText());
        assertThat(kinds).as("%s", lines).contains("test", "section", "action", "check", "read", "checkpoint", "end");
        assertThat(lines.get(lines.size() - 1).path("outcome").asText()).as("%s", lines).isEqualTo("passed");

        Path checkpoints = folder.resolve("checkpoints").resolve("FixtureAppDriveIT$JournalConformance#placesAnOrder");
        JsonNode qty = JSON.readTree(new String(Files.readAllBytes(checkpoints.resolve("paper-qty.json")), StandardCharsets.UTF_8));
        JsonNode cell = call("read", "{'target':{'name':'lines','row':{'Product':'Paper'},'column':'Qty'},'form':'geometry'}");
        JsonNode table = call("read", "{'target':{'name':'lines'},'form':'geometry'}");
        // just the cell, as the agent measures it: narrower than the table (whose one row is as high as the cell)
        assertThat(qty.path("width").asInt()).as(cell.path("why").asText()).isEqualTo(cell.path("geometry").path("width").asInt());
        assertThat(qty.path("height").asInt()).as(cell.path("why").asText()).isEqualTo(cell.path("geometry").path("height").asInt());
        assertThat(qty.path("width").asInt()).as(table.path("why").asText())
                .isLessThan(table.path("geometry").path("width").asInt());
        JsonNode form = JSON.readTree(new String(Files.readAllBytes(checkpoints.resolve("order-form.json")), StandardCharsets.UTF_8));
        assertThat(form.path("masks")).as("%s", form).hasSize(1);
        assertThat(form.path("masks").path(0).path("target").asText()).as("%s", form).contains("status");
        assertThat(Files.isRegularFile(checkpoints.resolve("order-form.png"))).isTrue();

        // the recorded test the JUnit runner ran (order 13) wrote to the same run folder, in a file of its own
        List<Path> recorded;
        try (Stream<Path> files = Files.list(folder.resolve("steps"))) {
            recorded = files.filter(p -> p.getFileName().toString().startsWith("FixtureAppDriveIT$Runner#recorded["))
                    .collect(java.util.stream.Collectors.toList());
        }
        assertThat(recorded).as("journals in %s", folder.resolve("steps")).hasSize(1);
        List<String> recordedLines = Files.readAllLines(recorded.get(0), StandardCharsets.UTF_8);
        assertThat(JSON.readTree(recordedLines.get(0)).path("kind").asText()).isEqualTo("test");
        assertThat(JSON.readTree(recordedLines.get(recordedLines.size() - 1)).path("kind").asText()).isEqualTo("end");
    }

    @Test
    @Order(28)
    void aFailingUiTestsJournalEndsAtTheFailedCheck() throws Exception {
        Path folder = launchUiFixture(JournalFailing.class, false);
        assertThat(RunFolderFormat.check(folder)).as("run folder %s", folder).isEmpty();
        List<JsonNode> lines = journal(folder, "FixtureAppDriveIT$JournalFailing#failsAtTheStatus");
        JsonNode check = lines.get(lines.size() - 2);
        JsonNode end = lines.get(lines.size() - 1);
        assertThat(check.path("kind").asText()).as("%s", lines).isEqualTo("check");
        assertThat(check.path("outcome").asText()).as("%s", lines).isEqualTo("failed");
        assertThat(check.path("message").asText()).as("%s", lines).contains("never shown");
        assertThat(end.path("kind").asText()).as("%s", lines).isEqualTo("end");
        assertThat(end.path("outcome").asText()).as("%s", lines).isEqualTo("failed");
        assertThat(end.path("evidence").asText()).as("%s", lines).isNotEmpty();
    }

    @Test
    @Order(29)
    void twoCheckpointsOfTheSameScreenAreTheSamePicture() throws Exception {
        Path folder = launchUiFixture(CheckpointTwice.class, true);
        Path dir = folder.resolve("checkpoints").resolve("FixtureAppDriveIT$CheckpointTwice#sameScreenSamePicture");
        java.awt.image.BufferedImage first = javax.imageio.ImageIO.read(dir.resolve("first.png").toFile());
        java.awt.image.BufferedImage second = javax.imageio.ImageIO.read(dir.resolve("second.png").toFile());
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(second.getWidth()).isEqualTo(first.getWidth());
        assertThat(second.getHeight()).isEqualTo(first.getHeight());
        int differ = 0;
        for (int y = 0; y < first.getHeight(); y++) {
            for (int x = 0; x < first.getWidth(); x++) {
                if (first.getRGB(x, y) != second.getRGB(x, y)) differ++;
            }
        }
        // the checkpoint moved the pointer off (no rollover on Save) and hid the caret in Customer for its paint
        assertThat(differ).as("pixels that differ between %s and %s", dir.resolve("first.png"), dir.resolve("second.png"))
                .isZero();
    }

    /**
     * Launches the UI test class {@code fixture} against the app, as a build runs a {@code @UiTest}; requires it to pass
     * (or, with {@code passes} false, to fail its one test). Returns the run folder its STARTED line in latest.txt names.
     */
    private static Path launchUiFixture(Class<?> fixture, boolean passes) throws Exception {
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(fixture))
                .configurationParameter("swingui.fixtures", "run")
                .configurationParameter("swingui.agents", "127.0.0.1:" + port).build(), listener);
        TestExecutionSummary summary = listener.getSummary();
        assertThat(summary.getTestsFoundCount()).isEqualTo(1);
        if (passes) {
            assertThat(summary.getFailures()).as("failures: %s\napp output:\n%s", summary.getFailures(), appOutput).isEmpty();
            assertThat(summary.getTestsSucceededCount()).isEqualTo(1);
        } else {
            assertThat(summary.getTestsFailedCount()).isEqualTo(1);
        }
        Path runs = Paths.get(FixtureApp.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent()
                .resolve("ui-runs");
        String started = Files.readAllLines(runs.resolve("latest.txt"), StandardCharsets.UTF_8).get(0);
        Matcher m = Pattern.compile("^STARTED \\S+ tests=1 run=(\\S+)$").matcher(started);
        assertThat(m.matches()).as(started).isTrue();
        Path folder = runs.resolve(m.group(1));
        assertThat(folder).isDirectory();
        assertThat(new String(Files.readAllBytes(runs.resolve("latest-run.txt")), StandardCharsets.UTF_8).trim())
                .isEqualTo(m.group(1));
        return folder;
    }

    /** The lines of test {@code test}'s journal in run folder {@code folder}. */
    private static List<JsonNode> journal(Path folder, String test) throws Exception {
        Path file = folder.resolve("steps").resolve(test + ".jsonl");
        assertThat(file).isRegularFile();
        List<JsonNode> out = new java.util.ArrayList<JsonNode>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) out.add(JSON.readTree(line));
        return out;
    }

    /** The Number column of the Register table, top to bottom; a read is not one of the agent's steps. */
    private static List<String> registerNumbers() throws Exception {
        JsonNode read = call("read", "{'target':{" + REGISTER + ",'name':'Register'},'maxRows':500}");
        assertThat(read.path("found").asBoolean()).as(read.path("why").asText()).isTrue();
        List<String> out = new java.util.ArrayList<String>();
        for (JsonNode row : read.path("node").path("rows")) out.add(row.path(0).asText());
        return out;
    }

    /** The step of a recorded test's steps (groups searched too) that has {@code id}; null when none has. */
    private static ObjectNode stepWithId(JsonNode steps, String id) {
        for (JsonNode s : steps) {
            if (id.equals(s.path("id").asText())) return (ObjectNode) s;
            ObjectNode inGroup = stepWithId(s.path("steps"), id);
            if (inGroup != null) return inGroup;
        }
        return null;
    }

    /** What {@code reference} stood for in a run: the first step or check of its result that used it. */
    private static String resolved(JsonNode ran, String reference) {
        for (JsonNode s : ran.path("steps")) {
            JsonNode value = s.path("resolved").get(reference);
            if (value != null) return value.asText();
        }
        throw new AssertionError("no step used " + reference + ": " + ran.path("why").asText());
    }

    /**
     * Compiles the exported Java test {@code source} (under the test sources root {@code root}) against this test's own
     * class path and runs class {@code name} through the JUnit launcher on the live app, as a build would.
     */
    private static void runExportedJava(Path root, Path source, String name) throws Exception {
        javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertThat(javac).as("ITs run on a JDK").isNotNull();
        Path classes = Files.createDirectories(root.resolve("classes"));
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int rc = javac.run(null, errors, errors, "-encoding", "UTF-8", "-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString(), source.toString());
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        assertThat(rc).as("the exported test compiles:\n%s\n%s", errors.toString("UTF-8"), text).isZero();
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(new java.net.URL[] {classes.toUri().toURL()},
                FixtureAppDriveIT.class.getClassLoader())) {
            SummaryGeneratingListener listener = new SummaryGeneratingListener();
            LauncherFactory.create().execute(LauncherDiscoveryRequestBuilder.request()
                    .selectors(selectClass(loader.loadClass(name)))
                    .configurationParameter("swingui.agents", "127.0.0.1:" + port).build(), listener);
            TestExecutionSummary summary = listener.getSummary();
            StringBuilder failures = new StringBuilder();
            for (TestExecutionSummary.Failure f : summary.getFailures()) {
                java.io.StringWriter trace = new java.io.StringWriter();
                f.getException().printStackTrace(new java.io.PrintWriter(trace));
                failures.append(f.getTestIdentifier().getDisplayName()).append(": ").append(trace).append('\n');
            }
            assertThat(summary.getFailures()).as("failures:\n%s\n%s\napp output:\n%s", failures, text, appOutput).isEmpty();
            assertThat(summary.getTestsSucceededCount()).as(text).isEqualTo(1);
        }
    }

    private static String slashes(Path p) {
        return p.toString().replace('\\', '/');
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

    /** Reads {@code target} until it is there; a read is not one of the agent's steps. */
    private static void awaitFound(String target) throws Exception {
        long end = System.currentTimeMillis() + 10_000;
        while (true) {
            JsonNode read = call("read", "{'target':" + target + "}");
            if (read.path("found").asBoolean()) return;
            assertThat(System.currentTimeMillis()).as("%s never there: %s", target, read.path("why").asText()).isLessThan(end);
            Thread.sleep(50);
        }
    }

    /** Reads {@code name} until it shows enabled; a read is not one of the agent's steps. */
    private static void awaitEnabled(String name) throws Exception {
        long end = System.currentTimeMillis() + 10_000;
        while (true) {
            JsonNode read = call("read", "{'target':{'name':'" + name + "'}}");
            boolean found = read.path("found").asBoolean();
            boolean disabled = read.path("node").path("state").toString().contains("\"disabled\"");
            if (found && !disabled) return;
            assertThat(System.currentTimeMillis()).as("%s never enabled: %s", name, read.path("why").asText()).isLessThan(end);
            Thread.sleep(50);
        }
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
