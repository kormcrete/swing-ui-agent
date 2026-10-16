package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Node;
import io.github.kormcrete.swingui.driver.model.Recorder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** The batch tool: several verb steps in one call, in order, stopping at the first failure, recorded as singles are. */
class UiMcpBatchTest {

    private static final String CLICK = "{\"verb\":\"click\",\"target\":{\"ref\":\"e3\"}}";
    private static final String TYPE = "{\"verb\":\"type\",\"target\":{\"role\":\"text\",\"name\":\"Name\"},\"text\":\"X\"}";
    private static final String PRESS = "{\"verb\":\"press\",\"keys\":\"Escape\"}";

    @TempDir
    Path testsDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final UiMcpReadToolsTest.DriverHost backend = new UiMcpReadToolsTest.DriverHost();
    private final UiMcpSession session = new UiMcpSession(backend, new UiMcpToolsTest.FakeInventory(), mapper);

    @BeforeEach
    void exportToTemp() {
        System.setProperty(UiMcpSession.TESTS_DIR_PROP, testsDir.toString());
        System.setProperty(UiMcpTiming.FILE_PROP, "off");
        backend.verbs.outcome.recorded.put("target", new Recorder.Recorded(Locator.of("button", "Save"), false));
        session.startRecording(true);
    }

    @AfterEach
    void clear() {
        System.clearProperty(UiMcpSession.TESTS_DIR_PROP);
        System.clearProperty(UiMcpTiming.FILE_PROP);
    }

    private JsonNode batch(String... steps) throws Exception {
        return mapper.readTree(session.batch(mapper.readTree("{\"steps\":[" + String.join(",", steps) + "]}")));
    }

    /** Fails the {@code n}th verb call (1-based) the driver sees, and no other. */
    private void failCall(final int n, final RuntimeException failure) {
        backend.verbs.during = () -> backend.verbs.failure = backend.verbs.calls.size() == n ? failure : null;
    }

    @Test
    void aBatchAndDoActionsAreOneRunForThePaletteASingleStepIsNot() throws Exception {
        // a run: the palette that stepped aside for one of its steps stays aside until the run ends
        java.util.List<Boolean> running = new java.util.ArrayList<Boolean>();
        backend.verbs.during = () -> running.add(io.github.kormcrete.swingui.driver.inspect.Inspector.agentIsRunning());
        batch(CLICK, PRESS);
        session.doActions(mapper.readTree("[{\"action\":\"click\",\"target\":{\"ref\":\"e3\"}},"
                + "{\"action\":\"press\",\"keys\":\"Escape\"}]"));
        assertThat(running).containsExactly(true, true, true, true);
        assertThat(io.github.kormcrete.swingui.driver.inspect.Inspector.agentIsRunning()).isFalse();

        // one step (a verb tool, do_action): it returns at the step's end
        running.clear();
        session.verb("click", mapper.readTree("{\"target\":{\"ref\":\"e3\"}}"));
        assertThat(running).containsExactly(false);
    }

