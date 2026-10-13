package io.github.kormcrete.swingui.driver.inspect;

import org.junit.jupiter.api.Test;

import javax.swing.JTable;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Boxes snap to what a locator can address, and a table cell is addressed by its row's key. */
class InspectorTest {

    private final Scene scene = new Scene();

    @Test
    void aButtonSnapsToTheLocatorTheRecorderWrites() {
        Target t = scene.inspector.snap(scene.save, new Point(5, 5));
        assertThat(t.locator.role).isEqualTo("button");
        assertThat(t.locator.name).isEqualTo("Save");
        assertThat(t.element).contains("button 'Save'");
        assertThat(t.fragile).isFalse();
    }

    @Test
    void aFieldWithANameSnapsToItsId() {
        Target t = scene.inspector.component(scene.customer);
        assertThat(t.locator.id).isEqualTo("customer");
    }

    @Test
    void aPointInATableSnapsToThatCellKeyedByItsFirstUniqueColumn() {
        Rectangle cell = scene.orders.getCellRect(1, 2, true);
        Target t = scene.inspector.snap(scene.orders, new Point(cell.x + 2, cell.y + 2));
        assertThat(t.role).isEqualTo("cell");
        assertThat(t.locator.row).isEqualTo(Collections.<String, Object>singletonMap("Order", "ORD-1641"));
        assertThat(t.locator.column).isEqualTo("Status");
        assertThat(t.row).isEqualTo(1);
        assertThat(t.column).isEqualTo(2);
    }

    @Test
    void theHeaderSnapsToTheWholeColumn() {
        Rectangle h = scene.header.getHeaderRect(1);
        Target t = scene.inspector.snap(scene.header, new Point(h.x + 3, 3));
        assertThat(t.role).isEqualTo("column");
        assertThat(t.locator.column).isEqualTo("Customer");
        assertThat(t.locator.row).isNull();
        assertThat(t.wholeColumn()).isTrue();
    }

    @Test
    void aRepeatedValueIsNoKeyTheNextUniqueColumnIs() {
        JTable byCustomer = new JTable(new Object[][] {{"Acme", "ORD-1"}, {"Acme", "ORD-2"}}, new Object[] {"Customer", "Order"});
        assertThat(Inspector.rowKey(byCustomer, 1)).isEqualTo(Collections.<String, Object>singletonMap("Order", "ORD-2"));
    }

    @Test
    void withoutAUniqueColumnTheRowGoesByIndex() {
        JTable twins = new JTable(new Object[][] {{"Acme", "Open"}, {"Acme", "Open"}}, new Object[] {"Customer", "Status"});
        Map<String, Object> key = Inspector.rowKey(twins, 1);
        assertThat(key).isEqualTo(Collections.<String, Object>singletonMap("index", 1));
    }

    @Test
    void anActionAndTheChecksAfterItAreOneStep() {
        List<String> steps = Arrays.asList("click(#save)", "expect(#status text 'saved')", "expect(#save !disabled)",
                "click(#new)", "type(#customer 'Acme')");
        assertThat(Inspector.checksAfter(steps, 0, steps.size())).isEqualTo(2);
        assertThat(Inspector.checksAfter(steps, 3, steps.size())).isEqualTo(0);
        assertThat(Inspector.checksAfter(steps, 0, 2)).isEqualTo(1);
    }
}
