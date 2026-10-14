package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.flow.Flow;
import io.github.kormcrete.swingui.flow.FlowFiles;
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

class UiMcpRunFlowTest {

    @TempDir Path flows;
    @TempDir Path evidence;
    private final ObjectMapper mapper = new ObjectMapper();
    private UiMcpReadToolsTest.DriverHost backend;
    private UiMcpSession session;

    @BeforeEach
    void setUp() {
        backend = new UiMcpReadToolsTest.DriverHost();
        session = new UiMcpSession(backend, new UiMcpToolsTest.FakeInventory(), mapper);
        session.flowsDir(flows, true);
        session.appReset(new UiMcpSession.AppReset() {
            @Override public boolean defined() { return false; }
            @Override public void run() { backend.verbs.calls.add("reset"); }
        });
        session.personBusy = () -> null;
    }

    private Flow flow(String name) {
        Flow f = new Flow();
        f.version = FlowFiles.VERSION;
        f.name = name;
        f.start = FlowFiles.START_RESET;
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
        Path file = flows.resolve(name + ".json");
        Files.write(file, FlowFiles.write(flow(name)).getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private JsonNode run(String json) throws Exception {
        return mapper.readTree(session.runFlow(mapper.readTree(json)));
    }

    private String evidenceArg() {
        return "\"evidence\":" + mapper.valueToTree(evidence.toString());
    }

    @Test
    void aPassingFlowStartsWithAResetRunsEveryStepAndRecordsNothing() throws Exception {
        save("place-order");
        session.startRecording(true);
        int before = session.humanSink().steps().size();
        JsonNode out = run("{\"name\":\"place-order\"," + evidenceArg() + "}");
        assertThat(out.path("passed").asBoolean()).isTrue();
        assertThat(out.path("ran").asInt()).isEqualTo(2);
        assertThat(out.path("total").asInt()).isEqualTo(2);
        assertThat(out.path("flow").asText()).isEqualTo("place-order");
        assertThat(out.path("file").asText()).isEqualTo(flows.resolve("place-order.json").toAbsolutePath().normalize().toString());
        assertThat(out.path("steps")).hasSize(2);
        assertThat(out.path("steps").get(1).path("index").asInt()).isEqualTo(1);
        assertThat(out.path("report").asText()).contains("passed");
        assertThat(backend.verbs.calls.get(0)).isEqualTo("reset");
        assertThat(backend.verbs.calls.get(1)).startsWith("click");
        assertThat(backend.verbs.calls.get(2)).startsWith("expect");
        assertThat(session.humanSink().steps()).hasSize(before);
    }

    @Test
    void aFailingStepStopsTheRunAndCarriesItsDiagnosis() throws Exception {
        save("place-order");
        backend.verbs.failure = new DriverException(Reason.NOT_FOUND, "nothing matches");
        backend.verbs.candidates = Collections.singletonList("button 'Store' #save");
        JsonNode out = run("{\"name\":\"place-order\"," + evidenceArg() + "}");
        assertThat(out.path("passed").asBoolean()).isFalse();
        assertThat(out.path("ran").asInt()).isEqualTo(1);
        assertThat(out.path("total").asInt()).isEqualTo(2);
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
    void byNameItRunsFromTheFlowsFolder() throws Exception {
        save("add-to-cart");
        assertThat(run("{\"name\":\"add-to-cart.json\"," + evidenceArg() + "}").path("passed").asBoolean()).isTrue();
    }

    @Test
    void byPathItRunsTheFile() throws Exception {
        Path file = save("add-to-cart");
        JsonNode out = run("{\"path\":" + mapper.valueToTree(file.toString()) + "," + evidenceArg() + "}");
        assertThat(out.path("passed").asBoolean()).isTrue();
        assertThat(out.path("flow").asText()).isEqualTo("add-to-cart");
    }

    @Test
    void anUnknownNameListsTheFlowsThere() throws Exception {
        save("add-to-cart");
        save("place-order");
        DriverException e = catchThrowableOfType(() -> run("{\"name\":\"nope\"}"), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("no flow 'nope' in " + flows).contains("[add-to-cart, place-order]");
        assertThat(backend.verbs.calls).isEmpty();
    }

    @Test
    void anInlineFlowIsCheckedLikeAFile() throws Exception {
        String ok = FlowFiles.write(flow("inline"));
        JsonNode out = run("{\"flow\":" + ok + "," + evidenceArg() + "}");
        assertThat(out.path("passed").asBoolean()).isTrue();
        assertThat(out.has("file")).isFalse();

        ObjectMapper m = mapper;
        com.fasterxml.jackson.databind.node.ObjectNode bad = (com.fasterxml.jackson.databind.node.ObjectNode) m.readTree(ok);
        bad.put("version", 2);
        DriverException e = catchThrowableOfType(() -> session.runFlow(m.createObjectNode().set("flow", bad)),
                DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("the 'flow' argument");
    }

    @Test
    void anInlineFlowGivenAsTextIsReadAsItsJson() throws Exception {
        String ok = FlowFiles.write(flow("inline"));
        JsonNode out = run("{\"flow\":" + mapper.valueToTree(ok) + "," + evidenceArg() + "}");
        assertThat(out.path("passed").asBoolean()).isTrue();
        assertThat(out.path("flow").asText()).isEqualTo("inline");

        for (String notAFlow : new String[] {"42", "[1]", "true"}) {
            DriverException e = catchThrowableOfType(() -> run("{\"flow\":" + notAFlow + "}"), DriverException.class);
            assertThat(e.reason()).as(notAFlow).isEqualTo(Reason.BAD_INPUT);
            assertThat(e.getMessage()).as(notAFlow).contains("'flow' must be a flow file's JSON object");
        }
    }

    @Test
    void itTakesExactlyOneOfNamePathOrFlow() {
        for (String args : new String[] {"{}", "{\"name\":\"a\",\"path\":\"b\"}"}) {
            DriverException e = catchThrowableOfType(() -> run(args), DriverException.class);
            assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
            assertThat(e.getMessage()).contains("run_flow takes one of 'name', 'path' or 'flow'");
        }
    }

    @Test
    void whileAPersonIsBusyInThePaletteItIsRefused() throws Exception {
        save("place-order");
        session.personBusy = () -> "a person is recording";
        DriverException e = catchThrowableOfType(() -> run("{\"name\":\"place-order\"}"), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BUSY);
        assertThat(e.getMessage()).contains("busy: a person is recording");
        assertThat(backend.verbs.calls).isEmpty();
    }

    @Test
    void withoutAResetThereIsNoRun() throws Exception {
        save("place-order");
        UiMcpSession plain = new UiMcpSession(backend, new UiMcpToolsTest.FakeInventory(), mapper);
        plain.flowsDir(flows, true);
        plain.personBusy = () -> null;
        DriverException e = catchThrowableOfType(
                () -> plain.runFlow(mapper.readTree("{\"name\":\"place-order\"}")), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.CAPABILITY_GAP);
        assertThat(backend.verbs.calls).isEmpty();
    }
}
