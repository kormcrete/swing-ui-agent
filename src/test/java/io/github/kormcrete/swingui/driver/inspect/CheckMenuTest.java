package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.model.Locator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The checks offered for an element are built from what it shows now, each one expect step. */
class CheckMenuTest {

    private final Scene scene = new Scene();

    @Test
    void aFieldOffersItsValue() {
        scene.customer.setText("Acme");
        CheckMenu.Choice value = first(CheckMenu.choices(scene.inspector.component(scene.customer)));
        assertThat(value.label).isEqualTo("Value is “Acme”");
        assertThat(value.step).containsEntry("action", "expect").containsEntry("value", "Acme");
    }

    @Test
    void aCheckBoxOffersItsState() {
        scene.rush.setSelected(true);
        CheckMenu.Choice state = first(CheckMenu.choices(scene.inspector.component(scene.rush)));
        assertThat(state.label).isEqualTo("Is checked");
        assertThat(state.step).containsEntry("state", Collections.singletonList("checked"));
    }

    @Test
    void aCellOffersItsTextOnTheTableAndTheRowCount() {
        List<CheckMenu.Choice> choices = CheckMenu.choices(scene.inspector.cell(scene.orders, 0, 2));
        CheckMenu.Choice cell = first(choices);
        Locator target = (Locator) cell.step.get("target");
        assertThat(target.row).isNull();
        assertThat(target.column).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) cell.step.get("cell");
        assertThat(spec).containsEntry("row", Collections.<String, Object>singletonMap("Order", "ORD-1640"))
                .containsEntry("column", "Status").containsEntry("expected", "Open");
        assertThat(choices).extracting(c -> c.label).contains("Has exactly 3 rows", "Has at least 1 row");
    }

    @Test
    void aCellCheckCarriesItsViewRow() {
        List<CheckMenu.Choice> choices = CheckMenu.choices(scene.inspector.cell(scene.orders, 1, 2));
        assertThat(first(choices).step).containsEntry(Inspector.ROW_INDEX, 1);
        scene.orders.setRowSelectionInterval(1, 1);
        List<CheckMenu.Choice> selected = CheckMenu.choices(scene.inspector.cell(scene.orders, 1, 2));
        CheckMenu.Choice row = selected.stream().filter(c -> c.label.equals("This row is selected")).findFirst().get();
        assertThat(row.step).containsEntry(Inspector.ROW_INDEX, 1);
        CheckMenu.Choice rows = selected.stream().filter(c -> c.label.startsWith("Has exactly")).findFirst().get();
        assertThat(rows.step).doesNotContainKey(Inspector.ROW_INDEX);
    }

    @Test
    void aColumnShowsTheChecksExpectCannotMakeYetGreyed() {
        List<CheckMenu.Choice> choices = CheckMenu.choices(scene.inspector.column(scene.orders, 2));
        assertThat(first(choices).step).containsEntry("text", "Status");
        assertThat(choices).filteredOn(c -> c.step == null).extracting(c -> c.label)
                .containsExactly("Every row's Status is “Open”", "Status contains “Open”", "Sorted by Status, ascending");
    }

    private static CheckMenu.Choice first(List<CheckMenu.Choice> choices) {
        assertThat(choices).isNotEmpty();
        return choices.get(0);
    }
}
