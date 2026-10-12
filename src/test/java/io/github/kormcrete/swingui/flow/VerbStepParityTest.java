package io.github.kormcrete.swingui.flow;

import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.flow.verb.VerbAction;
import io.github.kormcrete.swingui.testing.RecordingVerbs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** Each verb step, read from flow JSON, makes the same driver call as the verb called directly. */
class VerbStepParityTest {

    private static List<String> viaStep(String json) throws Exception {
        RecordingVerbs v = new RecordingVerbs();
        ((VerbAction) FlowJson.mapper().readValue(json, Action.class)).run(v);
        return v.calls;
    }

    private static List<String> direct(Consumer<Verbs> call) {
        RecordingVerbs v = new RecordingVerbs();
        call.accept(v);
        return v.calls;
    }

    private static Locator role(String role, String name) {
        Locator l = new Locator();
        l.role = role;
        l.name = name;
        return l;
    }

    @Test
    void hover() throws Exception {
        assertThat(viaStep("{\"action\":\"hover\",\"target\":{\"role\":\"button\",\"name\":\"OK\"},\"inside\":false,\"timeout\":500}"))
                .isEqualTo(direct(v -> v.hover(role("button", "OK"), false, 500L)));
    }

    @Test
    void hoverWithoutInsideMakesTheSameCallAsTheDefaultHover() throws Exception {
        assertThat(viaStep("{\"action\":\"hover\",\"target\":{\"role\":\"button\",\"name\":\"OK\"}}"))
                .isEqualTo(direct(v -> v.hover(role("button", "OK"), true, null)));
    }

    @Test
    void resizeColumn() throws Exception {
        assertThat(viaStep("{\"action\":\"resizeColumn\",\"target\":{\"role\":\"table\",\"column\":\"Name\"},\"delta\":-40}"))
                .isEqualTo(direct(v -> {
                    Locator l = role("table", null);
                    l.column = "Name";
                    v.resizeColumn(l, -40, null);
                }));
    }

    @Test
    void resizeWindowAndCloseWindow() throws Exception {
        assertThat(viaStep("{\"action\":\"resizeWindow\",\"window\":\"main\",\"width\":1200,\"height\":800}"))
                .isEqualTo(direct(v -> v.resizeWindow("main", 1200, 800, null)));
        assertThat(viaStep("{\"action\":\"closeWindow\",\"window\":\"dialog:Notice\",\"timeout\":2000}"))
                .isEqualTo(direct(v -> v.closeWindow("dialog:Notice", 2000L)));
    }

    @Test
    void focusFileChooserName() throws Exception {
        assertThat(viaStep("{\"action\":\"focusFileChooserName\",\"timeout\":3000}"))
                .isEqualTo(direct(v -> v.focusFileChooserName(3000L)));
    }

    private static StepResult runStep(String json) {
        Flow flow = FlowJson.parse("{\"name\":\"t\",\"steps\":[" + json + "]}");
        return new FlowExecutor().run(flow, new RecordingStepHost(new io.github.kormcrete.swingui.mcp.FakeVerbs())).steps.get(0);
    }

    @Test
    void aMissingResizeNumberIsBadInputNamingIt() {
        StepResult column = runStep("{\"action\":\"resizeColumn\",\"target\":{\"role\":\"table\",\"column\":\"Name\"}}");
        assertThat(column.passed).isFalse();
        assertThat(column.reason).isEqualTo("BAD_INPUT");
        assertThat(column.message).contains("resizeColumn needs 'delta'");
        StepResult width = runStep("{\"action\":\"resizeWindow\",\"window\":\"main\",\"height\":800}");
        assertThat(width.reason).isEqualTo("BAD_INPUT");
        assertThat(width.message).contains("resizeWindow needs 'width'");
        StepResult height = runStep("{\"action\":\"resizeWindow\",\"window\":\"main\",\"width\":800}");
        assertThat(height.reason).isEqualTo("BAD_INPUT");
        assertThat(height.message).contains("resizeWindow needs 'height'");
    }
}
