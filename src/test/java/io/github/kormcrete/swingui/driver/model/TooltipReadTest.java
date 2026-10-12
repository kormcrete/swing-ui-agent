package io.github.kormcrete.swingui.driver.model;

import io.github.kormcrete.swingui.driver.Expectation;
import io.github.kormcrete.swingui.driver.widgets.Cells;
import io.github.kormcrete.swingui.driver.widgets.Expectations;
import io.github.kormcrete.swingui.driver.widgets.Widgets;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.TableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A component's tooltip is readable and expectable - a field's, and a table cell's whose renderer sets it on a child
 * label rather than on the component JTable itself asks.
 */
class TooltipReadTest {

    private final Snapshot snapshot = new Snapshot(Visibility.TREE, new Names(Visibility.TREE), new Refs(c -> true),
            new FakeWindows());

    @Test
    void aFieldsTooltipRidesOnItsNode() {
        JTextField field = new JTextField("x");
        field.setToolTipText("At most 20 characters.");
        Node n = new Node("text", "Product");
        Widgets.describe(field, "text", n, 0);
        assertThat(n.tooltip).isEqualTo("At most 20 characters.");

        field.setToolTipText("<html>At most <b>20</b> characters.</html>");
        Node html = new Node("text", "Product");
        Widgets.describe(field, "text", html, 0);
        assertThat(html.tooltip).as("HTML is stripped, as a name's is").isEqualTo("At most 20 characters.");

        field.setToolTipText(null);
        Node none = new Node("text", "Product");
        Widgets.describe(field, "text", none, 0);
        assertThat(none.tooltip).isNull();
    }

    @Test
    void aCellsTooltipComesFromWhatTheRendererBuilt_evenWhenAChildCarriesIt() {
        JTable table = markerTable();
        assertThat(Cells.tableTooltip(table, 0, 1)).isEqualTo("was: 1.5");
        assertThat(Cells.tableTooltip(table, 1, 1)).as("a cell without a marker has none").isNull();

        Node cell = snapshot.describe(table, null, RowSpec.of(Collections.<String, Object>singletonMap("Line items", "Screws")),
                "Quantity", 20);
        assertThat(cell.value).isEqualTo("7.2");
        assertThat(cell.tooltip).isEqualTo("was: 1.5");
    }

    @Test
    void expectComparesTheTooltip_andAnEmptyOneMeansNone() {
        JTextField field = new JTextField("x");
        field.setToolTipText("At most 20 characters.");
        Resolved r = new Resolved(field, null, "text", "Product", "main", Collections.<String>emptyList(),
                Locator.of("text", "Product"));
        assertThat(Expectations.check(r, tooltip("At most 20 characters."))).isNull();
        assertThat(Expectations.check(r, tooltip(""))).isEqualTo("expected tooltip '' but was 'At most 20 characters.'");
        field.setToolTipText(null);
        assertThat(Expectations.check(r, tooltip(""))).isNull();

        JTable table = markerTable();
        Locator l = Locator.of("table", null);
        l.row = Collections.<String, Object>singletonMap("Line items", "Screws");
        l.column = "Quantity";
        Resolved cell = new Resolved(table, null, "table", null, "main", Collections.<String>emptyList(), l);
        assertThat(Expectations.check(cell, tooltip("was: 1.5"))).isNull();
    }

    private static Expectation tooltip(String t) {
        Expectation e = new Expectation();
        e.tooltip = t;
        return e;
    }

    /**
     * Row 0's Quantity cell renders as a panel whose CHILD label carries the tooltip - the shape that
     * defeats asking the rendered component alone; row 1's renders plain.
     */
    private static JTable markerTable() {
        JTable table = new JTable(new Object[][] {{"Screws", "7.2"}, {"Nuts", "0.1"}}, new Object[] {"Line items", "Quantity"});
        final JPanel panel = new JPanel(new BorderLayout());
        final JLabel marker = new JLabel();
        panel.add(marker, BorderLayout.CENTER);
        final TableCellRenderer plain = table.getDefaultRenderer(Object.class);
        table.getColumnModel().getColumn(1).setCellRenderer(new TableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                                                           int row, int column) {
                if (row != 0) return plain.getTableCellRendererComponent(t, value, selected, focus, row, column);
                marker.setText(String.valueOf(value));
                marker.setToolTipText("was: 1.5");
                return panel;
            }
        });
        return table;
    }
}
