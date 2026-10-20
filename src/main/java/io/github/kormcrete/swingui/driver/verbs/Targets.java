package io.github.kormcrete.swingui.driver.verbs;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Resolved;
import io.github.kormcrete.swingui.driver.widgets.ListWidget;
import io.github.kormcrete.swingui.driver.widgets.Scrolls;
import io.github.kormcrete.swingui.driver.widgets.TableWidget;
import io.github.kormcrete.swingui.driver.widgets.TreeWidget;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.table.JTableHeader;
import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.Map;

/**
 * Where to act on a resolved target: its sub-target scrolled into view, or its visible centre. EDT.
 * A table target with a column and no row is that column's header cell: the point is then in the
 * header's coordinates, and {@link #surface} names the header as the component to act on.
 */
final class Targets {

    private Targets() {}

    /** The component a pointer verb acts on: a table's header for a column target with no row, else the target. */
    static Component surface(Resolved r) {
        if (!isHeader(r)) return r.component;
        JTableHeader header = ((JTable) r.component).getTableHeader();
        if (header == null) {
            throw new DriverException(Reason.BAD_INPUT, "the table has no header for column '" + r.column() + "'");
        }
        return header;
    }

    /** A table column with no row: its header cell, as {@code Geometries} measures it. */
    static boolean isHeader(Resolved r) {
        return r.tab == null && r.component instanceof JTable && r.row() == null && r.column() != null;
    }

    /** The header cell's right border, where a resize drag starts (header coordinates). */
    static Point rightBorder(Resolved r) {
        Rectangle cell = headerCell(r);
        return new Point(cell.x + cell.width - 1, cell.y + cell.height / 2);
    }

    /** Throws BAD_INPUT when the table has no header (TableWidget's rule, shared with Geometries). */
    private static Rectangle headerCell(Resolved r) {
        return TableWidget.headerRect((JTable) r.component, r.column());
    }

    static Point aim(Resolved r) {
        if (isHeader(r)) return centre(headerCell(r));
        Component c = r.component;
        if (r.part != null) return centre(r.part.bounds);
        if (r.tab != null) return centre(((JTabbedPane) c).getBoundsAt(r.tab));
        Rectangle area = null;
        if (c instanceof JTable && r.row() != null) {
            JTable table = (JTable) c;
            int row = TableWidget.row(table, r.row());
            int column = r.column() == null ? -1 : TableWidget.column(table, r.column());
            area = TableWidget.bounds(table, row, column);
            Scrolls.reveal(table, area);
        } else if (c instanceof JList && r.item() != null) {
            JList<?> list = (JList<?>) c;
            int index = ListWidget.index(list, single(r.item()));
            area = list.getCellBounds(index, index);
            if (area != null) Scrolls.reveal(list, area);
        } else if (c instanceof JTree && r.item() != null) {
            JTree tree = (JTree) c;
            int row = TreeWidget.row(tree, r.item());
            area = tree.getRowBounds(row);
            if (area != null) Scrolls.reveal(tree, area);
        } else {
            Scrolls.reveal(c, new Rectangle(0, 0, c.getWidth(), c.getHeight()));
        }
        Map<String, Double> point = r.point();
        if (point != null) {
            return new Point((int) Math.round(point.get("x") * Math.max(0, c.getWidth() - 1)),
                    (int) Math.round(point.get("y") * Math.max(0, c.getHeight() - 1)));
        }
        return centre(area != null ? area : visible(c));
    }

    /**
     * Selects the table row, list item or tree row a right-click is aimed at, unless it is already part of the
     * selection, as a right-click does in most apps: their context menus act on the selection, and Swing's own
     * components do not select on a right-click. The same call a left click makes, so the app's selection listeners
     * see it. Any other target is left alone. EDT.
     */
    static void selectForContextMenu(Resolved r) {
        Component c = r.component;
        if (c instanceof JTable && r.row() != null) {
            JTable table = (JTable) c;
            int row = TableWidget.row(table, r.row());
            if (table.isRowSelected(row)) return;
            int column = r.column() == null ? -1 : TableWidget.column(table, r.column());
            table.changeSelection(row, Math.max(column, 0), false, false);
        } else if (c instanceof JList && r.item() != null) {
            JList<?> list = (JList<?>) c;
            int index = ListWidget.index(list, single(r.item()));
            if (!list.isSelectedIndex(index)) list.setSelectedIndex(index);
        } else if (c instanceof JTree && r.item() != null) {
            JTree tree = (JTree) c;
            int row = TreeWidget.row(tree, r.item());
            if (!tree.isRowSelected(row)) tree.setSelectionRow(row);
        }
    }

    /** The {row, column} a cell target names; both are required. */
    static int[] cell(JTable table, Resolved r) {
        if (r.row() == null || r.column() == null) {
            throw new DriverException(Reason.BAD_INPUT, "a table cell needs 'row' and 'column'");
        }
        return new int[] {TableWidget.row(table, r.row()), TableWidget.column(table, r.column())};
    }

    static Point centre(Rectangle r) {
        return new Point(r.x + r.width / 2, r.y + r.height / 2);
    }

    static Point centre(Component c) {
        return new Point(c.getWidth() / 2, c.getHeight() / 2);
    }

    private static Rectangle visible(Component c) {
        return c instanceof JComponent
                ? ((JComponent) c).getVisibleRect()
                : new Rectangle(0, 0, c.getWidth(), c.getHeight());
    }

    private static String single(List<String> item) {
        if (item.size() != 1) throw new DriverException(Reason.BAD_INPUT, "a list 'item' names one entry");
        return item.get(0);
    }
}
