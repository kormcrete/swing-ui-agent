package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.testing.RecordingVerbs;

import io.github.kormcrete.swingui.driver.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TargetReadTest {

    private final RecordingVerbs verbs = new RecordingVerbs();
    private final Client ui = new Client(verbs, null, "UIT-test");

    @Test
    void rowsZipColumnsAndCells() {
        Node n = new Node();
        n.columns = Arrays.asList("Order no.", "Customer");
        n.rows = Arrays.asList(Arrays.asList("26-1", "A"), Arrays.asList("26-2", "B"));
        n.rowCount = 17;
        verbs.readAnswer = n;
        assertThat(ui.table("Orders").rows()).hasSize(2);
        assertThat(ui.table("Orders").rows().get(1)).containsEntry("Customer", "B");
        assertThat(ui.table("Orders").rowCount()).isEqualTo(17);
        assertThat(ui.table("Orders").columns()).containsExactly("Order no.", "Customer");
    }

    @Test
    void textIsTheNameAndValueIsTheValue() {
        Node n = new Node();
        n.name = "Description";
        n.value = "Widget 3";
        verbs.readAnswer = n;
        assertThat(ui.field("Description").text()).isEqualTo("Description");
        assertThat(ui.field("Description").value()).isEqualTo("Widget 3");
        assertThat(ui.field("Description").states()).isEmpty();
    }

    @Test
    void countAndTextsReadEveryMatch() {
        verbs.readAllAnswer = Arrays.asList(new Node("menuitem", "Profile"), new Node("menuitem", "Log out"));
        Target entries = ui.popup().role("menuitem", null);
        assertThat(entries.count()).isEqualTo(2);
        assertThat(entries.texts()).containsExactly("Profile", "Log out");
        assertThat(verbs.calls).contains("readAll " + entries.locator());
    }

    @Test
    void countRefusesAnNth() {
        assertThatThrownBy(() -> ui.button("OK").nth(1).count()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void selectionItemsAndExpansionComeFromTheNode() {
        Node tree = new Node("tree", "Grouping");
        tree.items = Arrays.asList("A", "A > B", "C");
        tree.expanded = Collections.singletonList("A");
        tree.selected = Collections.singletonList(1);
        verbs.readAnswer = tree;
        Target t = ui.tree("Grouping");
        assertThat(t.items()).containsExactly("A", "A > B", "C");
        assertThat(t.expandedItems()).containsExactly("A");
        assertThat(t.selectedRows()).containsExactly(1);
    }

    @Test
    void anUnselectedTableHasNoSelectedRows() {
        verbs.readAnswer = new Node("table", "Orders");
        assertThat(ui.table("Orders").selectedRows()).isEmpty();
    }
}
