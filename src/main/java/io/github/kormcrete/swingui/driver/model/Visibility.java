package io.github.kormcrete.swingui.driver.model;

import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;

import java.awt.Component;
import java.awt.Container;

/** The predicate "the element is there to act on", pluggable so unit tests need no window. */
public interface Visibility {

    boolean isVisible(Component c);

    /**
     * Live rule: showing, with a non-empty size. A zero-height native screen menu bar is not visible.
     * An emptied table, list or tree is the one exception - see {@link #isEmptyDataView}.
     */
    Visibility SHOWING = c -> c.isShowing()
            && (c.getWidth() > 0 && c.getHeight() > 0 || isEmptyDataView(c));

    /** Unit tests: the component and all its ancestors are visible. */
    Visibility TREE = c -> {
        for (Component x = c; x != null; x = x.getParent()) {
            if (!x.isVisible()) return false;
        }
        return true;
    };

    /**
     * A table, list or tree holding no rows collapses to zero height, which the size rule above would drop,
     * so "no such table" and "the filter matched nothing" would read alike.
     *
     * <p>Such a component is still on screen: its viewport is. Keep it, with {@code rowCount} 0, so
     * {@code expect {rows: {op:"eq", value:0}}} means something. The viewport is required, so this
     * cannot revive a genuinely unshown widget.
     */
    static boolean isEmptyDataView(Component c) {
        if (!(c instanceof JTable || c instanceof JList || c instanceof JTree)) return false;
        if (rowCount(c) != 0) return false;
        Container viewport = SwingUtilities.getAncestorOfClass(JViewport.class, c);
        return viewport != null && viewport.isShowing()
                && viewport.getWidth() > 0 && viewport.getHeight() > 0;
    }

    static int rowCount(Component c) {
        if (c instanceof JTable) return ((JTable) c).getRowCount();
        if (c instanceof JList) return ((JList<?>) c).getModel().getSize();
        return ((JTree) c).getRowCount();
    }
}