    @Test
    void theStepsRunInOrderAndEachReportsItsResult() throws Exception {
        JsonNode r = batch(CLICK, TYPE, PRESS);
        assertThat(r.path("passed").asBoolean()).isTrue();
        assertThat(r.path("ran").asInt()).isEqualTo(3);
        assertThat(r.path("total").asInt()).isEqualTo(3);
        assertThat(r.has("ms")).isTrue();
        assertThat(backend.verbs.calls).extracting(c -> c.split(" ")[0]).containsExactly("click", "type", "press");
        JsonNode steps = r.path("steps");
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0).path("verb").asText()).isEqualTo("click");
        assertThat(steps.get(0).path("passed").asBoolean()).isTrue();
        assertThat(steps.get(0).has("ms")).isTrue();
        assertThat(steps.get(0).path("recorded").path("target").path("name").asText()).isEqualTo("Save");
    }

    @Test
    void theFirstFailedStepEndsTheBatchAndIsNotRecorded() throws Exception {
        failCall(2, new DriverException(Reason.NOT_ACTIONABLE, "covered by blocker"));
        JsonNode r = batch(CLICK, TYPE, PRESS);
        assertThat(r.path("passed").asBoolean()).isFalse();
        assertThat(r.path("ran").asInt()).isEqualTo(2);
        assertThat(r.path("steps")).hasSize(2);
        assertThat(r.path("steps").get(1).path("reason").asText()).isEqualTo("NOT_ACTIONABLE");
        assertThat(backend.verbs.calls).hasSize(2);
        JsonNode exported = mapper.readTree(session.exportTest("BUiIT", "b", "p", true));
        assertThat(exported.path("steps").asInt()).isEqualTo(1);
        assertThat(exported.path("source").asText()).contains(".click()");
    }

    @Test
    void theRecordedRecordedTestIsTheSameAsTheStepsIssuedOneByOne() throws Exception {
        // navigate is a step type with a Java form: the batch runs it as do_action would
        batch("{\"verb\":\"navigate\",\"target\":\"Orders\"}", CLICK, TYPE, PRESS);
        String batched = mapper.readTree(session.exportTest("XUiIT", "x", "p", true)).path("source").asText();

        UiMcpReadToolsTest.DriverHost other = new UiMcpReadToolsTest.DriverHost();
        other.verbs.outcome.recorded.put("target", new Recorder.Recorded(Locator.of("button", "Save"), false));
        UiMcpSession single = new UiMcpSession(other, new UiMcpToolsTest.FakeInventory(), mapper);
        single.startRecording(true);
        single.doAction(mapper.readTree("{\"action\":\"navigate\",\"target\":\"Orders\"}"));
        single.verb("click", mapper.readTree("{\"target\":{\"ref\":\"e3\"}}"));
        single.verb("type", mapper.readTree("{\"target\":{\"role\":\"text\",\"name\":\"Name\"},\"text\":\"X\"}"));
        single.verb("press", mapper.readTree("{\"keys\":\"Escape\"}"));
        System.setProperty(UiMcpSession.TESTS_DIR_PROP, testsDir.resolve("single").toString());
        String oneByOne = mapper.readTree(single.exportTest("XUiIT", "x", "p", true)).path("source").asText();

        assertThat(batched).isEqualTo(oneByOne);
        assertThat(batched).contains("ui.navigate(\"Orders\");").contains(".click()").contains("Escape");
    }

    @Test
    void theVerbToolNamesMapToTheirRecordedTestActions() throws Exception {
        backend.verbs.outcome.recorded.clear(); // chooseFile has no target to record
        JsonNode r = batch("{\"verb\":\"choose_file\",\"path\":\"/tmp/x.xlsx\"}");
        assertThat(r.path("steps").get(0).path("recorded").path("action").asText()).isEqualTo("chooseFile");
        assertThat(backend.verbs.calls).containsExactly("chooseFile /tmp/x.xlsx null");
    }

    @Test
    void aReadStepRecordsNothingAndOneThatFindsNothingStopsTheBatch() throws Exception {
        Node field = new Node("text", "Name");
        field.value = "X";
        backend.verbs.node = field;
        JsonNode r = batch("{\"verb\":\"read\",\"target\":{\"role\":\"text\",\"name\":\"Name\"}}", PRESS);
        assertThat(r.path("passed").asBoolean()).isTrue();
        assertThat(r.path("steps").get(0).path("node").path("value").asText()).isEqualTo("X");
        assertThat(r.path("steps").get(0).has("recorded")).isFalse();
        assertThat(mapper.readTree(session.exportTest("RUiIT", "r", "p", true)).path("steps").asInt()).isEqualTo(1);

        backend.verbs.failure = new DriverException(Reason.NOT_FOUND, "nothing matches");
        JsonNode missing = batch("{\"verb\":\"read\",\"target\":{\"role\":\"text\",\"name\":\"Nope\"}}", PRESS);
        assertThat(missing.path("passed").asBoolean()).isFalse();
        assertThat(missing.path("ran").asInt()).isEqualTo(1);
        assertThat(missing.path("steps").get(0).path("found").asBoolean()).isFalse();
    }

    @Test
    void aHarnessErrorNamesTheStepAndKeepsWhatRanBefore() throws Exception {
        failCall(2, new DriverException(Reason.CAPABILITY_GAP, "the popup did not open"));
        DriverException e = catchThrowableOfType(() -> batch(CLICK, "{\"verb\":\"select\",\"target\":{\"role\":\"combo\"},"
                + "\"item\":\"A\"}", PRESS), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.CAPABILITY_GAP);
        assertThat(e.getMessage()).contains("step 2 of 3 (select)").contains("the popup did not open");
        assertThat(e.details()).contains("steps 1-1 passed and were recorded");
        assertThat(mapper.readTree(session.exportTest("GUiIT", "g", "p", true)).path("steps").asInt()).isEqualTo(1);
    }

    @Test
    void aSnapshotCanFollowTheLastStepScopedOrNot() throws Exception {
        JsonNode r = batch(PRESS);
        assertThat(r.has("snapshot")).isFalse();
        JsonNode withOne = mapper.readTree(session.batch(mapper.readTree("{\"steps\":[" + PRESS + "],"
                + "\"snapshot\":{\"target\":{\"role\":\"tab\",\"name\":\"Customer\"},\"depth\":2,\"compact\":true}}")));
        assertThat(withOne.path("snapshot").path("size").path("scoped").asBoolean()).isTrue();
        assertThat(backend.verbs.calls).contains("snapshot null null tab 'Customer' 2 null 0");
    }

    @Test
    void aSnapshotThatCannotResolveKeepsTheStepResults() throws Exception {
        backend.verbs.scopedSnapshotFailure = new DriverException(Reason.NOT_FOUND, "no tab 'Gone'",
                java.util.Arrays.asList("tab 'Customer'"));
        JsonNode r = mapper.readTree(session.batch(mapper.readTree("{\"steps\":[" + PRESS + "],"
                + "\"snapshot\":{\"target\":{\"role\":\"tab\",\"name\":\"Gone\"}}}")));
        assertThat(r.path("passed").asBoolean()).isTrue();
        assertThat(r.path("steps")).hasSize(1);
        assertThat(r.has("snapshot")).isFalse();
        assertThat(r.path("snapshotError").path("errorType").asText()).isEqualTo("NOT_FOUND");
        assertThat(r.path("snapshotError").path("message").asText()).contains("no tab 'Gone'");
        assertThat(r.path("snapshotError").path("details").get(0).asText()).isEqualTo("tab 'Customer'");
    }

    @Test
    void malformedBatchesAreBadInput() {
        assertThat(catchThrowableOfType(() -> session.batch(mapper.readTree("{\"steps\":[]}")), DriverException.class)
                .reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(catchThrowableOfType(() -> batch("{\"target\":{\"ref\":\"e1\"}}"), DriverException.class)
                .getMessage()).contains("needs a 'verb'");
        assertThat(catchThrowableOfType(() -> batch("{\"verb\":\"click\",\"target\":{\"ref\":\"e1\"},\"bogus\":1}"),
                DriverException.class).reason()).isEqualTo(Reason.BAD_INPUT);
    }
}
