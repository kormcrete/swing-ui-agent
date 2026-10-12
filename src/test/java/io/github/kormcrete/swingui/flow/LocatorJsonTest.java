package io.github.kormcrete.swingui.flow;

import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocatorJsonTest {

    private static final String FULL_CELL = "{\"window\":\"dialog:Assign\",\"scope\":[\"tab:Line items\"],"
            + "\"role\":\"table\",\"name\":\"Orders\",\"match\":\"exact\",\"row\":{\"Order no.\":\"ORD-1640\"},"
            + "\"column\":\"Apply\",\"nth\":0}";

    @Test
    void aFullCellLocatorRoundTrips() throws Exception {
        Locator l = FlowJson.mapper().readValue(FULL_CELL, Locator.class);
        l.validate();
        assertThat(l.scope).containsExactly("tab:Line items");
        assertThat(l.row).containsEntry("Order no.", "ORD-1640");
        JsonNode back = FlowJson.mapper().valueToTree(l);
        assertThat(back).isEqualTo(FlowJson.mapper().readTree(FULL_CELL));
    }

    @Test
    void nullFieldsAreLeftOut() {
        JsonNode node = FlowJson.mapper().valueToTree(Locator.of("button", "Save"));
        assertThat(node.size()).isEqualTo(2);
        assertThat(node.get("role").asText()).isEqualTo("button");
    }

    @Test
    void anIndexRowAndAPointReadAsNumbers() throws Exception {
        Locator l = FlowJson.mapper().readValue(
                "{\"role\":\"clickable\",\"row\":{\"index\":3},\"point\":{\"x\":0.5,\"y\":1}}", Locator.class);
        assertThat(l.rowSpec().index).isEqualTo(3);
        assertThat(l.point.get("y")).isEqualTo(1.0);
    }

    @Test
    void parsedLocatorsCompareEqual() throws Exception {
        assertThat(FlowJson.mapper().readValue(FULL_CELL, Locator.class))
                .isEqualTo(FlowJson.mapper().readValue(FULL_CELL, Locator.class));
    }
}
