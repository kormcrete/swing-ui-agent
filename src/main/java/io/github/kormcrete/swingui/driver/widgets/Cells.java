package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.model.Names;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * What a rendered cell shows: the renderer's text; a check box's state; otherwise
 * {@code toString()}, marked approximate. Rendering adds no listeners, unlike the accessibility API.
 */
public final class Cells {

    public static final class Text {
        public final String text;
        public final boolean approx;

        Text(String text, boolean approx) {
            this.text = text == null ? "" : text;
            this.approx = approx;
        }
    }

    private Cells() {}

    public static Text of(Component rendered, Object value) {
        if (rendered instanceof JLabel && ((JLabel) rendered).getText() != null) {
            return new Text(clean(((JLabel) rendered).getText()), false);
        }
        if (rendered instanceof AbstractButton) {
            AbstractButton b = (AbstractButton) rendered;
            String text = Names.clean(b.getText());
            if (text != null) return new Text(text, false);
            if (b instanceof JToggleButton) return new Text(String.valueOf(b.isSelected()), false);
        }
        if (rendered instanceof JTextComponent) {
            return new Text(clean(((JTextComponent) rendered).getText()), false);
        }
        String composed = fromChildren(rendered);
        if (composed != null) return new Text(composed, false);
        return new Text(value == null ? "" : String.valueOf(value), true);
    }

    /**
     * The text of a renderer that returns a small panel (a check box, a label and a count, say), read from
     * the components it built, so it does not fall back to an unassertable {@code toString()}. Joined with
     * spaces; null when no child has text. Creates no listener, accessible context or selection.
     */
    private static String fromChildren(Component rendered) {
        if (!(rendered instanceof Container)) return null;
        List<String> parts = new ArrayList<String>();
        collect((Container) rendered, parts, 0);
        if (parts.isEmpty()) return null;
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) out.append(' ');
            out.append(part);
        }
        return out.toString();
    }

    private static void collect(Container c, List<String> parts, int depth) {
        if (depth > 4 || parts.size() >= 8) return;
        for (Component child : c.getComponents()) {
            if (!child.isVisible()) continue;
            String text = null;
            if (child instanceof JLabel) {
                text = clean(((JLabel) child).getText());
            } else if (child instanceof AbstractButton) {
                AbstractButton b = (AbstractButton) child;
                text = Names.clean(b.getText());
                if (text == null && b instanceof JToggleButton) text = String.valueOf(b.isSelected());
            } else if (child instanceof JTextComponent) {
                text = clean(((JTextComponent) child).getText());
            }
            if (text != null && !text.isEmpty()) {
                parts.add(text);
            } else if (child instanceof Container) {
                collect((Container) child, parts, depth + 1);
            }
        }
    }

    public static Text table(JTable table, int row, int column) {
        Component rendered = table.prepareRenderer(table.getCellRenderer(row, column), row, column);
        return of(rendered, table.getValueAt(row, column));
    }

    /**
     * The tooltip a table cell shows on hover, HTML stripped, or null. The component the renderer
     * prepares for the cell is sized to it and asked the way {@code JTable.getToolTipText} asks: the
     * deepest component at the cell's centre first; failing that, the renderer component itself or its
     * first descendant (depth first) that carries one - a renderer that wraps its marker label in a
     * panel still reads as that label's tooltip.
     */
    public static String tableTooltip(JTable table, int row, int column) {
        Component rendered = table.prepareRenderer(table.getCellRenderer(row, column), row, column);
        Rectangle cell = table.getCellRect(row, column, false);
        rendered.setBounds(0, 0, Math.max(1, cell.width), Math.max(1, cell.height));
        rendered.validate();
        Component deepest = SwingUtilities.getDeepestComponentAt(rendered, rendered.getWidth() / 2,
                rendered.getHeight() / 2);
        String tip = deepest == null ? null : Widgets.tooltip(deepest);
        return tip != null ? tip : firstTooltip(rendered, 0);
    }

    private static String firstTooltip(Component c, int depth) {
        String own = Widgets.tooltip(c);
        if (own != null || depth > 4 || !(c instanceof Container)) return own;
        for (Component child : ((Container) c).getComponents()) {
            String tip = firstTooltip(child, depth + 1);
            if (tip != null) return tip;
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Text list(JList list, int index) {
        Object value = list.getModel().getElementAt(index);
        Component rendered = list.getCellRenderer()
                .getListCellRendererComponent(list, value, index, list.isSelectedIndex(index), false);
        return of(rendered, value);
    }

    public static Text tree(JTree tree, int row) {
        TreePath path = tree.getPathForRow(row);
        Object value = path.getLastPathComponent();
        Component rendered = tree.getCellRenderer().getTreeCellRendererComponent(tree, value,
                tree.isRowSelected(row), tree.isExpanded(row), tree.getModel().isLeaf(value), row, false);
        return of(rendered, value);
    }

    /** Index -1 renders the selected item, the way the combo paints it. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Text combo(JComboBox combo, JList scratch, int index) {
        Object value = index < 0 ? combo.getSelectedItem() : combo.getItemAt(index);
        ListCellRenderer renderer = combo.getRenderer();
        if (renderer == null) return new Text(value == null ? "" : String.valueOf(value), true);
        Component rendered = renderer.getListCellRendererComponent(scratch, value, index, false, false);
        return of(rendered, value);
    }

    private static String clean(String s) {
        String c = Names.clean(s);
        return c == null ? "" : c;
    }
}
