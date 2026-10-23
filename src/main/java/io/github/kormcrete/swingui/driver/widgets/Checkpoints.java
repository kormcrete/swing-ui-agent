package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.CheckpointShot;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Resolved;
import io.github.kormcrete.swingui.driver.model.RowSpec;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.table.JTableHeader;
import javax.swing.text.Caret;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Checkpoint pictures: the area a target names as it shows (a cell, row or column target is just that part), painted at
 * scale 1 and never scaled down, and the rectangles of its masks relative to the picture. EDT.
 *
 * <p>Painted like every capture ({@link Screenshots}): offscreen, the palette left out. The one thing it changes is the
 * text caret: a caret that is on (a focused field, or its blink) would make two runs differ, so every visible caret under
 * the area is turned off for the paint and on again in the same event-thread task; the app never sees it.
 */
public final class Checkpoints {

    private Checkpoints() {}

    /** Where a picture or a mask is: {@link #rect} in {@link #root}'s coordinates, empty when nothing of it shows. */
    public static final class Area {
        public final Component root;
        public final Rectangle rect;

        Area(Component root, Rectangle rect) {
            this.root = root;
            this.rect = rect == null || rect.isEmpty() ? new Rectangle() : rect;
        }
    }

    /** A whole window: its root pane as it shows (no title bar or border), or the content itself for a popup. */
    public static Area window(Component content) {
        Component root = content instanceof RootPaneContainer ? ((RootPaneContainer) content).getRootPane() : content;
        return new Area(root, Screenshots.shown(root));
    }

    /**
     * The area {@code r} names, as it shows: a painted part, a tab, a table cell, a table row (its cells across all
     * columns), a table column (its header cell and its cells; with the header shown in a scroll pane, the scroll pane is
     * the root), a tree or list item, each cut to what is in view; anything else the part of the component on screen.
     */
    public static Area area(Resolved r) {
        Component c = r.component;
        if (r.part != null) return new Area(c, visible(c, r.part.bounds));
        if (r.tab != null && c instanceof JTabbedPane) return new Area(c, visible(c, ((JTabbedPane) c).getBoundsAt(r.tab)));
        if (c instanceof JTable) {
            JTable table = (JTable) c;
            RowSpec row = r.row();
            if (row != null) {
                int at = TableWidget.row(table, row);
                Rectangle cells = r.column() != null
                        ? table.getCellRect(at, TableWidget.column(table, r.column()), true)
                        : table.getCellRect(at, 0, true).union(table.getCellRect(at, table.getColumnCount() - 1, true));
                return new Area(table, visible(table, cells));
            }
            if (r.column() != null) return column(table, TableWidget.column(table, r.column()));
        }
        if (c instanceof JTree && r.item() != null) {
            JTree tree = (JTree) c;
            return new Area(tree, visible(tree, tree.getRowBounds(TreeWidget.row(tree, r.item()))));
        }
        if (c instanceof JList && r.item() != null) {
            JList<?> list = (JList<?>) c;
            int i = ListWidget.index(list, r.item().get(0));
            return new Area(list, visible(list, list.getCellBounds(i, i)));
        }
        return new Area(c, Screenshots.shown(c));
    }

    /**
     * The picture of {@code area} with its masks' rectangles. NOT_ACTIONABLE when nothing of the area shows; BAD_INPUT
     * for a mask in another window or one that does not overlap the picture; a mask partly outside is cut to it and
     * says {@code clipped}. {@code targetWords} and {@code maskWords} (one per mask) name them in those messages.
     */
    public static CheckpointShot take(Area area, List<Area> masks, String targetWords, List<String> maskWords) {
        if (area.rect.isEmpty()) {
            throw new DriverException(Reason.NOT_ACTIONABLE, "nothing to capture: " + targetWords
                    + " is scrolled out of view or has no size; scroll it into view first");
        }
        Component window = windowOf(area.root);
        List<CheckpointShot.Mask> rects = new ArrayList<CheckpointShot.Mask>();
        for (int i = 0; i < masks.size(); i++) {
            Area m = masks.get(i);
            String words = maskWords.get(i);
            if (windowOf(m.root) != window) {
                throw new DriverException(Reason.BAD_INPUT, "mask " + words + " is in another window than checkpoint "
                        + targetWords);
            }
            Rectangle in = SwingUtilities.convertRectangle(m.root, m.rect, area.root);
            Rectangle cut = in.intersection(area.rect);
            if (m.rect.isEmpty() || cut.isEmpty()) {
                throw new DriverException(Reason.BAD_INPUT, "mask " + words + " does not overlap checkpoint " + targetWords);
            }
            rects.add(new CheckpointShot.Mask(cut.x - area.rect.x, cut.y - area.rect.y, cut.width, cut.height,
                    !cut.equals(in)));
        }
        List<Caret> hidden = new ArrayList<Caret>();
        BufferedImage image;
        try {
            hideCarets(area.root, hidden);
            image = Screenshots.paint(area.root, area.rect, 1);
        } finally {
            for (Caret caret : hidden) caret.setVisible(true);
        }
        return new CheckpointShot(Screenshots.png(image), image.getWidth(), image.getHeight(), rects);
    }

    /**
     * A column's cells in view; with the table's header shown in its scroll pane, the header cell too, both in the
     * scroll pane's coordinates (header and cells lie in two viewports, the pane holds both).
     */
    private static Area column(JTable table, int column) {
        Rectangle cells = table.getRowCount() == 0 ? new Rectangle()
                : visible(table, table.getCellRect(0, column, true)
                        .union(table.getCellRect(table.getRowCount() - 1, column, true)));
        JTableHeader header = table.getTableHeader();
        JScrollPane pane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
        JViewport heading = pane == null ? null : pane.getColumnHeader();
        boolean shown = header != null && header.isVisible() && heading != null && heading.isVisible()
                && heading.getView() == header && pane.getViewport() != null && pane.getViewport().getView() == table;
        if (!shown) return new Area(table, cells);
        Rectangle head = visible(header, header.getHeaderRect(column));
        Rectangle out = new Rectangle();
        if (!head.isEmpty()) out = SwingUtilities.convertRectangle(header, head, pane);
        if (!cells.isEmpty()) {
            Rectangle body = SwingUtilities.convertRectangle(table, cells, pane);
            out = out.isEmpty() ? body : out.union(body);
        }
        return new Area(pane, out);
    }

    /** {@code rect} cut to what shows of {@code c}; empty for none (a null rect: not laid out). */
    private static Rectangle visible(Component c, Rectangle rect) {
        if (rect == null) return new Rectangle();
        Rectangle view = c instanceof JComponent ? ((JComponent) c).getVisibleRect()
                : new Rectangle(0, 0, c.getWidth(), c.getHeight());
        Rectangle cut = rect.intersection(view);
        return cut.isEmpty() ? new Rectangle() : cut;
    }

    /**
     * The window {@code c} is in, or the top of a tree in no window; never past a window (a dialog's parent is its
     * owner).
     */
    private static Component windowOf(Component c) {
        Component top = c;
        for (Component p = c; p != null; p = p.getParent()) {
            top = p;
            if (p instanceof Window) break;
        }
        return top;
    }

    /** Turns off every caret that is on in a visible text component under {@code c}, adding it to {@code hidden}. */
    private static void hideCarets(Component c, List<Caret> hidden) {
        if (!c.isVisible()) return;
        if (c instanceof JTextComponent) {
            Caret caret = ((JTextComponent) c).getCaret();
            if (caret != null && caret.isVisible()) {
                caret.setVisible(false);
                hidden.add(caret);
            }
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) hideCarets(child, hidden);
        }
    }
}
