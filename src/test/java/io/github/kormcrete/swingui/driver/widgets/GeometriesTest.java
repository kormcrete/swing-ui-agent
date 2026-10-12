package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Geometry;
import io.github.kormcrete.swingui.driver.model.RowSpec;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Component;
import java.awt.Insets;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class GeometriesTest {

    @Test
    void nestedComponentsShareTheTopAncestorsOrigin() {
        JPanel top = new JPanel(null);
        JPanel strip = new JPanel(null);
        strip.setBounds(10, 20, 400, 40);
        JPanel avatar = new JPanel(null);
        avatar.setBounds(300, 5, 28, 28);
        strip.add(avatar);
        top.add(strip);

        Geometry s = Geometries.of(strip, null, null, null, null);
        Geometry a = Geometries.of(avatar, null, null, null, null);

        assertThat(new int[] {s.x, s.y, s.width, s.height}).containsExactly(10, 20, 400, 40);
        assertThat(new int[] {a.x, a.y, a.width, a.height}).containsExactly(310, 25, 28, 28);
    }

    @Test
    void aTableRowIsOneRowHighAndATableCarriesItsColumnWidths() {
        JTable table = new JTable(3, 2);
        table.setRowHeight(21);
        table.setBounds(0, 0, 200, 63);
        table.getColumnModel().getColumn(0).setWidth(120);
        table.getColumnModel().getColumn(1).setWidth(80);

        Geometry row = Geometries.of(table, null, RowSpec.of(Collections.<String, Object>singletonMap("index", 1)), null, null);
        Geometry whole = Geometries.of(table, null, null, null, null);

        assertThat(row.height).isEqualTo(21);
        assertThat(row.y).isEqualTo(21);
        assertThat(whole.columnWidths).containsExactly(120, 80);
        assertThat(whole.headerTextWidths).hasSize(2);
    }

    @Test
    void aColumnWithoutARowIsItsHeaderCellInTheWindowsSpace() {
        JTable table = new JTable(new Object[][] {{"a", "b"}}, new Object[] {"Name", "Info"});
        JScrollPane pane = new JScrollPane(table);
        pane.setColumnHeaderView(table.getTableHeader());   // JTable does this in addNotify, which an unshown tree never gets
        JPanel parent = new JPanel(null);
        pane.setBounds(30, 40, 300, 150);
        parent.add(pane);
        pane.doLayout();
        pane.getColumnHeader().doLayout();
        pane.getViewport().doLayout();
        table.getColumnModel().getColumn(0).setWidth(120);
        table.getColumnModel().getColumn(1).setWidth(80);
        Insets in = pane.getInsets();

        Geometry info = Geometries.of(table, null, null, "Info", null);
        Geometry row = Geometries.of(table, null, RowSpec.of(Collections.<String, Object>singletonMap("index", 0)), null, null);

        // The pane's offset is in both: a header rect left in the header's own space would read (120, 0).
        assertThat(info.x).isEqualTo(30 + in.left + 120);
        assertThat(info.y).isEqualTo(40 + in.top);
        assertThat(info.width).isEqualTo(80);
        assertThat(info.height).isGreaterThan(0);
        assertThat(row.y).isGreaterThanOrEqualTo(info.y + info.height);
    }

    @Test
    void aHeaderThatIsNotShownIsNotFound() {
        JTable table = new JTable(new Object[][] {{"a", "b"}}, new Object[] {"Name", "Info"});   // in no scroll pane
        try {
            Geometries.of(table, null, null, "Info", null);
            fail("an unparented header has no window coordinates");
        } catch (DriverException e) {
            assertThat(e.reason()).isEqualTo(Reason.NOT_FOUND);
        }
    }

    @Test
    void eachColumnsHeaderWidthComesFromItsOwnRendererInstance() {
        JTable table = new JTable(new Object[][] {{"a", "b"}}, new Object[] {"A", "A much longer header text"});
        table.getTableHeader().setDefaultRenderer(new FreezingHeaderRenderer());
        int shortOnly = measureOnce(table, "A");
        int longOnly = measureOnce(table, "A much longer header text");

        // Positive control: one reused instance echoes the first column's width - the bug being guarded.
        FreezingHeaderRenderer shared = new FreezingHeaderRenderer();
        shared.getTableCellRendererComponent(table, "A", false, false, -1, 0);
        assertThat(shared.getTableCellRendererComponent(table, "A much longer header text", false, false, -1, 1)
                .getPreferredSize().width).isEqualTo(shortOnly);

        Geometry whole = Geometries.of(table, null, null, null, null);

        assertThat(shortOnly).isNotEqualTo(longOnly);
        assertThat(whole.headerTextWidths).containsExactly(shortOnly, longOnly);
    }

    private static int measureOnce(JTable table, String header) {
        return new FreezingHeaderRenderer().getTableCellRendererComponent(table, header, false, false, -1, 0)
                .getPreferredSize().width;
    }

    /** A header renderer that sets its own preferred size on first use, then keeps it. */
    public static final class FreezingHeaderRenderer extends DefaultTableCellRenderer {
        private boolean frozen;

        public FreezingHeaderRenderer() {}

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focused,
                                                       int row, int column) {
            super.getTableCellRendererComponent(t, value, selected, focused, row, column);
            if (!frozen) {
                setPreferredSize(getPreferredSize());
                frozen = true;
            }
            return this;
        }
    }

    @Test
    void aTreeItemIsItsRowBounds() {
        JTree tree = new JTree();   // the JDK sample model: colors, sports, food
        tree.setBounds(0, 0, 200, 200);
        java.awt.Rectangle expected = tree.getRowBounds(1);
        Geometry item = Geometries.of(tree, null, null, null, Arrays.asList("JTree", "colors"));
        assertThat(new int[] {item.x, item.y, item.width, item.height})
                .containsExactly(expected.x, expected.y, expected.width, expected.height);
    }

    @Test
    void aScrolledViewReportsItsViewportAndBar() {
        JTable table = new JTable(3, 2);
        JScrollPane pane = new JScrollPane(table);
        pane.setBounds(0, 0, 300, 100);
        pane.doLayout();
        Geometry g = Geometries.of(table, null, null, null, null);
        assertThat(g.viewportWidth).isNotNull();
        assertThat(g.horizontalScrollBar).isNotNull();
    }
}
