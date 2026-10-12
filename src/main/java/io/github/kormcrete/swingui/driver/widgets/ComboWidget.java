package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Node;

import javax.swing.JComboBox;
import javax.swing.JList;
import java.util.ArrayList;
import java.util.List;

/** Combo boxes: the shown value, the rendered items, lookup by text. EDT. */
public final class ComboWidget {

    private ComboWidget() {}

    public static void describe(JComboBox<?> combo, Node n, int maxRows) {
        JList<Object> scratch = new JList<Object>();
        Cells.Text value = Cells.combo(combo, scratch, -1);
        n.value = value.text;
        List<String> items = new ArrayList<String>();
        boolean approx = value.approx;
        int shown = Math.min(maxRows, combo.getItemCount());
        for (int i = 0; i < shown; i++) {
            Cells.Text text = Cells.combo(combo, scratch, i);
            approx |= text.approx;
            items.add(text.text);
        }
        n.items = items;
        n.rowCount = combo.getItemCount();
        if (shown < combo.getItemCount()) n.truncated = Boolean.TRUE;
        if (approx) n.approx = Boolean.TRUE;
        n.addState(combo.isEditable() ? "editable" : "readonly");
    }

    public static String selectedText(JComboBox<?> combo) {
        return Cells.combo(combo, new JList<Object>(), -1).text;
    }

    public static int index(JComboBox<?> combo, String text) {
        JList<Object> scratch = new JList<Object>();
        List<Integer> hits = new ArrayList<Integer>();
        List<String> seen = new ArrayList<String>();
        for (int i = 0; i < combo.getItemCount(); i++) {
            String t = Cells.combo(combo, scratch, i).text;
            if (seen.size() < 10) seen.add(t);
            if (t.equals(text)) hits.add(i);
        }
        if (hits.isEmpty()) throw new DriverException(Reason.NOT_FOUND, "no item '" + text + "' in the combo box", seen);
        if (hits.size() > 1) throw new DriverException(Reason.AMBIGUOUS, hits.size() + " combo items read '" + text + "'");
        return hits.get(0);
    }
}
