package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.model.Locator;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
    void anyElementOffersIsShownAsTheLastChoice() {
        List<CheckMenu.Choice> choices = CheckMenu.choices(scene.inspector.component(scene.rush));
        CheckMenu.Choice shown = choices.get(choices.size() - 1);
        assertThat(shown.label).isEqualTo("Is shown");
        assertThat(shown.step).containsEntry("action", "expect").containsEntry("shown", true);
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

    @Test
    void itsTitleSaysWhatThePersonSeesNotTheLocator() {
        Target cell = scene.inspector.cell(scene.orders, 0, 2);
        assertThat(CheckMenu.of(cell, (step, t) -> { }).title()).isEqualTo("ORD-1640 · Status");
        Target column = scene.inspector.column(scene.orders, 2);
        assertThat(CheckMenu.of(column, (step, t) -> { }).title()).isEqualTo("Status column");
    }

    @Test
    void aValueLabelsChecksAreAboutItsCaption() {
        JLabel caption = new JLabel("Total");
        caption.setBounds(10, 130, 80, 20);
        JLabel value = new JLabel("€7.32");
        value.setBounds(100, 130, 80, 20);
        caption.setLabelFor(value);
        scene.content.add(caption);
        scene.content.add(value);

        Target target = scene.inspector.component(value);
        assertThat(CheckMenu.of(target, (step, t) -> { }).title()).isEqualTo("Total");
        CheckMenu.Choice reads = first(CheckMenu.choices(target));
        assertThat(reads.label).isEqualTo("Reads “€7.32”");
        Locator checked = (Locator) reads.step.get("target");
        assertThat(checked.role).isEqualTo("label");
        assertThat(checked.name).isEqualTo("Total");
        assertThat(reads.step).containsEntry("text", "€7.32");
    }

    @Test
    void aDomainCheckIsOfferedForTheTotal() {
        JLabel caption = new JLabel("Total");
        caption.setBounds(10, 130, 80, 20);
        JLabel value = new JLabel("€31.32");
        value.setBounds(100, 130, 80, 20);
        caption.setLabelFor(value);
        scene.content.add(caption);
        scene.content.add(value);
        Target target = scene.inspector.component(value);

        // the plugin's checks are matched against the checks offered for the element, as it shows now
        List<CheckMenu.Choice> choices = CheckMenu.choices(target);
        List<Map<String, Object>> asked = CheckMenu.steps(choices);
        assertThat(asked).hasSize((int) choices.stream().filter(c -> c.step != null).count());
        assertThat(asked.get(0)).containsEntry("action", "expect").containsEntry("text", "€31.32");

        Map<String, Object> cartTotal = new LinkedHashMap<String, Object>();
        cartTotal.put("action", "do");
        cartTotal.put("step", "order-desk:cartTotalIs");
        cartTotal.put("params", Collections.singletonMap("total", "€31.32"));
        List<Map<String, Object>> chosen = new ArrayList<Map<String, Object>>();
        CheckMenu menu = CheckMenu.of(target, choices,
                Collections.singletonList(new Inspector.DomainCheck("The cart total is €31.32", cartTotal)),
                (step, t) -> chosen.add(step));

        // first, in the plugin's words; the element's own checks below
        assertThat(menu.labels()).containsSubsequence("Total", "✓ The cart total is €31.32", "Reads “€31.32”");
        menu.choose("✓ The cart total is €31.32");
        assertThat(chosen).containsExactly(cartTotal);
        // still on their way: a greyed "…" holds their place, and they take it when they come
        CheckMenu later = CheckMenu.of(target, choices, null, (step, t) -> chosen.add(step));
        assertThat(later.labels()).containsSubsequence("Total", CheckMenu.WAITING, "Reads “€31.32”");
        assertThat(later.waiting()).isTrue();
        later.domain(Collections.singletonList(new Inspector.DomainCheck("The cart total is €31.32", cartTotal)));
        assertThat(later.labels()).containsSubsequence("Total", "✓ The cart total is €31.32", "Reads “€31.32”")
                .doesNotContain(CheckMenu.WAITING);
        // without a plugin's check the menu is as before
        assertThat(CheckMenu.of(target, choices, Collections.<Inspector.DomainCheck>emptyList(), (step, t) -> { }).labels())
                .noneMatch(l -> l.startsWith("✓"));
    }

    private static CheckMenu.Choice first(List<CheckMenu.Choice> choices) {
        assertThat(choices).isNotEmpty();
        return choices.get(0);
    }
}
