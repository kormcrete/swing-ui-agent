package io.github.kormcrete.swingui.flow;

import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The app steps' and the contextMenu verb's JSON; the other verbs are in {@code VerbJsonTest}. */
class ActionJsonTest {

    private final ObjectMapper mapper = FlowJson.mapper();

    @Test
    void theStepTypesAreTheVerbsAndTheThreeAppSteps() {
        assertThat(FlowJson.stepTypes()).containsExactly(
                "click", "type", "press", "check", "select", "pick", "menu", "answer", "chooseFile", "drag", "drop",
                "expect", "scroll", "setClipboard", "screenshot", "contextMenu", "hover", "resizeColumn", "resizeWindow", "closeWindow", "focusFileChooserName",
                "navigate", "close", "loginAs");
    }

    @Test
    void navigateRoundTrips() throws Exception {
        String json = "{\"action\":\"navigate\",\"target\":\"Orders\"}";
        Action a = mapper.readValue(json, Action.class);
        assertThat(a).isInstanceOf(NavigateAction.class);
        assertThat(((NavigateAction) a).target).isEqualTo("Orders");
        assertThat(mapper.readTree(mapper.writeValueAsString(a))).isEqualTo(mapper.readTree(json));
    }

    @Test
    void closeRoundTrips() throws Exception {
        String json = "{\"action\":\"close\"}";
        Action a = mapper.readValue(json, Action.class);
        assertThat(a).isInstanceOf(CloseAction.class);
        assertThat(a.describe()).isEqualTo("close");
        assertThat(mapper.readTree(mapper.writeValueAsString(a))).isEqualTo(mapper.readTree(json));
    }

    @Test
    void contextMenuRoundTripsItsTargetForm() throws Exception {
        String json = "{\"action\":\"contextMenu\",\"target\":{\"role\":\"table\",\"name\":\"Orders\"},"
                + "\"path\":[\"Export\",\"CSV file\"]}";
        ContextMenuAction a = (ContextMenuAction) mapper.readValue(json, Action.class);
        assertThat(a.target).isEqualTo(Locator.of("table", "Orders"));
        assertThat(a.path).containsExactly("Export", "CSV file");
        JsonNode back = mapper.valueToTree(a);
        assertThat(back).isEqualTo(mapper.readTree(json));
    }

    @Test
    void loginAsParsesItsAlias() {
        Flow flow = FlowJson.parse("{\"name\":\"x\",\"steps\":["
                + "{\"action\":\"loginAs\",\"as\":\"customer\"}]}");

        assertThat(flow.steps).hasSize(1);
        assertThat(flow.steps.get(0)).isInstanceOf(LoginAsAction.class);
        assertThat(((LoginAsAction) flow.steps.get(0)).as).isEqualTo("customer");
        assertThat(flow.steps.get(0).describe()).isEqualTo("loginAs(customer)");
    }
}
