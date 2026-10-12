package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Geometry;
import io.github.kormcrete.swingui.driver.model.RowSpec;

import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumnModel;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

/**
 * Element geometry for {@code geometry}. EDT. Rectangles are the element's own, not clipped to what
 * shows: a row scrolled out of its viewport, or a tab scrolled out of its strip, lies outside the visible area.
 */
public final class Geometries {

    private Geometries() {}

    public static Geometry ofWindow(Window w) {
        Geometry g = new Geometry();
        g.width = w.getWidth();
        g.height = w.getHeight();
        return g;
    }

    /** A painted part's area ({@code area} in {@code c}'s coordinates), in window coordinates. */
    public static Geometry ofArea(Component c, Rectangle area) {
        Rectangle at = SwingUtilities.convertRectangle(c, area, top(c));
        Geometry g = new Geometry();
        g.x = at.x;
        g.y = at.y;
        g.width = at.width;
        g.height = at.height;
        return g;
    }

    public static Geometry of(Component c, Integer tab, RowSpec row, String column, List<String> item) {
        Component source = c;
        Rectangle rect;
        if (tab != null) {
            rect = ((JTabbedPane) c).getBoundsAt(tab);
        } else if (c instanceof JTable && row != null) {
            JTable table = (JTable) c;
            int r = TableWidget.row(table, row);
            rect = column != null ? table.getCellRect(r, TableWidget.column(table, column), true)
                    : table.getCellRect(r, 0, true).union(table.getCellRect(r, table.getColumnCount() - 1, true));
        } else if (c instanceof JTable && column != null) {
            JTable table = (JTable) c;
            rect = TableWidget.headerRect(table, column);
            source = table.getTableHeader();
            // An unparented header (a table outside a scroll pane) would give a rectangle in its own space, not the window's.
            if (source.getParent() == null) {
                throw new DriverException(Reason.NOT_FOUND, "the table's header is not shown (the table is in no scroll pane)");
            }
        } else if (c instanceof JTree && item != null) {
            JTree tree = (JTree) c;
            rect = tree.getRowBounds(TreeWidget.row(tree, item));
        } else if (c instanceof JList && item != null) {
            JList<?> list = (JList<?>) c;
            int i = ListWidget.index(list, item.get(0));
            rect = list.getCellBounds(i, i);
        } else {
            rect = new Rectangle(0, 0, c.getWidth(), c.getHeight());
        }
        // A tabbed pane, tree or list with no UI delegate has no rectangle for its tab, row or item.
        if (rect == null) throw new DriverException(Reason.NOT_FOUND, "the element is not laid out, so it has no bounds");
        Rectangle at = SwingUtilities.convertRectangle(source, rect, top(source));
        Geometry g = new Geometry();
        g.x = at.x;
        g.y = at.y;
        g.width = at.width;
        g.height = at.height;
        if (c instanceof JTable && row == null && column == null && tab == null) columns((JTable) c, g);
        JScrollPane pane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, c);
        if (pane != null && pane.getViewport() != null && pane.getViewport().getView() == c) {
            g.horizontalScrollBar = pane.getHorizontalScrollBar() != null && pane.getHorizontalScrollBar().isVisible();
            g.viewportWidth = pane.getViewport().getWidth();
        }
        return g;
    }

    /** The window, or the top ancestor of an unshown tree: every rectangle is converted into it. */
    private static Component top(Component c) {
        Window w = c instanceof Window ? (Window) c : SwingUtilities.getWindowAncestor(c);
        if (w != null) return w;
        Component t = c;
        while (t.getParent() != null) t = t.getParent();
        return t;
    }

    private static void columns(JTable table, Geometry g) {
        TableColumnModel model = table.getColumnModel();
        List<Integer> widths = new ArrayList<Integer>();
        List<Integer> natural = new ArrayList<Integer>();
        for (int i = 0; i < model.getColumnCount(); i++) {
            widths.add(model.getColumn(i).getWidth());
            TableCellRenderer fresh = freshHeaderRenderer(table);
            Component r = fresh == null ? null : fresh.getTableCellRendererComponent(
                    table, model.getColumn(i).getHeaderValue(), false, false, -1, i);
            natural.add(r == null ? -1 : r.getPreferredSize().width);
        }
        g.columnWidths = widths;
        g.headerTextWidths = natural;
    }

    /**
     * A NEW instance of the header's own default renderer class, used once per column: a shared renderer
     * that sets its preferred size on first use echoes that size for every later column.
     * Falls back to the shared instance when the class has no public no-argument constructor.
     */
    private static TableCellRenderer freshHeaderRenderer(JTable table) {
        JTableHeader header = table.getTableHeader();
        TableCellRenderer shared = header == null ? null : header.getDefaultRenderer();
        if (shared == null) return null;
        try {
            return shared.getClass().getConstructor().newInstance();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return shared;
        }
    }
}
