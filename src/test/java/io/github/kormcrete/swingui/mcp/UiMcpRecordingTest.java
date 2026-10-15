package io.github.kormcrete.swingui.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.inspect.Inspector;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.flow.RecordedTestFiles;
import io.github.kormcrete.swingui.flow.Flow;
import io.github.kormcrete.swingui.flow.verb.ClickVerb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Every recorded step keeps an id and who made it. */
class UiMcpRecordingTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UiMcpReadToolsTest.DriverHost backend = new UiMcpReadToolsTest.DriverHost();
    private final UiMcpSession session = new UiMcpSession(backend, new UiMcpToolsTest.FakeInventory(), mapper);
    private Inspector.Sink sink;
    @TempDir Path dir;

    @BeforeEach
    void setUp() {
        session.folderMemory(FolderMemory.inMemory());
        session.startRecording(true);
        sink = session.humanSink();
    }

    /** The recording's lines, as the palette's steps list reads them. */
    private List<String> lines() {
        List<String> out = new java.util.ArrayList<String>();
        for (Inspector.StepLine s : sink.steps()) out.add(s.line);
        return out;
    }

    /** The id of the step in row {@code row} (0-based). */
    private int id(int row) {
        return sink.steps().get(row).id;
    }

    private static Map<String, Object> step(String action, Locator target) {
        Map<String, Object> s = new LinkedHashMap<String, Object>();
        s.put("action", action);
        s.put("target", target);
        return s;
    }

    @Test
    void thePersonsStepsAndChecksAreThePersonsAndTheAgentsAreTheAgents() throws Exception {
        assertThat(sink.done(step("click", Locator.ofId("save"))).ok).isTrue();
        session.verb("click", mapper.readTree("{\"target\":{\"id\":\"next\"}}"));
        Map<String, Object> check = step("expect", Locator.ofId("status"));
        check.put("text", "saved");
        assertThat(sink.check(check).ok).isTrue();

        List<RecordedStep> steps = session.recordedSteps();
        assertThat(steps).extracting(s -> s.id).containsExactly(1, 2, 3);
        assertThat(steps).extracting(s -> s.author).containsExactly(
                RecordedStep.Author.PERSON, RecordedStep.Author.AGENT, RecordedStep.Author.PERSON);
    }

    @Test
    void aLoadedTestsStepsAreTheLoadedOnes() throws Exception {
        Flow flow = new Flow();
        flow.version = RecordedTestFiles.VERSION;
        flow.name = "two clicks";
        flow.start = RecordedTestFiles.START_RESET;
        ClickVerb a = new ClickVerb(); a.target = Locator.ofId("a");
        ClickVerb b = new ClickVerb(); b.target = Locator.ofId("b");
        flow.steps.add(a);
        flow.steps.add(b);
        Path file = dir.resolve("two.json");
        Files.write(file, RecordedTestFiles.write(flow).getBytes(StandardCharsets.UTF_8));
        session.loadRecordedTest(file);

        assertThat(session.recordedSteps()).hasSize(2)
                .allMatch(s -> s.author == RecordedStep.Author.LOADED);
    }

    // ---- the recording tool: start, stop, list ----------------------------------------------

    private JsonNode call(String json) throws Exception {
        return mapper.readTree(session.recording(mapper.readTree(json)));
    }

    private void click(String id) throws Exception {
        session.verb("click", mapper.readTree("{\"target\":{\"id\":\"" + id + "\"}}"));
    }

    @Test
    void startListAndStopWithThePaletteOff() throws Exception {
        JsonNode started = call("{\"op\":\"start\"}");
        assertThat(started.path("state").asText()).isEqualTo("recording");
        assertThat(started.path("startedBy").asText()).isEqualTo("agent");
        assertThat(started.path("shared").asBoolean()).isFalse();
        assertThat(started.path("startedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
        click("save");
        click("next");

        JsonNode list = call("{\"op\":\"list\"}");
        assertThat(list.path("steps")).hasSize(2);
        for (int i = 0; i < 2; i++) {
            JsonNode s = list.path("steps").get(i);
            assertThat(s.path("n").asInt()).isEqualTo(i + 1);
            assertThat(s.path("author").asText()).isEqualTo("agent");
            assertThat(s.path("line").asText()).startsWith("click(");
            assertThat(s.path("java").asText()).startsWith("ui.");
            assertThat(s.path("step").path("action").asText()).isEqualTo("click");
        }
        assertThat(list.path("rev").asInt()).isPositive();

        assertThat(call("{\"op\":\"stop\"}").path("state").asText()).isEqualTo("stopped");
        assertThat(call("{\"op\":\"list\"}").path("state").asText()).isEqualTo("stopped");
    }

    @Test
    void listBeforeAnythingIsRecordedIsIdle() throws Exception {
        UiMcpSession fresh = new UiMcpSession(backend, new UiMcpToolsTest.FakeInventory(), mapper);
        JsonNode list = mapper.readTree(fresh.recording(mapper.readTree("{\"op\":\"list\"}")));
        assertThat(list.path("state").asText()).isEqualTo("idle");
        assertThat(list.path("steps")).isEmpty();
        assertThat(list.has("startedBy")).isFalse();
        assertThat(list.path("fromReset").asBoolean()).isFalse();
    }

    @Test
    void startWithResetResetsTheAppFirst() throws Exception {
        session.appReset(new UiMcpSession.AppReset() {
            @Override public boolean defined() { return true; }
            @Override public void run() { backend.verbs.calls.add("reset"); }
        });
        JsonNode list = call("{\"op\":\"start\",\"reset\":true}");
        assertThat(backend.verbs.calls).containsExactly("reset");
        assertThat(list.path("definedStart").asBoolean()).isTrue();
        assertThat(list.path("fromReset").asBoolean()).isTrue();
        assertThat(list.has("startWarning")).isFalse();
    }

    @Test
    void afterStopTheAgentsStepsDoNotJoin() throws Exception {
        call("{\"op\":\"start\"}");
        click("save");
        call("{\"op\":\"stop\"}");
        JsonNode ran = mapper.readTree(session.verb("click", mapper.readTree("{\"target\":{\"id\":\"next\"}}")));
        assertThat(ran.path("passed").asBoolean()).isTrue();
        assertThat(ran.has("recorded")).isFalse();
        assertThat(ran.path("notRecorded").asText()).contains("the recording is stopped").contains("recording start");
        assertThat(call("{\"op\":\"list\"}").path("steps")).hasSize(1);
    }

    @Test
    void stopWithoutARunningRecordingSaysSo() throws Exception {
        call("{\"op\":\"start\"}");
        call("{\"op\":\"stop\"}");
        DriverException e = catchThrowableOfType(() -> call("{\"op\":\"stop\"}"), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("no recording is running (it is stopped)");
    }

    @Test
    void anUnknownOrMissingOpIsBadInput() {
        for (String args : new String[] {"{}", "{\"op\":\"dance\"}"}) {
            DriverException e = catchThrowableOfType(() -> call(args), DriverException.class);
            assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
            assertThat(e.getMessage()).contains("start, stop, list, insert, delete, move, edit, undo, replay, redo, export, load");
        }
    }

    @Test
    void aStartWithoutResetIsAFreshStartThatExportsWithAStartWarning() throws Exception {
        fakeReset();
        JsonNode started = call("{\"op\":\"start\"}");
        // no reset ran, and the list says so; the start is still a fresh one an export writes
        assertThat(started.path("fromReset").asBoolean()).isFalse();
        assertThat(started.path("definedStart").asBoolean()).isFalse();
        click("a");
        // the app has a plugin: the warning must not say it has none
        String warning = call("{\"op\":\"list\"}").path("startWarning").asText();
        assertThat(warning).contains("did not begin at the app plugin's reset").doesNotContain("no app plugin");
        call("{\"op\":\"stop\"}");
        JsonNode exported = mapper.readTree(session.exportRecordedTest("first", quoted(dir), null, true));
        assertThat(exported.path("startWarning").asText()).isEqualTo(warning);
        // after a clearing export the steps that follow have no start of their own
        click("b");
        call("{\"op\":\"insert\",\"at\":1,\"step\":{\"action\":\"click\",\"target\":{\"id\":\"b\"}}}");
        exportRefusedForNoReset();
        // a passing replay from the reset shows that it starts there
        assertThat(call("{\"op\":\"replay\"}").path("passed").asBoolean()).isTrue();
        JsonNode list = call("{\"op\":\"list\"}");
        assertThat(list.path("fromReset").asBoolean()).isTrue();
        assertThat(list.has("startWarning")).isFalse();
        assertThat(session.exportRecordedTest("second", quoted(dir), null, false)).contains("savedTo");
    }

    @Test
    void thePersonsRecordIsAFreshStartWithoutAReset() throws Exception {
        sink.begin();
        assertThat(sink.done(step("click", Locator.ofId("save"))).ok).isTrue();
        sink.end();
        JsonNode list = call("{\"op\":\"list\"}");
        assertThat(list.path("fromReset").asBoolean()).isFalse();
        assertThat(list.path("startWarning").asText()).contains("(no app plugin: a reset only closes dialogs)");
        assertThat(session.exportRecordedTest("shown", quoted(dir), null, false)).contains("savedTo");
    }

    @Test
    void withoutAPluginTheStartWarningSaysAResetOnlyClosesDialogs() throws Exception {
        call("{\"op\":\"start\"}");
        click("a");
        assertThat(call("{\"op\":\"list\"}").path("startWarning").asText())
                .contains("(no app plugin: a reset only closes dialogs)");
    }

    @Test
    void listWaitReturnsAtOnceOnASessionResetsRecording() throws Exception {
        // setUp's session reset started it: nobody but the agent could stop it
        long t0 = System.currentTimeMillis();
        JsonNode list = call("{\"op\":\"list\",\"wait\":5000}");
        assertThat(System.currentTimeMillis() - t0).isLessThan(2000);
        assertThat(list.path("state").asText()).isEqualTo("recording");
    }

    @Test
    void aClearingExportIsRefusedWhileARecordingRuns() throws Exception {
        Path tests = dir.resolve("tests");
        System.setProperty(UiMcpSession.TESTS_DIR_PROP, tests.toString());
        try {
            session.palette(true);
            List<String> heard = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
            session.recordingView(hearing(heard));
            fakeReset();
            call("{\"op\":\"start\",\"reset\":true}");
            click("a");
            heard.clear();
            List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> clearing = java.util.Arrays.asList(
                    () -> session.exportTest("ClearUiIT", null, "demo", true),
                    () -> session.exportRecordedTest("clear", quoted(dir), null, true),
                    () -> call("{\"op\":\"export\",\"as\":\"java\",\"class\":\"ClearUiIT\",\"package\":\"demo\",\"clear\":true}"),
                    () -> call("{\"op\":\"export\",\"as\":\"recorded\",\"name\":\"clear\",\"dir\":\"" + quoted(dir) + "\",\"clear\":true}"));
            for (org.assertj.core.api.ThrowableAssert.ThrowingCallable export : clearing) {
                DriverException e = catchThrowableOfType(export, DriverException.class);
                assertThat(e.reason()).isEqualTo(Reason.BUSY);
                assertThat(e.getMessage()).contains("a recording is running").contains("export without clear, or stop it first");
            }
            assertThat(Files.exists(tests.resolve("demo/ClearUiIT.java"))).isFalse();
            assertThat(Files.exists(dir.resolve("clear.json"))).isFalse();
            JsonNode list = call("{\"op\":\"list\"}");
            assertThat(list.path("state").asText()).isEqualTo("recording");
            assertThat(list.path("steps")).hasSize(1);
            assertThat(heard).isEmpty();
            // without clear it writes; once stopped, clear works too
            assertThat(session.exportRecordedTest("kept", quoted(dir), null, false)).contains("savedTo");
            call("{\"op\":\"stop\"}");
            assertThat(session.exportTest("ClearUiIT", null, "demo", true)).contains("savedTo");
            assertThat(call("{\"op\":\"list\"}").path("steps")).isEmpty();
        } finally {
            System.clearProperty(UiMcpSession.TESTS_DIR_PROP);
        }
    }

    @Test
    void listWaitsForTheStop() throws Exception {
        call("{\"op\":\"start\"}");
        final JsonNode[] seen = new JsonNode[1];
        final long[] took = new long[1];
        Thread waiter = new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                seen[0] = call("{\"op\":\"list\",\"wait\":5000}");
                took[0] = System.currentTimeMillis() - t0;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        waiter.start();
        Thread.sleep(200);
        call("{\"op\":\"stop\"}");
        waiter.join(10_000);
        assertThat(seen[0].path("state").asText()).isEqualTo("stopped");
        assertThat(took[0]).isLessThan(5000);
    }

    @Test
    void listWaitDoesNotHoldTheSession() throws Exception {
        call("{\"op\":\"start\"}");
        Thread waiter = new Thread(() -> {
            try {
                call("{\"op\":\"list\",\"wait\":5000}");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        waiter.start();
        Thread.sleep(200);
        long t0 = System.currentTimeMillis();
        click("save");
        assertThat(System.currentTimeMillis() - t0).isLessThan(2000);
        assertThat(waiter.isAlive()).isTrue();
        call("{\"op\":\"stop\"}");
        waiter.join(10_000);
        assertThat(waiter.isAlive()).isFalse();
    }

    @Test
    void aRunningRecordingIsNotWipedByAnotherStart() throws Exception {
        call("{\"op\":\"start\"}");
        click("save");
        DriverException e = catchThrowableOfType(() -> call("{\"op\":\"start\"}"), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BUSY);
        assertThat(e.getMessage()).contains("stop it first");
        assertThat(call("{\"op\":\"list\"}").path("steps")).hasSize(1);
        assertThat(session.recordingReason()).contains("started by the agent").contains("recording {\"op\":\"stop\"}");
    }

    @Test
    void anImplicitRecordingBlocksNothing() throws Exception {
        // setUp's session reset starts one
        assertThat(session.recordingReason()).isNull();
        assertThat(call("{\"op\":\"start\"}").path("state").asText()).isEqualTo("recording");
        assertThat(session.recordingReason()).isNotNull();
        call("{\"op\":\"stop\"}");
        assertThat(session.recordingReason()).isNull();
    }

    @Test
    void theListSaysWhatThePersonCouldNotRecord() throws Exception {
        Locator at = Locator.ofId("canvas");
        at.point = new LinkedHashMap<String, Double>();
        at.point.put("x", 0.5);
        at.point.put("y", 0.5);
        assertThat(sink.done(step("click", at)).ok).isFalse();
        JsonNode list = call("{\"op\":\"list\"}");
        assertThat(list.path("steps")).isEmpty();
        assertThat(list.path("notRecorded")).hasSize(1);
        assertThat(list.path("notRecorded").get(0).asText()).startsWith("click(");
    }

    @Test
    void aLoadedRecordedTestListsWhereItCameFrom() throws Exception {
        Flow flow = new Flow();
        flow.version = RecordedTestFiles.VERSION;
        flow.name = "one click";
        flow.start = RecordedTestFiles.START_RESET;
        ClickVerb a = new ClickVerb(); a.target = Locator.ofId("a");
        flow.steps.add(a);
        Path file = dir.resolve("one.json");
        Files.write(file, RecordedTestFiles.write(flow).getBytes(StandardCharsets.UTF_8));
        session.loadRecordedTest(file);

        JsonNode list = call("{\"op\":\"list\"}");
        assertThat(list.path("state").asText()).isEqualTo("stopped");
        assertThat(list.path("startedBy").asText()).isEqualTo("loaded");
        assertThat(list.path("loadedFrom").asText()).isEqualTo(file.toAbsolutePath().normalize().toString());
        assertThat(list.path("steps").get(0).path("author").asText()).isEqualTo("loaded");
    }

    @Test
    void thePersonsDoneStopsTheRecording() throws Exception {
        sink.begin();
        assertThat(sink.done(step("click", Locator.ofId("save"))).ok).isTrue();
        assertThat(call("{\"op\":\"list\"}").path("state").asText()).isEqualTo("recording");
        assertThat(session.recordingReason()).contains("started by the person");
        sink.end();
        JsonNode list = call("{\"op\":\"list\"}");
        assertThat(list.path("state").asText()).isEqualTo("stopped");
        assertThat(list.path("startedBy").asText()).isEqualTo("person");
        assertThat(list.path("steps").get(0).path("author").asText()).isEqualTo("person");
        assertThat(session.recordingReason()).isNull();
        Inspector.Result late = sink.done(step("click", Locator.ofId("next")));
        assertThat(late.ok).isFalse();
        assertThat(late.line).contains("stopped");
    }

    @Test
    void aPalettesRedoRecordsOnAfterDone() {
        sink.begin();
        sink.done(step("click", Locator.ofId("a")));
        sink.done(step("click", Locator.ofId("b")));
        sink.end();
        assertThat(sink.cutFrom(id(1)).ok).isTrue();
        sink.resume();
        assertThat(sink.done(step("click", Locator.ofId("c"))).ok).isTrue();
        assertThat(lines()).containsExactly("click(#a)", "click(#c)");
    }

    @Test
    void aRedoThatNeverRecordsOnLeavesTheRecordingStopped() throws Exception {
        sink.begin();
        sink.done(step("click", Locator.ofId("a")));
        sink.end();
        assertThat(sink.cutFrom(id(0)).ok).isTrue();
        // the replay failed: the palette never recorded on
        assertThat(call("{\"op\":\"list\"}").path("state").asText()).isEqualTo("stopped");
        assertThat(session.recordingReason()).isNull();
    }

    @Test
    void withThePaletteOnAnExplicitStartIsShared() throws Exception {
        session.palette(true);
        assertThat(call("{\"op\":\"start\"}").path("shared").asBoolean()).isTrue();
    }

    @Test
    void inspectRecordingAndStepsSayWhereTheyMoved() throws Exception {
        for (boolean palette : new boolean[] {false, true}) {
            session.palette(palette);
            for (String op : new String[] {"recording", "steps"}) {
                DriverException e = catchThrowableOfType(
                        () -> session.inspect(mapper.readTree("{\"op\":\"" + op + "\"}")), DriverException.class);
                assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
                assertThat(e.getMessage()).contains("moved to the recording tool").contains("\"op\":\"list\"").contains("wait");
            }
        }
    }

    // ---- editing --------------------------------------------------------------------------------

    private void four() throws Exception {
        four(false);
    }

    /** A recording of four clicks the agent started, from a reset when {@code reset}. */
    private void four(boolean reset) throws Exception {
        call("{\"op\":\"start\",\"reset\":" + reset + "}");
        click("a");
        click("b");
        click("c");
        click("d");
    }

    private static String ids(JsonNode list) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode s : list.path("steps")) sb.append(s.path("step").path("target").path("id").asText());
        return sb.toString();
    }

    private DriverException refused(String json) {
        return catchThrowableOfType(() -> call(json), DriverException.class);
    }

    @Test
    void insertAddsACheckWithoutRunningItAndMarksItEdited() throws Exception {
        four();
        int calls = backend.verbs.calls.size();
        JsonNode r = call("{\"op\":\"insert\",\"at\":2,\"step\":{\"action\":\"expect\",\"target\":{\"id\":\"status\"},\"text\":\"ok\"}}");
        assertThat(backend.verbs.calls).hasSize(calls);
        assertThat(r.path("did").asText()).isEqualTo("inserted at step 2");
        assertThat(r.path("steps")).hasSize(5);
        assertThat(r.path("steps").get(1).path("step").path("action").asText()).isEqualTo("expect");
        assertThat(r.path("steps").get(1).path("edited").asBoolean()).isTrue();
        assertThat(r.path("steps").get(0).has("edited")).isFalse();
        assertThat(r.path("steps").get(1).path("author").asText()).isEqualTo("agent");
        // at size+1 appends
        assertThat(call("{\"op\":\"insert\",\"at\":6,\"step\":{\"action\":\"click\",\"target\":{\"id\":\"z\"}}}")
                .path("steps")).hasSize(6);
    }

    @Test
    void aStepWithoutAJavaFormIsRefusedAndNothingChanges() throws Exception {
        four();
        int rev = call("{\"op\":\"list\"}").path("rev").asInt();
        for (String step : new String[] {
                "{\"action\":\"click\",\"target\":{\"id\":\"c\",\"point\":{\"x\":0.5,\"y\":0.5}}}",
                "{\"action\":\"click\",\"target\":{\"ref\":\"e3\"}}"}) {
            DriverException e = refused("{\"op\":\"insert\",\"at\":1,\"step\":" + step + "}");
            assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
            assertThat(e.getMessage()).contains("step 1:");
        }
        JsonNode list = call("{\"op\":\"list\"}");
        assertThat(list.path("rev").asInt()).isEqualTo(rev);
        assertThat(list.path("steps")).hasSize(4);
    }

    @Test
    void anUnknownStepTypeIsRefused() throws Exception {
        four();
        DriverException e = refused("{\"op\":\"insert\",\"at\":1,\"step\":{\"action\":\"dance\"}}");
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("there is no step type 'dance'");
    }

    @Test
    void numbersArePositionsAndCloseUpAfterADelete() throws Exception {
        four();
        JsonNode r = call("{\"op\":\"delete\",\"n\":2}");
        assertThat(r.path("did").asText()).isEqualTo("deleted step 2");
        assertThat(ids(r)).isEqualTo("acd");
        assertThat(r.path("steps").get(1).path("n").asInt()).isEqualTo(2);
        assertThat(r.path("steps").get(1).path("step").path("target").path("id").asText()).isEqualTo("c");
    }

    @Test
    void aRangeDeleteSaysWhatItDid() throws Exception {
        four();
        JsonNode r = call("{\"op\":\"delete\",\"from\":2,\"to\":3}");
        assertThat(r.path("did").asText()).isEqualTo("deleted steps 2-3");
        assertThat(ids(r)).isEqualTo("ad");
        assertThat(refused("{\"op\":\"delete\",\"n\":1,\"from\":1,\"to\":2}").reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(refused("{\"op\":\"delete\",\"n\":9}").getMessage()).contains("no step 9");
    }

    @Test
    void deletingTheLastStepsLeavesAnEmptyRecordingAndUndoBringsThemBack() throws Exception {
        four();
        assertThat(call("{\"op\":\"delete\",\"from\":1,\"to\":4}").path("steps")).isEmpty();
        assertThat(ids(call("{\"op\":\"undo\"}"))).isEqualTo("abcd");
    }

    @Test
    void moveCarriesAStepToItsNewPosition() throws Exception {
        four();
        JsonNode r = call("{\"op\":\"move\",\"n\":4,\"to\":1}");
        assertThat(r.path("did").asText()).isEqualTo("moved step 4 to 1");
        assertThat(ids(r)).isEqualTo("dabc");
    }

    @Test
    void aMoveThatBreaksTheExportIsRefused() throws Exception {
        call("{\"op\":\"start\"}");
        call("{\"op\":\"insert\",\"at\":1,\"step\":{\"action\":\"loginAs\",\"as\":\"ada\"}}");
        call("{\"op\":\"insert\",\"at\":2,\"step\":{\"action\":\"click\",\"target\":{\"id\":\"a\"}}}");
        int rev = call("{\"op\":\"list\"}").path("rev").asInt();
        DriverException e = refused("{\"op\":\"move\",\"n\":1,\"to\":2}");
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("step 2:").contains("loginAs");
        assertThat(call("{\"op\":\"list\"}").path("rev").asInt()).isEqualTo(rev);
    }

    @Test
    void editReplacesTheTargetOrTheTextAndClearsFragile() throws Exception {
        call("{\"op\":\"start\"}");
        call("{\"op\":\"insert\",\"at\":1,\"step\":{\"action\":\"type\",\"target\":{\"id\":\"name\",\"nth\":1},\"text\":\"Ada\",\"fragile\":true}}");
        JsonNode r = call("{\"op\":\"edit\",\"n\":1,\"text\":\"Grace\"}");
        assertThat(r.path("did").asText()).isEqualTo("edited step 1");
        JsonNode step = r.path("steps").get(0).path("step");
        assertThat(step.path("text").asText()).isEqualTo("Grace");
        assertThat(step.path("fragile").asBoolean()).isTrue();
        assertThat(r.path("steps").get(0).path("edited").asBoolean()).isTrue();

        step = call("{\"op\":\"edit\",\"n\":1,\"target\":{\"id\":\"customer\"}}").path("steps").get(0).path("step");
        assertThat(step.path("target").path("id").asText()).isEqualTo("customer");
        assertThat(step.has("fragile")).isFalse();
        assertThat(step.path("text").asText()).isEqualTo("Grace");

        step = call("{\"op\":\"edit\",\"n\":1,\"step\":{\"action\":\"click\",\"target\":{\"id\":\"q\"}}}")
                .path("steps").get(0).path("step");
        assertThat(step.path("action").asText()).isEqualTo("click");
    }

    @Test
    void editOfAFieldTheStepDoesNotHaveIsRefused() throws Exception {
        call("{\"op\":\"start\"}");
        call("{\"op\":\"insert\",\"at\":1,\"step\":{\"action\":\"menu\",\"path\":[\"File\",\"New\"]}}");
        DriverException e = refused("{\"op\":\"edit\",\"n\":1,\"text\":\"x\"}");
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("step 1 (menu) has no text");
        assertThat(refused("{\"op\":\"edit\",\"n\":1,\"target\":{\"id\":\"x\"}}").getMessage())
                .contains("step 1 (menu) has no target");
        assertThat(refused("{\"op\":\"edit\",\"n\":1}").reason()).isEqualTo(Reason.BAD_INPUT);
    }

    @Test
    void editTextOfACheckOrDropThatHasNoTextIsRefused() throws Exception {
        call("{\"op\":\"start\"}");
        call("{\"op\":\"insert\",\"at\":1,\"step\":{\"action\":\"expect\",\"target\":{\"id\":\"total\"},\"value\":\"42.00\"}}");
        call("{\"op\":\"insert\",\"at\":2,\"step\":{\"action\":\"drop\",\"target\":{\"id\":\"zone\"},\"files\":[\"/tmp/a.txt\"]}}");
        int rev = call("{\"op\":\"list\"}").path("rev").asInt();
        assertThat(refused("{\"op\":\"edit\",\"n\":1,\"text\":\"x\"}").getMessage()).contains("step 1 (expect) has no text");
        assertThat(refused("{\"op\":\"edit\",\"n\":2,\"text\":\"x\"}").getMessage()).contains("step 2 (drop) has no text");
        assertThat(call("{\"op\":\"list\"}").path("rev").asInt()).isEqualTo(rev);
    }

    @Test
    void sayAndGroupWaitForTheSentencesVersion() throws Exception {
        four();
        assertThat(refused("{\"op\":\"edit\",\"n\":1,\"say\":\"Open it\"}").getMessage())
                .contains("sentences ('say') come in a later version of swing-ui-agent");
        assertThat(refused("{\"op\":\"group\",\"from\":1,\"to\":2}").getMessage())
                .contains("group (steps under one sentence) comes in a later version of swing-ui-agent");
    }

    @Test
    void aStaleRevisionIsRefusedAndSaysWhatChanged() throws Exception {
        four();
        int rev = call("{\"op\":\"list\"}").path("rev").asInt();
        assertThat(sink.done(step("click", Locator.ofId("e"))).ok).isTrue();
        DriverException e = refused("{\"op\":\"delete\",\"n\":1,\"rev\":" + rev + "}");
        assertThat(e.reason()).isEqualTo(Reason.BUSY);
        assertThat(e.getMessage()).contains("changed since revision " + rev).contains("the person").contains("list it again");
        int now = call("{\"op\":\"list\"}").path("rev").asInt();
        assertThat(call("{\"op\":\"delete\",\"n\":1,\"rev\":" + now + "}").path("did").asText()).isEqualTo("deleted step 1");
    }

    @Test
    void undoTakesBackTheLastEditByEitherAuthor() throws Exception {
        four();
        call("{\"op\":\"delete\",\"n\":3}");
        assertThat(call("{\"op\":\"undo\"}").path("did").asText()).isEqualTo("undid the delete of step 3 by the agent");
        assertThat(sink.delete(id(0)).ok).isTrue();
        JsonNode r = call("{\"op\":\"undo\"}");
        assertThat(r.path("did").asText()).isEqualTo("undid the delete of step 1 by the person");
        assertThat(ids(r)).isEqualTo("abcd");
        assertThat(refused("{\"op\":\"undo\"}").getMessage()).contains("nothing to undo in this recording");
    }

    @Test
    void undoingADeleteKeepsStepsRecordedSince() throws Exception {
        four();
        call("{\"op\":\"delete\",\"n\":2}");
        click("e");
        assertThat(ids(call("{\"op\":\"undo\"}"))).isEqualTo("abcde");
    }

    @Test
    void undoNeverReachesBeforeTheRecordingStarted() throws Exception {
        four();
        call("{\"op\":\"delete\",\"n\":1}");
        session.startRecording(true);
        assertThat(refused("{\"op\":\"undo\"}").getMessage()).contains("nothing to undo");

        call("{\"op\":\"start\"}");
        click("a");
        call("{\"op\":\"delete\",\"n\":1}");
        Flow flow = new Flow();
        flow.version = RecordedTestFiles.VERSION;
        flow.name = "one";
        flow.start = RecordedTestFiles.START_RESET;
        ClickVerb a = new ClickVerb(); a.target = Locator.ofId("a");
        flow.steps.add(a);
        Path file = dir.resolve("one.json");
        Files.write(file, RecordedTestFiles.write(flow).getBytes(StandardCharsets.UTF_8));
        session.loadRecordedTest(file);
        assertThat(refused("{\"op\":\"undo\"}").getMessage()).contains("nothing to undo");

        call("{\"op\":\"delete\",\"n\":1}");
        call("{\"op\":\"insert\",\"at\":1,\"step\":{\"action\":\"menu\",\"path\":[\"File\"]}}");
        session.exportRecordedTest("out", dir.toString(), null, true);
        assertThat(refused("{\"op\":\"undo\"}").getMessage()).contains("nothing to undo");
    }

    @Test
    void editsWorkOnAStoppedRecording() throws Exception {
        four();
        call("{\"op\":\"stop\"}");
        JsonNode r = call("{\"op\":\"delete\",\"n\":1}");
        assertThat(r.path("state").asText()).isEqualTo("stopped");
        assertThat(r.path("steps")).hasSize(3);
    }

    @Test
    void thePalettesDeleteAndRedoCutAreUndoable() throws Exception {
        sink.begin();
        sink.done(step("click", Locator.ofId("a")));
        sink.done(step("click", Locator.ofId("b")));
        sink.done(step("click", Locator.ofId("c")));
        sink.end();
        assertThat(sink.cutFrom(id(1)).ok).isTrue();
        assertThat(call("{\"op\":\"undo\"}").path("did").asText()).isEqualTo("undid the cut from step 2 (redo) by the person");
        assertThat(sink.steps()).hasSize(3);
    }

    @Test
    void thePaletteHearsEveryChangeWithTheAgentsNotice() throws Exception {
        List<String> heard = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        session.recordingView(notice -> heard.add(String.valueOf(notice)));
        four();
        assertThat(heard).isNotEmpty().containsOnly("null");

        heard.clear();
        call("{\"op\":\"delete\",\"n\":2}");
        assertThat(heard).containsExactly("the agent deleted step 2");

        // the person's own steps and edits come with no notice: the palette made them
        heard.clear();
        sink.done(step("click", Locator.ofId("e")));
        assertThat(sink.delete(id(0)).ok).isTrue();
        assertThat(heard).containsExactly("null", "null");

        heard.clear();
        call("{\"op\":\"undo\"}");
        assertThat(heard).containsExactly("the agent undid the delete of step 1 by the person");
        // the history says who undid it
        DriverException stale = refused("{\"op\":\"delete\",\"n\":1,\"rev\":0}");
        assertThat(stale.getMessage()).contains("last change: the agent undid the delete of step 1 by the person");

        heard.clear();
        call("{\"op\":\"insert\",\"at\":1,\"step\":{\"action\":\"click\",\"target\":{\"id\":\"x\"}}}");
        call("{\"op\":\"move\",\"n\":1,\"to\":2}");
        call("{\"op\":\"edit\",\"n\":2,\"target\":{\"id\":\"y\"}}");
        assertThat(heard).containsExactly("the agent inserted at step 1", "the agent moved step 1 to 2",
                "the agent edited step 2");
    }

    @Test
    void anAgentEditNeverWaitsForTheEdt() throws Exception {
        four();
        session.recordingView(UiMcpSession.PALETTE);
        java.util.concurrent.CountDownLatch blocked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        javax.swing.SwingUtilities.invokeLater(() -> {
            blocked.countDown();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertThat(blocked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    call("{\"op\":\"delete\",\"n\":1}");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).get(1, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
    }

    // ---- the palette follows the agent --------------------------------------------------------

    /** A view that writes down what the palette would be told besides changes. */
    private static UiMcpSession.RecordingView hearing(List<String> heard) {
        return new UiMcpSession.RecordingView() {
            @Override public void changed(String notice) { }
            @Override public void agentStarted() { heard.add("started"); }
            @Override public void agentStopped() { heard.add("stopped"); }
            @Override public void agentLoaded(String name, int steps) { heard.add("loaded " + name + " - " + steps); }
            @Override public void replaced() { heard.add("replaced"); }
            @Override public void agentReplaying(String reason) { heard.add("replaying: " + reason); }
            @Override public void replayed(int id, boolean ok) { heard.add("replayed " + id + (ok ? " ok" : " failed")); }
        };
    }

    @Test
    void theViewHearsStartStopLoadResetAndReplay() throws Exception {
        session.palette(true);
        fakeReset();
        List<String> heard = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        session.recordingView(hearing(heard));

        call("{\"op\":\"start\"}");
        click("a");
        click("b");
        assertThat(heard).containsExactly("started");
        // the person's ● Record while the agent records is refused; the agent's steps stay
        Inspector.Result record = sink.begin();
        assertThat(record.ok).isFalse();
        assertThat(record.line).isEqualTo(Inspector.AGENTS_RECORDING_RUNS)
                .isEqualTo("the agent's recording is running: Done ends it");
        assertThat(lines()).containsExactly("click(#a)", "click(#b)");
        call("{\"op\":\"stop\"}");
        assertThat(heard).containsExactly("started", "stopped");

        int a = id(0);
        int b = id(1);
        heard.clear();
        call("{\"op\":\"replay\"}");
        assertThat(heard).containsExactly(
                "replaying: resetting the app, then replaying steps 1-2",
                "replaying: replaying 1/2  click(#a)", "replayed " + a + " ok",
                "replaying: replaying 2/2  click(#b)", "replayed " + b + " ok",
                "replaying: null");

        // a redo that records on starts the agent's recording again
        heard.clear();
        call("{\"op\":\"redo\",\"from\":2}");
        assertThat(heard).containsExactly(
                "replaying: resetting the app, then replaying steps 1-1",
                "replaying: replaying 1/1  click(#a)", "replayed " + a + " ok",
                "replaying: null", "started");
        heard.clear();
        call("{\"op\":\"redo\",\"from\":1}");
        assertThat(heard).containsExactly("replaying: resetting the app", "replaying: null", "started");
        call("{\"op\":\"stop\"}");

        heard.clear();
        Path file = savedTest("three");
        call("{\"op\":\"load\",\"path\":\"" + quoted(file) + "\"}");
        assertThat(heard).containsExactly("loaded three - 3");

        // a session reset's recording, and an export that clears, replace whatever the palette showed
        heard.clear();
        session.startRecording(true);
        click("c");
        call("{\"op\":\"export\",\"as\":\"recorded\",\"name\":\"one\",\"dir\":\"" + quoted(dir) + "\",\"clear\":true}");
        assertThat(heard).containsExactly("replaced", "replaced");
    }

    @Test
    void withThePaletteOffNoRecordingIsShared() throws Exception {
        fakeReset();
        List<String> heard = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        session.recordingView(hearing(heard));

        assertThat(call("{\"op\":\"start\"}").path("shared").asBoolean()).isFalse();
        click("a");
        assertThat(call("{\"op\":\"stop\"}").path("shared").asBoolean()).isFalse();
        call("{\"op\":\"replay\"}");
        call("{\"op\":\"redo\",\"from\":2}");
        call("{\"op\":\"stop\"}");
        call("{\"op\":\"load\",\"path\":\"" + quoted(savedTest("three")) + "\"}");
        session.startRecording(true);
        assertThat(sink.begin().ok).isTrue();
        assertThat(call("{\"op\":\"list\"}").path("shared").asBoolean()).isFalse();
        sink.end();
        assertThat(heard).isEmpty();
    }

    // ---- replay, redo, export, load -------------------------------------------------------------

    private void fakeReset() {
        session.appReset(new UiMcpSession.AppReset() {
            @Override public boolean defined() { return true; }
            @Override public void run() { backend.verbs.calls.add("reset"); }
        });
    }

    @Test
    void replayRunsEveryStepFromAResetAndRecordsNothing() throws Exception {
        fakeReset();
        four();
        call("{\"op\":\"insert\",\"at\":2,\"step\":{\"action\":\"click\",\"target\":{\"id\":\"x\"}}}");
        backend.verbs.calls.clear();
        JsonNode r = call("{\"op\":\"replay\"}");
        assertThat(r.path("passed").asBoolean()).isTrue();
        assertThat(r.path("ran").asInt()).isEqualTo(5);
        assertThat(r.path("total").asInt()).isEqualTo(5);
        assertThat(r.path("reset").asBoolean()).isTrue();
        assertThat(backend.verbs.calls).hasSize(6);
        assertThat(backend.verbs.calls.get(0)).isEqualTo("reset");
        assertThat(r.path("steps")).hasSize(5);
        assertThat(r.path("steps").get(1).path("n").asInt()).isEqualTo(2);
        assertThat(r.path("steps").get(1).path("line").asText()).startsWith("click(");
        assertThat(r.path("steps").get(1).path("passed").asBoolean()).isTrue();
        JsonNode list = call("{\"op\":\"list\"}");
        assertThat(list.path("steps")).hasSize(5);
        assertThat(list.path("steps").get(1).has("edited")).isFalse();
        assertThat(list.path("rev").asInt()).isEqualTo(r.path("rev").asInt());
    }

    @Test
    void replayFromALaterStepDoesNotReset() throws Exception {
        fakeReset();
        four();
        backend.verbs.calls.clear();
        JsonNode r = call("{\"op\":\"replay\",\"from\":2,\"to\":3}");
        assertThat(r.path("reset").asBoolean()).isFalse();
        assertThat(r.path("ran").asInt()).isEqualTo(2);
        assertThat(r.path("total").asInt()).isEqualTo(2);
        assertThat(backend.verbs.calls).hasSize(2).doesNotContain("reset");
        assertThat(refused("{\"op\":\"replay\",\"from\":2,\"to\":9}").getMessage()).contains("no step 9");
    }

    @Test
    void aFailingStepEndsTheReplayAndSaysWhich() throws Exception {
        fakeReset();
        four();
        backend.verbs.calls.clear();
        backend.verbs.during = () -> backend.verbs.failure =
                backend.verbs.calls.size() == 3 ? new DriverException(Reason.NOT_FOUND, "nothing matches") : null;
        JsonNode r = call("{\"op\":\"replay\"}");
        assertThat(r.path("passed").asBoolean()).isFalse();
        assertThat(r.path("ran").asInt()).isEqualTo(2);
        assertThat(r.path("total").asInt()).isEqualTo(4);
        assertThat(r.path("steps")).hasSize(2);
        assertThat(r.path("steps").get(1).path("n").asInt()).isEqualTo(2);
        assertThat(r.path("steps").get(1).path("passed").asBoolean()).isFalse();
        assertThat(r.path("steps").get(1).path("reason").asText()).isEqualTo("NOT_FOUND");
        assertThat(call("{\"op\":\"list\"}").path("steps")).hasSize(4);
    }

    @Test
    void aHarnessErrorInAReplayNamesTheStep() throws Exception {
        fakeReset();
        four();
        backend.verbs.calls.clear();
        backend.verbs.during = () -> backend.verbs.failure =
                backend.verbs.calls.size() == 3 ? new DriverException(Reason.CAPABILITY_GAP, "no popup") : null;
        DriverException e = refused("{\"op\":\"replay\"}");
        assertThat(e.getMessage()).contains("step 2").contains("no popup");
    }

    @Test
    void stepByStepIsThePersons() throws Exception {
        four();
        DriverException e = refused("{\"op\":\"replay\",\"speed\":\"step by step\"}");
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("step by step is the person's")
                .contains("Step by step in the palette's Steps panel");
        assertThat(refused("{\"op\":\"replay\",\"speed\":\"warp\"}").reason()).isEqualTo(Reason.BAD_INPUT);
    }

    @Test
    void replayingNothingSaysSo() throws Exception {
        call("{\"op\":\"start\"}");
        JsonNode r = call("{\"op\":\"replay\"}");
        assertThat(r.path("passed").asBoolean()).isTrue();
        assertThat(r.path("ran").asInt()).isZero();
        assertThat(r.path("message").asText()).contains("nothing to replay");
    }

    @Test
    void whileTheAgentReplaysThePalettesEditsAreRefused() throws Exception {
        fakeReset();
        four();
        final java.util.concurrent.CountDownLatch inStep = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        backend.verbs.calls.clear();
        backend.verbs.during = () -> {
            if (backend.verbs.calls.size() == 2) {
                inStep.countDown();
                try {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        final JsonNode[] out = new JsonNode[1];
        Thread replay = new Thread(() -> {
            try {
                out[0] = call("{\"op\":\"replay\"}");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        replay.start();
        try {
            assertThat(inStep.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            int rev = call("{\"op\":\"list\"}").path("rev").asInt();
            Inspector.Result delete = sink.delete(id(0));
            assertThat(delete.ok).isFalse();
            assertThat(delete.line).isEqualTo("the agent is replaying the recording");
            assertThat(sink.cutFrom(id(1)).ok).isFalse();
            assertThat(sink.undo().ok).isFalse();
            sink.resume();
            DriverException e = refused("{\"op\":\"delete\",\"n\":1}");
            assertThat(e.reason()).isEqualTo(Reason.BUSY);
            assertThat(e.getMessage()).contains("the agent is replaying the recording (steps 1-4)");
            assertThat(refused("{\"op\":\"undo\"}").reason()).isEqualTo(Reason.BUSY);
            JsonNode list = call("{\"op\":\"list\"}");
            assertThat(list.path("steps")).hasSize(4);
            assertThat(list.path("rev").asInt()).isEqualTo(rev);
        } finally {
            release.countDown();
            replay.join(10_000);
        }
        assertThat(out[0].path("passed").asBoolean()).isTrue();
        // afterwards the person's edits work again
        assertThat(sink.delete(id(0)).ok).isTrue();
        assertThat(call("{\"op\":\"list\"}").path("steps")).hasSize(3);
    }

    @Test
    void redoCutsReplaysUpToTheStepAndRecordsOn() throws Exception {
        fakeReset();
        four();
        call("{\"op\":\"stop\"}");
        backend.verbs.calls.clear();
        JsonNode r = call("{\"op\":\"redo\",\"from\":3}");
        assertThat(r.path("passed").asBoolean()).isTrue();
        assertThat(r.path("ran").asInt()).isEqualTo(2);
        assertThat(r.path("recording").asBoolean()).isTrue();
        assertThat(r.path("list").path("state").asText()).isEqualTo("recording");
        assertThat(r.path("list").path("steps")).hasSize(2);
        assertThat(backend.verbs.calls).hasSize(3);
        assertThat(backend.verbs.calls.get(0)).isEqualTo("reset");
        JsonNode ran = mapper.readTree(session.verb("click", mapper.readTree("{\"target\":{\"id\":\"z\"}}")));
        assertThat(ran.has("recorded")).isTrue();
        JsonNode list = call("{\"op\":\"list\"}");
        assertThat(list.path("steps")).hasSize(3);
        assertThat(list.path("steps").get(2).path("n").asInt()).isEqualTo(3);
        assertThat(ids(list)).isEqualTo("abz");
        assertThat(list.path("startedBy").asText()).isEqualTo("agent");
    }

    @Test
    void redoFromTheEndContinuesAStoppedRecording() throws Exception {
        fakeReset();
        four();
        call("{\"op\":\"stop\"}");
        backend.verbs.calls.clear();
        JsonNode r = call("{\"op\":\"redo\",\"from\":5}");
        assertThat(r.path("ran").asInt()).isEqualTo(4);
        assertThat(r.path("recording").asBoolean()).isTrue();
        assertThat(r.path("list").path("steps")).hasSize(4);
        assertThat(backend.verbs.calls).hasSize(5);
        assertThat(refused("{\"op\":\"redo\",\"from\":6}").getMessage()).contains("no step 6");
        assertThat(refused("{\"op\":\"redo\"}").reason()).isEqualTo(Reason.BAD_INPUT);
    }

    @Test
    void redoFromTheFirstStepOnlyResets() throws Exception {
        fakeReset();
        four();
        backend.verbs.calls.clear();
        JsonNode r = call("{\"op\":\"redo\",\"from\":1}");
        assertThat(backend.verbs.calls).containsExactly("reset");
        assertThat(r.path("list").path("steps")).isEmpty();
        assertThat(r.path("recording").asBoolean()).isTrue();
    }

    @Test
    void aRedoThatFailsKeepsTheCutAndUndoBringsItBack() throws Exception {
        fakeReset();
        four();
        call("{\"op\":\"stop\"}");
        backend.verbs.calls.clear();
        backend.verbs.during = () -> backend.verbs.failure =
                backend.verbs.calls.size() == 3 ? new DriverException(Reason.NOT_FOUND, "nothing matches") : null;
        JsonNode r = call("{\"op\":\"redo\",\"from\":4}");
        assertThat(r.path("passed").asBoolean()).isFalse();
        assertThat(r.path("recording").asBoolean()).isFalse();
        assertThat(r.path("list").path("state").asText()).isEqualTo("stopped");
        assertThat(r.path("list").path("steps")).hasSize(3);
        backend.verbs.during = () -> { };
        backend.verbs.failure = null;
        assertThat(ids(call("{\"op\":\"undo\"}"))).isEqualTo("abcd");
    }

    @Test
    void aRedoThatFailsOnARunningRecordingStopsIt() throws Exception {
        fakeReset();
        // an explicit recording the palette shares, and the implicit one of a session reset
        for (boolean explicit : new boolean[] {true, false}) {
            session.palette(explicit);
            List<String> heard = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
            session.recordingView(hearing(heard));
            if (explicit) call("{\"op\":\"start\",\"reset\":true}"); else session.startRecording(true);
            click("a");
            click("b");
            click("c");
            backend.verbs.calls.clear();
            backend.verbs.during = () -> backend.verbs.failure =
                    backend.verbs.calls.size() == 3 ? new DriverException(Reason.NOT_FOUND, "nothing matches") : null;
            heard.clear();
            JsonNode r = call("{\"op\":\"redo\",\"from\":3}");
            backend.verbs.during = () -> { };
            backend.verbs.failure = null;
            assertThat(r.path("passed").asBoolean()).isFalse();
            assertThat(r.path("recording").asBoolean()).isFalse();
            assertThat(r.path("list").path("state").asText()).isEqualTo("stopped");
            assertThat(call("{\"op\":\"list\"}").path("state").asText()).isEqualTo("stopped");
            assertThat(session.recordingReason()).isNull();
            if (explicit) assertThat(heard).endsWith("stopped"); else assertThat(heard).doesNotContain("stopped");
            // the next step is on a screen the replay did not reach: it does not follow the cut
            JsonNode ran = mapper.readTree(session.verb("click", mapper.readTree("{\"target\":{\"id\":\"z\"}}")));
            assertThat(ran.has("recorded")).isFalse();
            assertThat(ids(call("{\"op\":\"list\"}"))).isEqualTo("ab");
        }
    }

    @Test
    void aRedoThatBreaksOnARunningRecordingStopsIt() throws Exception {
        // a harness error in a step, and a reset that throws before a redo from the first step
        for (int from : new int[] {3, 1}) {
            session.palette(true);
            List<String> heard = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
            session.recordingView(hearing(heard));
            fakeReset();
            call("{\"op\":\"start\",\"reset\":true}");
            click("a");
            click("b");
            click("c");
            backend.verbs.calls.clear();
            if (from == 1) {
                session.appReset(new UiMcpSession.AppReset() {
                    @Override public boolean defined() { return true; }
                    @Override public void run() { throw new DriverException(Reason.CAPABILITY_GAP, "no reset today"); }
                });
            } else {
                backend.verbs.during = () -> backend.verbs.failure =
                        backend.verbs.calls.size() == 2 ? new DriverException(Reason.CAPABILITY_GAP, "no popup") : null;
            }
            heard.clear();
            DriverException e = refused("{\"op\":\"redo\",\"from\":" + from + "}");
            backend.verbs.during = () -> { };
            backend.verbs.failure = null;
            assertThat(e.getMessage()).contains(from == 1 ? "no reset today" : "no popup");
            assertThat(call("{\"op\":\"list\"}").path("state").asText()).isEqualTo("stopped");
            assertThat(session.recordingReason()).isNull();
            assertThat(heard).endsWith("stopped");
        }
    }

    @Test
    void aPassingRedoBeginsTheRecordingAtTheReset() throws Exception {
        // only the reset (from the first step), and the steps before the cut from the reset
        for (int from : new int[] {1, 3}) {
            fakeReset();
            call("{\"op\":\"start\"}");
            click("a");
            click("b");
            click("c");
            call("{\"op\":\"stop\"}");
            JsonNode before = call("{\"op\":\"list\"}");
            assertThat(before.path("fromReset").asBoolean()).isFalse();
            assertThat(before.path("definedStart").asBoolean()).isFalse();
            JsonNode r = call("{\"op\":\"redo\",\"from\":" + from + "}");
            assertThat(r.path("recording").asBoolean()).isTrue();
            assertThat(r.path("list").path("fromReset").asBoolean()).isTrue();
            assertThat(r.path("list").path("definedStart").asBoolean()).isTrue();
            click("d");
            call("{\"op\":\"stop\"}");
            assertThat(session.exportRecordedTest("redo-" + from, quoted(dir), null, false)).contains("savedTo");
        }
    }

    @Test
    void exportAsFlowIsRefusedNamingJavaOrRecorded() throws Exception {
        DriverException e = catchThrowableOfType(() -> call("{\"op\":\"export\",\"as\":\"flow\",\"name\":\"x\"}"), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("java").contains("recorded");
    }

    @Test
    void exportWritesJavaOrARecordedTestAndKeepsTheRecording() throws Exception {
        Path tests = dir.resolve("tests");
        Path testsDir = dir.resolve("recorded");
        System.setProperty(UiMcpSession.TESTS_DIR_PROP, tests.toString());
        try {
            fakeReset();
            four(true);
            JsonNode java = call("{\"op\":\"export\",\"as\":\"java\",\"class\":\"PlaceUiIT\",\"package\":\"demo\"}");
            assertThat(Files.exists(tests.resolve("demo/PlaceUiIT.java"))).isTrue();
            assertThat(java.path("steps").asInt()).isEqualTo(4);
            JsonNode flow = call("{\"op\":\"export\",\"as\":\"recorded\",\"name\":\"place\",\"dir\":\""
                    + testsDir.toString().replace("\\", "\\\\") + "\",\"description\":\"four clicks\"}");
            assertThat(Files.exists(testsDir.resolve("place.json"))).isTrue();
            assertThat(flow.path("steps").asInt()).isEqualTo(4);
            assertThat(call("{\"op\":\"list\"}").path("steps")).hasSize(4);
            assertThat(refused("{\"op\":\"export\",\"as\":\"xml\"}").reason()).isEqualTo(Reason.BAD_INPUT);
            call("{\"op\":\"stop\"}");
            call("{\"op\":\"export\",\"as\":\"java\",\"class\":\"SecondUiIT\",\"package\":\"demo\",\"clear\":true}");
            assertThat(call("{\"op\":\"list\"}").path("steps")).isEmpty();
        } finally {
            System.clearProperty(UiMcpSession.TESTS_DIR_PROP);
        }
    }

    private Path savedTest(String name) throws Exception {
        Flow flow = new Flow();
        flow.version = RecordedTestFiles.VERSION;
        flow.name = name;
        flow.start = RecordedTestFiles.START_RESET;
        for (String id : new String[] {"a", "b", "c"}) {
            ClickVerb c = new ClickVerb();
            c.target = Locator.ofId(id);
            flow.steps.add(c);
        }
        Path file = dir.resolve(name + RecordedTestFiles.SUFFIX);
        Files.write(file, RecordedTestFiles.write(flow).getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String quoted(Path p) {
        return p.toString().replace("\\", "\\\\");
    }

    @Test
    void loadMakesARecordedTestTheRecordingAndRedoContinuesIt() throws Exception {
        fakeReset();
        Path file = savedTest("three");
        JsonNode list = call("{\"op\":\"load\",\"path\":\"" + quoted(file) + "\"}");
        assertThat(list.path("state").asText()).isEqualTo("stopped");
        assertThat(list.path("steps")).hasSize(3);
        assertThat(list.path("steps").get(0).path("author").asText()).isEqualTo("loaded");
        assertThat(list.path("loadedFrom").asText()).isEqualTo(file.toAbsolutePath().normalize().toString());
        backend.verbs.calls.clear();
        JsonNode r = call("{\"op\":\"redo\",\"from\":3}");
        assertThat(r.path("recording").asBoolean()).isTrue();
        click("z");
        assertThat(ids(call("{\"op\":\"list\"}"))).isEqualTo("abz");
    }

    @Test
    void aLoadByNameReadsTheRecordedTestsFolderAndSaysWhenThereIsNone() throws Exception {
        savedTest("three");
        UiMcpSession other = new UiMcpSession(backend, new UiMcpToolsTest.FakeInventory(), mapper);
        other.folderMemory(FolderMemory.inMemory());
        other.recordedDir(dir, true);
        JsonNode list = mapper.readTree(other.recording(mapper.readTree("{\"op\":\"load\",\"name\":\"three\"}")));
        assertThat(list.path("steps")).hasSize(3);
        DriverException e = catchThrowableOfType(
                () -> other.recording(mapper.readTree("{\"op\":\"load\",\"name\":\"nope\"}")), DriverException.class);
        assertThat(e.getMessage()).contains("no recorded test 'nope'");
        assertThat(refused("{\"op\":\"load\"}").reason()).isEqualTo(Reason.BAD_INPUT);
    }

    @Test
    void theAgentsStepsAfterALoadDoNotJoinIt() throws Exception {
        Path file = savedTest("three");
        call("{\"op\":\"load\",\"path\":\"" + quoted(file) + "\"}");
        JsonNode ran = mapper.readTree(session.verb("click", mapper.readTree("{\"target\":{\"id\":\"z\"}}")));
        assertThat(ran.has("recorded")).isFalse();
        assertThat(ran.path("notRecorded").asText()).contains("recording redo");
        assertThat(call("{\"op\":\"list\"}").path("steps")).hasSize(3);
    }

    @Test
    void aLoadIsRefusedWhileARecordingRuns() throws Exception {
        Path file = savedTest("three");
        call("{\"op\":\"start\"}");
        click("a");
        DriverException e = refused("{\"op\":\"load\",\"path\":\"" + quoted(file) + "\"}");
        assertThat(e.reason()).isEqualTo(Reason.BUSY);
        assertThat(call("{\"op\":\"list\"}").path("steps")).hasSize(1);
    }

    @Test
    void withThePaletteOffTheAgentRecordsEditsAndExportsAlone() throws Exception {
        fakeReset();
        call("{\"op\":\"start\",\"reset\":true}");
        click("a");
        click("b");
        click("c");
        call("{\"op\":\"delete\",\"n\":2}");
        assertThat(ids(call("{\"op\":\"undo\"}"))).isEqualTo("abc");
        call("{\"op\":\"insert\",\"at\":4,\"step\":{\"action\":\"expect\",\"target\":{\"id\":\"status\"},\"text\":\"ok\"}}");
        JsonNode r = call("{\"op\":\"replay\"}");
        assertThat(r.path("passed").asBoolean()).isTrue();
        assertThat(call("{\"op\":\"list\"}").path("steps").get(3).has("edited")).isFalse();
        assertThat(session.recordingReason()).isNotNull();
        call("{\"op\":\"stop\"}");
        assertThat(session.recordingReason()).isNull();
        call("{\"op\":\"export\",\"as\":\"recorded\",\"name\":\"alone\",\"dir\":\"" + quoted(dir) + "\"}");
        Flow back = RecordedTestFiles.read(dir.resolve("alone.json"));
        assertThat(back.steps).hasSize(4);
        assertThat(new String(Files.readAllBytes(dir.resolve("alone.json")), StandardCharsets.UTF_8)).doesNotContain("author");
    }

    private void clearedRecordingOfThree() throws Exception {
        fakeReset();
        four(true);
        call("{\"op\":\"stop\"}");
        session.exportRecordedTest("first", quoted(dir), null, true);
        for (int i = 1; i <= 3; i++) {
            call("{\"op\":\"insert\",\"at\":" + i + ",\"step\":{\"action\":\"click\",\"target\":{\"id\":\"s" + i + "\"}}}");
        }
    }

    private void exportRefusedForNoReset() {
        assertThat(catchThrowableOfType(() -> session.exportRecordedTest("second", quoted(dir), null, false), IllegalArgumentException.class)
                .getMessage()).contains("did not begin at a session reset");
    }

    @Test
    void aFailingOrPartialReplayFromTheFirstStepDoesNotMakeTheRecordingExportable() throws Exception {
        clearedRecordingOfThree();
        backend.verbs.calls.clear();
        backend.verbs.during = () -> backend.verbs.failure =
                backend.verbs.calls.size() == 3 ? new DriverException(Reason.NOT_FOUND, "nothing matches") : null;
        assertThat(call("{\"op\":\"replay\"}").path("passed").asBoolean()).isFalse();
        exportRefusedForNoReset();
        backend.verbs.during = () -> { };
        backend.verbs.failure = null;
        assertThat(call("{\"op\":\"replay\",\"from\":1,\"to\":2}").path("passed").asBoolean()).isTrue();
        exportRefusedForNoReset();
        assertThat(call("{\"op\":\"replay\"}").path("passed").asBoolean()).isTrue();
        assertThat(session.exportRecordedTest("second", quoted(dir), null, false)).contains("savedTo");
    }
}
