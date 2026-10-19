package io.github.kormcrete.swingui.spi;

import io.github.kormcrete.swingui.driver.model.Section;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryDefaultsTest {

    @Test
    void aSectionRowSaysWhetherItWasBuiltAndCarriesItsStateAndDetails() {
        Section lines = new Section();
        lines.id = "order.lines";
        lines.name = "Line items";
        lines.content = new JPanel();
        lines.addState("selected");
        lines.addState("incomplete");
        lines.details = new LinkedHashMap<String, String>();
        lines.details.put("hint", "add a product");
        Section notes = new Section();
        notes.id = "order.notes";
        notes.name = "Notes";
        List<Map<String, Object>> rows = InventoryDefaults.rows(Arrays.asList(lines, notes));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly(
                entry("id", "order.lines"), entry("name", "Line items"), entry("created", true),
                entry("state", Arrays.asList("selected", "incomplete")),
                entry("details", Collections.singletonMap("hint", "add a product")));
        assertThat(rows.get(1)).containsExactly(
                entry("id", "order.notes"), entry("name", "Notes"), entry("created", false),
                entry("state", Collections.emptyList()));
    }

    @Test
    void withoutADriverThereIsNothingToReport() {
        assertThat(InventoryDefaults.menuPaths(null)).isEmpty();
        assertThat(InventoryDefaults.viewSections(null)).isEmpty();
        assertThat(InventoryDefaults.viewSectionProblems(null)).isEmpty();
    }

    private static Map.Entry<String, Object> entry(String key, Object value) {
        return new java.util.AbstractMap.SimpleEntry<String, Object>(key, value);
    }
}
