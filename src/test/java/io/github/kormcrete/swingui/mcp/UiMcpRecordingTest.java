package io.github.kormcrete.swingui.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kormcrete.swingui.agent.PluginDescriptor;
import io.github.kormcrete.swingui.agent.PluginSlot;
import io.github.kormcrete.swingui.agent.Plugins;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.inspect.Inspector;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.RowChoice;
import io.github.kormcrete.swingui.flow.Action;
import io.github.kormcrete.swingui.flow.DoAction;
import io.github.kormcrete.swingui.flow.RecordedTestFiles;
import io.github.kormcrete.swingui.flow.Flow;
import io.github.kormcrete.swingui.flow.refs.Refs;
import io.github.kormcrete.swingui.flow.verb.ClickVerb;
import io.github.kormcrete.swingui.spi.DomainStep;
import io.github.kormcrete.swingui.spi.ParamType;
import io.github.kormcrete.swingui.spi.PluginParts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
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
            assertThat(e.getMessage()).contains("start, stop, list, insert, delete, move, edit, group, ungroup, row, undo, "
                    + "fold, unfold, keepRaw, link, unlink, remember, unique, replay, redo, export, load");
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
    void aCancelledFileChooserIsRecorded() throws Exception {
        call("{\"op\":\"start\"}");
        backend.verbs.outcome.recorded.clear(); // chooseFile has no target to record
        JsonNode ran = mapper.readTree(session.batch(mapper.readTree(
                "{\"steps\":[{\"verb\":\"choose_file\",\"approve\":false}]}")));
        assertThat(ran.path("passed").asBoolean()).as(ran.toString()).isTrue();
        JsonNode steps = call("{\"op\":\"list\"}").path("steps");
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).path("java").asText()).isEqualTo("ui.chooseFile(null, false);");
    }

    @Test
    void aPopupListPickIsRecorded() throws Exception {
        call("{\"op\":\"start\"}");
        JsonNode ran = mapper.readTree(session.verb("click",
                mapper.readTree("{\"target\":{\"window\":\"popup\",\"role\":\"list\",\"item\":[\"Ada Shop Ltd\"]}}")));
        assertThat(ran.path("passed").asBoolean()).isTrue();
        assertThat(ran.has("recorded")).isTrue();
        assertThat(ran.has("notRecorded")).isFalse();
        assertThat(call("{\"op\":\"list\"}").path("steps")).hasSize(1);
    }

    /** The person's pick of a Customer suggestion, as the palette records it: a click on the popup list's entry. */
    private static Map<String, Object> suggestionPick(String customer) {
        Locator entry = Locator.of("list", null);
        entry.window = "popup";
        entry.item = Arrays.asList(customer);
        Map<String, Object> pick = step("click", entry);
        pick.put(Inspector.SEEN, entry.copy());
        return pick;
    }

    @Test
    void aPersonsSuggestionPickIsRecordedWithItsJava() throws Exception {
        assertThat(sink.done(suggestionPick("Ada Shop Ltd")).ok).isTrue();

        JsonNode steps = call("{\"op\":\"list\"}").path("steps");
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).path("author").asText()).isEqualTo("person");
        assertThat(steps.get(0).path("java").asText()).isEqualTo("ui.popup().list(null).item(\"Ada Shop Ltd\").click();");
    }

    @Test
    void aPersonsSuggestionPickFoldsIntoTheStepThatMatchesItsItem() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs), DomainStep.act("chooseCustomer", "Choose the customer {customer}")
                .param("customer", ParamType.TEXT)
                .code((ui, p) -> ui.popup().list(null).item(p.text("customer")).click())
                .match("{\"action\":\"click\",\"target\":{\"role\":\"list\",\"item\":[\"{customer}\"]}}")
                .java(STEPS_CLASS, "chooseCustomer(ui, {customer})")
                .build());
        assertThat(s.humanSink().done(suggestionPick("Ada Shop Ltd")).ok).isTrue();

        JsonNode steps = recording(s, "{\"op\":\"list\"}").path("steps");
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).path("step")).isEqualTo(mapper.readTree("{\"action\":\"do\",\"say\":\"Choose the customer "
                + "Ada Shop Ltd\",\"step\":\"order-desk:chooseCustomer\",\"params\":{\"customer\":\"Ada Shop Ltd\"}}"));
        assertThat(steps.get(0).path("java").asText()).isEqualTo("OrderDeskSteps.chooseCustomer(ui, \"Ada Shop Ltd\");");
    }

    @Test
    void whatThePersonDidThatCannotBeRecordedIsListedForTheAgent() throws Exception {
        Inspector.Result r = sink.refused("the expand arrow of 'Office': double-click the row instead");

        assertThat(r.ok).isFalse();
        assertThat(r.line).isEqualTo("the expand arrow of 'Office': double-click the row instead");
        JsonNode list = call("{\"op\":\"list\"}");
        assertThat(list.path("steps")).isEmpty();
        assertThat(list.path("notRecorded").get(0).asText()).isEqualTo("the expand arrow of 'Office': double-click the row instead");
    }

    @Test
    void aPersonsStepThatNeededNthIsFragileAndSaysWhy() throws Exception {
        Locator second = Locator.of("button", "Add to cart");
        second.nth = 1;
        assertThat(sink.done(step("click", second)).ok).isTrue();

        Action recorded = session.recordedSteps().get(0).action;
        assertThat(((ClickVerb) recorded).fragile).isTrue();
        assertThat(JavaTestWriter.write(Arrays.asList(recorded), "demo", "AddUiIT", "add"))
                .contains("// FRAGILE: the recorder needed nth to make this target unique\n");
    }

    @Test
    void aClickAtAPointIsFragileWhoeverRecordsIt() throws Exception {
        Locator at = Locator.ofId("canvas");
        at.point = new LinkedHashMap<String, Double>();
        at.point.put("x", 0.5);
        at.point.put("y", 0.25);
        assertThat(sink.done(step("click", at)).ok).isTrue();
        session.verb("click", mapper.readTree("{\"target\":{\"id\":\"canvas\",\"point\":{\"x\":0.2,\"y\":0.2}}}"));

        List<RecordedStep> steps = session.recordedSteps();
        assertThat(steps).extracting(r -> r.author).containsExactly(RecordedStep.Author.PERSON, RecordedStep.Author.AGENT);
        assertThat(steps).allMatch(r -> Boolean.TRUE.equals(((ClickVerb) r.action).fragile));
        // an edit to another point stays fragile, one off the point is not
        JsonNode step = call("{\"op\":\"edit\",\"n\":1,\"target\":{\"id\":\"canvas\",\"point\":{\"x\":0.7,\"y\":0.7}}}")
                .path("steps").get(0).path("step");
        assertThat(step.path("fragile").asBoolean()).isTrue();
        step = call("{\"op\":\"edit\",\"n\":1,\"target\":{\"id\":\"canvas\"}}").path("steps").get(0).path("step");
        assertThat(step.has("fragile")).isFalse();
    }

    @Test
    void theListSaysWhatThePersonCouldNotRecord() throws Exception {
        Locator at = Locator.ofId("canvas");
        at.point = new LinkedHashMap<String, Double>();
        at.point.put("x", 0.5);
        at.point.put("y", 0.5);
        Map<String, Object> twice = step("click", at);
        twice.put("count", 2);
        assertThat(sink.done(twice).ok).isFalse();
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
        JsonNode r = call("{\"op\":\"insert\",\"at\":\"1.1\",\"step\":{\"action\":\"expect\",\"target\":{\"id\":\"status\"},\"text\":\"ok\"}}");
        assertThat(backend.verbs.calls).hasSize(calls);
        assertThat(r.path("did").asText()).isEqualTo("inserted check 1.1");   // the check goes under step 1
        assertThat(r.path("steps")).hasSize(4);
        JsonNode check = r.path("steps").get(0).path("checks").get(0);
        assertThat(check.path("step").path("action").asText()).isEqualTo("expect");
        assertThat(check.path("edited").asBoolean()).isTrue();
        assertThat(r.path("steps").get(0).has("edited")).isFalse();
        assertThat(check.path("author").asText()).isEqualTo("agent");
        // at steps+1 appends
        assertThat(call("{\"op\":\"insert\",\"at\":5,\"step\":{\"action\":\"click\",\"target\":{\"id\":\"z\"}}}")
                .path("steps")).hasSize(5);
    }

    @Test
    void aStepWithoutAJavaFormIsRefusedAndNothingChanges() throws Exception {
        four();
        int rev = call("{\"op\":\"list\"}").path("rev").asInt();
        for (String step : new String[] {
                "{\"action\":\"click\",\"count\":2,\"target\":{\"id\":\"c\",\"point\":{\"x\":0.5,\"y\":0.5}}}",
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
    void aRefusedInsertBehindChecksSaysTheStepItWouldBe() throws Exception {
        call("{\"op\":\"start\"}");
        click("a");
        call("{\"op\":\"insert\",\"at\":\"1.1\",\"step\":" + CHECK + "}");
        call("{\"op\":\"insert\",\"at\":\"1.2\",\"step\":" + CHECK + "}");
        click("d");
        DriverException e = refused("{\"op\":\"insert\",\"at\":3,\"step\":{\"action\":\"click\",\"target\":{\"ref\":\"e3\"}}}");
        assertThat(e.getMessage()).startsWith("BAD_INPUT: step 3: ");
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
        assertThat(refused("{\"op\":\"edit\",\"n\":\"1.1\",\"text\":\"x\"}").getMessage()).contains("check 1.1 (expect) has no text");
        assertThat(refused("{\"op\":\"edit\",\"n\":2,\"text\":\"x\"}").getMessage()).contains("step 2 (drop) has no text");
        assertThat(call("{\"op\":\"list\"}").path("rev").asInt()).isEqualTo(rev);
    }

    private static final String CHECK = "{\"action\":\"expect\",\"target\":{\"id\":\"status\"},\"text\":\"ok\"}";

    /** Four clicks (a b c d) with two checks under step 1 (1.1, 1.2) and one under step 3 (3.1). */
    private void fourWithChecks() throws Exception {
        four();
        call("{\"op\":\"insert\",\"at\":\"1.1\",\"step\":" + CHECK + "}");
        call("{\"op\":\"insert\",\"at\":\"1.2\",\"step\":" + CHECK + "}");
        call("{\"op\":\"insert\",\"at\":\"3.1\",\"step\":" + CHECK + "}");
    }

    @Test
    void editSaysAndUnsaysAStepAndACheck() throws Exception {
        fourWithChecks();
        JsonNode r = call("{\"op\":\"edit\",\"n\":1,\"say\":\"Open it\"}");
        assertThat(r.path("did").asText()).isEqualTo("set the sentence of step 1");
        assertThat(r.path("steps").get(0).path("say").asText()).isEqualTo("Open it");
        assertThat(r.path("steps").get(0).has("edited")).isFalse();   // a sentence is not a change of behaviour
        r = call("{\"op\":\"edit\",\"n\":\"1.1\",\"say\":\"  The status is ok \"}");
        assertThat(r.path("did").asText()).isEqualTo("set the sentence of check 1.1");
        assertThat(r.path("steps").get(0).path("checks").get(0).path("say").asText()).isEqualTo("The status is ok");
        assertThat(r.path("steps").get(0).path("say").asText()).isEqualTo("Open it");
        r = call("{\"op\":\"edit\",\"n\":1,\"say\":\"\"}");
        assertThat(r.path("did").asText()).isEqualTo("removed the sentence of step 1");
        assertThat(r.path("steps").get(0).has("say")).isFalse();
        assertThat(r.path("steps").get(0).path("checks").get(0).has("say")).isTrue();
    }

    @Test
    void removingASentenceThatIsNotThereChangesNothing() throws Exception {
        fourWithChecks();
        call("{\"op\":\"edit\",\"n\":1,\"say\":\"Open it\"}");
        JsonNode before = call("{\"op\":\"list\"}");
        String undo = sink.undoLabel();

        JsonNode r = call("{\"op\":\"edit\",\"n\":3,\"say\":\"\"}");
        assertThat(r.path("did").asText()).isEqualTo("step 3 has no sentence");
        r = call("{\"op\":\"edit\",\"n\":\"1.1\",\"say\":\"\"}");
        assertThat(r.path("did").asText()).isEqualTo("check 1.1 has no sentence");

        assertThat(call("{\"op\":\"list\"}").path("rev").asInt()).isEqualTo(before.path("rev").asInt());
        assertThat(sink.undoLabel()).isEqualTo(undo);
        assertThat(call("{\"op\":\"undo\"}").path("did").asText()).isEqualTo("undid the sentence of step 1 by the agent");
    }

    @Test
    void aGroupWithoutASentenceIsRefusedSayingHowToEndIt() throws Exception {
        four();
        DriverException e = refused("{\"op\":\"group\",\"from\":1,\"to\":2,\"say\":\" \"}");
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("a group needs a sentence").contains("ungroup").doesNotContain("leave");
    }

    @Test
    void editCanChangeTheSentenceAndTheTextTogether() throws Exception {
        call("{\"op\":\"start\"}");
        call("{\"op\":\"insert\",\"at\":1,\"step\":{\"action\":\"type\",\"target\":{\"id\":\"name\"},\"text\":\"Ada\"}}");
        call("{\"op\":\"edit\",\"n\":1,\"say\":\"Name the customer\"}");
        JsonNode r = call("{\"op\":\"edit\",\"n\":1,\"text\":\"Grace\",\"say\":\"Name Grace\"}");
        assertThat(r.path("did").asText()).isEqualTo("edited step 1");
        JsonNode step = r.path("steps").get(0);
        assertThat(step.path("step").path("text").asText()).isEqualTo("Grace");
        assertThat(step.path("say").asText()).isEqualTo("Name Grace");
        assertThat(step.path("edited").asBoolean()).isTrue();
        // one history entry: one undo takes back the text and the sentence
        r = call("{\"op\":\"undo\"}");
        assertThat(r.path("did").asText()).isEqualTo("undid the edit of step 1 by the agent");
        assertThat(r.path("steps").get(0).path("step").path("text").asText()).isEqualTo("Ada");
        assertThat(r.path("steps").get(0).path("say").asText()).isEqualTo("Name the customer");
    }

    @Test
    void aSentenceWithALineBreakIsRefused() throws Exception {
        fourWithChecks();
        int rev = call("{\"op\":\"list\"}").path("rev").asInt();
        DriverException e = refused("{\"op\":\"edit\",\"n\":2,\"say\":\"one\\ntwo\"}");
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("step 2").contains("one line");
        e = refused("{\"op\":\"edit\",\"n\":\"1.2\",\"say\":\"one\\ntwo\"}");
        assertThat(e.getMessage()).contains("check 1.2").contains("one line");
        e = refused("{\"op\":\"edit\",\"n\":2,\"text\":\"x\",\"say\":\"one\\ntwo\"}");
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(refused("{\"op\":\"group\",\"from\":1,\"to\":2,\"say\":\"one\\ntwo\"}").getMessage()).contains("one line");
        assertThat(call("{\"op\":\"list\"}").path("rev").asInt()).isEqualTo(rev);
    }

    @Test
    void aSentenceInsideAStepObjectIsTrimmedAndCheckedOnInsertEditAndDoAction() throws Exception {
        four();
        int rev = call("{\"op\":\"list\"}").path("rev").asInt();
        String click = "\"action\":\"click\",\"target\":{\"id\":\"x\"}";
        // insert: trimmed; a blank one is dropped; a bad one refused
        JsonNode r = call("{\"op\":\"insert\",\"at\":2,\"step\":{" + click + ",\"say\":\"  Open x \"}}");
        assertThat(r.path("steps").get(1).path("say").asText()).isEqualTo("Open x");
        r = call("{\"op\":\"insert\",\"at\":3,\"step\":{" + click + ",\"say\":\"   \"}}");
        assertThat(r.path("steps").get(2).has("say")).isFalse();
        rev = r.path("rev").asInt();
        for (String bad : new String[] {"a\\nb", new String(new char[201]).replace('\0', 'x')}) {
            DriverException e = refused("{\"op\":\"insert\",\"at\":2,\"step\":{" + click + ",\"say\":\"" + bad + "\"}}");
            assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
            assertThat(e.getMessage()).contains("step 2");
        }
        // whole-step edit: "" removes, a bad one is refused
        r = call("{\"op\":\"edit\",\"n\":2,\"step\":{" + click + ",\"say\":\"\"}}");
        assertThat(r.path("steps").get(1).has("say")).isFalse();
        rev = r.path("rev").asInt();
        assertThat(refused("{\"op\":\"edit\",\"n\":2,\"step\":{" + click + ",\"say\":\"a\\nb\"}}").getMessage()).contains("one line");
        assertThat(call("{\"op\":\"list\"}").path("rev").asInt()).isEqualTo(rev);
        // an agent step run while recording: refused before it acts, or recorded trimmed
        int calls = backend.verbs.calls.size();
        DriverException e = catchThrowableOfType(
                () -> session.verb("click", mapper.readTree("{\"target\":{\"id\":\"q\"},\"say\":\"a\\nb\"}")), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(backend.verbs.calls).hasSize(calls);
        session.verb("click", mapper.readTree("{\"target\":{\"id\":\"q\"},\"say\":\" Go \"}"));
        assertThat(call("{\"op\":\"list\"}").path("steps").get(6).path("say").asText()).isEqualTo("Go");
    }

    @Test
    void groupPutsStepsUnderOneSentence() throws Exception {
        four();
        JsonNode r = call("{\"op\":\"group\",\"from\":2,\"to\":3,\"say\":\"Pick two\"}");
        assertThat(r.path("did").asText()).isEqualTo("grouped steps 2-3");
        assertThat(r.path("groups")).hasSize(1);
        assertThat(r.path("groups").get(0).path("from").asInt()).isEqualTo(2);
        assertThat(r.path("groups").get(0).path("to").asInt()).isEqualTo(3);
        assertThat(r.path("groups").get(0).path("say").asText()).isEqualTo("Pick two");
        assertThat(r.path("steps").get(0).has("group")).isFalse();
        assertThat(r.path("steps").get(1).path("group").asInt()).isEqualTo(1);
        assertThat(r.path("steps").get(2).path("group").asInt()).isEqualTo(1);
        assertThat(r.path("steps").get(3).has("group")).isFalse();
        assertThat(r.path("steps").get(1).has("edited")).isFalse();
        r = call("{\"op\":\"group\",\"from\":2,\"to\":3,\"say\":\"Pick both\"}");
        assertThat(r.path("did").asText()).isEqualTo("renamed the group of steps 2-3");
        assertThat(r.path("groups").get(0).path("say").asText()).isEqualTo("Pick both");
        assertThat(refused("{\"op\":\"group\",\"from\":1,\"to\":9,\"say\":\"x\"}").getMessage()).contains("no step 9");
        assertThat(refused("{\"op\":\"group\",\"from\":3,\"to\":3,\"say\":\"x\"}").getMessage()).contains("at least two");
        assertThat(refused("{\"op\":\"group\",\"from\":1,\"to\":2}").reason()).isEqualTo(Reason.BAD_INPUT);
    }

    @Test
    void ungroupKeepsTheSteps() throws Exception {
        four();
        call("{\"op\":\"group\",\"from\":2,\"to\":4,\"say\":\"Pick three\"}");
        JsonNode r = call("{\"op\":\"ungroup\",\"n\":3}");
        assertThat(r.path("did").asText()).isEqualTo("ungrouped steps 2-4");
        assertThat(r.path("groups")).isEmpty();
        assertThat(ids(r)).isEqualTo("abcd");
        assertThat(refused("{\"op\":\"ungroup\",\"n\":3}").getMessage()).contains("step 3 is in no group");
        assertThat(refused("{\"op\":\"ungroup\"}").reason()).isEqualTo(Reason.BAD_INPUT);
    }

    @Test
    void aGroupThatOverlapsIsRefusedAndNothingChanges() throws Exception {
        four();
        call("{\"op\":\"group\",\"from\":1,\"to\":2,\"say\":\"First two\"}");
        JsonNode before = call("{\"op\":\"list\"}");
        for (String range : new String[] {"\"from\":2,\"to\":3", "\"from\":1,\"to\":4"}) {
            DriverException e = refused("{\"op\":\"group\"," + range + ",\"say\":\"x\"}");
            assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        }
        JsonNode after = call("{\"op\":\"list\"}");
        assertThat(after.path("rev").asInt()).isEqualTo(before.path("rev").asInt());
        assertThat(after.path("groups")).isEqualTo(before.path("groups"));
    }

    @Test
    void sentenceOpsAreUndoableAndRevChecked() throws Exception {
        four();
        int rev = call("{\"op\":\"list\"}").path("rev").asInt();
        call("{\"op\":\"edit\",\"n\":1,\"say\":\"Open it\",\"rev\":" + rev + "}");
        DriverException stale = refused("{\"op\":\"group\",\"from\":1,\"to\":2,\"say\":\"x\",\"rev\":" + rev + "}");
        assertThat(stale.reason()).isEqualTo(Reason.BUSY);
        assertThat(stale.getMessage()).contains("changed since revision " + rev);
        assertThat(refused("{\"op\":\"ungroup\",\"n\":1,\"rev\":" + rev + "}").reason()).isEqualTo(Reason.BUSY);
        call("{\"op\":\"group\",\"from\":1,\"to\":2,\"say\":\"Two\"}");
        call("{\"op\":\"ungroup\",\"n\":2}");
        assertThat(call("{\"op\":\"undo\"}").path("did").asText()).isEqualTo("undid the ungroup of steps 1-2 by the agent");
        assertThat(call("{\"op\":\"undo\"}").path("did").asText()).isEqualTo("undid the group of steps 1-2 by the agent");
        JsonNode r = call("{\"op\":\"undo\"}");
        assertThat(r.path("did").asText()).isEqualTo("undid the sentence of step 1 by the agent");
        assertThat(r.path("steps").get(0).has("say")).isFalse();
    }

    @Test
    void sentenceOpsAreBusyWhileTheAgentReplays() throws Exception {
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
        Thread replay = new Thread(() -> {
            try {
                call("{\"op\":\"replay\"}");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        replay.start();
        try {
            assertThat(inStep.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            for (String op : new String[] {
                    "{\"op\":\"edit\",\"n\":1,\"say\":\"x\"}",
                    "{\"op\":\"group\",\"from\":1,\"to\":2,\"say\":\"x\"}",
                    "{\"op\":\"ungroup\",\"n\":1}"}) {
                DriverException e = refused(op);
                assertThat(e.reason()).isEqualTo(Reason.BUSY);
                assertThat(e.getMessage()).contains("the agent is replaying the recording");
            }
            Inspector.Result d = sink.describe(id(0), id(1), "x");
            assertThat(d.ok).isFalse();
            assertThat(d.line).isEqualTo("the agent is replaying the recording");
            assertThat(sink.ungroup(id(0)).ok).isFalse();
        } finally {
            release.countDown();
            replay.join(10_000);
        }
    }

    @Test
    void theListNestsChecksUnderTheirStep() throws Exception {
        fourWithChecks();
        call("{\"op\":\"group\",\"from\":3,\"to\":4,\"say\":\"Last two\"}");
        JsonNode list = call("{\"op\":\"list\"}");
        assertThat(list.path("steps")).hasSize(4);
        JsonNode one = list.path("steps").get(0);
        assertThat(one.path("n").isInt()).isTrue();
        assertThat(one.path("n").asInt()).isEqualTo(1);
        assertThat(one.path("line").asText()).isEqualTo("click(#a)");
        assertThat(one.has("java")).isTrue();
        assertThat(one.path("step").path("target").path("id").asText()).isEqualTo("a");
        assertThat(one.path("checks")).hasSize(2);
        assertThat(one.path("checks").get(0).path("n").isTextual()).isTrue();
        assertThat(one.path("checks").get(0).path("n").asText()).isEqualTo("1.1");
        assertThat(one.path("checks").get(1).path("n").asText()).isEqualTo("1.2");
        assertThat(one.path("checks").get(0).path("author").asText()).isEqualTo("agent");
        assertThat(one.path("checks").get(0).path("line").asText()).startsWith("expect(");
        assertThat(one.path("checks").get(0).has("java")).isTrue();
        assertThat(one.path("checks").get(0).path("step").path("action").asText()).isEqualTo("expect");
        assertThat(one.path("checks").get(0).path("edited").asBoolean()).isTrue();
        assertThat(list.path("steps").get(1).path("checks")).isEmpty();
        assertThat(list.path("steps").get(2).path("checks").get(0).path("n").asText()).isEqualTo("3.1");
        assertThat(list.path("groups")).hasSize(1);
        assertThat(list.path("groups").get(0).path("from").asInt()).isEqualTo(3);
        assertThat(list.path("groups").get(0).path("to").asInt()).isEqualTo(4);
        assertThat(list.path("steps").get(2).path("group").asInt()).isEqualTo(1);
    }

    @Test
    void aCheckOnlyStepHasNoLineAndNothingGoesInFrontOfIt() throws Exception {
        call("{\"op\":\"start\"}");
        JsonNode r = call("{\"op\":\"insert\",\"at\":1,\"step\":" + CHECK + "}");
        assertThat(r.path("did").asText()).isEqualTo("inserted check 1.1");
        JsonNode first = r.path("steps").get(0);
        assertThat(first.path("n").asInt()).isEqualTo(1);
        assertThat(first.has("line")).isFalse();
        assertThat(first.has("step")).isFalse();
        assertThat(first.has("java")).isFalse();
        assertThat(first.path("checks").get(0).path("n").asText()).isEqualTo("1.1");
        int rev = r.path("rev").asInt();
        // an action in front would silently take the checks as its own: refused, not moved to 2
        DriverException e = refused("{\"op\":\"insert\",\"at\":1,\"step\":{\"action\":\"click\",\"target\":{\"id\":\"a\"}}}");
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("step 1 holds only checks");
        assertThat(call("{\"op\":\"list\"}").path("rev").asInt()).isEqualTo(rev);
        r = call("{\"op\":\"insert\",\"at\":2,\"step\":{\"action\":\"click\",\"target\":{\"id\":\"a\"}}}");
        assertThat(r.path("steps")).hasSize(2);
        assertThat(refused("{\"op\":\"move\",\"n\":2,\"to\":1}").getMessage()).contains("step 1 holds only checks");
        assertThat(refused("{\"op\":\"move\",\"n\":1,\"to\":2}").getMessage()).contains("cannot be moved");
    }

    @Test
    void aCheckCannotBeMovedAndARangeIsSteps() throws Exception {
        fourWithChecks();
        int rev = call("{\"op\":\"list\"}").path("rev").asInt();
        DriverException e = refused("{\"op\":\"move\",\"n\":\"1.2\",\"to\":3}");
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("check 1.2").contains("cannot be moved")
                .contains("checks cannot be moved; delete and insert");
        assertThat(call("{\"op\":\"list\"}").path("rev").asInt()).isEqualTo(rev);
        // a range delete is steps and takes their checks along
        JsonNode r = call("{\"op\":\"delete\",\"from\":1,\"to\":2}");
        assertThat(r.path("did").asText()).isEqualTo("deleted steps 1-2 and their 2 checks");
        assertThat(r.path("steps")).hasSize(2);
        assertThat(r.path("steps").get(0).path("checks").get(0).path("n").asText()).isEqualTo("1.1");
        // moving a step takes its checks along
        r = call("{\"op\":\"move\",\"n\":1,\"to\":2}");
        assertThat(r.path("did").asText()).isEqualTo("moved step 1 to 2");
        assertThat(r.path("steps").get(1).path("checks")).hasSize(1);
    }

    @Test
    void aCheckIsNotMovedAndTheRefusalSaysToDeleteAndInsert() throws Exception {
        fourWithChecks();
        assertThat(why("{\"op\":\"move\",\"n\":\"3.1\",\"to\":\"1.2\"}"))
            .contains("check 3.1 cannot be moved", "checks cannot be moved; delete and insert").doesNotContain("must be an integer");
        assertThat(why("{\"op\":\"move\",\"n\":3,\"to\":\"1.2\"}")).contains("move takes a step to a step number");
    }

    @Test
    void oneUndoTakesBackARememberMadeWhileRecording() throws Exception {
        session.reader(readings(ordersOf("ORD-1640", "Bea"), ordersOf("ORD-1640", "Bea", "ORD-1641", "Ada")));
        session.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Copy\"}}"));
        backend.verbs.remembered = "ORD-1641";
        call("{\"op\":\"remember\",\"target\":{\"role\":\"label\",\"name\":\"Number\"},\"as\":\"number\"}");
        assertThat(sink.undoLabel()).isEqualTo("remember of check 1.1 by the agent");
        assertThat(call("{\"op\":\"undo\"}").path("did").asText()).isEqualTo("undid the remember of check 1.1 by the agent");
        assertThat(session.recordedSteps()).hasSize(1);
        assertThat(session.runValues().resolve(Refs.scan("${number}").get(0))).isEmpty();
        // the name is free again
        assertThat(call("{\"op\":\"remember\",\"target\":{\"role\":\"label\",\"name\":\"Number\"},\"as\":\"number\"}")
            .path("did").asText()).isEqualTo("remembered ORD-1641 as number (check 1.1)");
    }

    @Test
    void editAndInsertOfACheckTakeTheFileFormWithoutAction() throws Exception {
        fourWithChecks();
        JsonNode r = call("{\"op\":\"edit\",\"n\":\"1.1\",\"step\":{\"target\":{\"id\":\"status\"},\"text\":\"done\"}}");
        assertThat(r.at("/steps/0/checks/0/step/action").asText()).isEqualTo("expect");
        assertThat(r.at("/steps/0/checks/0/step/text").asText()).isEqualTo("done");
        r = call("{\"op\":\"insert\",\"at\":\"2.1\",\"step\":{\"target\":{\"id\":\"status\"},\"state\":[\"!disabled\"]}}");
        assertThat(r.at("/steps/1/checks/0/step/action").asText()).isEqualTo("expect");
        // at 1, what an expect checks makes the check-only step 1, as in the file
        r = call("{\"op\":\"insert\",\"at\":1,\"step\":{\"target\":{\"id\":\"status\"},\"shown\":true}}");
        assertThat(r.path("did").asText()).isEqualTo("inserted check 1.1");
        assertThat(r.at("/steps/0/checks/0/step/action").asText()).isEqualTo("expect");
        assertThat(r.at("/steps/0/step").isMissingNode()).isTrue();
        // an action still names its type
        assertThat(why("{\"op\":\"edit\",\"n\":2,\"step\":{\"target\":{\"id\":\"status\"}}}")).contains("needs an 'action'");
    }

    @Test
    void deleteOfAStepTakesItsChecksAndDeleteOfACheckOnlyThat() throws Exception {
        fourWithChecks();
        JsonNode r = call("{\"op\":\"delete\",\"n\":\"1.1\"}");
        assertThat(r.path("did").asText()).isEqualTo("deleted check 1.1");
        assertThat(r.path("steps")).hasSize(4);
        assertThat(r.path("steps").get(0).path("checks")).hasSize(1);
        assertThat(r.path("steps").get(0).path("checks").get(0).path("n").asText()).isEqualTo("1.1");
        r = call("{\"op\":\"delete\",\"n\":1}");
        assertThat(r.path("did").asText()).isEqualTo("deleted step 1 and its 1 check");
        assertThat(r.path("steps")).hasSize(3);
        assertThat(ids(r)).isEqualTo("bcd");
        assertThat(r.path("steps").get(1).path("checks").get(0).path("n").asText()).isEqualTo("2.1");
        DriverException e = refused("{\"op\":\"delete\",\"n\":\"2.5\"}");
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("no check 2.5");
        assertThat(refused("{\"op\":\"delete\",\"n\":\"seven\"}").reason()).isEqualTo(Reason.BAD_INPUT);
        // the person's delete follows the same rule, by record id
        List<Inspector.StepLine> lines = sink.steps();
        assertThat(lines.get(1).label).isEqualTo("2");
        assertThat(lines.get(2).label).isEqualTo("2.1");
        assertThat(sink.delete(lines.get(2).id).line).isEqualTo("deleted check 2.1");
        assertThat(sink.delete(lines.get(1).id).line).isEqualTo("deleted step 2");
    }

    @Test
    void insertTakesAStepPositionOrACheckLabel() throws Exception {
        four();
        JsonNode r = call("{\"op\":\"insert\",\"at\":2,\"step\":{\"action\":\"click\",\"target\":{\"id\":\"x\"}}}");
        assertThat(r.path("did").asText()).isEqualTo("inserted at step 2");
        assertThat(ids(r)).isEqualTo("axbcd");
        r = call("{\"op\":\"insert\",\"at\":\"2.1\",\"step\":" + CHECK + "}");
        assertThat(r.path("did").asText()).isEqualTo("inserted check 2.1");
        assertThat(r.path("steps").get(1).path("checks")).hasSize(1);
        r = call("{\"op\":\"insert\",\"at\":\"2.1\",\"step\":" + CHECK.replace("ok", "first") + "}");
        assertThat(r.path("steps").get(1).path("checks").get(0).path("step").path("text").asText()).isEqualTo("first");
        int rev = r.path("rev").asInt();
        assertThat(refused("{\"op\":\"insert\",\"at\":3,\"step\":" + CHECK + "}").getMessage())
                .contains("a check goes under a step").contains("\"3.1\"");
        assertThat(refused("{\"op\":\"insert\",\"at\":\"2.9\",\"step\":" + CHECK + "}").getMessage()).contains("no place for check 2.9");
        assertThat(refused("{\"op\":\"insert\",\"at\":\"2.1\",\"step\":{\"action\":\"click\",\"target\":{\"id\":\"q\"}}}")
                .getMessage()).contains("an action goes at a step position");
        assertThat(refused("{\"op\":\"insert\",\"at\":\"x\",\"step\":" + CHECK + "}").reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(call("{\"op\":\"list\"}").path("rev").asInt()).isEqualTo(rev);
        // at 1 a check makes the check-only step 1
        r = call("{\"op\":\"insert\",\"at\":1,\"step\":" + CHECK + "}");
        assertThat(r.path("steps")).hasSize(6);
        assertThat(r.path("steps").get(0).has("line")).isFalse();
    }

    @Test
    void replayAndRedoCountSteps() throws Exception {
        fakeReset();
        fourWithChecks();
        backend.verbs.calls.clear();
        JsonNode r = call("{\"op\":\"replay\",\"from\":1,\"to\":1}");
        assertThat(r.path("passed").asBoolean()).isTrue();
        assertThat(r.path("reset").asBoolean()).isTrue();
        assertThat(r.path("ran").asInt()).isEqualTo(1);      // one step: the action and its two checks
        assertThat(r.path("steps").get(0).path("n").asInt()).isEqualTo(1);
        assertThat(r.path("steps").get(1).path("n").asText()).isEqualTo("1.1");
        assertThat(r.path("steps").get(2).path("n").asText()).isEqualTo("1.2");
        backend.verbs.calls.clear();
        r = call("{\"op\":\"replay\",\"from\":3,\"to\":3}");
        assertThat(r.path("reset").asBoolean()).isFalse();
        assertThat(r.path("ran").asInt()).isEqualTo(1);
        assertThat(r.path("total").asInt()).isEqualTo(1);
        assertThat(r.path("steps").get(1).path("n").asText()).isEqualTo("3.1");
        assertThat(refused("{\"op\":\"replay\",\"from\":3,\"to\":5}").getMessage()).contains("no step 5");
        assertThat(refused("{\"op\":\"replay\",\"from\":3,\"to\":2}").getMessage()).contains("before");
        // redo from step 3 cuts steps 3 and 4 with step 3's check, replays step 1 and 2 with theirs, then records on
        call("{\"op\":\"stop\"}");
        backend.verbs.calls.clear();
        r = call("{\"op\":\"redo\",\"from\":3}");
        assertThat(r.path("passed").asBoolean()).isTrue();
        assertThat(r.path("ran").asInt()).isEqualTo(2);
        assertThat(r.path("list").path("steps")).hasSize(2);
        assertThat(r.path("list").path("steps").get(0).path("checks")).hasSize(2);
        // the number after the last step continues
        call("{\"op\":\"stop\"}");
        r = call("{\"op\":\"redo\",\"from\":3}");
        assertThat(r.path("recording").asBoolean()).isTrue();
        assertThat(refused("{\"op\":\"redo\",\"from\":4}").getMessage()).contains("no step 4");
        assertThat(refused("{\"op\":\"redo\",\"from\":\"1.1\"}").reason()).isEqualTo(Reason.BAD_INPUT);
    }

    @Test
    void theListTheReportAndTheFileAgreeOnNumbers() throws Exception {
        fakeReset();
        four(true);
        call("{\"op\":\"insert\",\"at\":\"2.1\",\"step\":" + CHECK + "}");
        call("{\"op\":\"insert\",\"at\":\"2.2\",\"step\":" + CHECK + "}");
        call("{\"op\":\"group\",\"from\":3,\"to\":4,\"say\":\"The last two\"}");
        call("{\"op\":\"stop\"}");
        JsonNode list = call("{\"op\":\"list\"}");
        List<String> listed = new java.util.ArrayList<String>();
        for (JsonNode st : list.path("steps")) {
            listed.add(st.path("n").asText());
            for (JsonNode c : st.path("checks")) listed.add(c.path("n").asText());
        }
        assertThat(listed).containsExactly("1", "2", "2.1", "2.2", "3", "4");
        call("{\"op\":\"export\",\"as\":\"recorded\",\"name\":\"agree\",\"dir\":\"" + quoted(dir) + "\"}");
        JsonNode file = mapper.readTree(Files.readAllBytes(dir.resolve("agree.json")));
        List<String> nested = new java.util.ArrayList<String>();
        int n = 0;
        for (JsonNode el : file.path("steps")) {
            if (el.has("steps")) {
                for (JsonNode inner : el.path("steps")) nested.add(String.valueOf(++n));
            } else {
                n++;
                nested.add(String.valueOf(n));
                int k = 0;
                for (JsonNode c : el.path("checks")) nested.add(n + "." + (++k));
            }
        }
        assertThat(nested).containsExactly("1", "2", "2.1", "2.2", "3", "4");
        session.recordedDir(dir, true);
        JsonNode run = mapper.readTree(session.runRecordedTest(mapper.readTree("{\"name\":\"agree\"}")));
        List<String> reported = new java.util.ArrayList<String>();
        for (JsonNode st : run.path("steps")) reported.add(st.path("n").asText());
        assertThat(reported).isEqualTo(listed);
        assertThat(run.path("report").asText()).contains("[2.1]").contains("[4]");
    }

    @Test
    void exportWritesGroupsAndChecksNestedAndCommentsIntoJava() throws Exception {
        Path tests = dir.resolve("tests");
        System.setProperty(UiMcpSession.TESTS_DIR_PROP, tests.toString());
        try {
            fakeReset();
            four(true);
            call("{\"op\":\"insert\",\"at\":\"1.1\",\"step\":" + CHECK + "}");
            call("{\"op\":\"edit\",\"n\":\"1.1\",\"say\":\"The status is ok\"}");
            call("{\"op\":\"edit\",\"n\":2,\"say\":\"Open the second\"}");
            call("{\"op\":\"group\",\"from\":2,\"to\":3,\"say\":\"Pick two\"}");
            call("{\"op\":\"stop\"}");
            JsonNode written = call("{\"op\":\"export\",\"as\":\"recorded\",\"name\":\"nested\",\"dir\":\"" + quoted(dir) + "\"}");
            assertThat(written.path("steps").asInt()).isEqualTo(4);    // steps, not the 5 records
            assertThat(written.path("checks").asInt()).isEqualTo(1);
            JsonNode file = mapper.readTree(Files.readAllBytes(dir.resolve("nested.json")));
            assertThat(file.path("version").asInt()).isEqualTo(2);
            assertThat(file.path("steps")).hasSize(3);
            assertThat(file.path("steps").get(0).path("checks")).hasSize(1);
            assertThat(file.path("steps").get(0).path("checks").get(0).path("say").asText()).isEqualTo("The status is ok");
            assertThat(file.path("steps").get(1).path("say").asText()).isEqualTo("Pick two");
            assertThat(file.path("steps").get(1).path("steps")).hasSize(2);
            assertThat(file.path("steps").get(1).path("steps").get(0).path("say").asText()).isEqualTo("Open the second");
            assertThat(file.path("steps").get(2).path("target").path("id").asText()).isEqualTo("d");
            JsonNode java = call("{\"op\":\"export\",\"as\":\"java\",\"class\":\"NestedUiIT\",\"package\":\"demo\"}");
            assertThat(java.path("steps").asInt()).isEqualTo(4);
            assertThat(java.path("checks").asInt()).isEqualTo(1);
            String source = new String(Files.readAllBytes(tests.resolve("demo/NestedUiIT.java")), StandardCharsets.UTF_8);
            assertThat(source).contains("// The status is ok").contains("// Pick two").contains("// Open the second");
            assertThat(source.indexOf("// Pick two")).isLessThan(source.indexOf("// Open the second"));
        } finally {
            System.clearProperty(UiMcpSession.TESTS_DIR_PROP);
        }
    }

    @Test
    void aLoadedGroupedTestKeepsItsGroups() throws Exception {
        Flow flow = new Flow();
        flow.version = RecordedTestFiles.VERSION;
        flow.name = "grouped";
        flow.start = RecordedTestFiles.START_RESET;
        for (String id : new String[] {"a", "b", "c"}) {
            ClickVerb c = new ClickVerb();
            c.target = Locator.ofId(id);
            if (id.equals("b")) c.say = "Open b";
            flow.steps.add(c);
        }
        flow.groups.add(new io.github.kormcrete.swingui.flow.FlowGroup(1, 2, "The last two"));
        Path file = dir.resolve("grouped.json");
        Files.write(file, RecordedTestFiles.write(flow).getBytes(StandardCharsets.UTF_8));
        JsonNode list = call("{\"op\":\"load\",\"path\":\"" + quoted(file) + "\"}");
        assertThat(list.path("groups")).hasSize(1);
        assertThat(list.path("groups").get(0).path("from").asInt()).isEqualTo(2);
        assertThat(list.path("groups").get(0).path("to").asInt()).isEqualTo(3);
        assertThat(list.path("groups").get(0).path("say").asText()).isEqualTo("The last two");
        assertThat(list.path("steps").get(1).path("say").asText()).isEqualTo("Open b");
        // saved back, the groups stay
        call("{\"op\":\"export\",\"as\":\"recorded\",\"name\":\"grouped\",\"dir\":\"" + quoted(dir) + "\"}");
        assertThat(RecordedTestFiles.read(file).groups).hasSize(1);
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
        assertThat(r.path("steps").get(1).path("action").asText()).startsWith("click(");
        assertThat(r.path("steps").get(1).path("passed").asBoolean()).isTrue();
        JsonNode list = call("{\"op\":\"list\"}");
        assertThat(list.path("steps")).hasSize(5);
        assertThat(list.path("steps").get(1).has("edited")).isFalse();
        assertThat(list.path("rev").asInt()).isEqualTo(r.path("rev").asInt());
    }

    @Test
    void theAgentsReplayBarCountsStepsAndNamesTheCheckAfterThem() throws Exception {
        session.palette(true);
        fakeReset();
        List<String> heard = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        session.recordingView(hearing(heard));
        call("{\"op\":\"start\"}");
        click("a");
        click("b");
        call("{\"op\":\"insert\",\"at\":\"1.1\",\"step\":{\"action\":\"expect\",\"target\":{\"id\":\"status\"},\"text\":\"ok\"}}");
        call("{\"op\":\"stop\"}");
        heard.clear();
        call("{\"op\":\"replay\"}");
        List<String> bars = new java.util.ArrayList<String>();
        for (String h : heard) if (h.startsWith("replaying: replaying ")) bars.add(h.substring("replaying: replaying ".length()));
        assertThat(bars).hasSize(3);
        assertThat(bars.get(0)).isEqualTo("1/2  click(#a)");
        assertThat(bars.get(1)).startsWith("1/2  check 1.1: ").doesNotContain("expect(");
        assertThat(bars.get(2)).isEqualTo("2/2  click(#b)");
    }

    @Test
    void replayEntriesAreNumberedByStepAndCheck() throws Exception {
        fakeReset();
        four();
        call("{\"op\":\"insert\",\"at\":\"1.1\",\"step\":{\"action\":\"expect\",\"target\":{\"id\":\"status\"},\"text\":\"ok\"}}");
        JsonNode r = call("{\"op\":\"replay\"}");
        assertThat(r.path("steps")).hasSize(5);
        assertThat(r.path("steps").get(0).path("n").isInt()).isTrue();
        assertThat(r.path("steps").get(0).path("n").asInt()).isEqualTo(1);
        assertThat(r.path("steps").get(1).path("n").isTextual()).isTrue();
        assertThat(r.path("steps").get(1).path("n").asText()).isEqualTo("1.1");
        assertThat(r.path("steps").get(2).path("n").asInt()).isEqualTo(2);
        assertThat(r.path("steps").get(4).path("n").asInt()).isEqualTo(4);
    }

    @Test
    void replayEntriesCarryEachStepsSentence() throws Exception {
        fakeReset();
        four();
        call("{\"op\":\"edit\",\"n\":1,\"say\":\"Open the Orders list\"}");
        call("{\"op\":\"insert\",\"at\":\"1.1\",\"step\":{\"action\":\"expect\",\"target\":{\"id\":\"status\"},\"text\":\"ok\"}}");
        JsonNode r = call("{\"op\":\"replay\",\"from\":1,\"to\":1}");
        assertThat(r.path("steps").get(0).path("say").asText()).isEqualTo("Open the Orders list");
        assertThat(r.path("steps").get(1).path("n").asText()).isEqualTo("1.1");
        assertThat(r.path("steps").get(1).has("say")).isFalse();
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
    void whileTheAgentRunsARecordedTestThePalettesEditsSaySo() throws Exception {
        fakeReset();
        four();
        call("{\"op\":\"stop\"}");   // a running recording keeps a test run out
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
        final java.util.concurrent.CountDownLatch inStep = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        backend.verbs.calls.clear();
        backend.verbs.during = () -> {
            if (inStep.getCount() > 0) {
                inStep.countDown();
                try {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        Thread run = new Thread(() -> {
            try {
                session.runRecordedTest(mapper.readTree("{\"path\":" + mapper.writeValueAsString(file.toString()) + "}"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        run.start();
        try {
            assertThat(inStep.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(sink.delete(id(0)).line).isEqualTo("the agent is running recorded test two clicks");
        } finally {
            release.countDown();
            run.join(10_000);
        }
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
        call("{\"op\":\"insert\",\"at\":\"3.1\",\"step\":{\"action\":\"expect\",\"target\":{\"id\":\"status\"},\"text\":\"ok\"}}");
        JsonNode r = call("{\"op\":\"replay\"}");
        assertThat(r.path("passed").asBoolean()).isTrue();
        assertThat(call("{\"op\":\"list\"}").path("steps").get(2).path("checks").get(0).has("edited")).isFalse();
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

    // ---- row: this value or by position -------------------------------------------------------

    private static final String ROW_CLICK = "{\"target\":{\"role\":\"table\",\"name\":\"Orders\",\"row\":{\"Order\":\"ORD-1642\"},"
            + "\"column\":\"Customer\"}}";

    private static RowChoice ord1642() {
        return new RowChoice(2, java.util.Collections.<String, Object>singletonMap("Order", "ORD-1642"));
    }

    @Test
    void rowSwitchesBetweenValueAndPosition() throws Exception {
        backend.verbs.outcome.row = ord1642();
        session.verb("click", mapper.readTree(ROW_CLICK));
        JsonNode step = call("{\"op\":\"list\"}").path("steps").get(0);
        assertThat(step.path("row").path("by").asText()).isEqualTo("value");
        assertThat(step.path("row").path("value").toString()).isEqualTo("{\"Order\":\"ORD-1642\"}");
        assertThat(step.path("row").path("index").asInt()).isEqualTo(2);
        assertThat(step.has("edited")).isFalse();

        JsonNode r = call("{\"op\":\"row\",\"n\":1,\"by\":\"position\"}");
        assertThat(r.path("did").asText()).isEqualTo("changed the row of step 1");
        step = r.path("steps").get(0);
        assertThat(step.path("step").path("target").path("row").toString()).isEqualTo("{\"index\":2}");
        assertThat(step.path("row").path("by").asText()).isEqualTo("position");
        assertThat(step.path("edited").asBoolean()).isTrue();

        r = call("{\"op\":\"row\",\"n\":1,\"by\":\"value\"}");
        step = r.path("steps").get(0);
        assertThat(step.path("step").path("target").path("row").toString()).isEqualTo("{\"Order\":\"ORD-1642\"}");
        assertThat(step.path("row").path("by").asText()).isEqualTo("value");
        assertThat(step.path("step").path("target").path("column").asText()).isEqualTo("Customer");
    }

    @Test
    void thisValueComesBackAsTheColumnTheStepWasRecordedWith() throws Exception {
        // the table's first unique column is Order; the step names its row by the Customer column
        backend.verbs.outcome.row = new RowChoice(0, java.util.Collections.<String, Object>singletonMap("Order", "ORD-1640"));
        session.verb("click", mapper.readTree("{\"target\":{\"role\":\"table\",\"name\":\"Orders\","
                + "\"row\":{\"Customer\":\"Ada Shop Ltd\"},\"column\":\"Status\"}}"));
        JsonNode step = call("{\"op\":\"list\"}").path("steps").get(0);
        assertThat(step.path("row").path("value").toString()).isEqualTo("{\"Customer\":\"Ada Shop Ltd\"}");

        call("{\"op\":\"row\",\"n\":1,\"by\":\"position\"}");
        step = call("{\"op\":\"list\"}").path("steps").get(0);
        assertThat(step.path("row").path("value").toString()).isEqualTo("{\"Customer\":\"Ada Shop Ltd\"}");
        JsonNode r = call("{\"op\":\"row\",\"n\":1,\"by\":\"value\"}");
        assertThat(r.path("steps").get(0).path("step").path("target").path("row").toString())
                .isEqualTo("{\"Customer\":\"Ada Shop Ltd\"}");
    }

    @Test
    void aLoadedStepByAnotherColumnKeepsItWhenItLearnsItsRow() throws Exception {
        fakeReset();
        Flow flow = new Flow();
        flow.version = RecordedTestFiles.VERSION;
        flow.name = "row click";
        flow.start = RecordedTestFiles.START_RESET;
        ClickVerb c = new ClickVerb();
        c.target = Locator.of("table", "Orders");
        c.target.row = java.util.Collections.<String, Object>singletonMap("Customer", "Ada Shop Ltd");
        flow.steps.add(c);
        Path file = dir.resolve("row.json");
        Files.write(file, RecordedTestFiles.write(flow).getBytes(StandardCharsets.UTF_8));
        session.loadRecordedTest(file);

        backend.verbs.outcome.row = new RowChoice(0, java.util.Collections.<String, Object>singletonMap("Order", "ORD-1640"));
        assertThat(call("{\"op\":\"replay\"}").path("passed").asBoolean()).isTrue();
        call("{\"op\":\"row\",\"n\":1,\"by\":\"position\"}");
        JsonNode r = call("{\"op\":\"row\",\"n\":1,\"by\":\"value\"}");
        assertThat(r.path("steps").get(0).path("step").path("target").path("row").toString())
                .isEqualTo("{\"Customer\":\"Ada Shop Ltd\"}");
    }

    @Test
    void aValueColumnNamedIndexIsStillThisValue() throws Exception {
        backend.verbs.outcome.row = new RowChoice(2, java.util.Collections.<String, Object>singletonMap("index", "A-7"));
        session.verb("click", mapper.readTree("{\"target\":{\"role\":\"table\",\"row\":{\"index\":\"A-7\"},\"column\":\"Customer\"}}"));
        call("{\"op\":\"row\",\"n\":1,\"by\":\"position\"}");
        call("{\"op\":\"row\",\"n\":1,\"by\":\"value\"}");
        assertThat(sink.undoLabel()).isEqualTo("row of step 1 by value by the agent");
        call("{\"op\":\"undo\"}");
        assertThat(sink.undoLabel()).isEqualTo("row of step 1 by position by the agent");
    }

    @Test
    void aCellCheckRowSwitchesToo() throws Exception {
        click("save");
        backend.verbs.outcome.row = new RowChoice(0, java.util.Collections.<String, Object>singletonMap("Order", "ORD-1640"));
        session.verb("expect", mapper.readTree("{\"target\":{\"role\":\"table\",\"name\":\"Orders\"},"
                + "\"cell\":{\"row\":{\"Order\":\"ORD-1640\"},\"column\":\"Status\",\"expected\":\"Open\"}}"));
        JsonNode r = call("{\"op\":\"row\",\"n\":\"1.1\",\"by\":\"position\"}");
        JsonNode check = r.path("steps").get(0).path("checks").get(0);
        assertThat(check.path("step").path("cell").path("row").toString()).isEqualTo("{\"index\":0}");
        assertThat(check.path("row").path("by").asText()).isEqualTo("position");
        assertThat(check.path("edited").asBoolean()).isTrue();
        assertThat(r.path("steps").get(0).has("edited")).isFalse();
        assertThat(sink.undoLabel()).isEqualTo("row of check 1.1 by position by the agent");
    }

    @Test
    void rowWithoutAKnownPositionSaysReplayIt() throws Exception {
        fakeReset();
        call("{\"op\":\"insert\",\"at\":1,\"step\":" + ROW_CLICK.replace("{\"target\"", "{\"action\":\"click\",\"target\"") + "}");
        DriverException e = catchThrowableOfType(() -> call("{\"op\":\"row\",\"n\":1,\"by\":\"position\"}"), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("step 1's row by position is not known yet: replay the step once");
        JsonNode step = call("{\"op\":\"list\"}").path("steps").get(0);
        assertThat(step.path("row").path("by").asText()).isEqualTo("value");
        assertThat(step.path("row").has("index")).isFalse();
        assertThat(step.path("row").path("value").toString()).isEqualTo("{\"Order\":\"ORD-1642\"}");

        backend.verbs.outcome.row = ord1642();
        assertThat(call("{\"op\":\"replay\"}").path("passed").asBoolean()).isTrue();
        step = call("{\"op\":\"list\"}").path("steps").get(0);
        assertThat(step.path("row").path("index").asInt()).isEqualTo(2);
        JsonNode r = call("{\"op\":\"row\",\"n\":1,\"by\":\"position\"}");
        assertThat(r.path("steps").get(0).path("step").path("target").path("row").toString()).isEqualTo("{\"index\":2}");
    }

    @Test
    void aLoadedStepLearnsItsRowOnAPassingReplay() throws Exception {
        fakeReset();
        Flow flow = new Flow();
        flow.version = RecordedTestFiles.VERSION;
        flow.name = "row click";
        flow.start = RecordedTestFiles.START_RESET;
        ClickVerb c = new ClickVerb();
        c.target = Locator.of("table", "Orders");
        c.target.row = java.util.Collections.<String, Object>singletonMap("Order", "ORD-1642");
        flow.steps.add(c);
        Path file = dir.resolve("row.json");
        Files.write(file, RecordedTestFiles.write(flow).getBytes(StandardCharsets.UTF_8));
        session.loadRecordedTest(file);
        assertThat(call("{\"op\":\"list\"}").path("steps").get(0).path("row").has("index")).isFalse();
        String revBefore = call("{\"op\":\"list\"}").path("rev").asText();

        backend.verbs.outcome.row = ord1642();
        // the palette's own replay of the step teaches it too
        assertThat(sink.replay(0).ok).isTrue();
        JsonNode list = call("{\"op\":\"list\"}");
        assertThat(list.path("steps").get(0).path("row").path("index").asInt()).isEqualTo(2);
        assertThat(list.path("rev").asText()).isNotEqualTo(revBefore);
        assertThat(call("{\"op\":\"row\",\"n\":1,\"by\":\"position\"}").path("did").asText()).contains("row of step 1");
    }

    @Test
    void rowOnAStepWithoutARowIsRefused() throws Exception {
        session.verb("click", mapper.readTree("{\"target\":{\"id\":\"save\"}}"));
        session.verb("menu", mapper.readTree("{\"path\":[\"File\",\"Open\"]}"));
        DriverException e = catchThrowableOfType(() -> call("{\"op\":\"row\",\"n\":2,\"by\":\"value\"}"), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("step 2 (menu) has no row");
        e = catchThrowableOfType(() -> call("{\"op\":\"row\",\"n\":1,\"by\":\"value\"}"), DriverException.class);
        assertThat(e.getMessage()).contains("step 1 (click) has no row");
        e = catchThrowableOfType(() -> call("{\"op\":\"row\",\"n\":1,\"by\":\"sideways\"}"), DriverException.class);
        assertThat(e.getMessage()).contains("'by' must be value or position");
    }

    @Test
    void rowIsUndoableAndMarksTheStepEdited() throws Exception {
        fakeReset();
        backend.verbs.outcome.row = ord1642();
        session.verb("click", mapper.readTree(ROW_CLICK));
        call("{\"op\":\"row\",\"n\":1,\"by\":\"position\"}");
        assertThat(sink.undoLabel()).isEqualTo("row of step 1 by position by the agent");
        JsonNode r = call("{\"op\":\"undo\"}");
        JsonNode step = r.path("steps").get(0);
        assertThat(step.path("step").path("target").path("row").toString()).isEqualTo("{\"Order\":\"ORD-1642\"}");
        assertThat(step.has("edited")).isFalse();
        assertThat(step.path("row").path("index").asInt()).isEqualTo(2);   // what was learned survives
        call("{\"op\":\"row\",\"n\":1,\"by\":\"position\"}");
        call("{\"op\":\"replay\"}");
        assertThat(call("{\"op\":\"list\"}").path("steps").get(0).has("edited")).isFalse();
    }

    @Test
    void theListShowsEachRowStepsMeaning() throws Exception {
        backend.verbs.outcome.row = new RowChoice(3, null);   // no unique column
        session.verb("click", mapper.readTree("{\"target\":{\"role\":\"table\",\"row\":{\"index\":3}}}"));
        session.verb("click", mapper.readTree("{\"target\":{\"id\":\"save\"}}"));
        List<Inspector.StepLine> lines = sink.steps();
        assertThat(lines.get(0).row).isNotNull();
        assertThat(lines.get(0).row.by).isEqualTo("position");
        assertThat(lines.get(0).row.index).isEqualTo(3);
        assertThat(lines.get(0).row.indexKnown).isTrue();
        assertThat(lines.get(0).row.valueKnown).isFalse();
        JsonNode steps = call("{\"op\":\"list\"}").path("steps");
        assertThat(steps.get(0).path("row").path("by").asText()).isEqualTo("position");
        assertThat(steps.get(0).path("row").has("value")).isFalse();
        assertThat(steps.get(1).has("row")).isFalse();
        // the table has no value to name the row by
        DriverException e = catchThrowableOfType(() -> call("{\"op\":\"row\",\"n\":1,\"by\":\"value\"}"), DriverException.class);
        assertThat(e.getMessage()).contains("step 1's row by value is not known");
    }
    // ---- do steps: one recorded step, a version 3 file, checked when a file is loaded -----------

    private static final String STEPS_CLASS = "io.github.kormcrete.orderdesk.uitest.OrderDeskSteps";
    private static final String ADD = "{\"action\":\"do\",\"step\":\"addToCart\",\"params\":{\"product\":\"Gel pen, black\"}}";

    /** A session over plugins whose order-desk plugin offers addToCart (a typed call), cartTotalIs (a check) and {@code more}. */
    private UiMcpSession orderDesk(Plugins plugins, DomainStep... more) {
        List<DomainStep> steps = new java.util.ArrayList<DomainStep>(Arrays.asList(
                DomainStep.act("addToCart", "Add {product} to the cart")
                        .param("product", ParamType.TEXT)
                        .code((ui, p) -> {
                            ui.table().row("Product", p.text("product")).cell("Product").click();
                            ui.button("Add to cart").click();
                        })
                        .java(STEPS_CLASS, "addToCart(ui, {product})")
                        .build(),
                DomainStep.check("cartTotalIs", "The cart total is {total}")
                        .param("total", ParamType.TEXT)
                        .steps("{\"action\":\"expect\",\"target\":{\"role\":\"label\",\"name\":\"Total\"},\"text\":\"{total}\"}")
                        .build()));
        steps.addAll(Arrays.asList(more));
        plugins.add("order-desk", Paths.get("order-desk")).ready(new PluginParts(() -> steps), "ok");
        UiMcpSession s = new UiMcpSession(plugins, new UiMcpToolsTest.FakeInventory(), mapper);
        s.folderMemory(FolderMemory.inMemory());
        s.recordedDir(dir, true);
        s.appReset(new UiMcpSession.AppReset() {
            @Override public boolean defined() { return false; }
            @Override public void run() { }
        });
        s.personBusy = () -> null;
        s.startRecording(true);
        return s;
    }

    @Test
    void theAgentsDoStepIsRecordedAsOneStep() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs));

        JsonNode added = mapper.readTree(s.doAction(mapper.readTree(ADD)));
        JsonNode total = mapper.readTree(s.doAction(mapper.readTree("{\"action\":\"do\",\"step\":\"cartTotalIs\","
                + "\"params\":{\"total\":\"4.99\"},\"say\":\"The total is right\"}")));

        assertThat(backend.verbs.calls).hasSize(3);   // the bodies ran their steps ...
        assertThat(added.has("notRecorded")).isFalse();
        assertThat(added.path("recorded")).isEqualTo(mapper.readTree("{\"action\":\"do\",\"say\":\"Add Gel pen, black to "
                + "the cart\",\"step\":\"order-desk:addToCart\",\"params\":{\"product\":\"Gel pen, black\"}}"));
        assertThat(total.path("recorded").path("say").asText()).isEqualTo("The total is right");
        assertThat(total.path("recorded").path("check").asBoolean()).isTrue();
        List<RecordedStep> steps = s.recordedSteps();   // ... and each do step is one record, the check under its step
        assertThat(steps).hasSize(2).allMatch(r -> r.author == RecordedStep.Author.AGENT);
        JsonNode list = mapper.readTree(s.recording(mapper.readTree("{\"op\":\"list\"}")));
        assertThat(list.path("steps")).hasSize(1);
        assertThat(list.path("steps").get(0).path("java").asText()).isEqualTo("OrderDeskSteps.addToCart(ui, \"Gel pen, black\");");
        assertThat(list.path("steps").get(0).path("checks").get(0).path("n").asText()).isEqualTo("1.1");

        System.setProperty(UiMcpSession.TESTS_DIR_PROP, dir.resolve("tests").toString());
        try {
            String source = mapper.readTree(s.exportTest("AddPenUiIT", null, "demo", false)).path("source").asText();
            assertThat(source).contains("import " + STEPS_CLASS + ";\n")
                    .contains("        OrderDeskSteps.addToCart(ui, \"Gel pen, black\");\n"
                            + "        // The total is right\n"
                            + "        assertThat(ui.label(\"Total\")).hasText(\"4.99\");\n");
        } finally {
            System.clearProperty(UiMcpSession.TESTS_DIR_PROP);
        }
        JsonNode file = mapper.readTree(s.exportRecordedTest("add-pen", quoted(dir), null, false)).path("test");
        assertThat(file.path("version").asInt()).isEqualTo(3);
        assertThat(file.path("requires").toString()).isEqualTo("[\"order-desk\"]");
        assertThat(file.path("steps").get(0).path("checks").get(0).path("step").asText()).isEqualTo("order-desk:cartTotalIs");
        // the file runs again: its do steps through the plugin
        JsonNode run = mapper.readTree(s.runRecordedTest(mapper.readTree("{\"name\":\"add-pen\"}")));
        assertThat(run.path("passed").asBoolean()).as(run.toString()).isTrue();
        assertThat(run.path("steps").get(0).path("action").asText()).isEqualTo("Add Gel pen, black to the cart");
    }

    private static DomainStep checkOutWithPo() {
        return DomainStep.act("checkOutWithPo", "Check out with PO number {po}").param("po", ParamType.TEXT)
                .steps("{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Checkout…\"}}",
                        "{\"action\":\"click\",\"target\":{\"window\":\"dialog\",\"role\":\"button\",\"name\":\"Next\"}}",
                        "{\"action\":\"click\",\"target\":{\"window\":\"dialog\",\"role\":\"button\",\"name\":\"Next\"}}",
                        "{\"action\":\"type\",\"target\":{\"window\":\"dialog\",\"role\":\"text\",\"name\":\"PO number\"},\"text\":\"{po}\"}",
                        "{\"action\":\"click\",\"target\":{\"window\":\"dialog\",\"role\":\"button\",\"name\":\"Next\"}}")
                .build();
    }

    @Test
    void aDomainStepThatFailsPartWayRecordsTheStepsThatRanAsPlainSteps() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs), checkOutWithPo());
        backend.verbs.during = () -> backend.verbs.failure = backend.verbs.calls.size() == 4
                ? new DriverException(Reason.NOT_FOUND, "nothing matches text 'PO number' in dialog") : null;
        JsonNode r = mapper.readTree(s.doAction(mapper.readTree(
                "{\"action\":\"do\",\"step\":\"checkOutWithPo\",\"params\":{\"po\":\"PO-2201\"}}")));
        assertThat(r.path("passed").asBoolean()).isFalse();
        assertThat(s.recordedSteps()).extracting(x -> x.action.describe()).containsExactly(
                "click(button 'Checkout…')", "click(button 'Next' in dialog)", "click(button 'Next' in dialog)");
        assertThat(s.recordedSteps()).allMatch(x -> x.author == RecordedStep.Author.AGENT);
        assertThat(r.path("recordedPart")).hasSize(3);
        assertThat(r.path("recordedPartNote").asText()).startsWith("the domain step failed at its step 4 (")
                .endsWith("); its steps 1-3 ran and are recorded as plain steps 1-3, so the recording matches the screen");
        backend.verbs.during = () -> { };
        backend.verbs.failure = null;
        assertThat(mapper.readTree(s.doAction(mapper.readTree(ADD))).has("recorded")).isTrue();   // the next step follows them
        assertThat(s.recordedSteps()).hasSize(4);
    }

    @Test
    void aFailedDomainCheckRecordsNothing() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs));
        backend.verbs.failure = new DriverException(Reason.EXPECTATION, "expected text '4.99' but was '5.00'");
        JsonNode r = mapper.readTree(s.doAction(mapper.readTree(
                "{\"action\":\"do\",\"step\":\"cartTotalIs\",\"params\":{\"total\":\"4.99\"}}")));
        assertThat(r.path("passed").asBoolean()).isFalse();
        assertThat(r.has("recordedPart")).isFalse();
        assertThat(s.recordedSteps()).isEmpty();
    }

    @Test
    void aSubStepThatCannotBeKeptEndsWhatIsKeptAndSaysWhy() throws Exception {
        // step 2 names a row by three keys: it has no Java form, so it cannot be recorded raw
        DomainStep d = DomainStep.act("addAndPay", "Add and pay")
                .steps("{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Add to cart\"}}",
                        "{\"action\":\"click\",\"target\":{\"role\":\"table\",\"row\":{\"SKU\":\"PEN-GEL-BLK\",\"Product\":\"Gel pen, black\",\"Price\":\"€1.20\"},\"column\":\"Product\"}}",
                        "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Pay\"}}").build();
        UiMcpSession s = orderDesk(new Plugins(backend.verbs), d);
        backend.verbs.during = () -> backend.verbs.failure = backend.verbs.calls.size() == 3
                ? new DriverException(Reason.NOT_FOUND, "no button 'Pay'") : null;
        JsonNode r = mapper.readTree(s.doAction(mapper.readTree("{\"action\":\"do\",\"step\":\"addAndPay\"}")));
        assertThat(s.recordedSteps()).hasSize(1);
        assertThat(r.path("recordedPartNote").asText()).contains("its step 1 ran and is recorded as plain step 1")
                .contains("its step 2 ran but is not recorded (").contains("recording redo {\"from\":1}");
    }

    @Test
    void aDomainStepThatOpensWithACheckAndThenTypesASecretSendsRedoPastTheStepBeforeIt() throws Exception {
        // a sign-in step: it checks the dialog, then types the password (masked: it cannot be kept), then fails
        DomainStep d = DomainStep.act("signIn", "Sign in")
                .steps("{\"action\":\"expect\",\"target\":{\"role\":\"label\",\"name\":\"Sign in\"},\"text\":\"Sign in\"}",
                        "{\"action\":\"type\",\"target\":{\"role\":\"text\",\"name\":\"Password\"},\"text\":\"***\"}",
                        "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"OK\"}}").build();
        UiMcpSession s = orderDesk(new Plugins(backend.verbs), d);
        assertThat(mapper.readTree(s.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Sign in…\"}}")))
                .path("passed").asBoolean()).isTrue();   // step 1: the agent's own, before the do step
        backend.verbs.during = () -> backend.verbs.failure = backend.verbs.calls.size() == 4
                ? new DriverException(Reason.NOT_FOUND, "no button 'OK'") : null;
        JsonNode r = mapper.readTree(s.doAction(mapper.readTree("{\"action\":\"do\",\"step\":\"signIn\"}")));
        assertThat(s.recordedSteps()).extracting(x -> x.action.describe()).hasSize(2)
                .startsWith("click(button 'Sign in…')");
        assertThat(r.path("recordedPartNote").asText()).isEqualTo("the domain step failed at its step 3 (click(button 'OK')); "
                + "its step 1 ran and is recorded as check 1.1, so the recording matches the screen; its step 2 ran but is "
                + "not recorded (it holds a secret): recording redo {\"from\":2} records from there again");
    }

    @Test
    void aKeptPartThatOpensWithACheckAndThenActsSaysCheckAndPlainStep() throws Exception {
        DomainStep d = DomainStep.act("signIn", "Sign in")
                .steps("{\"action\":\"expect\",\"target\":{\"role\":\"label\",\"name\":\"Sign in\"},\"text\":\"Sign in\"}",
                        "{\"action\":\"type\",\"target\":{\"role\":\"text\",\"name\":\"Password\"},\"text\":\"ada\"}",
                        "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"OK\"}}").build();
        UiMcpSession s = orderDesk(new Plugins(backend.verbs), d);
        assertThat(mapper.readTree(s.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Sign in…\"}}")))
                .path("passed").asBoolean()).isTrue();
        backend.verbs.during = () -> backend.verbs.failure = backend.verbs.calls.size() == 4
                ? new DriverException(Reason.NOT_FOUND, "no button 'OK'") : null;
        JsonNode r = mapper.readTree(s.doAction(mapper.readTree("{\"action\":\"do\",\"step\":\"signIn\"}")));
        assertThat(r.path("recordedPartNote").asText()).isEqualTo("the domain step failed at its step 3 (click(button 'OK')); "
                + "its steps 1-2 ran and are recorded as check 1.1 and plain step 2, so the recording matches the screen");
    }

    @Test
    void whenNoStepThatRanCanBeKeptTheNoteSaysTheRecordingDoesNotMatchTheScreen() throws Exception {
        // step 1 names a row by three keys: it has no Java form, so nothing of the step can be recorded raw
        DomainStep d = DomainStep.act("payForRow", "Pay for the row")
                .steps("{\"action\":\"click\",\"target\":{\"role\":\"table\",\"row\":{\"SKU\":\"PEN-GEL-BLK\",\"Product\":\"Gel pen, black\",\"Price\":\"€1.20\"},\"column\":\"Product\"}}",
                        "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Add to cart\"}}",
                        "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Pay\"}}").build();
        UiMcpSession s = orderDesk(new Plugins(backend.verbs), d);
        backend.verbs.during = () -> backend.verbs.failure = backend.verbs.calls.size() == 3
                ? new DriverException(Reason.NOT_FOUND, "no button 'Pay'") : null;
        JsonNode r = mapper.readTree(s.doAction(mapper.readTree("{\"action\":\"do\",\"step\":\"payForRow\"}")));
        assertThat(s.recordedSteps()).isEmpty();
        assertThat(r.path("recordedPart")).isEmpty();
        assertThat(r.path("recordedPartNote").asText()).isEqualTo("the domain step failed at its step 3 (click(button 'Pay')); "
                + "its steps 1-2 ran but none is recorded: its step 1 cannot be recorded as a plain step (export_test has no "
                + "Java form for it: row with more than two keys), so the recording does not match the screen: finish by "
                + "hand, or recording redo {\"from\":1} puts the app back where the recording ends and records on");

        // a tool error says the same at the end of its details
        backend.verbs.calls.clear();
        backend.verbs.during = () -> backend.verbs.failure = backend.verbs.calls.size() == 2
                ? new DriverException(Reason.BAD_INPUT, "'keys' is empty") : null;
        DriverException e = catchThrowableOfType(() -> s.doAction(mapper.readTree("{\"action\":\"do\",\"step\":\"payForRow\"}")),
                DriverException.class);
        assertThat(e.details().get(e.details().size() - 1)).startsWith("the domain step failed at its step 2 (")
                .contains("its step 1 ran but is not recorded (export_test has no Java form for it: row with more than two keys)")
                .contains("so the recording does not match the screen");
        assertThat(s.recordedSteps()).isEmpty();
    }

    @Test
    void theStepsKeptOfAFailedDomainStepDoNotFoldBackIntoIt() throws Exception {
        // its code clicked Add to cart and then threw: the steps that ran match its own pattern, yet it failed
        DomainStep d = DomainStep.act("addAndConfirm", "Add and confirm")
                .code((ui, p) -> {
                    ui.button("Add to cart").click();
                    throw new IllegalStateException("no confirmation came");
                })
                .match("{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Add to cart\"}}")
                .build();
        UiMcpSession s = orderDesk(new Plugins(backend.verbs), d);
        JsonNode r = mapper.readTree(s.doAction(mapper.readTree("{\"action\":\"do\",\"step\":\"addAndConfirm\"}")));
        assertThat(r.path("passed").asBoolean()).isFalse();
        assertThat(r.path("recordedPartNote").asText()).isEqualTo("the domain step failed after its step 1 (click(button "
                + "'Add to cart')); its step 1 ran and is recorded as plain step 1, so the recording matches the screen");
        JsonNode steps = recording(s, "{\"op\":\"list\"}").path("steps");
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).has("folded")).isFalse();
        assertThat(steps.get(0).path("keepRaw").asBoolean()).isTrue();
        assertThat(s.recordedSteps().get(0).action.describe()).isEqualTo("click(button 'Add to cart')");
        // the same click by the agent on its own folds as before
        assertThat(mapper.readTree(s.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Add to cart\"}}")))
                .path("passed").asBoolean()).isTrue();
        steps = recording(s, "{\"op\":\"list\"}").path("steps");
        assertThat(steps).hasSize(2);
        assertThat(steps.get(1).path("step").path("step").asText()).isEqualTo("order-desk:addAndConfirm");
    }

    @Test
    void aSubStepThatHoldsASecretOrDoesNotReadCannotBeKept() {
        // a secret the domain step read is masked in its kept step: recorded raw it would type the mask
        assertThat(UiMcpSession.cannotKeep("{\"action\":\"type\",\"target\":{\"role\":\"text\",\"name\":\"Password\"},"
                + "\"text\":\"***\"}")).isEqualTo("it holds a secret");
        assertThat(UiMcpSession.cannotKeep("{\"action\":\"no such step\"}")).isEqualTo("it does not read as a step");
        assertThat(UiMcpSession.cannotKeep(null)).isEqualTo("it does not read as a step");
        assertThat(UiMcpSession.cannotKeep("{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Pay\"}}")).isNull();
    }

    @Test
    void aDomainStepThatFailsWithBadInputPartWayStillRecordsWhatRan() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs), checkOutWithPo());
        backend.verbs.during = () -> backend.verbs.failure = backend.verbs.calls.size() == 3
                ? new DriverException(Reason.BAD_INPUT, "'keys' is empty") : null;
        DriverException e = catchThrowableOfType(() -> s.doAction(mapper.readTree(
                "{\"action\":\"do\",\"step\":\"checkOutWithPo\",\"params\":{\"po\":\"PO-2201\"}}")), DriverException.class);
        assertThat(e.details().get(e.details().size() - 1)).contains("its steps 1-2 ran and are recorded as plain steps 1-2");
        assertThat(s.recordedSteps()).hasSize(2);
    }

    @Test
    void aRememberEntryIsRecordedLoadedAndExportedAsARecordedTestAndAsJava() throws Exception {
        backend.verbs.remembered = "ORD-1641";
        assertThat(sink.done(step("click", Locator.of("button", "Copy"))).ok).isTrue();
        Map<String, Object> remember = step("remember", Locator.of("label", "Number"));
        remember.put("as", "number");
        Inspector.Result kept = sink.check(remember);
        assertThat(kept.ok).as(kept.line).isTrue();
        assertThat(lines()).hasSize(2);
        assertThat(sink.steps().get(1).check).isTrue();

        // the same name twice, or the run's own, is refused in plain words: nothing is recorded
        Inspector.Result twice = sink.check(remember);
        assertThat(twice.ok).isFalse();
        assertThat(twice.line).contains("'number' is already the name of check 1.1");
        remember.put("as", "run");
        assertThat(sink.check(remember).line).contains("'run' is reserved");
        assertThat(lines()).hasSize(2);
        // a free name for a suggestion: the one asked for, else numbered
        assertThat(sink.freeName("number")).isEqualTo("number2");
        assertThat(sink.freeName("total")).isEqualTo("total");

        // a recorded test keeps it, and a Java test reads the same text into a local
        JsonNode file = mapper.readTree(session.exportRecordedTest("copy", quoted(dir), null, false)).path("test");
        assertThat(file.path("version").asInt()).isEqualTo(4);
        JsonNode check = file.path("steps").get(0).path("checks").get(0);
        assertThat(check.path("action").asText()).isEqualTo("remember");
        assertThat(check.path("as").asText()).isEqualTo("number");
        JsonNode java = mapper.readTree(session.exportTest("CopyUiIT", null, "demo", false, dir.toString()));
        assertThat(new String(Files.readAllBytes(Paths.get(java.path("savedTo").asText())), StandardCharsets.UTF_8))
                .contains("        ui.button(\"Copy\").click();\n        String number = ui.label(\"Number\").shownText();\n");

        // and it loads again, the remember entry with it
        session.loadRecordedTest(dir.resolve("copy.json"));
        assertThat(session.recordedSteps()).hasSize(2);
        assertThat(lines().get(1)).contains("remember");
    }

    @Test
    void anIntParamThatUsesAnEarlierValueIsCheckedAtRunNotAtLoad() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs), DomainStep.act("setQty", "Set the quantity to {qty}")
                .param("qty", ParamType.INT)
                .steps("{\"action\":\"type\",\"target\":{\"role\":\"spinner\",\"name\":\"Qty\"},\"text\":\"{qty}\"}")
                .build());
        Path file = dir.resolve("same-qty.json");
        Files.write(file, ("{\"version\":4,\"name\":\"same-qty\",\"start\":\"reset\",\"requires\":[\"order-desk\"],\"steps\":["
                + "{\"action\":\"menu\",\"path\":[\"Orders\",\"New order\"],\"checks\":["
                + "{\"action\":\"remember\",\"target\":{\"role\":\"label\",\"name\":\"Last qty\"},\"as\":\"qty\"}]},"
                + "{\"action\":\"do\",\"step\":\"order-desk:setQty\",\"params\":{\"qty\":\"${qty}\"}}]}")
                .getBytes(StandardCharsets.UTF_8));

        backend.verbs.remembered = "3";
        JsonNode run = mapper.readTree(s.runRecordedTest(mapper.readTree("{\"path\":\"" + quoted(file) + "\"}")));
        assertThat(run.path("passed").asBoolean()).as(run.toString()).isTrue();
        assertThat(backend.verbs.calls).contains("type spinner 'Qty' 3 null null");
        assertThat(run.path("steps").get(2).path("action").asText()).isEqualTo("Set the quantity to 3");

        backend.verbs.remembered = "three";
        run = mapper.readTree(s.runRecordedTest(mapper.readTree("{\"path\":\"" + quoted(file) + "\"}")));
        assertThat(run.path("passed").asBoolean()).isFalse();
        assertThat(run.path("steps").get(2).path("message").asText())
                .isEqualTo("BAD_INPUT: param 'qty' of setQty is INT: a whole number in int range, not 'three'");
    }

    // ---- folding: the person's and the agent's raw steps become do steps ------------------------------------------

    private JsonNode recording(UiMcpSession s, String json) throws Exception {
        return mapper.readTree(s.recording(mapper.readTree(json)));
    }

    private static Map<String, Object> row(String... keysAndValues) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keysAndValues.length; i += 2) row.put(keysAndValues[i], keysAndValues[i + 1]);
        return row;
    }

    /**
     * The person adds Gel pen, black to the cart: a click on its row (recorded by the table's id and the row's SKU, the
     * cell seen with every column of its row), then Add to cart.
     */
    private static void addGelPen(Inspector.Sink person) {
        Locator cell = Locator.ofId("products");
        cell.row = row("SKU", "PEN-GEL-BLK");
        cell.column = "Product";
        Locator seen = new Locator();
        seen.role = "table";
        seen.row = row("SKU", "PEN-GEL-BLK", "Product", "Gel pen, black", "Price", "€1.20");
        seen.column = "Product";
        Map<String, Object> click = step("click", cell);
        click.put(Inspector.SEEN, seen);
        assertThat(person.done(click).ok).isTrue();
        assertThat(person.done(step("click", Locator.of("button", "Add to cart"))).ok).isTrue();
    }

    private static final String ADD_GEL_PEN = "{\"action\":\"do\",\"say\":\"Add Gel pen, black to the cart\","
            + "\"step\":\"order-desk:addToCart\",\"params\":{\"product\":\"Gel pen, black\"}}";

    @Test
    void aPersonsTwoStepsFoldIntoOneDoStep() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs));
        addGelPen(s.humanSink());

        JsonNode steps = recording(s, "{\"op\":\"list\"}").path("steps");

        assertThat(steps).hasSize(1);
        JsonNode add = steps.get(0);
        assertThat(add.path("author").asText()).isEqualTo("person");
        assertThat(add.path("step")).isEqualTo(mapper.readTree(ADD_GEL_PEN));
        assertThat(add.path("folded").asInt()).isEqualTo(2);
        assertThat(add.path("raw").get(1).asText()).isEqualTo("click(button 'Add to cart')");
        assertThat(add.path("java").asText()).isEqualTo("OrderDeskSteps.addToCart(ui, \"Gel pen, black\");");
        assertThat(s.recordedSteps()).hasSize(1);
        // the recorded test holds the do step only: none of the raw steps, nor how their elements were seen
        JsonNode file = mapper.readTree(s.exportRecordedTest("add-pen", quoted(dir), null, false)).path("test");
        assertThat(file.path("steps")).hasSize(1);
        assertThat(file.path("steps").get(0).path("step").asText()).isEqualTo("order-desk:addToCart");
        assertThat(file.toString()).doesNotContain("PEN-GEL-BLK").doesNotContain("€1.20").doesNotContain("Add to cart\"}");
    }

    @Test
    void unfoldRestoresTheRawStepsAndAddsABarrier() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs));
        Inspector.Sink person = s.humanSink();
        addGelPen(person);
        int rev = recording(s, "{\"op\":\"list\"}").path("rev").asInt();

        JsonNode unfolded = recording(s, "{\"op\":\"unfold\",\"n\":1,\"rev\":" + rev + "}");

        assertThat(unfolded.path("did").asText()).isEqualTo("unfolded step 1 into steps 1-2");
        JsonNode steps = unfolded.path("steps");
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).path("step").path("target").path("id").asText()).isEqualTo("products");
        assertThat(steps.get(0).path("keepRaw").asBoolean()).isTrue();
        assertThat(steps.get(1).path("keepRaw").asBoolean()).isTrue();
        assertThat(steps.get(0).path("author").asText()).isEqualTo("person");
        // kept raw: the same two steps again fold, the unfolded ones stay as they are
        addGelPen(person);
        steps = recording(s, "{\"op\":\"list\"}").path("steps");
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0).has("folded")).isFalse();
        assertThat(steps.get(1).has("folded")).isFalse();
        assertThat(steps.get(2).path("folded").asInt()).isEqualTo(2);
        // asked by name, they fold again: their elements are still known as they were seen
        JsonNode folded = recording(s, "{\"op\":\"fold\",\"from\":1,\"to\":2}");
        assertThat(folded.path("did").asText()).isEqualTo("folded steps 1-2 into \"Add Gel pen, black to the cart\"");
        assertThat(folded.path("steps")).hasSize(2);
        assertThat(folded.path("steps").get(0).path("step")).isEqualTo(mapper.readTree(ADD_GEL_PEN));
        assertThat(folded.path("steps").get(0).has("keepRaw")).isFalse();
    }

    @Test
    void undoTakesBackAFold() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs));
        Inspector.Sink person = s.humanSink();
        addGelPen(person);
        assertThat(recording(s, "{\"op\":\"list\"}").path("steps")).hasSize(1);

        JsonNode undone = recording(s, "{\"op\":\"undo\"}");

        // the person did not fold them: folding recognised their steps, and the agent undid it
        assertThat(undone.path("did").asText()).isEqualTo("undid the fold of steps 1-2 into \"Add Gel pen, black to the "
                + "cart\" (recognised in the person's steps)");
        JsonNode steps = undone.path("steps");
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).path("step").path("action").asText()).isEqualTo("click");
        // the steps undo brought back are kept raw: a later step does not fold them again
        assertThat(steps.get(1).path("keepRaw").asBoolean()).isTrue();
        assertThat(person.done(step("click", Locator.of("button", "Checkout…"))).ok).isTrue();
        assertThat(recording(s, "{\"op\":\"list\"}").path("steps")).hasSize(3).noneMatch(n -> n.has("folded"));
    }

    @Test
    void thePaletteHearsOfAFoldAndItsNoteTakesItBackWhileItIsTheLastEdit() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs));
        List<String> recognised = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        s.recordingView(new UiMcpSession.RecordingView() {
            @Override public void changed(String notice) { }
            @Override public void recognised(int id, String sentence, int steps) { recognised.add(id + " " + sentence + " " + steps); }
        });
        Inspector.Sink person = s.humanSink();
        addGelPen(person);
        assertThat(recording(s, "{\"op\":\"list\"}").path("steps")).hasSize(1);

        // the palette lists the fold as one line with its values and the lines of the steps it stands for
        Inspector.StepLine fold = person.steps().get(0);
        assertThat(fold.say).isEqualTo("Add Gel pen, black to the cart");
        assertThat(fold.params).containsEntry("product", "Gel pen, black");
        assertThat(fold.raw).hasSize(2);
        assertThat(fold.raw.get(1)).isEqualTo("click(button 'Add to cart')");
        assertThat(recognised).containsExactly(fold.id + " Add Gel pen, black to the cart 2");
        assertThat(person.undoLabel()).isEqualTo("fold of steps 1-2 into \"Add Gel pen, black to the cart\" (recognised in "
                + "the person's steps)");

        // the note's Undo takes back that fold, and only while it is the last edit
        int id = fold.id;
        assertThat(person.describe(id, id, "Add a pen").ok).isTrue();
        Inspector.Result late = person.undoFold(id);
        assertThat(late.ok).isFalse();
        assertThat(late.line).isEqualTo("the recording changed since: Unfold on its row takes the fold back");
        assertThat(person.undo().ok).isTrue();
        Inspector.Result undone = person.undoFold(id);
        assertThat(undone.ok).as(undone.line).isTrue();
        assertThat(undone.line).isEqualTo("fold of steps 1-2 into \"Add Gel pen, black to the cart\" (recognised in the "
                + "person's steps)");
        JsonNode steps = recording(s, "{\"op\":\"list\"}").path("steps");
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).path("keepRaw").asBoolean()).isTrue();
        assertThat(person.undoFold(id).ok).isFalse();
    }

    @Test
    void thePaletteUnfoldsAFoldByItsId() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs));
        Inspector.Sink person = s.humanSink();
        addGelPen(person);
        assertThat(recording(s, "{\"op\":\"list\"}").path("steps")).hasSize(1);
        int id = person.steps().get(0).id;

        Inspector.Result r = person.unfold(id);

        assertThat(r.ok).as(r.line).isTrue();
        assertThat(r.line).isEqualTo("unfolded step 1 into steps 1-2");
        JsonNode steps = recording(s, "{\"op\":\"list\"}").path("steps");
        assertThat(steps).hasSize(2);
        assertThat(steps.get(1).path("keepRaw").asBoolean()).isTrue();
        assertThat(person.steps().get(0).raw).isEmpty();
        // a step that is no fold, or one gone
        assertThat(person.unfold(person.steps().get(0).id).line).contains("no fold");
        assertThat(person.unfold(id).ok).isFalse();
    }

    @Test
    void theStepsSoFarSayWhatTheyCouldBecome() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs));
        Inspector.Sink person = s.humanSink();
        Locator cell = Locator.ofId("products");
        cell.row = row("SKU", "PEN-GEL-BLK");
        cell.column = "Product";
        Locator seen = new Locator();
        seen.role = "table";
        seen.row = row("SKU", "PEN-GEL-BLK", "Product", "Gel pen, black", "Price", "€1.20");
        seen.column = "Product";
        Map<String, Object> click = step("click", cell);
        click.put(Inspector.SEEN, seen);
        assertThat(person.done(click).ok).isTrue();
        recording(s, "{\"op\":\"list\"}");   // waits for folding

        assertThat(person.foldHint()).isEqualTo("… could become: Add Gel pen, black to the cart (1 of 2)");

        assertThat(person.done(step("click", Locator.of("button", "Add to cart"))).ok).isTrue();
        recording(s, "{\"op\":\"list\"}");
        assertThat(person.foldHint()).isNull();
        // a hint is about the last steps: a step that begins nothing ends it
        assertThat(person.done(click).ok).isTrue();
        recording(s, "{\"op\":\"list\"}");
        assertThat(person.foldHint()).isNotNull();
        assertThat(person.done(step("click", Locator.of("button", "Checkout…"))).ok).isTrue();
        recording(s, "{\"op\":\"list\"}");
        assertThat(person.foldHint()).isNull();
    }

    @Test
    void theCheckMenusDomainCheckIsFilledFromTheScreenAndRecordedAsADoCheck() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs));
        Inspector.Sink person = s.humanSink();
        addGelPen(person);
        Map<String, Object> reads = new LinkedHashMap<String, Object>();
        reads.put("action", "expect");
        reads.put("target", Locator.of("label", "Total"));
        reads.put("text", "€31.32");
        reads.put(Inspector.ROW_INDEX, null);
        Map<String, Object> enabled = new LinkedHashMap<String, Object>();
        enabled.put("action", "expect");
        enabled.put("target", Locator.of("label", "Total"));
        enabled.put("state", java.util.Collections.singletonList("!disabled"));

        List<Inspector.DomainCheck> offered = person.domainChecks(Arrays.asList(reads, enabled), null);

        // the plugin's check whose pattern is that one check, with the value the screen shows; never an act step
        assertThat(offered).extracting(c -> c.sentence).containsExactly("The cart total is €31.32");
        assertThat((JsonNode) mapper.valueToTree(offered.get(0).step)).isEqualTo(mapper.readTree(
                "{\"action\":\"do\",\"step\":\"order-desk:cartTotalIs\",\"params\":{\"total\":\"€31.32\"}}"));
        Inspector.Result checked = person.check(offered.get(0).step);
        assertThat(checked.ok).as(checked.line).isTrue();
        JsonNode list = recording(s, "{\"op\":\"list\"}");
        JsonNode check = list.path("steps").get(0).path("checks").get(0);
        assertThat(check.path("author").asText()).isEqualTo("person");
        assertThat(check.path("step").path("step").asText()).isEqualTo("order-desk:cartTotalIs");
        assertThat(check.path("step").path("check").asBoolean()).isTrue();
        assertThat(check.path("say").asText()).isEqualTo("The cart total is €31.32");
        // nothing that check fits: none offered; no plugin: none
        assertThat(person.domainChecks(java.util.Collections.singletonList(enabled), null)).isEmpty();
        assertThat(session.humanSink().domainChecks(Arrays.asList(reads, enabled), null)).isEmpty();
    }

    /** Add {qty} to the cart: a code step (it never folds); the reader shows the Total label before and after it. */
    private UiMcpSession totalAfterAddQty() {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs), DomainStep.act("addQty", "Add {qty}")
                .param("qty", ParamType.TEXT).code((ui, p) -> ui.button("Add").click()).build());
        s.reader(readings(io.github.kormcrete.swingui.driver.outcome.UiReading.builder().text("Total", "€0.00").build(),
                io.github.kormcrete.swingui.driver.outcome.UiReading.builder().text("Total", "€16.42").build()));
        return s;
    }

    @Test
    void aDomainChecksTotalIsNotLinkedToTheLabelItReads() throws Exception {
        UiMcpSession s = totalAfterAddQty();
        s.doAction(mapper.readTree("{\"action\":\"do\",\"step\":\"addQty\",\"params\":{\"qty\":\"12\"}}"));
        JsonNode r = mapper.readTree(s.doAction(mapper.readTree(
                "{\"action\":\"do\",\"step\":\"cartTotalIs\",\"params\":{\"total\":\"€16.42\"}}")));
        assertThat(r.path("passed").asBoolean()).as(r.toString()).isTrue();
        assertThat(s.recordedSteps().get(0).newValues).containsEntry("€16.42", ".texts.Total");

        assertThat(((DoAction) s.recordedSteps().get(1).action).params).containsEntry("total", "€16.42");
        assertThat(recording(s, "{\"op\":\"list\"}").at("/steps/0/checks/0/uses").isMissingNode()).isTrue();
    }

    @Test
    void aByHandLinkOfACheckToItsOwnPlaceIsRefusedOnlyUnderTheStepThatMadeIt() throws Exception {
        // 1: do addQty (Total €16.42); 1.1: do cartTotalIs €16.42; 1.2: expect label Total text €16.42;
        // 2: click button 'Other' (reading unchanged); 2.1: expect label Total text €16.42
        UiMcpSession s = totalAfterAddQty();
        String expect = "{\"action\":\"expect\",\"target\":{\"role\":\"label\",\"name\":\"Total\"},\"text\":\"€16.42\"}";
        s.doAction(mapper.readTree("{\"action\":\"do\",\"step\":\"addQty\",\"params\":{\"qty\":\"12\"}}"));
        s.doAction(mapper.readTree("{\"action\":\"do\",\"step\":\"cartTotalIs\",\"params\":{\"total\":\"€16.42\"}}"));
        s.doAction(mapper.readTree(expect));
        s.doAction(mapper.readTree("{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Other\"}}"));
        s.doAction(mapper.readTree(expect));
        JsonNode list = recording(s, "{\"op\":\"list\"}");
        assertThat(list.at("/steps/0/checks/1/n").asText()).as(list.toString()).isEqualTo("1.2");
        assertThat(list.at("/steps/1/checks/0/n").asText()).isEqualTo("2.1");
        assertThat(list.at("/steps/1/checks/0/uses").isMissingNode()).isTrue();

        DriverException e = catchThrowableOfType(() -> recording(s, "{\"op\":\"link\",\"n\":\"1.1\",\"ref\":\"${1.texts.Total}\"}"), DriverException.class);
        assertThat(e.getMessage()).contains("check 1.1 reads the very place step 1 showed this value in (.texts.Total)")
                .contains("compare the value with itself").endsWith("(nothing linked)");
        assertThat(catchThrowableOfType(() -> recording(s, "{\"op\":\"link\",\"n\":\"1.2\",\"ref\":\"${1.texts.Total}\"}"),
                DriverException.class).getMessage()).contains("check 1.2 reads the very place step 1");
        // nothing changed: the refused links left no id on step 1
        assertThat(s.recordedSteps().get(0).action.id).isNull();
        // under a later step it checks the value has not changed since: allowed
        JsonNode later = recording(s, "{\"op\":\"link\",\"n\":\"2.1\",\"ref\":\"${1.texts.Total}\"}");
        assertThat(later.at("/steps/1/checks/0/uses/0/value").asText()).isEqualTo("€16.42");
    }

    private static final String ADD_QTY = "{\"action\":\"do\",\"step\":\"addQty\",\"params\":{\"qty\":\"12\"}}";
    private static final String TOTAL_IS = "{\"action\":\"do\",\"step\":\"cartTotalIs\",\"params\":{\"total\":\"€16.42\"}}";
    private static final String EXPECT_TOTAL = "{\"action\":\"expect\",\"target\":{\"role\":\"label\",\"name\":\"Total\"},"
            + "\"text\":\"€16.42\"}";
    private static final String CLICK_OTHER = "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Other\"}}";

    @Test
    void aCheckIsNeverLinkedToAValueRememberedFromItsOwnPlaceUnderTheSameStep() throws Exception {
        // 1: do addQty; 1.1: remember label Total as total; 1.2: do cartTotalIs €16.42; 1.3: expect label Total €16.42;
        // 2: click button 'Other'; 2.1: expect label Total €16.42
        UiMcpSession s = totalAfterAddQty();
        backend.verbs.remembered = "€16.42";
        s.doAction(mapper.readTree(ADD_QTY));
        recording(s, "{\"op\":\"remember\",\"target\":{\"role\":\"label\",\"name\":\"Total\"},\"as\":\"total\"}");
        s.doAction(mapper.readTree(TOTAL_IS));
        s.doAction(mapper.readTree(EXPECT_TOTAL));
        s.doAction(mapper.readTree(CLICK_OTHER));
        s.doAction(mapper.readTree(EXPECT_TOTAL));
        JsonNode list = recording(s, "{\"op\":\"list\"}");
        assertThat(list.at("/steps/0/checks/0/n").asText()).as(list.toString()).isEqualTo("1.1");
        assertThat(list.at("/steps/1/checks/0/n").asText()).isEqualTo("2.1");

        // under the step that remembered it, both checks stay exact
        assertThat(((DoAction) s.recordedSteps().get(2).action).params).containsEntry("total", "€16.42");
        assertThat(mapper.valueToTree(s.recordedSteps().get(3).action).path("text").asText()).isEqualTo("€16.42");
        // under a later step the check says the value has not changed since
        assertThat(mapper.valueToTree(s.recordedSteps().get(5).action).path("text").asText()).isEqualTo("${total}");

        DriverException e = catchThrowableOfType(() -> recording(s, "{\"op\":\"link\",\"n\":\"1.2\",\"ref\":\"total\"}"),
                DriverException.class);
        assertThat(e.getMessage()).contains("check 1.2 reads the very place check 1.1 remembered this value from (total)")
                .contains("compare the value with itself").endsWith("(nothing linked)");
        assertThat(catchThrowableOfType(() -> recording(s, "{\"op\":\"link\",\"n\":\"1.3\",\"ref\":\"${total}\"}"),
                DriverException.class).getMessage()).contains("check 1.3 reads the very place check 1.1 remembered");
    }

    @Test
    void aCheckWrittenByHandWithItsOwnPlaceUnderTheStepThatMadeItIsRefused() throws Exception {
        // 1: do addQty; 1.1: do cartTotalIs; 1.2: expect label Total; 2: click button 'Other'; 2.1: expect label Total
        UiMcpSession s = totalAfterAddQty();
        s.doAction(mapper.readTree(ADD_QTY));
        s.doAction(mapper.readTree(TOTAL_IS));
        s.doAction(mapper.readTree(EXPECT_TOTAL));
        s.doAction(mapper.readTree(CLICK_OTHER));
        s.doAction(mapper.readTree(EXPECT_TOTAL));
        recording(s, "{\"op\":\"link\",\"n\":\"2.1\",\"ref\":\"${1.texts.Total}\"}");   // step 1 is addQty now
        String self = "{\"target\":{\"role\":\"label\",\"name\":\"Total\"},\"text\":\"${addQty.texts.Total}\"}";

        assertThat(catchThrowableOfType(() -> recording(s, "{\"op\":\"insert\",\"at\":\"1.3\",\"step\":" + self + "}"),
                DriverException.class).getMessage())
                .contains("check 1.3 reads the very place step 1 showed this value in (.texts.Total)")
                .endsWith("(nothing linked)");
        assertThat(catchThrowableOfType(() -> recording(s, "{\"op\":\"edit\",\"n\":\"1.2\",\"step\":" + self + "}"),
                DriverException.class).getMessage()).contains("check 1.2 reads the very place step 1");
        assertThat(catchThrowableOfType(() -> recording(s, "{\"op\":\"edit\",\"n\":\"1.1\",\"step\":{\"action\":\"do\","
                + "\"step\":\"order-desk:cartTotalIs\",\"params\":{\"total\":\"${addQty.texts.Total}\"},\"check\":true}}"),
                DriverException.class).getMessage()).contains("check 1.1 reads the very place step 1");
        assertThat(s.recordedSteps()).hasSize(5);
        // under a later step: allowed
        JsonNode later = recording(s, "{\"op\":\"insert\",\"at\":\"2.2\",\"step\":" + self + "}");
        assertThat(later.at("/steps/1/checks/1/n").asText()).as(later.toString()).isEqualTo("2.2");
    }

    private static String totalCheck(String total) {
        return "{\"action\":\"do\",\"step\":\"order-desk:cartTotalIs\",\"params\":{\"total\":\"" + total
                + "\"},\"check\":true}";
    }

    @Test
    void aDomainCheckWhoseReadsAreNotKnownMayReadAnyFieldOrTextOfItsStepButNoRow() throws Exception {
        // 1: do addQty (Total €16.42, a Cart row added with Total €16.42); 2: click button 'Other'
        UiMcpSession s = totalAfterAddQty();
        List<String> headers = Arrays.asList("Product", "Total");
        s.reader(readings(io.github.kormcrete.swingui.driver.outcome.UiReading.builder().text("Total", "€0.00")
                        .table("Cart", headers, java.util.Collections.<List<String>>emptyList()).build(),
                io.github.kormcrete.swingui.driver.outcome.UiReading.builder().text("Total", "€16.42")
                        .table("Cart", headers, java.util.Collections.singletonList(
                                Arrays.asList("Gel pen, black", "€16.42"))).build()));
        s.doAction(mapper.readTree(ADD_QTY));
        s.doAction(mapper.readTree(CLICK_OTHER));
        // 1.1: a domain check made by insert: it never ran, what it reads is not known
        recording(s, "{\"op\":\"insert\",\"at\":\"1.1\",\"step\":" + totalCheck("€16.42") + "}");

        DriverException e = catchThrowableOfType(
                () -> recording(s, "{\"op\":\"link\",\"n\":\"1.1\",\"ref\":\"${1.texts.Total}\"}"), DriverException.class);
        assertThat(e.getMessage()).contains("check 1.1 may read the very place step 1 showed this value in (.texts.Total)")
                .contains("compare the value with itself").endsWith("(nothing linked)");
        assertThat(s.recordedSteps().get(0).action.id).isNull();
        // a row the step added is not a place a check reads by itself: linked
        JsonNode row = recording(s, "{\"op\":\"link\",\"n\":\"1.1\",\"ref\":\"${1.rows.Cart.added[0].Total}\"}");
        assertThat(row.at("/steps/0/checks/0/uses/0/value").asText()).as(row.toString()).isEqualTo("€16.42");
        assertThat(((DoAction) s.recordedSteps().get(1).action).params)
                .containsEntry("total", "${addQty.rows.Cart.added[0].Total}");
        // insert with the reference in it: refused under the same step, allowed under a later one
        assertThat(catchThrowableOfType(() -> recording(s, "{\"op\":\"insert\",\"at\":\"1.2\",\"step\":"
                + totalCheck("${addQty.texts.Total}") + "}"), DriverException.class).getMessage())
                .contains("check 1.2 may read the very place step 1 showed this value in (.texts.Total)");
        assertThat(s.recordedSteps()).hasSize(3);
        JsonNode later = recording(s, "{\"op\":\"insert\",\"at\":\"2.1\",\"step\":"
                + totalCheck("${addQty.texts.Total}") + "}");
        assertThat(later.at("/steps/1/checks/0/n").asText()).as(later.toString()).isEqualTo("2.1");
    }

    @Test
    void aLoadedDomainChecksSelfLinkUnderItsStepIsRefused() throws Exception {
        // saved: 1: do addQty; 1.1: do cartTotalIs; 2: click button 'Other'; 2.1: expect label Total, linked to step 1
        UiMcpSession s = totalAfterAddQty();
        s.doAction(mapper.readTree(ADD_QTY));
        s.doAction(mapper.readTree(TOTAL_IS));
        s.doAction(mapper.readTree(CLICK_OTHER));
        s.doAction(mapper.readTree(EXPECT_TOTAL));
        recording(s, "{\"op\":\"link\",\"n\":\"2.1\",\"ref\":\"${1.texts.Total}\"}");
        s.exportRecordedTest("total", quoted(dir), null, false);
        UiMcpSession loaded = totalAfterAddQty();
        recording(loaded, "{\"op\":\"load\",\"name\":\"total\"}");

        assertThat(catchThrowableOfType(() -> recording(loaded, "{\"op\":\"edit\",\"n\":\"1.1\",\"step\":"
                + totalCheck("${addQty.texts.Total}") + "}"), DriverException.class).getMessage())
                .contains("check 1.1 may read the very place step 1 showed this value in (.texts.Total)")
                .endsWith("(nothing linked)");
        assertThat(((DoAction) loaded.recordedSteps().get(1).action).params).containsEntry("total", "€16.42");
    }

    @Test
    void theAgentsRawStepsFoldButNeverWithThePersons() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs));
        String row = "{\"target\":{\"role\":\"table\",\"row\":{\"Product\":\"Gel pen, black\"},\"column\":\"Product\"}}";
        String add = "{\"target\":{\"role\":\"button\",\"name\":\"Add to cart\"}}";
        s.verb("click", mapper.readTree(row));
        s.verb("click", mapper.readTree(add));
        // the person picks the row, the agent clicks Add to cart: two authors, no fold
        Locator cell = new Locator();
        cell.role = "table";
        cell.row = row("Product", "Gel pen, black");
        cell.column = "Product";
        assertThat(s.humanSink().done(step("click", cell)).ok).isTrue();
        s.verb("click", mapper.readTree(add));

        JsonNode steps = recording(s, "{\"op\":\"list\"}").path("steps");

        assertThat(steps).hasSize(3);
        assertThat(steps.get(0).path("author").asText()).isEqualTo("agent");
        assertThat(steps.get(0).path("folded").asInt()).isEqualTo(2);
        assertThat(steps.get(1).has("folded")).isFalse();
        assertThat(steps.get(2).has("folded")).isFalse();
        DriverException mixed = catchThrowableOfType(() -> recording(s, "{\"op\":\"fold\",\"from\":2,\"to\":3}"),
                DriverException.class);
        assertThat(mixed.getMessage()).contains("steps 2-3 are not one author's");
        DriverException none = catchThrowableOfType(() -> recording(s, "{\"op\":\"fold\",\"from\":3,\"to\":3}"),
                DriverException.class);
        assertThat(none.getMessage()).contains("step 3 matches no domain step's pattern");
    }

    @Test
    void keepRawKeepsAStepOutOfFolds() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs));
        Locator cell = new Locator();
        cell.role = "table";
        cell.row = row("Product", "Gel pen, black");
        cell.column = "Product";
        assertThat(s.humanSink().done(step("click", cell)).ok).isTrue();

        JsonNode kept = recording(s, "{\"op\":\"keepRaw\",\"from\":1,\"to\":1}");
        assertThat(s.humanSink().done(step("click", Locator.of("button", "Add to cart"))).ok).isTrue();

        assertThat(kept.path("did").asText()).isEqualTo("kept step 1 raw");
        JsonNode steps = recording(s, "{\"op\":\"list\"}").path("steps");
        assertThat(steps).hasSize(2).noneMatch(n -> n.has("folded"));
        assertThat(steps.get(0).path("keepRaw").asBoolean()).isTrue();
        assertThat(recording(s, "{\"op\":\"keepRaw\",\"from\":1,\"to\":1}").path("did").asText())
                .isEqualTo("step 1 is kept raw already");
    }

    @Test
    void aStepBeforeAReplayDoesNotFoldWithOneAfterIt() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs));
        Locator cell = new Locator();
        cell.role = "table";
        cell.row = row("Product", "Gel pen, black");
        cell.column = "Product";
        assertThat(s.humanSink().done(step("click", cell)).ok).isTrue();
        assertThat(recording(s, "{\"op\":\"replay\"}").path("passed").asBoolean()).isTrue();

        assertThat(s.humanSink().done(step("click", Locator.of("button", "Add to cart"))).ok).isTrue();

        assertThat(recording(s, "{\"op\":\"list\"}").path("steps")).hasSize(2).noneMatch(n -> n.has("folded"));
    }

    @Test
    void aTieIsListedWithItsCandidatesAndFoldByNameSettlesIt() throws Exception {
        Plugins plugins = new Plugins(backend.verbs);
        plugins.add("extras", Paths.get("extras")).ready(new PluginParts(() -> Arrays.asList(
                DomainStep.act("addToBasket", "Put {product} in the basket")
                        .param("product", ParamType.TEXT)
                        .code((ui, p) -> {
                            ui.table().row("Product", p.text("product")).cell("Product").click();
                            ui.button("Add to cart").click();
                        })
                        .build())), "ok");
        UiMcpSession s = orderDesk(plugins);
        addGelPen(s.humanSink());

        JsonNode steps = recording(s, "{\"op\":\"list\"}").path("steps");

        assertThat(steps).hasSize(2).noneMatch(n -> n.has("folded"));
        JsonNode tie = steps.get(1).path("foldCandidates");
        assertThat(tie.path("from").asInt()).isEqualTo(1);
        assertThat(tie.path("to").asInt()).isEqualTo(2);
        assertThat(tie.path("steps")).extracting(n -> n.path("step").asText())
                .containsExactlyInAnyOrder("extras:addToBasket", "order-desk:addToCart");
        DriverException ask = catchThrowableOfType(() -> recording(s, "{\"op\":\"fold\",\"from\":1,\"to\":2}"),
                DriverException.class);
        assertThat(ask.getMessage()).contains("match more than one domain step").contains("name one with 'step'");
        JsonNode folded = recording(s, "{\"op\":\"fold\",\"from\":1,\"to\":2,\"step\":\"addToBasket\"}");
        assertThat(folded.path("steps")).hasSize(1);
        assertThat(folded.path("steps").get(0).path("step").path("step").asText()).isEqualTo("extras:addToBasket");
        assertThat(folded.path("steps").get(0).path("say").asText()).isEqualTo("Put Gel pen, black in the basket");
    }

    @Test
    void anExportRightAfterTheLastStepsHoldsTheirFoldAndDoesNotWait() throws Exception {
        for (int i = 0; i < 5; i++) {
            UiMcpSession s = orderDesk(new Plugins(backend.verbs));
            addGelPen(s.humanSink());
            // the recording changes again at once: a match that started on the two steps is dropped and looks again
            recording(s, "{\"op\":\"stop\"}");
            long start = System.nanoTime();

            JsonNode file = mapper.readTree(s.exportRecordedTest("add-pen-" + i, quoted(dir), null, false)).path("test");

            assertThat(System.nanoTime() - start).isLessThan(java.util.concurrent.TimeUnit.SECONDS.toNanos(2));
            assertThat(file.path("steps")).hasSize(1);
            assertThat(file.path("steps").get(0).path("step").asText()).isEqualTo("order-desk:addToCart");
            assertThat(recording(s, "{\"op\":\"list\"}").path("steps")).hasSize(1);
        }
    }

    @Test
    void aLoadedTestsStepsNeverFoldAmongThemselves() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs));
        Flow flow = new Flow();
        flow.version = RecordedTestFiles.VERSION;
        flow.name = "add a pen";
        flow.start = RecordedTestFiles.START_RESET;
        ClickVerb row = new ClickVerb();
        row.target = new Locator();
        row.target.role = "table";
        row.target.row = row("Product", "Gel pen, black");
        row.target.column = "Product";
        ClickVerb add = new ClickVerb();
        add.target = Locator.of("button", "Add to cart");
        flow.steps.add(row);
        flow.steps.add(add);
        Path file = dir.resolve("add-pen.json");
        Files.write(file, RecordedTestFiles.write(flow).getBytes(StandardCharsets.UTF_8));
        s.loadRecordedTest(file);
        Inspector.Sink person = s.humanSink();

        // the person records on after the loaded steps
        person.resume();
        assertThat(person.done(step("click", Locator.of("button", "Checkout…"))).ok).isTrue();

        JsonNode steps = recording(s, "{\"op\":\"list\"}").path("steps");
        assertThat(steps).hasSize(3).noneMatch(n -> n.has("folded"));
        // asked, the dry run still says what they would fold into
        JsonNode match = mapper.readTree(s.vocabulary(mapper.readTree("{\"op\":\"match\",\"from\":1,\"to\":3}")));
        assertThat(match.path("folds")).hasSize(1);
        assertThat(match.path("folds").get(0).path("step").asText()).isEqualTo("order-desk:addToCart");
    }

    @Test
    void aFoldMadeAfterAReplayOfItsStepsDoesNotGrowWithALaterStep() throws Exception {
        Plugins plugins = new Plugins(backend.verbs);
        plugins.add("extras", Paths.get("extras")).ready(new PluginParts(() -> Arrays.asList(
                DomainStep.act("addAndCheckOut", "Add {product} and check out")
                        .param("product", ParamType.TEXT)
                        .steps("{\"action\":\"click\",\"target\":{\"role\":\"table\",\"row\":{\"Product\":\"{product}\"}}}",
                                "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Add to cart\"}}",
                                "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Checkout…\"}}")
                        .build())), "ok");
        UiMcpSession s = orderDesk(plugins);
        Inspector.Sink person = s.humanSink();
        addGelPen(person);
        recording(s, "{\"op\":\"unfold\",\"n\":1}");
        assertThat(recording(s, "{\"op\":\"replay\"}").path("passed").asBoolean()).isTrue();
        // folded again after the replay: the fold is new, its steps are from before the replay
        recording(s, "{\"op\":\"fold\",\"from\":1,\"to\":2,\"step\":\"addToCart\"}");

        assertThat(person.done(step("click", Locator.of("button", "Checkout…"))).ok).isTrue();

        JsonNode steps = recording(s, "{\"op\":\"list\"}").path("steps");
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).path("step").path("step").asText()).isEqualTo("order-desk:addToCart");
        assertThat(steps.get(1).has("folded")).isFalse();
    }

    @Test
    void withoutAVocabularyNothingFolds() throws Exception {
        addGelPen(sink);

        JsonNode steps = call("{\"op\":\"list\"}").path("steps");

        assertThat(steps).hasSize(2).noneMatch(n -> n.has("folded") || n.has("keepRaw"));
        DriverException e = catchThrowableOfType(() -> call("{\"op\":\"fold\",\"from\":1,\"to\":2}"),
                DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.CAPABILITY_GAP);
    }

    @Test
    void aDoStepWithoutAJavaFormIsRecordedAndOnlyTheJavaExportRefusesIt() throws Exception {
        Plugins plugins = new Plugins(backend.verbs);
        plugins.add("extras", Paths.get("extras")).ready(new PluginParts(() -> Arrays.asList(
                DomainStep.act("addOneMore", "Add one more of the last product")
                        .code((ui, p) -> ui.field("Qty").fill(ui.label("Last product").text()))
                        .build())), "ok");
        UiMcpSession s = orderDesk(plugins);

        JsonNode r = mapper.readTree(s.doAction(mapper.readTree("{\"action\":\"do\",\"step\":\"addOneMore\"}")));
        s.doAction(mapper.readTree(ADD));

        // recorded as one step like any do step; the list shows why it has no Java line
        assertThat(r.path("passed").asBoolean()).as(r.toString()).isTrue();
        assertThat(r.has("notRecorded")).isFalse();
        assertThat(r.path("recorded").path("step").asText()).isEqualTo("extras:addOneMore");
        assertThat(s.recordedSteps()).hasSize(2);
        String why = "Add one more of the last product (extras:addOneMore) has no Java form: the step names no java(...) "
                + "call, and its body cannot be written out as steps (the body reads the app (read), which a trace cannot "
                + "answer); give the step a java(...) call in the plugin, or export the recording as a recorded test";
        JsonNode steps = mapper.readTree(s.recording(mapper.readTree("{\"op\":\"list\"}"))).path("steps");
        assertThat(steps.get(0).has("java")).isFalse();
        assertThat(steps.get(0).path("noJava").asText()).isEqualTo(why);
        assertThat(steps.get(1).path("java").asText()).isEqualTo("OrderDeskSteps.addToCart(ui, \"Gel pen, black\");");

        // the Java export refuses it, naming the step; the recording stays
        System.setProperty(UiMcpSession.TESTS_DIR_PROP, dir.resolve("tests").toString());
        try {
            ExportRefused refused = catchThrowableOfType(() -> s.exportTest("AddOneMoreUiIT", null, "demo", false),
                    ExportRefused.class);
            assertThat(refused.problems()).containsExactly("step 1: " + why);
        } finally {
            System.clearProperty(UiMcpSession.TESTS_DIR_PROP);
        }
        assertThat(s.recordedSteps()).hasSize(2);

        // the recorded test export writes it, and the file loads back into the recording
        JsonNode file = mapper.readTree(s.exportRecordedTest("one-more", quoted(dir), null, false)).path("test");
        assertThat(file.path("requires").toString()).isEqualTo("[\"extras\",\"order-desk\"]");
        assertThat(file.path("steps").get(0).path("step").asText()).isEqualTo("extras:addOneMore");
        JsonNode loaded = mapper.readTree(s.loadRecordedTest(dir.resolve("one-more.json")));
        assertThat(loaded.path("steps").asInt()).isEqualTo(2);

        // an agent's insert of one is accepted too
        s.recording(mapper.readTree("{\"op\":\"insert\",\"at\":3,\"step\":{\"action\":\"do\",\"step\":\"addOneMore\"}}"));
        assertThat(s.recordedSteps()).hasSize(3);
    }

    @Test
    void aRefusedSameNamePluginLeavesTheFirstWorking() throws Exception {
        Plugins plugins = new Plugins(backend.verbs);
        UiMcpSession s = orderDesk(plugins);
        Path folder = Files.createTempDirectory("plugin").resolve("order-desk-extras/target/classes");
        Files.createDirectories(folder.resolve(PluginDescriptor.RESOURCE).getParent());
        Files.write(folder.resolve(PluginDescriptor.RESOURCE), "app-class=a.B\nname=order-desk\n".getBytes("ISO-8859-1"));
        PluginSlot refused = plugins.add(PluginDescriptor.read(folder));
        assertThat(refused.state()).isEqualTo(PluginSlot.State.FAILED);

        s.doAction(mapper.readTree(ADD));
        mapper.readTree(s.exportRecordedTest("one-pen", quoted(dir), null, false));
        // a file requiring the plugin loads: the name stands for the plugin that is ready
        JsonNode loaded = mapper.readTree(s.loadRecordedTest(dir.resolve("one-pen.json")));

        assertThat(loaded.path("steps").asInt()).isEqualTo(1);
        assertThat(plugins.appSlot().state()).isEqualTo(PluginSlot.State.READY);
        assertThat(plugins.slotNamed("order-desk").state()).isEqualTo(PluginSlot.State.READY);
        assertThat(plugins.vocabulary()).hasSize(2);
        assertThat(plugins.patternsOf(plugins.vocabulary().get(0))).isNotNull();
        assertThat(plugins.slots()).hasSize(2);
        assertThat(plugins.slots().get(1).detail()).contains("another plugin is already named order-desk");
    }

    @Test
    void aRefusedCheckDoStepShowsNoJavaInTheList() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs), DomainStep.check("pickedAndTotal", "Gel pen {product} is priced")
                .param("product", ParamType.TEXT)
                .steps("{\"action\":\"expect\",\"target\":{\"role\":\"table\",\"row\":{\"SKU\":\"PEN-GEL-BLK\","
                        + "\"Product\":\"{product}\",\"Price\":\"€1.20\"},\"column\":\"Product\"},\"text\":\"x\"}")
                .build());

        s.doAction(mapper.readTree("{\"action\":\"do\",\"step\":\"pickedAndTotal\",\"params\":{\"product\":\"Gel pen, black\"}}"));

        JsonNode steps = mapper.readTree(s.recording(mapper.readTree("{\"op\":\"list\"}"))).path("steps");
        JsonNode check = steps.get(0).path("checks").get(0);
        assertThat(check.has("java")).isFalse();
        assertThat(check.path("noJava").asText()).startsWith("this check: ").contains("row with more than two keys");
    }

    /** A domain step whose steps the Java writer refuses: a row with three keys. */
    private static DomainStep pickAndCheckOut() {
        return DomainStep.act("pickAndCheckOut", "Pick {product} and check out")
                .param("product", ParamType.TEXT)
                .steps("{\"action\":\"click\",\"target\":{\"role\":\"table\",\"row\":{\"SKU\":\"PEN-GEL-BLK\","
                                + "\"Product\":\"{product}\",\"Price\":\"€1.20\"},\"column\":\"Product\"}}",
                        "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Add to cart\"}}",
                        "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Checkout…\"}}")
                .build();
    }

    @Test
    void aDoStepWhoseStepsHaveNoJavaFormIsRecordedAndSoAreTheStepsAfterIt() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs), pickAndCheckOut());

        JsonNode r = mapper.readTree(s.doAction(mapper.readTree(
                "{\"action\":\"do\",\"step\":\"pickAndCheckOut\",\"params\":{\"product\":\"Gel pen, black\"}}")));
        JsonNode next = mapper.readTree(s.doAction(mapper.readTree(ADD)));

        assertThat(r.path("passed").asBoolean()).as(r.toString()).isTrue();
        assertThat(r.has("notRecorded")).as(r.toString()).isFalse();
        assertThat(next.has("notRecorded")).as(next.toString()).isFalse();
        assertThat(s.recordedSteps()).hasSize(2);
        JsonNode steps = mapper.readTree(s.recording(mapper.readTree("{\"op\":\"list\"}"))).path("steps");
        assertThat(steps.get(0).has("java")).isFalse();
        assertThat(steps.get(0).path("noJava").asText()).contains("row with more than two keys");

        // only the Java export refuses it
        System.setProperty(UiMcpSession.TESTS_DIR_PROP, dir.resolve("tests").toString());
        try {
            ExportRefused refused = catchThrowableOfType(() -> s.exportTest("PickUiIT", null, "demo", false),
                    ExportRefused.class);
            assertThat(refused.problems()).hasSize(1);
            assertThat(refused.problems().get(0)).startsWith("step 1").contains("row with more than two keys");
        } finally {
            System.clearProperty(UiMcpSession.TESTS_DIR_PROP);
        }
    }

    @Test
    void aFoldIntoADoStepWithNoJavaFormNeverFreezesTheRecording() throws Exception {
        Plugins plugins = new Plugins(backend.verbs);
        plugins.add("extras", Paths.get("extras")).ready(new PluginParts(() -> Arrays.asList(pickAndCheckOut())), "ok");
        UiMcpSession s = orderDesk(plugins);
        Inspector.Sink person = s.humanSink();
        addGelPen(person);
        assertThat(person.done(step("click", Locator.of("button", "Checkout…"))).ok).isTrue();

        JsonNode steps = recording(s, "{\"op\":\"list\"}").path("steps");
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).path("step").path("step").asText()).isEqualTo("extras:pickAndCheckOut");

        // the person's next step is recorded, and so is the agent's
        Inspector.Result after = person.done(step("click", Locator.of("button", "Next")));
        assertThat(after.ok).as(after.line).isTrue();
        JsonNode agent = mapper.readTree(s.doAction(mapper.readTree(ADD)));
        assertThat(agent.has("notRecorded")).as(agent.toString()).isFalse();
        assertThat(recording(s, "{\"op\":\"list\"}").path("steps")).hasSize(3);
    }

    @Test
    void aDoStepWhosePluginIsNotReadyNeverFreezesTheRecording() throws Exception {
        Plugins plugins = new Plugins(backend.verbs);
        UiMcpSession s = orderDesk(plugins);
        s.doAction(mapper.readTree(ADD));
        // the plugin fails (a reload that breaks it): no ready plugin offers the recorded do step
        plugins.slot("order-desk").failed("reloading");

        Inspector.Result after = s.humanSink().done(step("click", Locator.of("button", "Checkout…")));

        assertThat(after.ok).as(after.line).isTrue();
        assertThat(s.recordedSteps()).hasSize(2);
    }

    /** The palette replays by position: while it does, nothing folds under it. */
    @Test
    void noFoldIsMadeWhileThePaletteReplays() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs));
        s.paletteReplaying = () -> "a person is replaying their recording in the app";
        addGelPen(s.humanSink());

        JsonNode steps = recording(s, "{\"op\":\"list\"}").path("steps");

        assertThat(steps).hasSize(2).noneMatch(n -> n.has("folded"));
    }

    /** Whether folding's thread comes to wait for the session's lock (it matched and would apply), within 10 s. */
    private static boolean matcherWaitsForTheLock() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.getName().equals("swing-ui-agent folding") && t.getState() == Thread.State.BLOCKED) return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    /** A fold matched before a replay started is never made after it: its steps may be the replay's by then. */
    @Test
    void aFoldMatchedBeforeAReplayStartedIsNotMadeAfterIt() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs));
        s.paletteReplaying = () -> null;
        Inspector.Sink person = s.humanSink();
        Locator cell = Locator.ofId("products");
        cell.row = row("SKU", "PEN-GEL-BLK");
        cell.column = "Product";
        Locator seen = new Locator();
        seen.role = "table";
        seen.row = row("SKU", "PEN-GEL-BLK", "Product", "Gel pen, black", "Price", "€1.20");
        seen.column = "Product";
        Map<String, Object> click = step("click", cell);
        click.put(Inspector.SEEN, seen);
        assertThat(person.done(click).ok).isTrue();
        recording(s, "{\"op\":\"list\"}");   // folding has looked at the first step
        synchronized (s) {
            // the step that completes the pattern: folding matches it, then waits for this lock to apply the fold
            assertThat(person.done(step("click", Locator.of("button", "Add to cart"))).ok).isTrue();
            assertThat(matcherWaitsForTheLock()).isTrue();
            // a replay starts on the steps (the click without a row: its replay changes nothing in the recording)
            assertThat(person.replay(1).ok).isTrue();
        }

        JsonNode steps = recording(s, "{\"op\":\"list\"}").path("steps");

        assertThat(steps).hasSize(2).noneMatch(n -> n.has("folded"));
    }

    @Test
    void aDoStepAfterDoneAddsNothing() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs));
        s.recording(mapper.readTree("{\"op\":\"start\"}"));
        s.recording(mapper.readTree("{\"op\":\"stop\"}"));
        String before = s.recording(mapper.readTree("{\"op\":\"list\"}"));

        JsonNode r = mapper.readTree(s.doAction(mapper.readTree(ADD)));

        assertThat(r.path("passed").asBoolean()).isTrue();
        assertThat(r.path("notRecorded").asText()).startsWith("the recording is stopped");
        assertThat(s.recording(mapper.readTree("{\"op\":\"list\"}"))).isEqualTo(before);
    }

    /** A version 3 file with one step, a do step at line 9 whose step and params are {@code step}. */
    private Path doFile(String requires, String step) throws Exception {
        Path file = dir.resolve("broken.json");
        Files.write(file, ("{\n"
                + "  \"version\" : 3,\n"
                + "  \"name\" : \"broken\",\n"
                + "  \"start\" : \"reset\",\n"
                + "  \"requires\" : " + requires + ",\n"
                + "  \"steps\" : [ {\n"
                + "    \"action\" : \"menu\", \"path\" : [ \"Orders\", \"New order\" ]\n"
                + "  },\n"
                + "  " + step + " ]\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private DriverException refusedToLoad(UiMcpSession s, Path file) {
        DriverException run = catchThrowableOfType(() -> s.runRecordedTest(mapper.readTree("{\"path\":\"" + quoted(file) + "\"}")),
                DriverException.class);
        DriverException load = catchThrowableOfType(() -> s.loadRecordedTest(file), DriverException.class);
        assertThat(run).isNotNull();
        assertThat(load).isNotNull();
        assertThat(load.getMessage()).isEqualTo(run.getMessage());
        return run;
    }

    @Test
    void loadingARecordedTestWithAnUnknownStepNamesTheLine() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs));
        String at = dir.resolve("broken.json").toAbsolutePath().normalize() + ": step 2 (line 9), plugin order-desk: ";

        DriverException unknown = refusedToLoad(s, doFile("[ \"order-desk\" ]",
                "{ \"action\" : \"do\", \"step\" : \"order-desk:addToCrat\" }"));
        assertThat(unknown.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(unknown.getMessage()).isEqualTo("BAD_INPUT: " + at
                + "order-desk has no domain step 'addToCrat' (its steps: addToCart, cartTotalIs)");

        DriverException bare = refusedToLoad(s, doFile("[ \"order-desk\" ]", "{ \"action\" : \"do\", \"step\" : \"addToCrat\" }"));
        assertThat(bare.getMessage()).isEqualTo("BAD_INPUT: " + at
                + "order-desk has no domain step 'addToCrat' (its steps: addToCart, cartTotalIs)");

        DriverException param = refusedToLoad(s, doFile("[ \"order-desk\" ]",
                "{ \"action\" : \"do\", \"step\" : \"addToCart\", \"params\" : { \"qty\" : \"3\" } }"));
        assertThat(param.getMessage()).isEqualTo("BAD_INPUT: " + at + "addToCart has no param 'qty' (its params: product)");

        DriverException kind = refusedToLoad(s, doFile("[ \"order-desk\" ]",
                "{ \"action\" : \"do\", \"step\" : \"cartTotalIs\", \"params\" : { \"total\" : \"4.99\" } }"));
        assertThat(kind.getMessage()).isEqualTo("BAD_INPUT: " + at + "cartTotalIs is a check: it goes under the step it "
                + "checks (in its \"checks\")");

        DriverException missing = refusedToLoad(s, doFile("[ \"extras\" ]", "{ \"action\" : \"do\", \"step\" : \"extras:giftWrap\" }"));
        assertThat(missing.reason()).isEqualTo(Reason.CAPABILITY_GAP);
        assertThat(missing.getMessage()).isEqualTo("CAPABILITY_GAP: " + dir.resolve("broken.json").toAbsolutePath().normalize()
                + " requires the plugin extras, which is not configured (the plugins: order-desk)");

        Path ok = doFile("[ \"order-desk\" ]", ADD);
        DriverException plain = catchThrowableOfType(() -> session.runRecordedTest(mapper.readTree("{\"path\":\"" + quoted(ok)
                + "\"}")), DriverException.class);
        assertThat(plain.reason()).isEqualTo(Reason.CAPABILITY_GAP);
        assertThat(plain.getMessage()).isEqualTo("CAPABILITY_GAP: " + ok.toAbsolutePath().normalize() + " requires the plugin "
                + "order-desk; no plugin configured (agent argument plugins=…)");
        assertThat(mapper.readTree(s.loadRecordedTest(ok)).path("steps").asInt()).isEqualTo(2);
    }

    // ---- what each step changed: kept while recording, refreshed and used by replays ---------------------------------

    private static io.github.kormcrete.swingui.driver.outcome.UiReading orders(String... numbers) {
        List<List<String>> rows = new java.util.ArrayList<List<String>>();
        for (String n : numbers) rows.add(java.util.Collections.singletonList(n));
        return io.github.kormcrete.swingui.driver.outcome.UiReading.builder()
                .table("Orders", java.util.Collections.singletonList("Number"), rows).build();
    }

    /** Hands out {@code readings} in turn, the last one again once they run out; counts the calls in {@link #reads}. */
    private java.util.function.Supplier<io.github.kormcrete.swingui.driver.outcome.UiReading> readings(
            io.github.kormcrete.swingui.driver.outcome.UiReading... readings) {
        java.util.Deque<io.github.kormcrete.swingui.driver.outcome.UiReading> left =
                new java.util.ArrayDeque<io.github.kormcrete.swingui.driver.outcome.UiReading>(Arrays.asList(readings));
        return () -> {
            reads++;
            return left.size() > 1 ? left.poll() : left.peek();
        };
    }

    private int reads;

    private void noReset(UiMcpSession s) {
        s.appReset(new UiMcpSession.AppReset() {
            @Override public boolean defined() { return false; }
            @Override public void run() { }
        });
    }

    /** A recorded test where step 2 types the order number step 1 (Copy) added. */
    private Path copyAndSearch() throws Exception {
        Path file = dir.resolve("copy-and-search.json");
        Files.write(file, ("{\"version\":4,\"name\":\"copy-and-search\",\"start\":\"reset\",\"steps\":["
                + "{\"action\":\"click\",\"id\":\"copy\",\"target\":{\"role\":\"button\",\"name\":\"Copy\"}},"
                + "{\"action\":\"type\",\"target\":{\"id\":\"search\"},\"text\":\"${copy.rows.Orders.added[0].Number}\"}]}")
                .getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static final String NUMBER = "${copy.rows.Orders.added[0].Number}";

    private List<String> typed() {
        List<String> out = new java.util.ArrayList<String>();
        for (String c : backend.verbs.calls) if (c.startsWith("type ")) out.add(c);
        return out;
    }

    @Test
    void aPersonStepGetsItsOutcomeAtTheNextInput() {
        io.github.kormcrete.swingui.driver.outcome.UiReading before = orders("ORD-1640");
        io.github.kormcrete.swingui.driver.outcome.UiReading after = orders("ORD-1640", "ORD-1641");
        Map<String, Object> select = step("click", Locator.of("button", "Orders"));
        select.put(Inspector.SEQ, 1);
        Map<String, Object> copy = step("click", Locator.of("button", "Copy"));
        copy.put(Inspector.SEQ, 2);

        assertThat(sink.done(select).ok).isTrue();
        assertThat(sink.done(copy).ok).isTrue();
        // the palette read the app at the person's next input, after Copy: what Copy changed comes for its step
        Map<String, Object> outcome = io.github.kormcrete.swingui.driver.outcome.Outcomes.diff(before, after);
        sink.outcome(2, outcome, io.github.kormcrete.swingui.driver.outcome.Outcomes.newValues(before, outcome));

        List<RecordedStep> steps = session.recordedSteps();
        assertThat(steps.get(0).outcome).isNull();
        assertThat(steps.get(1).outcome.at("/rows/Orders/added/0/Number").asText()).isEqualTo("ORD-1641");
        assertThat(steps.get(1).newValues).containsEntry("ORD-1641", ".rows.Orders.added[0].Number");
        assertThat(session.runValues().has("#" + steps.get(1).id)).isTrue();
        // a step the session never recorded gets nothing
        sink.outcome(7, outcome, io.github.kormcrete.swingui.driver.outcome.Outcomes.newValues(before, outcome));
        assertThat(session.recordedSteps().get(0).outcome).isNull();
    }

    @Test
    void anAutoLinkIsOneUndoableEdit() throws Exception {
        session.reader(readings(orders("ORD-1640"), orders("ORD-1640", "ORD-1641")));
        session.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Copy\"}}"));

        JsonNode typed = mapper.readTree(session.verb("type",
                mapper.readTree("{\"target\":{\"id\":\"search\"},\"text\":\"Copy of ORD-1641\"}")));

        // the agent learns the step as the recording keeps it
        assertThat(typed.at("/recorded/text").asText()).isEqualTo("Copy of " + NUMBER);
        List<RecordedStep> steps = session.recordedSteps();
        assertThat(steps.get(0).action.id).isEqualTo("copy");
        assertThat(((io.github.kormcrete.swingui.flow.verb.TypeVerb) steps.get(1).action).text).isEqualTo("Copy of " + NUMBER);
        assertThat(steps.get(1).linkSeen).containsEntry(NUMBER, "ORD-1641");
        assertThat(steps.get(1).edited).isFalse();
        // what Copy changed is known under its new id: a replay from step 2 finds it
        assertThat(session.runValues().fill(NUMBER)).isEqualTo("ORD-1641");
        assertThat(sink.undoLabel()).isEqualTo("link of step 2 by the agent");

        // Undo takes back the link only: the step stays, with the value as typed
        assertThat(call("{\"op\":\"undo\"}").path("did").asText()).isEqualTo("undid the link of step 2 by the agent");
        steps = session.recordedSteps();
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).action.id).isNull();
        assertThat(((io.github.kormcrete.swingui.flow.verb.TypeVerb) steps.get(1).action).text).isEqualTo("Copy of ORD-1641");
        assertThat(steps.get(1).linkSeen).isEmpty();
        assertThat(sink.undoLabel()).isNull();
    }

    @Test
    void aFreshValueInAScopeIsLinkedAndAnIdScopeSaysItStaysExact() throws Exception {
        session.reader(readings(orders("ORD-1640"), orders("ORD-1640", "ORD-1641")));
        session.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Copy\"}}"));
        JsonNode check = mapper.readTree(session.verb("expect", mapper.readTree(
                "{\"target\":{\"scope\":[\"tab:ORD-1641\"],\"role\":\"label\",\"name\":\"ORD-1641\"},\"shown\":true}")));
        assertThat(check.at("/recorded/target/scope/0").asText()).isEqualTo("tab:" + NUMBER);
        assertThat(check.at("/recorded/target/name").asText()).isEqualTo(NUMBER);
        assertThat(check.has("linkNote")).isFalse();
        JsonNode click = mapper.readTree(session.verb("click", mapper.readTree(
                "{\"target\":{\"scope\":[\"id:ORD-1641\"],\"role\":\"button\",\"name\":\"Save\"}}")));
        assertThat(click.path("linkNote").asText()).isEqualTo("stays exact: scope step 'id:ORD-1641' holds a value an "
                + "earlier step made, and an id or text scope step or a column cannot use one");
    }

    @Test
    void aRowLinkStillResolvesAfterDoneFromTheMiddle() throws Exception {
        io.github.kormcrete.swingui.driver.outcome.UiReading before = orders("ORD-1640");
        // the new number shows in a field as well: the value index names the field, the click names the row
        io.github.kormcrete.swingui.driver.outcome.UiReading after = io.github.kormcrete.swingui.driver.outcome.UiReading
                .builder().field("Number", "ORD-1641").table("Orders", java.util.Collections.singletonList("Number"),
                        Arrays.asList(java.util.Collections.singletonList("ORD-1640"),
                                java.util.Collections.singletonList("ORD-1641"))).build();
        session.reader(readings(before, after));
        session.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Copy\"}}"));
        session.verb("click", mapper.readTree(
                "{\"target\":{\"role\":\"table\",\"name\":\"Orders\",\"row\":{\"Number\":\"ORD-1641\"}}}"));
        JsonNode row = mapper.valueToTree(session.recordedSteps().get(1).action).at("/target/row/Number");
        assertThat(row.asText()).isEqualTo(NUMBER);

        call("{\"op\":\"stop\"}");
        noReset(session);
        backend.verbs.calls.clear();

        assertThat(call("{\"op\":\"replay\",\"from\":2}").path("passed").asBoolean()).isTrue();
        assertThat(backend.verbs.calls.toString()).contains("ORD-1641").doesNotContain(NUMBER);
    }

    /** Orders {@code numbers}, the last one also in the Number field (Copy shows the order it made). */
    private static io.github.kormcrete.swingui.driver.outcome.UiReading copiedTo(String... numbers) {
        List<List<String>> rows = new java.util.ArrayList<List<String>>();
        for (String n : numbers) rows.add(java.util.Collections.singletonList(n));
        return io.github.kormcrete.swingui.driver.outcome.UiReading.builder().field("Number", numbers[numbers.length - 1])
                .table("Orders", java.util.Collections.singletonList("Number"), rows).build();
    }

    @Test
    void aRowLinkGivesTheLatestRunsValueAfterItsSourceShrankAgain() throws Exception {
        session.reader(readings(orders("ORD-1640"), copiedTo("ORD-1640", "ORD-1641")));
        session.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Copy\"}}"));
        session.verb("click", mapper.readTree(
                "{\"target\":{\"role\":\"table\",\"name\":\"Orders\",\"row\":{\"Number\":\"ORD-1641\"}}}"));
        for (int i = 0; i < RecordingModel.FULL_OUTCOMES; i++) click("next");
        call("{\"op\":\"stop\"}");
        assertThat(session.recordedSteps().get(0).outcome).isNull();
        assertThat(session.runValues().fill(NUMBER)).isEqualTo("ORD-1641");

        // the next run: Copy makes ORD-1642, and shrinks again behind the 20 steps after it
        noReset(session);
        session.reader(readings(orders("ORD-1640", "ORD-1641"), copiedTo("ORD-1640", "ORD-1641", "ORD-1642")));
        backend.verbs.calls.clear();
        assertThat(call("{\"op\":\"replay\"}").path("passed").asBoolean()).isTrue();
        assertThat(session.recordedSteps().get(0).outcome).isNull();
        assertThat(session.runValues().fill(NUMBER)).isEqualTo("ORD-1642");

        backend.verbs.calls.clear();
        assertThat(call("{\"op\":\"replay\",\"from\":2,\"to\":2}").path("passed").asBoolean()).isTrue();
        assertThat(backend.verbs.calls.toString()).contains("ORD-1642").doesNotContain("ORD-1641");
    }

    @Test
    void thePersonsStepIsLinkedToWhatTheirEarlierStepMadeAndReplaysWithTheNewValue() throws Exception {
        io.github.kormcrete.swingui.driver.outcome.UiReading before = orders("ORD-1640");
        io.github.kormcrete.swingui.driver.outcome.UiReading after = orders("ORD-1640", "ORD-1641");
        Map<String, Object> copy = step("click", Locator.of("button", "Copy"));
        copy.put(Inspector.SEQ, 1);
        Map<String, Object> search = step("type", Locator.ofId("search"));
        search.put("text", "ORD-1641");
        search.put(Inspector.SEQ, 2);

        assertThat(sink.done(copy).ok).isTrue();
        Map<String, Object> outcome = io.github.kormcrete.swingui.driver.outcome.Outcomes.diff(before, after);
        sink.outcome(1, outcome, io.github.kormcrete.swingui.driver.outcome.Outcomes.newValues(before, outcome));
        assertThat(sink.done(search).ok).isTrue();

        List<RecordedStep> steps = session.recordedSteps();
        assertThat(steps.get(0).action.id).isEqualTo("copy");
        assertThat(((io.github.kormcrete.swingui.flow.verb.TypeVerb) steps.get(1).action).text).isEqualTo(NUMBER);
        assertThat(sink.undoLabel()).isEqualTo("link of step 2 by the person");

        // the next run copies another order: the search follows it
        noReset(session);
        session.reader(readings(orders("ORD-1640", "ORD-1641"), orders("ORD-1640", "ORD-1641", "ORD-1642")));
        backend.verbs.calls.clear();
        assertThat(call("{\"op\":\"replay\"}").path("passed").asBoolean()).isTrue();
        assertThat(typed()).containsExactly("type #search ORD-1642 null null");
    }

    // ---- links in the palette: shown in words, by hand, the exact value, unique per run, a delete that keeps values -----

    /** The person copies an order (step 1, which adds ORD-1641) and types its number into the search (step 2, linked). */
    private void copyThenSearch() {
        io.github.kormcrete.swingui.driver.outcome.UiReading before = orders("ORD-1640");
        io.github.kormcrete.swingui.driver.outcome.UiReading after = orders("ORD-1640", "ORD-1641");
        Map<String, Object> copy = step("click", Locator.of("button", "Copy"));
        copy.put(Inspector.SEQ, 1);
        assertThat(sink.done(copy).ok).isTrue();
        Map<String, Object> outcome = io.github.kormcrete.swingui.driver.outcome.Outcomes.diff(before, after);
        sink.outcome(1, outcome, io.github.kormcrete.swingui.driver.outcome.Outcomes.newValues(before, outcome));
        assertThat(sink.done(typing("search", "ORD-1641", 2)).ok).isTrue();
    }

    private static Map<String, Object> typing(String id, String text, int seq) {
        Map<String, Object> t = step("type", Locator.ofId(id));
        t.put("text", text);
        t.put(Inspector.SEQ, seq);
        return t;
    }

    private static String text(RecordedStep s) {
        return ((io.github.kormcrete.swingui.flow.verb.TypeVerb) s.action).text;
    }

    /** Each record as the recording keeps it: its step as JSON and the values its references stood for. */
    private List<String> kept() throws Exception {
        List<String> out = new java.util.ArrayList<String>();
        for (RecordedStep s : session.recordedSteps()) out.add(mapper.writeValueAsString(s.action) + " " + s.linkSeen);
        return out;
    }

    @Test
    void aLinkedStepShowsTheChainAndTheValueSeen() {
        copyThenSearch();
        Inspector.StepLine search = sink.steps().get(1);
        // the person reads the value seen, never the reference
        assertThat(search.line).isEqualTo("type(#search 'ORD-1641')");
        assertThat(search.links).extracting(l -> l.words + " = " + l.seen).containsExactly("from step 1: Copy added = ORD-1641");
        assertThat(sink.steps().get(0).usedBy).containsExactly("2");
        assertThat(sink.steps().get(0).links).isEmpty();
        assertThat(sink.steps().get(0).unique).isNull();
        assertThat(search.unique).isEqualTo("off");
        // the agent still reads the step as it is kept
        assertThat(session.recordedSteps().get(1).action.describe()).contains(NUMBER);
    }

    @Test
    void theStepMenuOffersEarlierChangesInWords() {
        copyThenSearch();
        assertThat(sink.unlink(id(1)).ok).isTrue();
        Map<String, Object> note = typing("note", "Copy of ORD-1641", 3);
        assertThat(sink.done(note).ok).isTrue();
        assertThat(sink.unlink(id(2)).ok).isTrue();
        assertThat(sink.done(typing("other", "ORD-1640", 4)).ok).isTrue();

        // what an earlier step changed that is in this step, in words; never the whole UI, never a path
        assertThat(sink.steps().get(1).offers).extracting(o -> o.words)
                .containsExactly("Step 1, Copy added to Orders: ORD-1641");
        assertThat(sink.steps().get(2).offers).extracting(o -> o.words)
                .containsExactly("Step 1, Copy added to Orders: ORD-1641");
        // ORD-1640 was there before Copy: Copy did not make it
        assertThat(sink.steps().get(3).offers).isEmpty();
        assertThat(sink.steps().get(0).offers).isEmpty();

        // after Done the value index is kept: still offered, and the person links by hand
        sink.end();
        Inspector.LinkOffer offer = sink.steps().get(2).offers.get(0);
        Inspector.Result r = sink.link(id(2), offer.ref);
        assertThat(r.ok).as(r.line).isTrue();
        assertThat(r.line).isEqualTo("linked step 3 to what step 1 changed");
        assertThat(text(session.recordedSteps().get(2))).isEqualTo("Copy of " + NUMBER);
        assertThat(sink.undoLabel()).isEqualTo("link of step 3 by the person");
        assertThat(sink.steps().get(2).links).extracting(l -> l.words).containsExactly("from step 1: Copy added");
        // an offer that no longer fits says so
        Inspector.Result stale = sink.link(id(3), offer.ref);
        assertThat(stale.ok).isFalse();
        assertThat(stale.line).isEqualTo("nothing in step 4 is what step 1 changed (nothing linked)");
    }

    @Test
    void useTheExactValueUnlinks() {
        copyThenSearch();
        Inspector.Result r = sink.unlink(id(1));
        assertThat(r.ok).isTrue();
        assertThat(r.line).isEqualTo("used the exact value in step 2");
        assertThat(text(session.recordedSteps().get(1))).isEqualTo("ORD-1641");
        assertThat(sink.steps().get(1).links).isEmpty();
        assertThat(sink.steps().get(0).usedBy).isEmpty();
        assertThat(sink.undoLabel()).isEqualTo("exact value of step 2 by the person");

        assertThat(sink.undo().ok).isTrue();
        assertThat(text(session.recordedSteps().get(1))).isEqualTo(NUMBER);
        assertThat(sink.steps().get(1).links).extracting(l -> l.words).containsExactly("from step 1: Copy added");
        // nothing to turn back: refused in words
        assertThat(sink.unlink(id(0)).line).isEqualTo("step 1 uses no value of an earlier step");
    }

    @Test
    void uniquePerRunAddsRunIdAndLinksLaterUses() throws Exception {
        // a short text too: the person asked for it
        assertThat(sink.done(typing("customer", "Al", 1)).ok).isTrue();
        assertThat(sink.done(step("click", Locator.ofId("save"))).ok).isTrue();
        assertThat(sink.done(typing("search", "Orders of Al", 3)).ok).isTrue();
        Map<String, Object> check = step("expect", Locator.ofId("status"));
        check.put("text", "Al");
        assertThat(sink.check(check).ok).isTrue();
        assertThat(sink.done(typing("note", "Alice", 4)).ok).isTrue();
        List<String> before = kept();
        backend.verbs.calls.clear();

        Inspector.Result r = sink.unique(id(0), "end");
        assertThat(r.ok).as(r.line).isTrue();
        assertThat(r.line).isEqualTo("made the text of step 1 unique per run; step 3 uses it");
        List<RecordedStep> steps = session.recordedSteps();
        assertThat(text(steps.get(0))).isEqualTo("Al ${run.id}");
        assertThat(text(steps.get(2))).isEqualTo("Orders of Al ${run.id}");
        assertThat(((io.github.kormcrete.swingui.flow.verb.ExpectVerb) steps.get(3).action).text).isEqualTo("Al ${run.id}");
        assertThat(text(steps.get(4))).isEqualTo("Alice");
        // nothing is typed again into the app: the recording goes on with the plain text
        assertThat(backend.verbs.calls).isEmpty();
        assertThat(sink.steps().get(0).line).isEqualTo("type(#customer 'Al')");
        assertThat(sink.steps().get(0).unique).isEqualTo("end");
        assertThat(sink.steps().get(0).links).extracting(l -> l.words).containsExactly("unique per run");
        assertThat(sink.steps().get(2).links).extracting(l -> l.words).containsExactly("unique per run");
        assertThat(sink.steps().get(2).line).isEqualTo("type(#search 'Orders of Al')");
        // one edit: Undo takes it all back
        assertThat(sink.undoLabel()).isEqualTo("unique per run of step 1 by the person");
        assertThat(sink.undo().ok).isTrue();
        assertThat(kept()).isEqualTo(before);

        // on again; a step recorded later that types the same text uses it at once
        assertThat(sink.unique(id(0), "end").ok).isTrue();
        assertThat(sink.done(typing("filter", "Al", 5)).ok).isTrue();
        assertThat(text(session.recordedSteps().get(5))).isEqualTo("Al ${run.id}");

        // at the start instead: the steps that use it follow
        assertThat(sink.unique(id(0), "start").ok).isTrue();
        steps = session.recordedSteps();
        assertThat(text(steps.get(0))).isEqualTo("${run.id} Al");
        assertThat(text(steps.get(2))).isEqualTo("Orders of ${run.id} Al");
        assertThat(text(steps.get(5))).isEqualTo("${run.id} Al");

        // off: the plain text everywhere
        assertThat(sink.unique(id(0), "off").ok).isTrue();
        steps = session.recordedSteps();
        assertThat(text(steps.get(0))).isEqualTo("Al");
        assertThat(text(steps.get(2))).isEqualTo("Orders of Al");
        assertThat(((io.github.kormcrete.swingui.flow.verb.ExpectVerb) steps.get(3).action).text).isEqualTo("Al");
        assertThat(text(steps.get(5))).isEqualTo("Al");
        assertThat(steps.get(0).linkSeen).isEmpty();
        assertThat(steps.get(2).linkSeen).isEmpty();
        assertThat(sink.steps().get(0).unique).isEqualTo("off");

        // only a typed text can be unique per run
        assertThat(sink.unique(id(1), "end").line).isEqualTo("only a typed text can be unique per run");
    }

    @Test
    void theExactValueOfAUniqueTextIsTheTextAsTypedAndItsUsesFollow() throws Exception {
        assertThat(sink.done(typing("customer", "Al", 1)).ok).isTrue();
        assertThat(sink.done(typing("search", "Orders of Al", 2)).ok).isTrue();
        assertThat(sink.unique(id(0), "end").ok).isTrue();
        call("{\"op\":\"stop\"}");
        noReset(session);
        assertThat(call("{\"op\":\"replay\"}").path("passed").asBoolean()).isTrue();
        String typedNow = typed().get(0);
        String runId = typedNow.substring("type #customer Al ".length(), "type #customer Al ".length() + 8);
        // the person reads what the replay typed
        assertThat(sink.steps().get(0).line).isEqualTo("type(#customer 'Al " + runId + "')");
        assertThat(sink.steps().get(0).links).extracting(l -> l.words + " = " + l.seen)
                .containsExactly("unique per run = " + runId);

        // the exact value of a text made unique is the text as typed, and its uses follow: the steps never drift apart
        Inspector.Result r = sink.unlink(id(0));
        assertThat(r.ok).as(r.line).isTrue();
        assertThat(r.line).isEqualTo("used the exact value in step 1; step 2 uses the plain text again");
        assertThat(text(session.recordedSteps().get(0))).isEqualTo("Al");
        assertThat(text(session.recordedSteps().get(1))).isEqualTo("Orders of Al");
        assertThat(session.recordedSteps().get(1).linkSeen).isEmpty();
        assertThat(sink.undoLabel()).isEqualTo("exact value of step 1 by the person");
        assertThat(sink.undo().ok).isTrue();
        assertThat(text(session.recordedSteps().get(1))).isEqualTo("Orders of Al ${run.id}");
    }

    /** A recorded test file with {@code texts} typed one after the other into #t1, #t2, ... (format 4: references). */
    private Path typedFile(String... texts) throws Exception {
        StringBuilder steps = new StringBuilder();
        for (int i = 0; i < texts.length; i++) {
            if (i > 0) steps.append(',');
            steps.append("{\"action\":\"type\",\"target\":{\"id\":\"t").append(i + 1).append("\"},\"text\":\"")
                    .append(texts[i]).append("\"}");
        }
        Path file = dir.resolve("typed.json");
        Files.write(file, ("{\"version\":4,\"name\":\"typed\",\"start\":\"reset\",\"steps\":[" + steps + "]}")
                .getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    void uniquePerRunIsOfferedOnTheTextItselfAndItsUsesFollowByWholeWords() throws Exception {
        session.loadRecordedTest(typedFile("Al ${run.id}", "XAl ${run.id}", "Orders of Al ${run.id}"));
        // a text of its own (even one that holds the other's at the end of a word); a use of step 1's text has none
        assertThat(sink.steps()).extracting(l -> l.unique).containsExactly("end", "end", null);
        Inspector.Result there = sink.unique(id(2), "off");
        assertThat(there.ok).isFalse();
        assertThat(there.line).isEqualTo("step 3 uses the unique text of step 1: change it there");

        assertThat(sink.unique(id(0), "start").ok).isTrue();
        List<RecordedStep> steps = session.recordedSteps();
        assertThat(text(steps.get(0))).isEqualTo("${run.id} Al");
        // XAl is another word: it stays
        assertThat(text(steps.get(1))).isEqualTo("XAl ${run.id}");
        assertThat(text(steps.get(2))).isEqualTo("Orders of ${run.id} Al");
        assertThat(sink.steps()).extracting(l -> l.unique).containsExactly("start", "end", null);
    }

    @Test
    void theListShowsWhatALaterStepCanLinkAndCountsTheRest() throws Exception {
        io.github.kormcrete.swingui.driver.outcome.UiReading after = io.github.kormcrete.swingui.driver.outcome.UiReading
                .builder().field("Number", "ORD-1643").table("Orders", java.util.Collections.singletonList("Number"),
                        Arrays.asList(java.util.Collections.singletonList("ORD-1640"),
                                java.util.Collections.singletonList("ORD-1641"),
                                java.util.Collections.singletonList("ORD-1642"),
                                java.util.Collections.singletonList("ORD-1643"))).build();
        session.reader(readings(orders("ORD-1640"), after));
        session.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Search\"}}"));
        JsonNode step = call("{\"op\":\"list\"}").at("/steps/0");
        assertThat(step.path("changed").toString()).isEqualTo("{\".fields.Number\":\"ORD-1643\"}");
        // the value index keeps one path per value: ORD-1643 under the field, ORD-1641 and ORD-1642 in a list of three rows
        assertThat(step.path("changedMore").asInt()).isEqualTo(2);
    }

    @Test
    void theListShowsWhatALaterStepUsesFirstAndAtMostTwenty() throws Exception {
        io.github.kormcrete.swingui.driver.outcome.UiReading.Builder after =
                io.github.kormcrete.swingui.driver.outcome.UiReading.builder();
        for (int k = 1; k <= 25; k++) after.field("Field " + k, "VAL-" + (1000 + k));
        session.reader(readings(orders("ORD-1640"), after.build(), after.build()));
        session.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Open\"}}"));
        session.verb("type", mapper.readTree("{\"target\":{\"id\":\"search\"},\"text\":\"VAL-1025\"}"));
        JsonNode step = call("{\"op\":\"list\"}").at("/steps/0");
        List<String> paths = new java.util.ArrayList<String>();
        step.path("changed").fieldNames().forEachRemaining(paths::add);
        assertThat(paths).hasSize(20);
        // the one step 2 uses first, then the value index's order
        assertThat(paths.get(0)).isEqualTo(".fields['Field 25']");
        assertThat(paths.get(1)).isEqualTo(".fields['Field 1']");
        assertThat(step.path("changedMore").asInt()).isEqualTo(5);
    }

    @Test
    void aCheckThatUsesAValueNotKnownYetIsCalledACheck() throws Exception {
        // Copy, then a step whose check (2.1) types nothing but expects the copy's number
        Path file = dir.resolve("copy-and-expect.json");
        Files.write(file, ("{\"version\":4,\"name\":\"copy-and-expect\",\"start\":\"reset\",\"steps\":["
                + "{\"action\":\"click\",\"id\":\"copy\",\"target\":{\"role\":\"button\",\"name\":\"Copy\"}},"
                + "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Orders\"},\"checks\":[{\"target\":"
                + "{\"id\":\"number\"},\"text\":\"" + NUMBER + "\"}]}]}").getBytes(StandardCharsets.UTF_8));
        session.loadRecordedTest(file);
        assertThat(call("{\"op\":\"list\"}").at("/steps/1/checks/0/uses/0/unknown").asText())
                .startsWith("Check 2.1 uses what step 1 changed");
        noReset(session);
        assertThat(why("{\"op\":\"replay\",\"from\":2}")).endsWith("Check 2.1 uses what step 1 changed; replay from step 1 or earlier");
    }

    @Test
    void aValueNotKnownYetIsSaidInWordsAndNothingChanges() throws Exception {
        // a loaded recorded test that has not run: step 2 uses what step 1 changed, which no run has shown yet
        session.loadRecordedTest(copyAndSearch());
        List<String> before = kept();
        String why = "Step 2 uses what step 1 changed, and that value is not known yet: replay up to step 1 first";
        // the exact value: greyed in the palette, saying why
        assertThat(sink.steps().get(1).links).extracting(l -> l.unknown).containsExactly(why);
        Inspector.Result exact = sink.unlink(id(1));
        assertThat(exact.ok).isFalse();
        assertThat(exact.line).isEqualTo(why).doesNotContain("${");
        // deleting step 1 cannot keep a value no one knows: refused before anything changes
        Inspector.Result delete = sink.delete(id(0));
        assertThat(delete.ok).isFalse();
        assertThat(delete.line).isEqualTo(why + " (nothing deleted)");
        assertThat(kept()).isEqualTo(before);
        assertThat(sink.undoLabel()).isNull();
    }

    @Test
    void deletingALinkedSourceWarnsTurnsLinksExactAndUndoRestoresThem() throws Exception {
        copyThenSearch();
        Map<String, Object> check = step("expect", Locator.ofId("total"));
        check.put("text", "ORD-1641");
        assertThat(sink.check(check).ok).isTrue();
        assertThat(sink.done(typing("note", "Copy of ORD-1641", 3)).ok).isTrue();
        List<RecordedStep> steps = session.recordedSteps();
        assertThat(text(steps.get(1))).isEqualTo(NUMBER);
        assertThat(((io.github.kormcrete.swingui.flow.verb.ExpectVerb) steps.get(2).action).text).isEqualTo(NUMBER);
        assertThat(text(steps.get(3))).isEqualTo("Copy of " + NUMBER);
        // what the palette asks about: the steps that use what step 1 changed
        assertThat(sink.steps().get(0).usedBy).containsExactly("2", "2.1", "3");
        List<String> before = kept();

        Inspector.Result r = sink.delete(id(0));
        assertThat(r.ok).as(r.line).isTrue();
        assertThat(r.line).isEqualTo("deleted step 1; steps 2 and 3 keep the values they used");
        steps = session.recordedSteps();
        assertThat(text(steps.get(0))).isEqualTo("ORD-1641");
        assertThat(((io.github.kormcrete.swingui.flow.verb.ExpectVerb) steps.get(1).action).text).isEqualTo("ORD-1641");
        assertThat(text(steps.get(2))).isEqualTo("Copy of ORD-1641");
        for (RecordedStep s : steps) assertThat(s.linkSeen).isEmpty();
        assertThat(sink.undoLabel()).isEqualTo("delete of step 1 by the person");

        // Undo: the step and the links come back exactly
        assertThat(sink.undo().ok).isTrue();
        assertThat(kept()).isEqualTo(before);
        assertThat(session.runValues().fill(NUMBER)).isEqualTo("ORD-1641");
    }

    @Test
    void aStepWithNothingToLinkMakesNoEdit() throws Exception {
        session.reader(readings(orders("ORD-1640"), orders("ORD-1640", "ORD-1641")));
        session.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Copy\"}}"));
        session.verb("type", mapper.readTree("{\"target\":{\"id\":\"search\"},\"text\":\"ORD-1640\"}"));

        assertThat(session.recordedSteps().get(0).action.id).isNull();
        assertThat(sink.undoLabel()).isNull();
    }

    @Test
    void anAgentStepGetsItsOutcomeAtOnce() throws Exception {
        session.reader(readings(orders("ORD-1640"), orders("ORD-1640", "ORD-1641")));

        session.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Copy\"}}"));

        RecordedStep copy = session.recordedSteps().get(0);
        assertThat(copy.outcome.at("/rows/Orders/added/0/Number").asText()).isEqualTo("ORD-1641");
        assertThat(copy.newValues).containsEntry("ORD-1641", ".rows.Orders.added[0].Number");
        assertThat(reads).isEqualTo(2);   // before and after the step
        // a check changes nothing: its own change is not read, only what the action before it made late (one reading)
        session.verb("expect", mapper.readTree("{\"target\":{\"id\":\"status\"},\"text\":\"New\"}"));
        assertThat(reads).isEqualTo(3);
        // the checks after it read nothing
        session.verb("expect", mapper.readTree("{\"target\":{\"id\":\"status\"},\"text\":\"New\"}"));
        session.verb("expect", mapper.readTree("{\"target\":{\"id\":\"total\"},\"text\":\"0.00\"}"));
        assertThat(reads).isEqualTo(3);
        // a step that is not recorded is not read either
        session.doAction(mapper.readTree("{\"action\":\"click\",\"target\":{\"id\":\"next\"}}"), false);
        assertThat(reads).isEqualTo(3);
    }

    @Test
    void anAgentStepGetsWhatItChangesLateUntilTheNextAction() throws Exception {
        // Copy adds its order a moment after the click settled: the reading after it has none yet
        session.reader(readings(orders("ORD-1640"), orders("ORD-1640"), orders("ORD-1640", "ORD-1641")));
        session.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Copy\"}}"));
        assertThat(session.recordedSteps().get(0).newValues).isEmpty();

        // the check waited for it: the copy's step has it now, and the check is linked to it
        JsonNode checked = mapper.readTree(session.verb("expect", mapper.readTree(
                "{\"target\":{\"role\":\"label\",\"name\":\"Last order\"},\"text\":\"ORD-1641\"}")));
        assertThat(checked.at("/recorded/text").asText()).isEqualTo(NUMBER);
        assertThat(session.recordedSteps().get(0).newValues).containsEntry("ORD-1641", ".rows.Orders.added[0].Number");
        // the next action is read before it acts, and links to it as well
        session.verb("click", mapper.readTree(
                "{\"target\":{\"role\":\"table\",\"name\":\"Orders\",\"row\":{\"Number\":\"ORD-1641\"}}}"));
        assertThat(mapper.valueToTree(session.recordedSteps().get(2).action).at("/target/row/Number").asText())
                .isEqualTo(NUMBER);
        // after that action, Copy's step keeps what it had: what comes now is the click's
        session.reader(readings(orders("ORD-1640", "ORD-1641", "ORD-1642")));
        session.verb("type", mapper.readTree("{\"target\":{\"id\":\"search\"},\"text\":\"x\"}"));
        assertThat(session.recordedSteps().get(0).newValues).containsOnlyKeys("ORD-1641");
    }

    @Test
    void whatAnUnrecordedActionChangesIsNeverTheRecordedStepBeforeIt() throws Exception {
        session.reader(readings(orders("ORD-1640"), orders("ORD-1640"), orders("ORD-1640", "ORD-1641")));
        session.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Copy\"}}"));
        session.doAction(mapper.readTree("{\"action\":\"click\",\"target\":{\"id\":\"new\"}}"), false);

        session.verb("click", mapper.readTree("{\"target\":{\"id\":\"next\"}}"));

        assertThat(session.recordedSteps().get(0).newValues).isEmpty();
    }

    @Test
    void olderStepsShrinkToTheValueIndex() throws Exception {
        int[] call = {0};
        session.reader(() -> {
            // each step adds one order: before it n rows, after it n + 1
            int n = call[0]++ / 2 + (call[0] % 2 == 0 ? 1 : 0);
            String[] numbers = new String[n];
            for (int k = 0; k < n; k++) numbers[k] = "ORD-" + (2000 + k);
            return orders(numbers);
        });
        for (int i = 0; i < 25; i++) session.verb("click", mapper.readTree("{\"target\":{\"id\":\"copy\"}}"));

        List<RecordedStep> steps = session.recordedSteps();
        assertThat(steps).hasSize(25);
        for (int i = 0; i < 25; i++) {
            RecordedStep s = steps.get(i);
            assertThat(s.newValues).as("step " + (i + 1)).containsEntry("ORD-" + (2000 + i), ".rows.Orders.added[0].Number");
            if (i < 5) assertThat(s.outcome).as("step " + (i + 1)).isNull();
            else assertThat(s.outcome).as("step " + (i + 1)).isNotNull();
        }
    }

    @Test
    void theAgentsAndThePersonsDollarBraceIsTextNotAReference() throws Exception {
        session.verb("type", mapper.readTree("{\"target\":{\"id\":\"note\"},\"text\":\"costs ${price}\"}"));
        Map<String, Object> typed = step("type", Locator.ofId("memo"));
        typed.put("text", "see ${total}");
        assertThat(sink.done(typed).ok).isTrue();

        // typed as given, kept escaped: a replay types it as it was typed
        assertThat(typed()).containsExactly("type #note costs ${price} null null");
        JsonNode list = call("{\"op\":\"list\"}").path("steps");
        assertThat(list.get(0).path("step").path("text").asText()).isEqualTo("costs $${price}");
        assertThat(list.get(1).path("step").path("text").asText()).isEqualTo("see $${total}");
        noReset(session);
        backend.verbs.calls.clear();
        assertThat(call("{\"op\":\"replay\"}").path("passed").asBoolean()).isTrue();
        assertThat(typed()).containsExactly("type #note costs ${price} null null", "type #memo see ${total} null null");
    }

    @Test
    void anOlderFilesDollarBraceTextIsTypedAsItWasOnReplay() throws Exception {
        Path file = dir.resolve("note.json");
        Files.write(file, ("{\"version\":3,\"name\":\"note\",\"start\":\"reset\",\"steps\":["
                + "{\"action\":\"type\",\"target\":{\"id\":\"note\"},\"text\":\"Total ${x}\"}]}").getBytes(StandardCharsets.UTF_8));
        session.loadRecordedTest(file);
        noReset(session);

        assertThat(call("{\"op\":\"replay\"}").path("passed").asBoolean()).isTrue();
        assertThat(typed()).containsExactly("type #note Total ${x} null null");
    }

    @Test
    void replayFromTheMiddleUsesTheLatestOutcomeInThisSession() throws Exception {
        session.loadRecordedTest(copyAndSearch());
        noReset(session);
        session.reader(readings(orders("ORD-1640"), orders("ORD-1640", "ORD-1641")));
        assertThat(call("{\"op\":\"replay\"}").path("passed").asBoolean()).isTrue();
        assertThat(typed()).containsExactly("type #search ORD-1641 null null");

        backend.verbs.calls.clear();
        assertThat(call("{\"op\":\"replay\",\"from\":2}").path("passed").asBoolean()).isTrue();
        assertThat(typed()).containsExactly("type #search ORD-1641 null null");

        // step 1 alone again: the app copies another order; step 2 now uses that one
        session.reader(readings(orders("ORD-1640", "ORD-1641"), orders("ORD-1640", "ORD-1641", "ORD-1642")));
        assertThat(call("{\"op\":\"replay\",\"from\":1,\"to\":1}").path("passed").asBoolean()).isTrue();
        backend.verbs.calls.clear();
        assertThat(call("{\"op\":\"replay\",\"from\":2}").path("passed").asBoolean()).isTrue();
        assertThat(typed()).containsExactly("type #search ORD-1642 null null");
    }

    @Test
    void replayFromTheMiddleWithoutAnOutcomeIsRefusedInWords() throws Exception {
        session.loadRecordedTest(copyAndSearch());
        noReset(session);

        DriverException refused = catchThrowableOfType(() -> call("{\"op\":\"replay\",\"from\":2}"), DriverException.class);

        assertThat(refused.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(refused.getMessage()).endsWith("Step 2 uses what step 1 changed; replay from step 1 or earlier");
        assertThat(backend.verbs.calls).isEmpty();   // no step ran
        // the palette's From selected is refused alike, before any step
        Inspector.Result palette = sink.replaying(1, 2);
        assertThat(palette.ok).isFalse();
        assertThat(palette.line).isEqualTo("Step 2 uses what step 1 changed; replay from step 1 or earlier");
        assertThat(sink.replaying(0, 2).ok).isTrue();
    }

    @Test
    void aStepWhoseValueNeverCameFailsInWords() throws Exception {
        session.loadRecordedTest(copyAndSearch());
        noReset(session);
        session.linkWait(0L);
        session.reader(readings(orders("ORD-1640")));   // Copy added nothing

        JsonNode r = call("{\"op\":\"replay\"}");

        assertThat(r.path("passed").asBoolean()).isFalse();
        assertThat(r.path("steps").get(1).path("message").asText()).isEqualTo("NOT_FOUND: Step 1 (Copy) added no row to Orders");
        assertThat(typed()).isEmpty();
    }

    @Test
    void aStepWhoseValueNeverCameNamesTheFilledSearch() throws Exception {
        session.loadRecordedTest(copyAndSearch());
        noReset(session);
        session.linkWait(0L);
        session.reader(readings(io.github.kormcrete.swingui.driver.outcome.UiReading.builder().textField("Search", "ORD-1640")
                .table("Orders", java.util.Collections.singletonList("Number"),
                        java.util.Collections.singletonList(java.util.Collections.singletonList("ORD-1640"))).build()));

        JsonNode r = call("{\"op\":\"replay\"}");

        assertThat(r.path("steps").get(1).path("message").asText()).isEqualTo("NOT_FOUND: Step 1 (Copy) added no row to Orders; "
                + "the table shows 1 row and Search holds \"ORD-1640\": a filter may hide the new row");
    }

    @Test
    void replayAllAndRedoStartAFreshRunFromTheMiddleKeepsIt() throws Exception {
        session.loadRecordedTest(copyAndSearch());
        noReset(session);
        session.reader(readings(orders("ORD-1640"), orders("ORD-1640", "ORD-1641")));
        String first = session.runValues().fill("${run.id}");
        assertThat(call("{\"op\":\"replay\"}").path("passed").asBoolean()).isTrue();
        String all = session.runValues().fill("${run.id}");
        assertThat(call("{\"op\":\"replay\",\"from\":2}").path("passed").asBoolean()).isTrue();
        String middle = session.runValues().fill("${run.id}");
        session.reader(readings(orders("ORD-1640", "ORD-1641"), orders("ORD-1640", "ORD-1641", "ORD-1642")));
        assertThat(mapper.readTree(session.recordingRedo(3, null)).path("passed").asBoolean()).isTrue();
        String redo = session.runValues().fill("${run.id}");

        assertThat(all).isNotEqualTo(first);
        assertThat(middle).isEqualTo(all);
        assertThat(redo).isNotEqualTo(middle);
        assertThat(sink.replaying(0, 2).ok).isTrue();
        assertThat(session.runValues().fill("${run.id}")).isNotEqualTo(redo);
    }

    @Test
    void redoRefreshesOutcomesAndSeenValues() throws Exception {
        session.loadRecordedTest(copyAndSearch());
        noReset(session);
        session.reader(readings(orders("ORD-1640"), orders("ORD-1640", "ORD-1641")));
        assertThat(call("{\"op\":\"replay\"}").path("passed").asBoolean()).isTrue();
        assertThat(session.recordedSteps().get(1).linkSeen).containsExactly(entry(NUMBER, "ORD-1641"));

        session.reader(readings(orders("ORD-1640", "ORD-1641"), orders("ORD-1640", "ORD-1641", "ORD-1642")));
        JsonNode redo = mapper.readTree(session.recordingRedo(3, null));

        assertThat(redo.path("passed").asBoolean()).isTrue();
        assertThat(session.recordedSteps().get(0).newValues).containsEntry("ORD-1642", ".rows.Orders.added[0].Number");
        assertThat(session.recordedSteps().get(1).linkSeen).containsExactly(entry(NUMBER, "ORD-1642"));
        assertThat(session.runValues().fill(NUMBER)).isEqualTo("ORD-1642");
    }

    private static Map.Entry<String, String> entry(String k, String v) {
        return new java.util.AbstractMap.SimpleEntry<String, String>(k, v);
    }

    @Test
    void resetClearsEverything() throws Exception {
        session.reader(readings(orders("ORD-1640"), orders("ORD-1640", "ORD-1641")));
        session.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Copy\"}}"));
        int id = session.recordedSteps().get(0).id;
        assertThat(session.runValues().has("#" + id)).isTrue();

        session.startRecording(true);   // what session reset does

        assertThat(session.recordedSteps()).isEmpty();
        assertThat(session.runValues().has("#" + id)).isFalse();
        // Done (or stop) keeps only the value index: links made afterwards still find the values
        session.reader(readings(orders("ORD-1640"), orders("ORD-1640", "ORD-1641")));
        session.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Copy\"}}"));
        call("{\"op\":\"stop\"}");
        assertThat(session.recordedSteps().get(0).outcome).isNull();
        assertThat(session.recordedSteps().get(0).newValues).containsEntry("ORD-1641", ".rows.Orders.added[0].Number");
        // and a new recording forgets that too
        session.startRecording(true);
        assertThat(session.runValues().has("#" + (id + 1))).isFalse();
    }

    @Test
    void aPersonsOutcomeReadAfterAReplayBeganLeavesTheReplaysOutcome() throws Exception {
        noReset(session);
        Map<String, Object> copy = step("click", Locator.of("button", "Copy"));
        copy.put(Inspector.SEQ, 1);
        assertThat(sink.done(copy).ok).isTrue();
        Map<String, Object> stale = io.github.kormcrete.swingui.driver.outcome.Outcomes.diff(orders(), orders("ORD-1999"));
        Map<String, String> staleValues = io.github.kormcrete.swingui.driver.outcome.Outcomes.newValues(orders(), stale);

        // the agent replays while the person records; the person's reading comes once the replay let go
        session.reader(readings(orders("ORD-1640"), orders("ORD-1640", "ORD-1641")));
        assertThat(call("{\"op\":\"replay\"}").path("passed").asBoolean()).isTrue();
        sink.outcome(1, stale, staleValues);
        assertThat(session.recordedSteps().get(0).newValues).containsOnlyKeys("ORD-1641");

        // the palette's replay alike
        session.reader(readings(orders("ORD-1640"), orders("ORD-1640", "ORD-1642")));
        assertThat(sink.replaying(0, 1).ok).isTrue();
        assertThat(sink.replay(0).ok).isTrue();
        sink.outcome(1, stale, staleValues);
        assertThat(session.recordedSteps().get(0).newValues).containsOnlyKeys("ORD-1642");
        assertThat(session.runValues().has("#" + session.recordedSteps().get(0).id)).isTrue();
    }

    @Test
    void replayFromTheMiddleIsRefusedWhenTheValueIndexLacksTheValueAStepUses() throws Exception {
        noReset(session);
        session.reader(readings(orders("ORD-1640"), orders("ORD-1640", "ORD-1641")));
        session.doAction(mapper.readTree("{\"action\":\"click\",\"id\":\"copy\",\"target\":{\"role\":\"button\","
                + "\"name\":\"Copy\"}}"));
        call("{\"op\":\"insert\",\"at\":2,\"step\":{\"action\":\"type\",\"target\":{\"id\":\"count\"},"
                + "\"text\":\"${copy.rows.Orders.count}\"}}");
        call("{\"op\":\"insert\",\"at\":3,\"step\":{\"action\":\"type\",\"target\":{\"id\":\"search\"},"
                + "\"text\":\"" + NUMBER + "\"}}");
        call("{\"op\":\"stop\"}");   // the outcome shrinks to its value index: the new number, not the row count
        backend.verbs.calls.clear();

        DriverException refused = catchThrowableOfType(() -> call("{\"op\":\"replay\",\"from\":2}"), DriverException.class);

        assertThat(refused.getMessage()).endsWith("Step 2 uses what step 1 changed; replay from step 1 or earlier");
        assertThat(backend.verbs.calls).isEmpty();
        // the number is in the index: step 3 alone may replay
        assertThat(call("{\"op\":\"replay\",\"from\":3}").path("passed").asBoolean()).isTrue();
        assertThat(typed()).containsExactly("type #search ORD-1641 null null");
    }

    @Test
    void anIntParamThatUsesAnEarlierValueIsFilledOnReplay() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs), DomainStep.act("setQty", "Set the quantity to {qty}")
                .param("qty", ParamType.INT)
                .steps("{\"action\":\"type\",\"target\":{\"role\":\"spinner\",\"name\":\"Qty\"},\"text\":\"{qty}\"}")
                .build());
        Path file = dir.resolve("same-qty.json");
        Files.write(file, ("{\"version\":4,\"name\":\"same-qty\",\"start\":\"reset\",\"requires\":[\"order-desk\"],\"steps\":["
                + "{\"action\":\"click\",\"id\":\"last\",\"target\":{\"role\":\"button\",\"name\":\"Last order\"}},"
                + "{\"action\":\"do\",\"step\":\"order-desk:setQty\",\"params\":{\"qty\":\"${last.fields.Qty}\"}}]}")
                .getBytes(StandardCharsets.UTF_8));
        s.loadRecordedTest(file);
        s.reader(readings(io.github.kormcrete.swingui.driver.outcome.UiReading.builder().field("Qty", "1").build(),
                io.github.kormcrete.swingui.driver.outcome.UiReading.builder().field("Qty", "3").build()));

        JsonNode r = recording(s, "{\"op\":\"replay\"}");

        assertThat(r.path("passed").asBoolean()).as(r.toString()).isTrue();
        assertThat(backend.verbs.calls).contains("type spinner 'Qty' 3 null null");
    }
    // ---- the agent's own links: link, unlink, remember and unique ops of the recording tool ----------------------

    /** Orders: one row per number, with its customer. */
    private static io.github.kormcrete.swingui.driver.outcome.UiReading ordersOf(String... numberCustomer) {
        List<List<String>> rows = new java.util.ArrayList<List<String>>();
        for (int i = 0; i < numberCustomer.length; i += 2) rows.add(Arrays.asList(numberCustomer[i], numberCustomer[i + 1]));
        return io.github.kormcrete.swingui.driver.outcome.UiReading.builder()
                .table("Orders", Arrays.asList("Number", "Customer"), rows).build();
    }

    private String textOf(int flat) {
        return ((io.github.kormcrete.swingui.flow.verb.TypeVerb) session.recordedSteps().get(flat).action).text;
    }

    private String why(String json) {
        return catchThrowableOfType(() -> call(json), DriverException.class).getMessage();
    }

    @Test
    void aRedoFromStepOneThatOnlyResetsSaysWhatTheStartScreenHolds() throws Exception {
        noReset(session);
        session.startReader(readings(io.github.kormcrete.swingui.driver.outcome.UiReading.builder()
                .textField("Search", "ORD-1640").table("Orders", Arrays.asList("Number"),
                        java.util.Collections.singletonList(java.util.Collections.singletonList("ORD-1640"))).build()));
        session.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Copy\"}}"));
        call("{\"op\":\"stop\"}");

        JsonNode r = call("{\"op\":\"redo\",\"from\":1}");

        assertThat(r.path("ran").asInt()).isEqualTo(0);
        assertThat(r.path("startNote").asText()).startsWith("Search holds \"ORD-1640\" next to the table Orders");
    }

    @Test
    void aResetThatLeavesASearchFilledSaysSo() throws Exception {
        io.github.kormcrete.swingui.driver.outcome.UiReading filled = io.github.kormcrete.swingui.driver.outcome.UiReading
                .builder().textField("Search", "ORD-1640").table("Orders", Arrays.asList("Number"),
                        java.util.Collections.singletonList(java.util.Collections.singletonList("ORD-1640"))).build();
        noReset(session);
        session.startReader(readings(filled));
        JsonNode started = call("{\"op\":\"start\",\"reset\":true}");
        assertThat(started.path("startNote").asText()).startsWith("Search holds \"ORD-1640\" next to the table Orders");
        session.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Copy\"}}"));
        call("{\"op\":\"stop\"}");
        assertThat(call("{\"op\":\"replay\"}").path("startNote").asText())
                .startsWith("Search holds \"ORD-1640\" next to the table Orders");

        session.startReader(readings(io.github.kormcrete.swingui.driver.outcome.UiReading.builder().textField("Search", "")
                .table("Orders", Arrays.asList("Number"),
                        java.util.Collections.singletonList(java.util.Collections.singletonList("ORD-1640"))).build()));
        assertThat(call("{\"op\":\"replay\"}").has("startNote")).isFalse();
        assertThat(call("{\"op\":\"start\",\"reset\":true}").has("startNote")).isFalse();
    }

    @Test
    void anEditedTargetKeepsItsLinkWhereTheValueIsTheSame() throws Exception {
        session.reader(readings(orders("ORD-1640"), orders("ORD-1640", "ORD-1641")));
        session.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Copy\"}}"));
        session.verb("click", mapper.readTree("{\"target\":{\"role\":\"table\",\"name\":\"Orders\",\"row\":{\"Number\":"
                + "\"ORD-1641\"}}}"));
        assertThat(mapper.valueToTree(session.recordedSteps().get(1).action).at("/target/row/Number").asText())
                .isEqualTo(NUMBER);
        JsonNode r = call("{\"op\":\"edit\",\"n\":2,\"target\":{\"role\":\"table\",\"name\":\"Orders\","
                + "\"row\":{\"Number\":\"ORD-1641\"},\"column\":\"Number\"}}");
        assertThat(r.path("did").asText()).isEqualTo("edited step 2; its link kept where it has the value it stood for");
        JsonNode edited = mapper.valueToTree(session.recordedSteps().get(1).action);
        assertThat(edited.at("/target/row/Number").asText()).isEqualTo(NUMBER);
        assertThat(edited.at("/target/column").asText()).isEqualTo("Number");
        assertThat(r.at("/steps/1/uses/0/value").asText()).isEqualTo("ORD-1641");
        // another value is taken as typed
        call("{\"op\":\"edit\",\"n\":2,\"target\":{\"role\":\"table\",\"name\":\"Orders\",\"row\":{\"Number\":\"ORD-1640\"}}}");
        assertThat(mapper.valueToTree(session.recordedSteps().get(1).action).at("/target/row/Number").asText())
                .isEqualTo("ORD-1640");
        assertThat(call("{\"op\":\"list\"}").at("/steps/1").has("uses")).isFalse();
    }

    @Test
    void aLinkByFieldTakesAPlaceThatHoldsAValueOfAnEarlierRun() throws Exception {
        // a loaded test: Copy, then a check of the label in the copy's own tab, recorded on a run that made ORD-1641
        Path file = dir.resolve("copy-and-check.json");
        Files.write(file, ("{\"version\":4,\"name\":\"copy-and-check\",\"start\":\"reset\",\"steps\":[{\"action\":\"click\","
                + "\"id\":\"copy\",\"target\":{\"role\":\"button\",\"name\":\"Copy\"},\"checks\":[{\"target\":{\"scope\":"
                + "[\"tab:ORD-1641\"],\"role\":\"label\",\"name\":\"ORD-1641\"},\"shown\":true}]}]}")
                .getBytes(StandardCharsets.UTF_8));
        session.loadRecordedTest(file);
        noReset(session);
        session.reader(readings(orders("ORD-1640"), orders("ORD-1640", "ORD-1642")));
        call("{\"op\":\"replay\",\"to\":1}");   // the fake app passes the check; Copy's outcome (ORD-1642) is known now
        assertThat(why("{\"op\":\"link\",\"n\":\"1.1\",\"ref\":\"" + NUMBER + "\"}"))
                .contains("ORD-1642 is not in check 1.1", "name the place with field");
        JsonNode r = call("{\"op\":\"link\",\"n\":\"1.1\",\"field\":\"target.name\",\"ref\":\"" + NUMBER + "\"}");
        assertThat(r.path("did").asText()).isEqualTo("linked check 1.1 to what step 1 changed");
        JsonNode check = mapper.valueToTree(session.recordedSteps().get(1).action);
        assertThat(check.at("/target/name").asText()).isEqualTo(NUMBER);
        assertThat(check.at("/target/scope/0").asText()).isEqualTo("tab:" + NUMBER);
        assertThat(session.recordedSteps().get(1).linkSeen).containsExactly(entry(NUMBER, "ORD-1641"));
        // a place that does not hold a value of an earlier run: refused as before
        assertThat(why("{\"op\":\"link\",\"n\":\"1.1\",\"field\":\"target.role\",\"ref\":\"" + NUMBER + "\"}"))
                .contains("is not in check 1.1's target.role");
    }

    @Test
    void aLinkThroughAPathThatNamesItsValueIsRefused() throws Exception {
        // the step's outcome has the label keyed by its own text: .texts['ORD-1641']
        session.reader(readings(orders("ORD-1640"), io.github.kormcrete.swingui.driver.outcome.UiReading.builder()
                .table("Orders", java.util.Collections.singletonList("Number"),
                        java.util.Collections.singletonList(java.util.Collections.singletonList("ORD-1640")))
                .text("ORD-1641", "ORD-1641").build()));
        session.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Open\"}}"));
        session.verb("type", mapper.readTree("{\"target\":{\"id\":\"search\"},\"text\":\"ORD-1641\"}"));
        assertThat(why("{\"op\":\"link\",\"n\":2,\"ref\":\"${1.texts['ORD-1641']}\"}"))
                .contains("the path names the value itself", "another run's value has another name");
    }

    @Test
    void recordingOpsLinkUnlinkRememberUnique() throws Exception {
        session.reader(readings(ordersOf("ORD-1640", "Bea"), ordersOf("ORD-1640", "Bea", "ORD-1641", "Ada")));
        session.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Copy\"}}"));
        // a short value is never linked by itself
        session.verb("type", mapper.readTree("{\"target\":{\"id\":\"search\"},\"text\":\"Ada\"}"));
        assertThat(textOf(1)).isEqualTo("Ada");
        JsonNode list = call("{\"op\":\"list\"}");
        // what each step changed that a later one may use, by its path
        assertThat(list.at("/steps/0/changed").toString())
                .contains("\".rows.Orders.added[0].Number\":\"ORD-1641\"", "\".rows.Orders.added[0].Customer\":\"Ada\"");
        int rev = list.path("rev").asInt();

        // link: the agent names the value by the step number and its path in what that step changed
        String customer = "${1.rows.Orders.added[0].Customer}";
        JsonNode linked = call("{\"op\":\"link\",\"n\":2,\"field\":\"text\",\"ref\":\"" + customer + "\",\"rev\":" + rev + "}");
        assertThat(linked.path("did").asText()).isEqualTo("linked step 2 to what step 1 changed");
        assertThat(textOf(1)).isEqualTo("${copy.rows.Orders.added[0].Customer}");
        assertThat(session.recordedSteps().get(0).action.id).isEqualTo("copy");
        assertThat(linked.at("/steps/1/uses/0/from").asText()).isEqualTo("from step 1: Copy added");
        assertThat(linked.at("/steps/1/uses/0/value").asText()).isEqualTo("Ada");
        assertThat(linked.at("/steps/0/usedBy").toString()).isEqualTo("[\"2\"]");
        assertThat(sink.undoLabel()).isEqualTo("link of step 2 by the agent");

        // refused in words, nothing changed
        assertThat(why("{\"op\":\"link\",\"n\":1,\"ref\":\"${2.fields.Note}\"}"))
                .contains("step 1 can only use what a step before it changed or remembered");
        assertThat(why("{\"op\":\"link\",\"n\":2,\"ref\":\"nobody\"}"))
                .contains("no step or remembered value is named 'nobody'");
        assertThat(why("{\"op\":\"link\",\"n\":2,\"ref\":\"${1.rows.Orders.added[1].Number}\"}"))
                .contains("what step 1 changed has no value at .rows.Orders.added[1].Number");
        assertThat(why("{\"op\":\"link\",\"n\":2,\"field\":\"text\",\"ref\":\"${1.rows.Orders.added[0].Number}\"}"))
                .contains("ORD-1641 is not in step 2's text");
        assertThat(why("{\"op\":\"link\",\"n\":2,\"ref\":\"${run.id}\"}")).contains("unique");
        assertThat(why("{\"op\":\"link\",\"n\":2}")).contains("link needs 'n' and 'ref'");
        assertThat(why("{\"op\":\"link\",\"n\":2,\"ref\":\"" + customer + "\",\"rev\":" + rev + "}"))
                .contains("the recording changed since revision " + rev);

        // unlink: the value it stood for, as text; one edit the agent can undo
        assertThat(call("{\"op\":\"unlink\",\"n\":2}").path("did").asText()).isEqualTo("used the exact value in step 2");
        assertThat(textOf(1)).isEqualTo("Ada");
        assertThat(why("{\"op\":\"unlink\",\"n\":2}")).contains("step 2 uses no value of an earlier step");
        call("{\"op\":\"undo\"}");
        assertThat(textOf(1)).isEqualTo("${copy.rows.Orders.added[0].Customer}");

        // remember: read now from the app, kept under the last action as a check-like entry
        backend.verbs.remembered = "ORD-1641";
        JsonNode remembered = call("{\"op\":\"remember\",\"target\":{\"role\":\"label\",\"name\":\"Number\"},\"as\":\"number\"}");
        assertThat(remembered.path("did").asText()).isEqualTo("remembered ORD-1641 as number (check 2.1)");
        assertThat(remembered.at("/steps/1/checks/0/remembered").asText()).isEqualTo("ORD-1641");
        assertThat(why("{\"op\":\"remember\",\"target\":{\"role\":\"label\",\"name\":\"Number\"},\"as\":\"number\"}"))
                .contains("'number' is already the name of check 2.1");
        assertThat(why("{\"op\":\"remember\",\"target\":{\"role\":\"label\",\"name\":\"Number\"},\"as\":\"run\"}"))
                .contains("'run' is reserved");
        assertThat(why("{\"op\":\"remember\",\"as\":\"x\"}")).contains("remember needs 'target' and 'as'");
        // under an earlier step: it goes after that step's checks
        backend.verbs.remembered = "Bea";
        JsonNode first = call("{\"op\":\"remember\",\"n\":1,\"target\":{\"role\":\"label\",\"name\":\"Customer\"},"
                + "\"as\":\"customer\",\"selected\":true}");
        assertThat(first.path("did").asText()).isEqualTo("remembered Bea as customer (check 1.1)");
        assertThat(first.at("/steps/0/checks/0/step/selected").asBoolean()).isTrue();
        assertThat(session.runValues().fill("${customer}")).isEqualTo("Bea");
        // the app has nothing there: refused, nothing recorded
        backend.verbs.failure = new DriverException(Reason.NOT_FOUND, "no label 'Total'");
        assertThat(why("{\"op\":\"remember\",\"target\":{\"role\":\"label\",\"name\":\"Total\"},\"as\":\"total\"}"))
                .contains("no label 'Total'", "nothing remembered");
        backend.verbs.failure = null;
        assertThat(session.recordedSteps()).hasSize(4);

        // a later step that types the remembered value uses it; the agent can link it by name too
        session.verb("type", mapper.readTree("{\"target\":{\"id\":\"note\"},\"text\":\"ORD-1641\"}"));
        assertThat(textOf(4)).isEqualTo("${number}");
        call("{\"op\":\"unlink\",\"n\":3}");
        assertThat(textOf(4)).isEqualTo("ORD-1641");
        assertThat(call("{\"op\":\"link\",\"n\":3,\"ref\":\"number\"}").path("did").asText())
                .isEqualTo("linked step 3 to a remembered value");
        assertThat(textOf(4)).isEqualTo("${number}");

        // unique per run, on a typed text
        session.verb("type", mapper.readTree("{\"target\":{\"id\":\"title\"},\"text\":\"Spring sale\"}"));
        JsonNode unique = call("{\"op\":\"unique\",\"n\":4,\"where\":\"end\"}");
        assertThat(unique.path("did").asText()).isEqualTo("made the text of step 4 unique per run");
        assertThat(textOf(5)).isEqualTo("Spring sale ${run.id}");
        assertThat(unique.at("/steps/3/unique").asText()).isEqualTo("end");
        assertThat(call("{\"op\":\"unique\",\"n\":4,\"where\":\"end\"}").path("did").asText())
                .isEqualTo("step 4 is that way already");
        assertThat(why("{\"op\":\"unique\",\"n\":1,\"where\":\"end\"}")).contains("only a typed text can be unique per run");
        assertThat(why("{\"op\":\"unique\",\"n\":4,\"where\":\"middle\"}")).contains("\"end\", \"start\" or \"off\"");
        assertThat(call("{\"op\":\"unique\",\"n\":4,\"where\":\"off\"}").path("did").asText())
                .isEqualTo("turned unique per run off in step 4");
        assertThat(textOf(5)).isEqualTo("Spring sale");
        assertThat(sink.undoLabel()).isEqualTo("unique per run of step 4 by the agent");
    }

    @Test
    void aDomainStepsOwnValuesAreUnderStepAndALaterStepLinksToThem() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs), DomainStep.act("placeOrder", "Place the order")
                .codeWithOutcome((ui, p) -> {
                    ui.button("Place order").click();
                    return java.util.Collections.<String, Object>singletonMap("number", "ORD-1641");
                })
                .build());

        JsonNode placed = mapper.readTree(s.doAction(mapper.readTree("{\"action\":\"do\",\"step\":\"placeOrder\"}"), true,
                true));
        assertThat(placed.at("/outcome/step/number").asText()).isEqualTo("ORD-1641");
        assertThat(s.recordedSteps().get(0).outcome.at("/step/number").asText()).isEqualTo("ORD-1641");

        // the number the step returned is new: a later step that types it is linked to it at once
        s.verb("type", mapper.readTree("{\"target\":{\"id\":\"search\"},\"text\":\"ORD-1641\"}"));
        assertThat(((io.github.kormcrete.swingui.flow.verb.TypeVerb) s.recordedSteps().get(1).action).text)
                .isEqualTo("${placeOrder.step.number}");
        assertThat(recording(s, "{\"op\":\"list\"}").at("/steps/0/changed/.step.number").asText()).isEqualTo("ORD-1641");
        // and by hand, after the exact value
        recording(s, "{\"op\":\"unlink\",\"n\":2}");
        JsonNode linked = recording(s, "{\"op\":\"link\",\"n\":2,\"ref\":\"${1.step.number}\"}");
        String text = ((io.github.kormcrete.swingui.flow.verb.TypeVerb) s.recordedSteps().get(1).action).text;
        assertThat(text).isEqualTo("${placeOrder.step.number}");
        assertThat(linked.at("/steps/1/uses/0/value").asText()).isEqualTo("ORD-1641");
    }

    @Test
    void aValueAStepReturnedUnderAnOrderNumberKeyIsLinkedAndReplaysButNotOneByItsPositionInAList() throws Exception {
        Map<String, Object> made = new LinkedHashMap<String, Object>();
        made.put("orders", java.util.Collections.singletonMap("ORD-1634", java.util.Collections.singletonMap(
                "Customer", "Grace Ltd")));
        made.put("added", Arrays.asList("Lamp", "Desk"));
        UiMcpSession s = orderDesk(new Plugins(backend.verbs), DomainStep.act("showOrders", "Show the orders")
                .codeWithOutcome((ui, p) -> {
                    ui.tab("Orders").click();
                    return made;
                })
                .build());

        s.doAction(mapper.readTree("{\"action\":\"do\",\"step\":\"showOrders\"}"));
        // offered: the key with a hyphen and digits is a plain path step
        JsonNode list = recording(s, "{\"op\":\"list\"}");
        assertThat(list.at("/steps/0/changed").has(".step.orders.ORD-1634.Customer")).isTrue();
        s.verb("type", mapper.readTree("{\"target\":{\"id\":\"search\"},\"text\":\"Grace Ltd\"}"));
        s.verb("type", mapper.readTree("{\"target\":{\"id\":\"note\"},\"text\":\"Lamp\"}"));
        assertThat(((io.github.kormcrete.swingui.flow.verb.TypeVerb) s.recordedSteps().get(1).action).text)
                .isEqualTo("${showOrders.step.orders.ORD-1634.Customer}");
        // a plain string list's position is no source, automatic or by hand
        assertThat(((io.github.kormcrete.swingui.flow.verb.TypeVerb) s.recordedSteps().get(2).action).text)
                .isEqualTo("Lamp");
        assertThat(why(s, "{\"op\":\"link\",\"n\":3,\"ref\":\"${1.step.added[0]}\"}"))
                .contains(".step.added[0] is a place in a list").contains("never linked by its position");

        // resolved on replay
        backend.verbs.calls.clear();
        JsonNode r = recording(s, "{\"op\":\"replay\"}");
        assertThat(r.path("passed").asBoolean()).as(r.toString()).isTrue();
        assertThat(r.at("/steps/1/resolved").path("${showOrders.step.orders.ORD-1634.Customer}").asText())
                .isEqualTo("Grace Ltd");
        assertThat(backend.verbs.calls).anySatisfy(c -> assertThat(c).startsWith("type #search Grace Ltd"));
    }

    private String why(UiMcpSession s, String json) {
        DriverException e = catchThrowableOfType(() -> recording(s, json), DriverException.class);
        return e == null ? "" : e.getMessage();
    }

    /** Order Desk with placeOrder (it returns the number ORD-1641) and openOrder {order}. */
    private UiMcpSession placeAndOpen() {
        return orderDesk(new Plugins(backend.verbs),
                DomainStep.act("placeOrder", "Place the order")
                        .codeWithOutcome((ui, p) -> {
                            ui.button("Place order").click();
                            return java.util.Collections.<String, Object>singletonMap("number", "ORD-1641");
                        })
                        .build(),
                DomainStep.act("openOrder", "Open order {order}")
                        .param("order", ParamType.TEXT)
                        .code((ui, p) -> ui.table().row("Order", p.text("order")).cell("Order").doubleClick())
                        .build());
    }

    @Test
    void aGeneratedSentenceFollowsItsLinkedParam() throws Exception {
        UiMcpSession s = placeAndOpen();
        s.doAction(mapper.readTree("{\"action\":\"do\",\"step\":\"placeOrder\"}"));
        s.doAction(mapper.readTree("{\"action\":\"do\",\"step\":\"openOrder\",\"params\":{\"order\":\"ORD-1641\"}}"));

        // the recorder linked the number at once: the saved sentence says where it comes from
        DoAction open = (DoAction) s.recordedSteps().get(1).action;
        assertThat(open.params).containsEntry("order", "${placeOrder.step.number}");
        assertThat(open.say).isEqualTo("Open order the number placeOrder made");
        recording(s, "{\"op\":\"unlink\",\"n\":2}");
        assertThat(s.recordedSteps().get(1).action.say).isEqualTo("Open order ORD-1641");
        recording(s, "{\"op\":\"link\",\"n\":2,\"ref\":\"${1.step.number}\"}");
        assertThat(s.recordedSteps().get(1).action.say).isEqualTo("Open order the number placeOrder made");
        // undo takes the sentence back with the link
        recording(s, "{\"op\":\"undo\"}");
        assertThat(s.recordedSteps().get(1).action.say).isEqualTo("Open order ORD-1641");
        // a sentence the agent wrote stays
        recording(s, "{\"op\":\"edit\",\"n\":2,\"say\":\"Open the new order\"}");
        recording(s, "{\"op\":\"link\",\"n\":2,\"ref\":\"${1.step.number}\"}");
        assertThat(((DoAction) s.recordedSteps().get(1).action).params).containsEntry("order", "${placeOrder.step.number}");
        assertThat(s.recordedSteps().get(1).action.say).isEqualTo("Open the new order");
    }

    @Test
    void anEditOfADoStepsParamsRegeneratesAGeneratedSentence() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs));
        s.doAction(mapper.readTree(ADD));
        assertThat(s.recordedSteps().get(0).action.say).isEqualTo("Add Gel pen, black to the cart");

        recording(s, "{\"op\":\"edit\",\"n\":1,\"step\":{\"action\":\"do\",\"step\":\"order-desk:addToCart\","
                + "\"params\":{\"product\":\"A4 paper, 500 sheets\"}}}");
        assertThat(s.recordedSteps().get(0).action.say).isEqualTo("Add A4 paper, 500 sheets to the cart");
        // a sentence given with the edit is the one kept
        recording(s, "{\"op\":\"edit\",\"n\":1,\"say\":\"Add paper\",\"step\":{\"action\":\"do\","
                + "\"step\":\"order-desk:addToCart\",\"params\":{\"product\":\"Gel pen, black\"}}}");
        assertThat(s.recordedSteps().get(0).action.say).isEqualTo("Add paper");
    }

    @Test
    void sentenceWordsSayWhereAValueComesFrom() {
        assertThat(UiMcpSession.sentenceWords(Refs.scan("${placeOrder.rows['Orders › table'].added[0].Order}").get(0)))
                .isEqualTo("the Order placeOrder added");
        assertThat(UiMcpSession.sentenceWords(Refs.scan("${addQtyToCart.texts['New order › Total']}").get(0)))
                .isEqualTo("the Total addQtyToCart showed");
        assertThat(UiMcpSession.sentenceWords(Refs.scan("${open.fields.Number}").get(0))).isEqualTo("the Number open set");
        assertThat(UiMcpSession.sentenceWords(Refs.scan("${orderTotal}").get(0))).isEqualTo("the remembered orderTotal");
        assertThat(UiMcpSession.sentenceWords(Refs.scan("${placeOrder.opened.tabs[0]}").get(0))).isEqualTo("the tab placeOrder opened");
        assertThat(UiMcpSession.sentenceWords(Refs.scan("${placeOrder.step.number}").get(0)))
                .isEqualTo("the number placeOrder made");
        assertThat(UiMcpSession.sentenceWords(Refs.scan("${placeOrder.rows.Orders.changed[0].Status}").get(0)))
                .isEqualTo("the Status placeOrder changed");
        assertThat(UiMcpSession.sentenceWords(Refs.scan("${run.id}").get(0))).isEqualTo("this run's id");
        assertThat(UiMcpSession.sentenceWords(Refs.scan("${copy.selected}").get(0))).isEqualTo("a value copy made");
        // a list item or a tree row: the list's or tree's name; a whole row: the table's
        assertThat(UiMcpSession.sentenceWords(Refs.scan("${newFolder.items.Folders.added[0]}").get(0)))
                .isEqualTo("the Folders item newFolder added");
        assertThat(UiMcpSession.sentenceWords(Refs.scan("${placeOrder.rows.Orders.added[0]}").get(0)))
                .isEqualTo("the Orders row placeOrder added");
        // no word to name it by: only an index, or nothing after the kind
        assertThat(UiMcpSession.sentenceWords(Refs.scan("${placeOrder.step[0]}").get(0)))
                .isEqualTo("a value placeOrder made");
        assertThat(UiMcpSession.sentenceWords(Refs.scan("${open.fields[0]}").get(0))).isEqualTo("a value open made");
        assertThat(UiMcpSession.sentenceWords(Refs.scan("${newFolder.items[0].added[0]}").get(0)))
                .isEqualTo("a value newFolder made");
    }

    @Test
    void aLinksNewIdNeverTakesALaterNameAndTheRecordedTestLoads() throws Exception {
        session.reader(readings(ordersOf("ORD-1640", "Bea"), ordersOf("ORD-1640", "Bea", "ORD-1641", "Ada")));
        session.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Copy\"}}"));
        session.verb("type", mapper.readTree("{\"target\":{\"id\":\"search\"},\"text\":\"Ada\"}"));
        // a later entry already holds the name the link would give step 1
        backend.verbs.remembered = "Bea";
        call("{\"op\":\"remember\",\"target\":{\"role\":\"label\",\"name\":\"Customer\"},\"as\":\"copy\"}");

        // the person's link by hand
        Inspector.Result r = sink.link(id(1), id(0) + "|.rows.Orders.added[0]");
        assertThat(r.ok).as(r.line).isTrue();
        assertThat(session.recordedSteps().get(0).action.id).isEqualTo("copy2");
        assertThat(textOf(1)).isEqualTo("${copy2.rows.Orders.added[0].Customer}");
        call("{\"op\":\"undo\"}");
        assertThat(session.recordedSteps().get(0).action.id).isNull();

        // the agent's
        call("{\"op\":\"link\",\"n\":2,\"ref\":\"${1.rows.Orders.added[0].Customer}\"}");
        assertThat(session.recordedSteps().get(0).action.id).isEqualTo("copy2");
        assertThat(textOf(1)).isEqualTo("${copy2.rows.Orders.added[0].Customer}");

        // the recorded test saves and loads
        session.exportRecordedTest("copy-once", quoted(dir), null, false);
        session.loadRecordedTest(dir.resolve("copy-once.json"));
        assertThat(session.recordedSteps().get(0).action.id).isEqualTo("copy2");
        assertThat(textOf(1)).isEqualTo("${copy2.rows.Orders.added[0].Customer}");
    }

    @Test
    void aLinkBreaksAFold() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs));
        List<String> product = java.util.Collections.singletonList("Product");
        backend.verbs.readings.add(io.github.kormcrete.swingui.driver.outcome.UiReading.builder()
                .table("Products", product, java.util.Collections.singletonList(java.util.Collections.singletonList("Ballpoint pen")))
                .build());
        backend.verbs.readings.add(io.github.kormcrete.swingui.driver.outcome.UiReading.builder()
                .table("Products", product, Arrays.asList(java.util.Collections.singletonList("Ballpoint pen"),
                        java.util.Collections.singletonList("Gel pen, black"))).build());
        String add = "{\"target\":{\"role\":\"button\",\"name\":\"Add to cart\"}}";
        // New product adds Gel pen, black; the row clicked next is linked to it: no fold takes the two clicks after it
        s.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"New product\"}}"));
        s.verb("click", mapper.readTree("{\"target\":{\"role\":\"table\",\"name\":\"Products\","
                + "\"row\":{\"Product\":\"Gel pen, black\"},\"column\":\"Product\"}}"));
        s.verb("click", mapper.readTree(add));
        // a remember entry between them breaks one too, as a check does
        s.verb("click", mapper.readTree("{\"target\":{\"role\":\"table\",\"name\":\"Products\","
                + "\"row\":{\"Product\":\"Ballpoint pen\"},\"column\":\"Product\"}}"));
        backend.verbs.remembered = "€2.40";
        recording(s, "{\"op\":\"remember\",\"target\":{\"role\":\"label\",\"name\":\"Price\"},\"as\":\"price\"}");
        s.verb("click", mapper.readTree(add));
        // the same two clicks with nothing linked fold
        s.verb("click", mapper.readTree("{\"target\":{\"role\":\"table\",\"name\":\"Products\","
                + "\"row\":{\"Product\":\"Ballpoint pen\"},\"column\":\"Product\"}}"));
        s.verb("click", mapper.readTree(add));

        JsonNode steps = recording(s, "{\"op\":\"list\"}").path("steps");

        assertThat(steps.get(1).path("step").path("target").path("row").path("Product").asText())
                .isEqualTo("${newProduct.rows.Products.added[0].Product}");
        assertThat(steps).extracting(st -> st.path("folded").asInt(0)).containsExactly(0, 0, 0, 0, 0, 2);
        assertThat(steps.get(5).path("step").path("params").path("product").asText()).isEqualTo("Ballpoint pen");
    }

    // ---- a fold keeps what its raw steps changed: a later step links to it ----------------------------------------

    /** Order Desk's Place order: the button, then the confirmation's answer; the answer makes the new order. */
    private static final DomainStep PLACE_ORDER = DomainStep.act("placeOrder", "Place the order")
            .code((ui, p) -> {
                ui.button("Place order").click();
                ui.answer("Place order");
            })
            .java(STEPS_CLASS, "placeOrder(ui)")
            .build();

    /** The Orders table with one more order for each Place order answered so far (ORD-1640, then ORD-1641, ...). */
    private java.util.function.Supplier<io.github.kormcrete.swingui.driver.outcome.UiReading> ordersPlaced() {
        return () -> {
            int placed = 0;
            for (String c : new java.util.ArrayList<String>(backend.verbs.calls)) if (c.equals("answer Place order")) placed++;
            String[] numbers = new String[placed + 1];
            for (int i = 0; i <= placed; i++) numbers[i] = "ORD-" + (1640 + i);
            return orders(numbers);
        };
    }

    /** The last text typed into #search. */
    private String lastSearch() {
        List<String> typed = typed();
        return typed.isEmpty() ? null : typed.get(typed.size() - 1);
    }

    private static final String PLACED = "${placeOrder.rows.Orders.added[0].Number}";

    @Test
    void theAgentsFoldKeepsWhatItsStepsChangedAndALaterStepLinksToIt() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs), PLACE_ORDER);
        s.reader(ordersPlaced());
        backend.verbs.reading = ordersPlaced();
        s.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Place order\"}}"));
        s.verb("answer", mapper.readTree("{\"button\":\"Place order\"}"));
        JsonNode steps = recording(s, "{\"op\":\"list\"}").path("steps");
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).path("folded").asInt()).isEqualTo(2);
        // the fold holds what its answer made
        assertThat(s.recordedSteps().get(0).newValues).containsEntry("ORD-1641", ".rows.Orders.added[0].Number");

        s.verb("type", mapper.readTree("{\"target\":{\"id\":\"search\"},\"text\":\"ORD-1641\"}"));

        steps = recording(s, "{\"op\":\"list\"}").path("steps");
        assertThat(steps.get(0).path("step").path("id").asText()).isEqualTo("placeOrder");
        assertThat(steps.get(1).path("step").path("text").asText()).isEqualTo(PLACED);
        // saved as a recorded test, it places a new order on every run and searches for that one
        JsonNode file = mapper.readTree(s.exportRecordedTest("place-order", quoted(dir), null, false)).path("test");
        assertThat(file.path("version").asInt()).isEqualTo(4);
        RecordedTestFiles.read(dir.resolve("place-order.json"));
        JsonNode run = mapper.readTree(s.runRecordedTest(mapper.readTree("{\"name\":\"place-order\"}")));
        assertThat(run.path("passed").asBoolean()).as(run.toString()).isTrue();
        assertThat(lastSearch()).isEqualTo("type #search ORD-1642 null null");
        run = mapper.readTree(s.runRecordedTest(mapper.readTree("{\"name\":\"place-order\"}")));
        assertThat(run.path("passed").asBoolean()).as(run.toString()).isTrue();
        assertThat(lastSearch()).isEqualTo("type #search ORD-1643 null null");
    }

    @Test
    void thePersonsOutcomeThatComesAfterTheFoldGoesToTheFold() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs), PLACE_ORDER);
        Inspector.Sink person = s.humanSink();
        Map<String, Object> place = step("click", Locator.of("button", "Place order"));
        place.put(Inspector.SEQ, 1);
        Map<String, Object> answer = new LinkedHashMap<String, Object>();
        answer.put("action", "answer");
        answer.put("button", "Place order");
        answer.put(Inspector.SEQ, 2);
        assertThat(person.done(place).ok).isTrue();
        assertThat(person.done(answer).ok).isTrue();
        // the person is slow: folding takes the two steps before the palette read what the answer made
        assertThat(recording(s, "{\"op\":\"list\"}").path("steps")).hasSize(1);
        io.github.kormcrete.swingui.driver.outcome.UiReading before = orders("ORD-1640");
        Map<String, Object> outcome = io.github.kormcrete.swingui.driver.outcome.Outcomes.diff(before, orders("ORD-1640", "ORD-1641"));
        person.outcome(2, outcome, io.github.kormcrete.swingui.driver.outcome.Outcomes.newValues(before, outcome));
        assertThat(s.recordedSteps().get(0).newValues).containsEntry("ORD-1641", ".rows.Orders.added[0].Number");

        assertThat(person.done(typing("search", "ORD-1641", 3)).ok).isTrue();

        JsonNode steps = recording(s, "{\"op\":\"list\"}").path("steps");
        assertThat(steps).hasSize(2);
        assertThat(steps.get(1).path("step").path("text").asText()).isEqualTo(PLACED);
        s.exportRecordedTest("place-order", quoted(dir), null, false);
        RecordedTestFiles.read(dir.resolve("place-order.json"));
        s.reader(ordersPlaced());
        backend.verbs.reading = ordersPlaced();
        JsonNode run = mapper.readTree(s.runRecordedTest(mapper.readTree("{\"name\":\"place-order\"}")));
        assertThat(run.path("passed").asBoolean()).as(run.toString()).isTrue();
        assertThat(lastSearch()).isEqualTo("type #search ORD-1641 null null");
    }

    // ---- no edit leaves a recording that cannot be saved as a recorded test ----------------------------------------

    /** The agent copies an order (step 1, id copy) and types its number (step 2, linked). */
    private void copyAndTypeItsNumber() throws Exception {
        session.reader(readings(orders("ORD-1640"), orders("ORD-1640", "ORD-1641")));
        session.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Copy\"}}"));
        session.verb("type", mapper.readTree("{\"target\":{\"id\":\"search\"},\"text\":\"Copy of ORD-1641\"}"));
        assertThat(text(session.recordedSteps().get(1))).isEqualTo("Copy of " + NUMBER);
    }

    private String refusal(String json) {
        return catchThrowableOfType(() -> call(json), DriverException.class).getMessage();
    }

    @Test
    void aMoveThatPutsAUseBeforeItsSourceIsRefused() throws Exception {
        copyAndTypeItsNumber();
        List<String> before = kept();

        assertThat(refusal("{\"op\":\"move\",\"n\":1,\"to\":2}"))
                .contains("refers to 'copy', but no earlier step is named 'copy'").endsWith("(the recording is unchanged)");
        assertThat(refusal("{\"op\":\"move\",\"n\":2,\"to\":1}")).endsWith("(the recording is unchanged)");
        assertThat(kept()).isEqualTo(before);
    }

    @Test
    void aWholeStepEditKeepsTheStepsIdAndTheRecordingStillSaves() throws Exception {
        copyAndTypeItsNumber();

        call("{\"op\":\"edit\",\"n\":1,\"step\":{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Copy order\"}}}");

        assertThat(session.recordedSteps().get(0).action.id).isEqualTo("copy");
        JsonNode file = mapper.readTree(session.exportRecordedTest("copy", quoted(dir), null, false)).path("test");
        assertThat(file.path("steps").get(0).path("id").asText()).isEqualTo("copy");
        // a whole step naming another id leaves the use without its source: refused
        assertThat(refusal("{\"op\":\"edit\",\"n\":1,\"step\":{\"action\":\"click\",\"id\":\"dup\","
                + "\"target\":{\"role\":\"button\",\"name\":\"Copy\"}}}")).endsWith("(the recording is unchanged)");
        assertThat(session.recordedSteps().get(0).action.id).isEqualTo("copy");
    }

    @Test
    void anInsertWithAClashingIdOrAUseOfNoEarlierStepIsRefused() throws Exception {
        copyAndTypeItsNumber();
        List<String> before = kept();

        assertThat(refusal("{\"op\":\"insert\",\"at\":3,\"step\":{\"action\":\"click\",\"id\":\"copy\","
                + "\"target\":{\"role\":\"button\",\"name\":\"Save\"}}}"))
                .contains("'copy' is already the name of step 1").endsWith("(the recording is unchanged)");
        assertThat(refusal("{\"op\":\"insert\",\"at\":1,\"step\":{\"action\":\"type\",\"target\":{\"id\":\"note\"},"
                + "\"text\":\"" + NUMBER.replace("\"", "\\\"") + "\"}}")).endsWith("(the recording is unchanged)");
        assertThat(kept()).isEqualTo(before);
        // a whole step is the file's form: its reference to an earlier step is one
        call("{\"op\":\"insert\",\"at\":3,\"step\":{\"action\":\"type\",\"target\":{\"id\":\"note\"},"
                + "\"text\":\"" + NUMBER + "\"}}");
        assertThat(text(session.recordedSteps().get(2))).isEqualTo(NUMBER);
        session.exportRecordedTest("copy", quoted(dir), null, false);
    }

    @Test
    void anEditsTextIsTakenAsTypedAndTheRecordingStillSaves() throws Exception {
        copyAndTypeItsNumber();

        call("{\"op\":\"edit\",\"n\":2,\"text\":\"Hello ${name\"}");

        // a ${ in it is text, as in a step the agent runs
        assertThat(text(session.recordedSteps().get(1))).isEqualTo("Hello $${name");
        JsonNode file = mapper.readTree(session.exportRecordedTest("hello", quoted(dir), null, false)).path("test");
        assertThat(file.path("steps").get(1).path("text").asText()).isEqualTo("Hello $${name");
        assertThat(RecordedTestFiles.read(dir.resolve("hello.json")).steps).hasSize(2);
        // and so is a target's name
        call("{\"op\":\"edit\",\"n\":2,\"target\":{\"role\":\"text\",\"name\":\"${note}\"}}");
        assertThat(mapper.valueToTree(session.recordedSteps().get(1).action).at("/target/name").asText()).isEqualTo("$${note}");
    }

    @Test
    void aFoldOrUnfoldThatWouldBreakALinkIsRefused() throws Exception {
        UiMcpSession s = orderDesk(new Plugins(backend.verbs), PLACE_ORDER);
        s.reader(ordersPlaced());
        s.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Place order\"}}"));
        s.verb("answer", mapper.readTree("{\"button\":\"Place order\"}"));
        assertThat(recording(s, "{\"op\":\"list\"}").path("steps")).hasSize(1);
        s.verb("type", mapper.readTree("{\"target\":{\"id\":\"search\"},\"text\":\"ORD-1641\"}"));
        assertThat(text(s.recordedSteps().get(1))).isEqualTo(PLACED);

        DriverException unfold = catchThrowableOfType(() -> recording(s, "{\"op\":\"unfold\",\"n\":1}"), DriverException.class);
        assertThat(unfold.getMessage()).contains("step 2 uses what step 1 changed, and a link breaks a fold")
                .endsWith("(the recording is unchanged)");

        // without the link it unfolds; linked again to the answer, the two steps no longer fold
        recording(s, "{\"op\":\"undo\"}");
        assertThat(recording(s, "{\"op\":\"unfold\",\"n\":1}").path("steps")).hasSize(3);
        recording(s, "{\"op\":\"link\",\"n\":3,\"ref\":\"${2.rows.Orders.added[0].Number}\"}");
        DriverException fold = catchThrowableOfType(() -> recording(s, "{\"op\":\"fold\",\"from\":1,\"to\":2}"),
                DriverException.class);
        assertThat(fold.getMessage()).contains("step 2 made a value step 3 uses, and a link breaks a fold")
                .endsWith("(the recording is unchanged)");
        assertThat(recording(s, "{\"op\":\"list\"}").path("steps")).hasSize(3);
    }

    @Test
    void aNameThePersonTypesForRememberThisIsKeptAsANameAndShown() throws Exception {
        backend.verbs.remembered = "ORD-1641";
        assertThat(sink.done(step("click", Locator.of("button", "Copy"))).ok).isTrue();
        Map<String, Object> remember = step("remember", Locator.of("label", "Number"));
        remember.put("as", "Order number");

        Inspector.Result kept = sink.check(remember);

        assertThat(kept.ok).as(kept.line).isTrue();
        assertThat(kept.line).contains("as orderNumber");
        assertThat(((io.github.kormcrete.swingui.flow.verb.RememberVerb) session.recordedSteps().get(1).action).as)
                .isEqualTo("orderNumber");
        // the same words again: the next free name
        assertThat(sink.check(remember).line).contains("as orderNumber2");
        // a name typed as a name stays the person's: taken twice it is refused in words
        remember.put("as", "number");
        assertThat(sink.check(remember).ok).isTrue();
        Inspector.Result twice = sink.check(remember);
        assertThat(twice.ok).isFalse();
        assertThat(twice.line).contains("'number' is already the name of check 1.3");
        session.exportRecordedTest("copy", quoted(dir), null, false);
    }

    @Test
    void aLinkedRowByPositionAndBackByValueKeepsItsLink() throws Exception {
        session.reader(readings(orders("ORD-1640"), orders("ORD-1640", "ORD-1641")));
        session.verb("click", mapper.readTree("{\"target\":{\"role\":\"button\",\"name\":\"Copy\"}}"));
        backend.verbs.outcome.row = new RowChoice(1, java.util.Collections.<String, Object>singletonMap("Number", "ORD-1641"));
        session.verb("click", mapper.readTree(
                "{\"target\":{\"role\":\"table\",\"name\":\"Orders\",\"row\":{\"Number\":\"ORD-1641\"}}}"));
        assertThat(call("{\"op\":\"list\"}").at("/steps/1/step/target/row/Number").asText()).isEqualTo(NUMBER);

        assertThat(call("{\"op\":\"row\",\"n\":2,\"by\":\"position\"}").at("/steps/1/step/target/row").toString())
                .isEqualTo("{\"index\":1}");
        JsonNode back = call("{\"op\":\"row\",\"n\":2,\"by\":\"value\"}");

        assertThat(back.at("/steps/1/step/target/row/Number").asText()).isEqualTo(NUMBER);
    }

    @Test
    void aSourceTheReplayCannotReadGivesNoValueOfAnEarlierRun() throws Exception {
        copyAndTypeItsNumber();
        call("{\"op\":\"stop\"}");
        noReset(session);
        session.reader(() -> null);
        backend.verbs.calls.clear();

        JsonNode run = call("{\"op\":\"replay\"}");

        assertThat(run.path("passed").asBoolean()).isFalse();
        assertThat(run.at("/steps/1/message").asText()).contains("Step 1 (Copy) added no row to Orders");
        assertThat(typed()).isEmpty();
    }
}
