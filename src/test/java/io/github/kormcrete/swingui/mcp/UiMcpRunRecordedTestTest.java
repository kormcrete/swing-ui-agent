package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.flow.Flow;
import io.github.kormcrete.swingui.flow.RecordedTestFiles;
import io.github.kormcrete.swingui.flow.verb.ClickVerb;
import io.github.kormcrete.swingui.flow.verb.ExpectVerb;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class UiMcpRunRecordedTestTest {

    @TempDir Path testsDir;
    @TempDir Path evidence;
    private final ObjectMapper mapper = new ObjectMapper();
    private UiMcpReadToolsTest.DriverHost backend;
    private UiMcpSession session;

    @BeforeEach
    void setUp() {
        backend = new UiMcpReadToolsTest.DriverHost();
        session = new UiMcpSession(backend, new UiMcpToolsTest.FakeInventory(), mapper);
        session.recordedDir(testsDir, true);
        session.appReset(new UiMcpSession.AppReset() {
            @Override public boolean defined() { return false; }
            @Override public void run() { backend.verbs.calls.add("reset"); }
        });
        session.personBusy = () -> null;
    }

    private Flow flow(String name) {
        Flow f = new Flow();
        f.version = RecordedTestFiles.VERSION;
        f.name = name;
        f.start = RecordedTestFiles.START_RESET;
        ClickVerb click = new ClickVerb();
        click.target = Locator.of("button", "Store");
        ExpectVerb expect = new ExpectVerb();
        expect.target = Locator.of("label", "Status");
        expect.text = "Stored";
        f.steps.add(click);
        f.steps.add(expect);
        return f;
    }

    private Path save(String name) throws Exception {
        Path file = testsDir.resolve(name + ".json");
        Files.write(file, RecordedTestFiles.write(flow(name)).getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private JsonNode run(String json) throws Exception {
        return mapper.readTree(session.runRecordedTest(mapper.readTree(json)));
    }

    private String evidenceArg() {
        return "\"evidence\":" + mapper.valueToTree(evidence.toString());
    }

    @Test
    void aPassingRecordedTestStartsWithAResetRunsEveryStepAndRecordsNothing() throws Exception {
        save("place-order");
        session.startRecording(true);
        int before = session.humanSink().steps().size();
        JsonNode out = run("{\"name\":\"place-order\"," + evidenceArg() + "}");
        assertThat(out.path("passed").asBoolean()).isTrue();
        assertThat(out.path("ran").asInt()).isEqualTo(1);
        assertThat(out.path("total").asInt()).isEqualTo(1);
        assertThat(out.path("test").asText()).isEqualTo("place-order");
        assertThat(out.path("file").asText()).isEqualTo(testsDir.resolve("place-order.json").toAbsolutePath().normalize().toString());
        assertThat(out.path("steps")).hasSize(2);
        assertThat(out.path("steps").get(1).path("n").asText()).isEqualTo("1.1");
        assertThat(out.path("steps").get(1).has("index")).isFalse();
        assertThat(out.path("report").asText()).contains("passed");
        assertThat(backend.verbs.calls.get(0)).isEqualTo("reset");
        assertThat(backend.verbs.calls.get(1)).startsWith("click");
        assertThat(backend.verbs.calls.get(2)).startsWith("expect");
        assertThat(session.humanSink().steps()).hasSize(before);
    }

    @Test
    void stepsCarryTheirLabelAndSentence() throws Exception {
        Flow f = flow("said");
        f.steps.get(0).say = "Store the order";
        Files.write(testsDir.resolve("said.json"), RecordedTestFiles.write(f).getBytes(StandardCharsets.UTF_8));
        JsonNode out = run("{\"name\":\"said\"," + evidenceArg() + "}");
        JsonNode first = out.path("steps").get(0);
        assertThat(first.path("n").isInt()).isTrue();
        assertThat(first.path("n").asInt()).isEqualTo(1);
        assertThat(first.path("say").asText()).isEqualTo("Store the order");
        JsonNode check = out.path("steps").get(1);
        assertThat(check.path("n").isTextual()).isTrue();
        assertThat(check.path("n").asText()).isEqualTo("1.1");
        assertThat(check.has("say")).isFalse();
        assertThat(out.path("report").asText()).contains("[1] PASS Store the order").contains("[1.1] PASS");
    }

    @Test
    void aFailingStepStopsTheRunAndCarriesItsDiagnosis() throws Exception {
        save("place-order");
        backend.verbs.failure = new DriverException(Reason.NOT_FOUND, "nothing matches");
        backend.verbs.candidates = Collections.singletonList("button 'Store' #save");
        JsonNode out = run("{\"name\":\"place-order\"," + evidenceArg() + "}");
        assertThat(out.path("passed").asBoolean()).isFalse();
        assertThat(out.path("ran").asInt()).isEqualTo(1);
        assertThat(out.path("total").asInt()).isEqualTo(1);
        JsonNode step = out.path("steps").get(0);
        assertThat(step.path("passed").asBoolean()).isFalse();
        assertThat(step.path("reason").asText()).isEqualTo("NOT_FOUND");
        assertThat(step.path("diagnostics").path("matches").get(0).path("candidates").get(0).asText())
                .isEqualTo("button 'Store' #save");
        Path shot = java.nio.file.Paths.get(step.path("diagnostics").path("screenshot").asText());
        assertThat(Files.isRegularFile(shot)).isTrue();
        assertThat(shot.startsWith(evidence.toAbsolutePath().normalize())).isTrue();
        assertThat(out.path("report").asText()).contains("FAIL");
    }

    @Test
    void byNameItRunsFromTheRecordedTestsFolder() throws Exception {
        save("add-to-cart");
        assertThat(run("{\"name\":\"add-to-cart.json\"," + evidenceArg() + "}").path("passed").asBoolean()).isTrue();
    }

    @Test
    void byPathItRunsTheFile() throws Exception {
        Path file = save("add-to-cart");
        JsonNode out = run("{\"path\":" + mapper.valueToTree(file.toString()) + "," + evidenceArg() + "}");
        assertThat(out.path("passed").asBoolean()).isTrue();
        assertThat(out.path("test").asText()).isEqualTo("add-to-cart");
    }

    @Test
    void anUnknownNameListsTheRecordedTestsThere() throws Exception {
        save("add-to-cart");
        save("place-order");
        DriverException e = catchThrowableOfType(() -> run("{\"name\":\"nope\"}"), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("no recorded test 'nope' in " + testsDir).contains("[add-to-cart, place-order]");
        assertThat(backend.verbs.calls).isEmpty();
    }

    @Test
    void anInlineRecordedTestIsCheckedLikeAFile() throws Exception {
        String ok = RecordedTestFiles.write(flow("inline"));
        JsonNode out = run("{\"test\":" + ok + "," + evidenceArg() + "}");
        assertThat(out.path("passed").asBoolean()).isTrue();
        assertThat(out.has("file")).isFalse();

        ObjectMapper m = mapper;
        com.fasterxml.jackson.databind.node.ObjectNode bad = (com.fasterxml.jackson.databind.node.ObjectNode) m.readTree(ok);
        bad.put("version", 3);
        DriverException e = catchThrowableOfType(() -> session.runRecordedTest(m.createObjectNode().set("test", bad)),
                DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("the 'test' argument");
    }

    @Test
    void anInlineRecordedTestGivenAsTextIsReadAsItsJson() throws Exception {
        String ok = RecordedTestFiles.write(flow("inline"));
        JsonNode out = run("{\"test\":" + mapper.valueToTree(ok) + "," + evidenceArg() + "}");
        assertThat(out.path("passed").asBoolean()).isTrue();
        assertThat(out.path("test").asText()).isEqualTo("inline");

        for (String notATest : new String[] {"42", "[1]", "true"}) {
            DriverException e = catchThrowableOfType(() -> run("{\"test\":" + notATest + "}"), DriverException.class);
            assertThat(e.reason()).as(notATest).isEqualTo(Reason.BAD_INPUT);
            assertThat(e.getMessage()).as(notATest).contains("'test' must be a recorded test file's JSON object");
        }
    }

    @Test
    void anInlineTestWithoutANameIsCalledInline() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode t = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(RecordedTestFiles.write(flow("x")));
        t.remove("name");
        JsonNode out = run("{\"test\":" + t + "," + evidenceArg() + "}");
        assertThat(out.path("test").asText()).isEqualTo("inline");
    }

    @Test
    void itTakesExactlyOneOfNamePathOrTest() {
        for (String args : new String[] {"{}", "{\"name\":\"a\",\"path\":\"b\"}"}) {
            DriverException e = catchThrowableOfType(() -> run(args), DriverException.class);
            assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
            assertThat(e.getMessage()).contains("run_recorded_test takes one of 'name', 'path' or 'test'");
        }
    }

    @Test
    void whileAPersonIsBusyInThePaletteItIsRefused() throws Exception {
        save("place-order");
        session.personBusy = () -> "a person is recording";
        DriverException e = catchThrowableOfType(() -> run("{\"name\":\"place-order\"}"), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BUSY);
        assertThat(e.getMessage()).contains("BUSY: a person is recording");
        assertThat(backend.verbs.calls).isEmpty();
    }

    @Test
    void withoutAResetThereIsNoRun() throws Exception {
        save("place-order");
        UiMcpSession plain = new UiMcpSession(backend, new UiMcpToolsTest.FakeInventory(), mapper);
        plain.recordedDir(testsDir, true);
        plain.personBusy = () -> null;
        DriverException e = catchThrowableOfType(
                () -> plain.runRecordedTest(mapper.readTree("{\"name\":\"place-order\"}")), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.CAPABILITY_GAP);
        assertThat(backend.verbs.calls).isEmpty();
    }

    @Test
    void whileARecordingRunsARecordedTestRunIsRefused() throws Exception {
        save("place-order");
        // the real reason, not the seam
        session = new UiMcpSession(backend, new UiMcpToolsTest.FakeInventory(), mapper);
        session.recordedDir(testsDir, true);
        session.appReset(new UiMcpSession.AppReset() {
            @Override public boolean defined() { return false; }
            @Override public void run() { backend.verbs.calls.add("reset"); }
        });
        session.recording(mapper.readTree("{\"op\":\"start\"}"));
        DriverException e = catchThrowableOfType(() -> run("{\"name\":\"place-order\"}"), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BUSY);
        assertThat(e.getMessage()).contains("a recording is running").contains("recording {\"op\":\"stop\"}");
        session.recording(mapper.readTree("{\"op\":\"stop\"}"));
        assertThat(run("{\"name\":\"place-order\"}").path("passed").asBoolean()).isTrue();
    }
}
