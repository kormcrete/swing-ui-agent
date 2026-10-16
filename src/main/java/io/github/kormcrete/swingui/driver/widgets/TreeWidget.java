package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Names;
import io.github.kormcrete.swingui.driver.model.Node;

import javax.swing.JTree;
import javax.swing.tree.TreePath;
import java.util.ArrayList;
import java.util.List;

/** Trees: visible rows as "A > B" paths; lookup of a visible path. EDT. */
public final class TreeWidget {

    private TreeWidget() {}

    public static void describe(JTree tree, Node n, int maxRows) {
        int rows = tree.getRowCount();
        int shown = Math.min(maxRows, rows);
        List<String> items = new ArrayList<String>();
        List<String> expanded = new ArrayList<String>();
        for (int r = 0; r < shown; r++) {
            String path = pathText(tree, r);
            items.add(path);
            if (tree.isExpanded(r)) expanded.add(path);
        }
        n.items = items;
        n.expanded = expanded;
        n.rowCount = rows;
        n.selected = Widgets.indices(tree.getSelectionRows());
        if (shown < rows) n.truncated = Boolean.TRUE;
    }

    /** A tree row's own state: selected, expanded, leaf. EDT. */
    public static void itemState(JTree tree, int row, Node n) {
        if (tree.isRowSelected(row)) n.addState("selected");
        if (tree.isExpanded(row)) n.addState("expanded");
        TreePath path = tree.getPathForRow(row);
        if (path != null && tree.getModel().isLeaf(path.getLastPathComponent())) n.addState("leaf");
    }

    public static String pathText(JTree tree, int row) {
        return join(path(tree, row));
    }

    /** The labels from the top visible level down to {@code row}, as an {@code item} names that row. EDT. */
    public static List<String> path(JTree tree, int row) {
        List<String> parts = new ArrayList<String>();
        for (TreePath p = tree.getPathForRow(row); p != null; p = p.getParentPath()) {
            if (p.getParentPath() == null && !tree.isRootVisible()) break;
            int r = tree.getRowForPath(p);
            parts.add(0, r < 0 ? String.valueOf(p.getLastPathComponent()) : Cells.tree(tree, r).text);
        }
        return parts;
    }

    /** The visible row showing {@code path}; collapsed rows are not searched. */
    public static int row(JTree tree, List<String> path) {
        List<String> clean = new ArrayList<String>();
        for (String p : path) clean.add(Names.clean(p));
        String wanted = join(clean);
        List<Integer> hits = new ArrayList<Integer>();
        for (int r = 0; r < tree.getRowCount(); r++) {
            if (pathText(tree, r).equals(wanted)) hits.add(r);
        }
        if (hits.isEmpty()) {
            throw new DriverException(Reason.NOT_FOUND, "no visible tree row '" + wanted
                    + "' (collapsed rows are not searched; click the parent's toggle first)");
        }
        if (hits.size() > 1) throw new DriverException(Reason.AMBIGUOUS, hits.size() + " tree rows read '" + wanted + "'");
        return hits.get(0);
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(" > ");
            sb.append(p);
        }
        return sb.toString();
    }
}
