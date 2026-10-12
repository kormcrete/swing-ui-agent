package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Node;
import io.github.kormcrete.swingui.driver.model.RowSpec;

import javax.swing.JTable;
import javax.swing.JViewport;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.Arrays;
import java.util.List;

/**
 * Where a scrollable element is scrolled to, and how to move it.
 *
 * <p>Moving is a direct {@link JViewport#setViewPosition} rather than posted wheel input: it is
 * exact, and it fires the same viewport change the scroll bar does, which is what paging listens
 * for. Steps taken this way record {@code via:api}. EDT.
 */
public final class Scrolls {

    /** The ends {@code to} may name. */
    public static final List<String> ENDS = Arrays.asList("top", "bottom", "left", "right");

    private Scrolls() {}

    /**
     * Fills {@code n.scroll} and the {@code atTop}/{@code atBottom} states when the component is a
     * viewport's view - the scrolled content itself, never a widget that merely sits inside a
     * scrolled panel, so the numbers always describe the element they hang off.
     */
    public static void describe(Component c, Node n) {
        JViewport vp = viewportOf(c);
        if (vp == null) return;
        Point p = vp.getViewPosition();
        Dimension extent = vp.getExtentSize();
        Dimension view = vp.getViewSize();
        Node.Scroll s = new Node.Scroll();
        s.x = p.x;
        s.y = p.y;
        s.width = extent.width;
        s.height = extent.height;
        s.maxX = Math.max(0, view.width - extent.width);
        s.maxY = Math.max(0, view.height - extent.height);
        n.scroll = s;
        if (p.y <= 0) n.addState("atTop");
        if (p.y >= s.maxY) n.addState("atBottom");
    }

    /**
     * Moves the viewport. Exactly one of {@code to}, {@code pages} and {@code row} is given; the
     * caller has already checked that. A row scrolls into view through the component itself, so it
     * works for a table whose rows are taller than one line.
     */
    public static void scroll(Component c, String to, Integer pages, RowSpec row) {
        if (row != null) {
            if (!(c instanceof JTable)) {
                throw new DriverException(Reason.BAD_INPUT, "a 'row' scrolls a table, not a "
                        + c.getClass().getSimpleName());
            }
            JTable table = (JTable) c;
            Rectangle area = TableWidget.bounds(table, TableWidget.row(table, row), -1);
            table.scrollRectToVisible(area);
            return;
        }
        JViewport vp = viewportOf(c);
        if (vp == null) {
            throw new DriverException(Reason.BAD_INPUT, c.getClass().getSimpleName()
                    + " does not scroll: it is not inside a scroll pane");
        }
        Point p = vp.getViewPosition();
        Dimension extent = vp.getExtentSize();
        Dimension view = vp.getViewSize();
        int maxX = Math.max(0, view.width - extent.width);
        int maxY = Math.max(0, view.height - extent.height);
        int x = p.x;
        int y = p.y;
        if ("top".equals(to)) {
            y = 0;
        } else if ("bottom".equals(to)) {
            y = maxY;
        } else if ("left".equals(to)) {
            x = 0;
        } else if ("right".equals(to)) {
            x = maxX;
        } else {
            y = p.y + pages * extent.height;
        }
        vp.setViewPosition(new Point(clamp(x, maxX), clamp(y, maxY)));
    }

    /** The viewport this component is the view of, or null when it does not scroll. */
    public static JViewport viewportOf(Component c) {
        Component parent = c.getParent();
        return parent instanceof JViewport && ((JViewport) parent).getView() == c ? (JViewport) parent : null;
    }


    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(value, max));
    }
}
