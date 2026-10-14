package io.github.kormcrete.swingui.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.inspect.Inspector;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.flow.FlowFiles;
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
    void aLoadedFlowsStepsAreTheFlows() throws Exception {
        Flow flow = new Flow();
        flow.version = FlowFiles.VERSION;
        flow.name = "two clicks";
        flow.start = FlowFiles.START_RESET;
        ClickVerb a = new ClickVerb(); a.target = Locator.ofId("a");
        ClickVerb b = new ClickVerb(); b.target = Locator.ofId("b");
        flow.steps.add(a);
        flow.steps.add(b);
        Path file = dir.resolve("two.json");
        Files.write(file, FlowFiles.write(flow).getBytes(StandardCharsets.UTF_8));
        session.loadFlow(file);

        assertThat(session.recordedSteps()).hasSize(2)
                .allMatch(s -> s.author == RecordedStep.Author.FLOW);
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
            assertThat(e.getMessage()).contains("start, stop");
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
    void aLoadedFlowListsWhereItCameFrom() throws Exception {
        Flow flow = new Flow();
        flow.version = FlowFiles.VERSION;
        flow.name = "one click";
        flow.start = FlowFiles.START_RESET;
        ClickVerb a = new ClickVerb(); a.target = Locator.ofId("a");
        flow.steps.add(a);
        Path file = dir.resolve("one.json");
        Files.write(file, FlowFiles.write(flow).getBytes(StandardCharsets.UTF_8));
        session.loadFlow(file);

        JsonNode list = call("{\"op\":\"list\"}");
        assertThat(list.path("state").asText()).isEqualTo("stopped");
        assertThat(list.path("startedBy").asText()).isEqualTo("flow");
        assertThat(list.path("loadedFrom").asText()).isEqualTo(file.toAbsolutePath().normalize().toString());
        assertThat(list.path("steps").get(0).path("author").asText()).isEqualTo("flow");
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
        sink.truncate(1);
        assertThat(sink.done(step("click", Locator.ofId("c"))).ok).isTrue();
        assertThat(sink.steps()).containsExactly("click(#a)", "click(#c)");
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
}
