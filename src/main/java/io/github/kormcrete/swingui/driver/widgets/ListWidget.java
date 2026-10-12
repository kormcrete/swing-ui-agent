package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Node;

import javax.swing.JList;
import java.util.ArrayList;
import java.util.List;

/** Lists: visible rendered items and lookup by text. EDT. */
public final class ListWidget {

    private ListWidget() {}

    public static void describe(JList<?> list, Node n, int maxRows) {
        int size = list.getModel().getSize();
        int first = Math.max(0, list.getFirstVisibleIndex());
        int end = Math.min(size, first + maxRows);
        List<String> items = new ArrayList<String>();
        boolean approx = false;
        for (int i = first; i < end; i++) {
            Cells.Text text = Cells.list(list, i);
            approx |= text.approx;
            items.add(text.text);
        }
        n.items = items;
        n.rowCount = size;
        n.selected = Widgets.indices(list.getSelectedIndices());
        if (first > 0 || end < size) n.truncated = Boolean.TRUE;
        if (approx) n.approx = Boolean.TRUE;
    }

    public static int index(JList<?> list, String text) {
        List<Integer> hits = new ArrayList<Integer>();
        List<String> seen = new ArrayList<String>();
        for (int i = 0; i < list.getModel().getSize(); i++) {
            String t = Cells.list(list, i).text;
            if (seen.size() < 10) seen.add(t);
            if (t.equals(text)) hits.add(i);
        }
        if (hits.isEmpty()) throw new DriverException(Reason.NOT_FOUND, "no item '" + text + "'", seen);
        if (hits.size() > 1) throw new DriverException(Reason.AMBIGUOUS, hits.size() + " items read '" + text + "'");
        return hits.get(0);
    }
}
