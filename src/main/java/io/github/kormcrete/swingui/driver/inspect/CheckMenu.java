package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.widgets.Cells;

import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The checks a person can record for one element, offered from what it shows NOW: a cell's text, a field's
 * value, a check box's state, a table's row count. Each is one {@code expect} step. A check {@code expect} cannot
 * express yet is listed greyed, so the gap is visible. EDT.
 */
final class CheckMenu extends JPopupMenu {

    static final class Choice {
        final String label;
        final Map<String, Object> step;

        Choice(String label, Map<String, Object> step) {
            this.label = label;
            this.step = step;
        }
    }

    static void show(Target t, MouseEvent e, BiConsumer<Map<String, Object>, Target> run) {
        CheckMenu menu = new CheckMenu();
        JMenuItem title = new JMenuItem(t.element);
        title.setEnabled(false);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        menu.add(title);
        menu.addSeparator();
        for (Choice c : choices(t)) {
            JMenuItem item = new JMenuItem(c.step == null ? c.label + "   (expect cannot check this yet)" : c.label);
            item.setEnabled(c.step != null);
            if (c.step != null) item.addActionListener(x -> run.accept(c.step, t));
            menu.add(item);
        }
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    static List<Choice> choices(Target t) {
        List<Choice> out = new ArrayList<Choice>();
        Component c = t.component;
        Locator element = t.locator.element();
        if (t.cell() && c instanceof JTable) {
            JTable table = (JTable) c;
            String text = Cells.table(table, t.row, t.column).text;
            Map<String, Object> cell = new LinkedHashMap<String, Object>();
            cell.put("row", t.locator.row);
            cell.put("column", t.locator.column);
            cell.put("expected", text);
            out.add(new Choice(t.locator.column + " is “" + text + "” in this row", expect(element, "cell", cell)));
            Locator row = element.copy();
            row.row = t.locator.row;
            if (table.isRowSelected(t.row)) out.add(new Choice("This row is selected", expect(row, "state", list("selected"))));
            String tip = Cells.tableTooltip(table, t.row, t.column);
            if (tip != null && !tip.isEmpty()) out.add(new Choice("Its tooltip is “" + tip + "”", expect(t.locator, "tooltip", tip)));
            rows(out, element, table.getRowCount());
            return out;
        }
        if (t.wholeColumn() && c instanceof JTable) {
            JTable table = (JTable) c;
            String header = t.locator.column;
            out.add(new Choice("Column “" + header + "” is shown", expect(t.locator, "text", header)));
            String first = table.getRowCount() > 0 ? Cells.table(table, 0, t.column).text : "";
            out.add(new Choice("Every row's " + header + " is “" + first + "”", null));
            out.add(new Choice(header + " contains “" + first + "”", null));
            out.add(new Choice("Sorted by " + header + ", ascending", null));
            rows(out, element, table.getRowCount());
            return out;
        }
        if (c instanceof JTextComponent) {
            String v = ((JTextComponent) c).getText();
            out.add(new Choice(v.isEmpty() ? "Is empty" : "Value is “" + v + "”", expect(t.locator, "value", v)));
            out.add(new Choice(((JTextComponent) c).isEditable() ? "Is editable" : "Is read-only",
                    expect(t.locator, "state", list(((JTextComponent) c).isEditable() ? "editable" : "!editable"))));
        } else if (c instanceof JComboBox) {
            JComboBox combo = (JComboBox) c;
            String v = combo.getSelectedIndex() < 0 ? String.valueOf(combo.getSelectedItem())
                    : Cells.combo(combo, new JList(), combo.getSelectedIndex()).text;
            out.add(new Choice("Shows “" + v + "”", expect(t.locator, "value", v)));
            rows(out, t.locator, combo.getItemCount());
        } else if (c instanceof JCheckBox || c instanceof JToggleButton) {
            boolean on = ((AbstractButton) c).isSelected();
            out.add(new Choice(on ? "Is checked" : "Is not checked", expect(t.locator, "state", list(on ? "checked" : "!checked"))));
        } else if (c instanceof AbstractButton) {
            String text = ((AbstractButton) c).getText();
            if (text != null && !text.isEmpty()) out.add(new Choice("Reads “" + text + "”", expect(t.locator, "text", text)));
        } else if (c instanceof JLabel) {
            String text = ((JLabel) c).getText();
            out.add(new Choice("Reads “" + text + "”", expect(t.locator, "text", text)));
        } else if (c instanceof JTabbedPane && t.tab != null) {
            boolean on = ((JTabbedPane) c).getSelectedIndex() == t.tab;
            out.add(new Choice(on ? "Is the open tab" : "Is not the open tab", expect(t.locator, "state", list(on ? "selected" : "!selected"))));
        } else if (c instanceof JTable) {
            rows(out, t.locator, ((JTable) c).getRowCount());
        }
        if (t.tab == null && !(c instanceof JTable)) {
            out.add(new Choice(c.isEnabled() ? "Is enabled" : "Is disabled", expect(t.locator, "state", list(c.isEnabled() ? "!disabled" : "disabled"))));
        }
        if (c instanceof JComponent && t.tab == null) {
            String tip = ((JComponent) c).getToolTipText();
            if (tip != null && !tip.isEmpty()) out.add(new Choice("Its tooltip is “" + tip + "”", expect(t.locator, "tooltip", tip)));
        }
        return out;
    }

    private static void rows(List<Choice> out, Locator element, int n) {
        Map<String, Object> eq = new LinkedHashMap<String, Object>();
        eq.put("op", "eq");
        eq.put("value", n);
        out.add(new Choice("Has exactly " + n + " rows", expect(element, "rows", eq)));
        Map<String, Object> gte = new LinkedHashMap<String, Object>();
        gte.put("op", "gte");
        gte.put("value", 1);
        out.add(new Choice("Has at least 1 row", expect(element, "rows", gte)));
    }

    private static Map<String, Object> expect(Locator target, String kind, Object value) {
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("action", "expect");
        step.put("target", target);
        step.put(kind, value);
        return step;
    }

    private static List<String> list(String... s) {
        return s.length == 1 ? Collections.singletonList(s[0]) : Arrays.asList(s);
    }
}
