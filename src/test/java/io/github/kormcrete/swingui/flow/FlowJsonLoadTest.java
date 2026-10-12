package io.github.kormcrete.swingui.flow;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowJsonLoadTest {

    @Test
    void parsesAFlowWithMixedActions() throws Exception {
        String json = "{"
                + "\"name\":\"orders-smoke\","
                + "\"description\":\"open orders finder\","
                + "\"steps\":["
                + "  {\"action\":\"navigate\",\"target\":\"Orders\"},"
                + "  {\"action\":\"expect\",\"target\":{\"role\":\"table\"},\"rows\":{\"op\":\"gte\",\"value\":1}},"
                + "  {\"action\":\"press\",\"keys\":\"Escape\"},"
                + "  {\"action\":\"close\"}"
                + "]}";

        Flow flow = FlowJson.parse(json);

        assertThat(flow.name).isEqualTo("orders-smoke");
        assertThat(flow.steps).hasSize(4);
        assertThat(flow.steps.get(0)).isInstanceOf(NavigateAction.class);
        assertThat(flow.steps.get(1)).isInstanceOf(io.github.kormcrete.swingui.flow.verb.ExpectVerb.class);
        assertThat(flow.steps.get(2)).isInstanceOf(io.github.kormcrete.swingui.flow.verb.PressVerb.class);
        assertThat(flow.steps.get(3)).isInstanceOf(CloseAction.class);
    }

    @Test
    void writeThenParseIsStable() throws Exception {
        Flow flow = new Flow();
        flow.name = "rt";
        NavigateAction nav = new NavigateAction();
        nav.target = "Customers";
        flow.steps = java.util.Collections.singletonList(nav);

        Flow back = FlowJson.parse(FlowJson.write(flow));

        assertThat(back.name).isEqualTo("rt");
        assertThat(((NavigateAction) back.steps.get(0)).target).isEqualTo("Customers");
    }

    @Test
    void loadsAValidFlowFile(@TempDir Path tempDir) throws Exception {
        String json = "{"
                + "\"name\":\"load-test\","
                + "\"description\":\"test load from file\","
                + "\"steps\":["
                + "  {\"action\":\"navigate\",\"target\":\"Orders\"},"
                + "  {\"action\":\"close\"}"
                + "]}";

        Path flowFile = tempDir.resolve("test-flow.json");
        Files.write(flowFile, json.getBytes(StandardCharsets.UTF_8));

        Flow flow = FlowJson.load(flowFile);

        assertThat(flow.name).isEqualTo("load-test");
        assertThat(flow.steps).hasSize(2);
    }

    @Test
    void malformedJsonIsBadInputNamingTheFile(@TempDir Path tempDir) throws Exception {
        Path flowFile = tempDir.resolve("malformed-flow.json");
        Files.write(flowFile, "{ not valid".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> FlowJson.load(flowFile))
                .isInstanceOfSatisfying(DriverException.class, e -> assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT))
                .hasMessageContaining(flowFile.toString())
                .hasMessageContaining("line 1");
    }

    @Test
    void anUnknownStepTypeKeepsItsReasonAndNamesTheFile(@TempDir Path tempDir) throws Exception {
        Path flowFile = tempDir.resolve("unknown-step.json");
        String json = "{\"name\":\"t\",\"steps\":[{\"action\":\"countRows\"}]}";
        Files.write(flowFile, json.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> FlowJson.load(flowFile))
                .isInstanceOfSatisfying(DriverException.class, e -> assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT))
                .hasMessageStartingWith("BAD_INPUT: flow file " + flowFile)
                .hasMessageContaining("'countRows'")
                .hasMessageContaining("the step types are");
    }

    @Test
    void aMissingFileIsBadInputNamingIt(@TempDir Path tempDir) {
        Path flowFile = tempDir.resolve("absent.json");

        assertThatThrownBy(() -> FlowJson.load(flowFile))
                .isInstanceOfSatisfying(DriverException.class, e -> assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT))
                .hasMessageContaining(flowFile.toString());
    }
}
