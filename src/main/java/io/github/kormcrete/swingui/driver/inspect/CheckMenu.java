package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.widgets.Cells;
import io.github.kormcrete.swingui.driver.widgets.Screenshots;

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
import java.awt.Graphics;
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
 * express yet is listed greyed, so the gap is visible. The app plugin's domain checks those checks fit come first, in
 * the plugin's words ("✓ The cart total is €31.32"), each a {@code do} check ({@link Inspector.Sink#domainChecks}). EDT.
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

    /** {@link #of(Target, List, List, BiConsumer)} with the element's own checks only. */
    static CheckMenu of(Target t, BiConsumer<Map<String, Object>, Target> run) {
        return of(t, choices(t), Collections.<Inspector.DomainCheck>emptyList(), run);
    }

    /**
     * The menu for {@code t}: its title is what the person sees there, not the locator, which would make the menu
     * wider than the window and means nothing to them; then the plugin's {@code domain} checks (null: still on their way,
     * a greyed "…" holds their place until {@link #domain}), then the element's own {@code choices} ({@link #choices}).
     */
    static CheckMenu of(Target t, List<Choice> choices, List<Inspector.DomainCheck> domain,
                        BiConsumer<Map<String, Object>, Target> run) {
        CheckMenu menu = new CheckMenu();
        menu.target = t;
        menu.run = run;
        JMenuItem title = new JMenuItem(t.shown);
        title.setEnabled(false);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        menu.add(title);
        menu.addSeparator();
        if (domain == null) {
            // the plugin's checks are still being matched: a greyed line holds their place
            menu.waiting = new JMenuItem(WAITING);
            menu.waiting.setEnabled(false);
            menu.add(menu.waiting);
            menu.addSeparator();
        } else {
            menu.addDomain(domain, 2);
        }
        for (Choice c : choices) {
            JMenuItem item = new JMenuItem(c.step == null ? c.label + "   (expect cannot check this yet)" : c.label);
            item.setEnabled(c.step != null);
            if (c.step != null) item.addActionListener(x -> run.accept(c.step, t));
            menu.add(item);
        }
        return menu;
    }

    /** What holds the place of the plugin's checks while they are matched. */
    static final String WAITING = "…";

    private Target target;
    private BiConsumer<Map<String, Object>, Target> run;
    /** The greyed "…" while the plugin's checks are on their way; null when they came (or were never asked for). */
    private JMenuItem waiting;

    /**
     * The plugin's checks came ({@code domain}, maybe none): they take the place of the greyed "…", and a menu that
     * shows grows to hold them. Nothing when they came already.
     */
    void domain(List<Inspector.DomainCheck> domain) {
        if (waiting == null) return;
        int at = getComponentIndex(waiting);
        remove(at);   // the "…"
        remove(at);   // and its separator
        waiting = null;
        addDomain(domain, at);
        if (isVisible()) pack(); else revalidate();
    }

    /** Whether the plugin's checks are still on their way. */
    boolean waiting() {
        return waiting != null;
    }

    /** Puts a "✓ …" item per domain check at {@code at}, then a separator when there is any. */
    private void addDomain(List<Inspector.DomainCheck> domain, int at) {
        for (Inspector.DomainCheck d : domain) {
            JMenuItem item = new JMenuItem("✓ " + d.sentence);
            item.addActionListener(x -> run.accept(d.step, target));
            insert(item, at++);
        }
        if (!domain.isEmpty()) insert(new JPopupMenu.Separator(), at);
    }

    /** The bold title on top. */
    String title() {
        return ((JMenuItem) getComponent(0)).getText();
    }

    /** The items' labels, in order, the title first. */
    List<String> labels() {
        List<String> out = new ArrayList<String>();
        for (Component c : getComponents()) if (c instanceof JMenuItem) out.add(((JMenuItem) c).getText());
        return out;
    }

    /** Chooses {@code label}, as the person would: the menu closes, the item's action runs. */
    void choose(String label) {
        for (Component c : getComponents()) {
            if (c instanceof JMenuItem && label.equals(((JMenuItem) c).getText())) {
                setVisible(false);
                ((JMenuItem) c).doClick(0);
                return;
            }
        }
        throw new IllegalArgumentException("no item '" + label + "' in the check menu, only " + labels());
    }

    /** The steps of {@code choices} that can be recorded (not the greyed ones): what the plugin's checks are matched with. */
    static List<Map<String, Object>> steps(List<Choice> choices) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Choice c : choices) if (c.step != null) out.add(c.step);
        return out;
    }

    /** The check also says which view row it is about (see {@link Inspector#ROW_INDEX}). */
    private static Map<String, Object> withRow(Map<String, Object> step, Target t) {
        step.put(Inspector.ROW_INDEX, t.row);
        return step;
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
            out.add(new Choice(t.locator.column + " is “" + text + "” in this row", withRow(expect(element, "cell", cell), t)));
            Locator row = element.copy();
            row.row = t.locator.row;
            if (table.isRowSelected(t.row)) {
                out.add(new Choice("This row is selected", withRow(expect(row, "state", list("selected")), t)));
            }
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

    /** Not in a screenshot: a capture leaves the palette out. */
    @Override
    public void paint(Graphics g) {
        if (!Screenshots.capturing()) super.paint(g);
    }
}
