package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Names;
import io.github.kormcrete.swingui.driver.model.RowSpec;
import io.github.kormcrete.swingui.driver.widgets.Cells;
import io.github.kormcrete.swingui.driver.widgets.ComboWidget;
import io.github.kormcrete.swingui.driver.widgets.TableWidget;
import io.github.kormcrete.swingui.driver.widgets.Screenshots;

import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The checks a person can record for one element, offered from what it shows NOW: a cell's text, a field's
 * value, a check box's state, a table's row count. Each is one {@code expect} step. A check {@code expect} cannot
 * express yet is listed greyed, so the gap is visible. The app plugin's domain checks those checks fit come first, in
 * the plugin's words ("✓ The cart total is €31.32"), each a {@code do} check ({@link Inspector.Sink#domainChecks}). Last,
 * Remember this as…, which keeps the element's text for later steps ({@link #remember}). EDT.
 */
final class CheckMenu extends JPopupMenu {

    static final class Choice {
        final String label;
        /** The step it records; null for a choice that is greyed. */
        final Map<String, Object> step;
        /** Why it is greyed, shown beside it; null: expect cannot check this yet. */
        final String reason;

        Choice(String label, Map<String, Object> step) {
            this(label, step, null);
        }

        Choice(String label, Map<String, Object> step, String reason) {
            this.label = label;
            this.step = step;
            this.reason = reason;
        }
    }

    /** The choice that keeps a value for later steps: it opens a name field, then records a {@code remember} entry. */
    static final String REMEMBER = "Remember this as…";
    /**
     * Why a list entry, tree row or tab is not remembered when it is not the selected one: nothing but its own value
     * would find it again.
     */
    static final String SELECT_FIRST = "select the row first, or remember a field";
    static final String SELECT_ITEM_FIRST = "select the item first, or remember a field";
    static final String SELECT_TAB_FIRST = "select the tab first, or remember a field";

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
            String why = c.step != null ? null : c.reason != null ? c.reason : "expect cannot check this yet";
            JMenuItem item = new JMenuItem(why == null ? c.label : c.label + "   (" + why + ")");
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
        if (!isVisible()) {
            revalidate();
            return;
        }
        pack();
        // grown: still inside the window
        if (invoker != null && invoker.isShowing()) {
            Point p = inside(click, getPreferredSize(), windowIn(invoker));
            SwingUtilities.convertPointToScreen(p, invoker);
            setLocation(p.x, p.y);
        }
    }

    /** The component clicked and where, while the menu shows ({@link #showInside}); null before. */
    private Component invoker;
    private Point click;

    /**
     * Shows the menu where the person clicked ({@code x}, {@code y} in {@code on}) when it fits in the window there;
     * else to the left of the click and/or above it, shifted inside the window when neither side has room: like a
     * well-behaved popup, it never opens outside the app's window.
     */
    void showInside(Component on, int x, int y) {
        invoker = on;
        click = new Point(x, y);
        Point at = inside(click, getPreferredSize(), windowIn(on));
        show(on, at.x, at.y);
    }

    /** The window's area (its root pane) in {@code c}'s coordinates. */
    private static Rectangle windowIn(Component c) {
        JRootPane root = SwingUtilities.getRootPane(c);
        if (root == null) return new Rectangle(-c.getX(), -c.getY(), Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2);
        return SwingUtilities.convertRectangle(root, new Rectangle(0, 0, root.getWidth(), root.getHeight()), c);
    }

    /**
     * Where a {@code menu} sized popup clicked at {@code click} goes inside {@code window} (both in the clicked
     * component's coordinates): at the click when it fits; else its right edge, or bottom edge, at the click; else as
     * far in as it goes, never past the window's left or top edge.
     */
    static Point inside(Point click, Dimension menu, Rectangle window) {
        return new Point(along(click.x, menu.width, window.x, window.x + window.width),
                along(click.y, menu.height, window.y, window.y + window.height));
    }

    private static int along(int click, int size, int min, int max) {
        if (click + size <= max) return click;
        int flipped = click - size;
        return flipped >= min ? flipped : Math.max(min, max - size);
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

    /**
     * The checks of {@code choices} that can be recorded (not the greyed ones, not Remember this): what the plugin's
     * checks are matched with.
     */
    static List<Map<String, Object>> steps(List<Choice> choices) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Choice c : choices) if (c.step != null && !remembers(c.step)) out.add(c.step);
        return out;
    }

    /** Whether {@code step} is a {@code remember} entry (Remember this), not a check. */
    static boolean remembers(Map<String, Object> step) {
        return "remember".equals(step.get("action"));
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
            out.add(remember(t));
            return out;
        }
        if ((c instanceof JList || c instanceof JTree) && t.locator.item != null && t.row >= 0) {
            boolean on = c instanceof JList ? ((JList<?>) c).isSelectedIndex(t.row) : ((JTree) c).isRowSelected(t.row);
            if (on) out.add(new Choice("This item is selected", expect(t.locator, "state", list("selected"))));
            rows(out, element, c instanceof JList ? ((JList<?>) c).getModel().getSize() : ((JTree) c).getRowCount());
            out.add(new Choice("Is shown", expect(t.locator, "shown", true)));
            out.add(remember(t));
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
        // anything the person can click is there to be seen: "absent" has no gesture (nothing to click), the agent writes it
        out.add(new Choice("Is shown", expect(t.locator, "shown", true)));
        Choice remember = remember(t);
        if (remember != null) out.add(remember);
        return out;
    }

    /**
     * Remember this for {@code t}: a {@code remember} entry with the name to suggest ({@code as}: the field's label or
     * the column's header, as a name), or greyed with why; null for an element with no text to keep (a check box, a
     * table, a password field). The entry's target never holds the value it keeps, or a later run could not find it
     * with another value: a cell's row is found by its other columns when they tell it from the rest, else as the
     * selected row, else by its place (-1 for the last); a list entry, tree row or tab only as the selected one. A field,
     * label, button or drop-down keeps its own locator.
     */
    static Choice remember(Target t) {
        Component c = t.component;
        Locator target;
        String value;
        String name;
        boolean selected = false;
        if (t.cell() && c instanceof JTable) {
            JTable table = (JTable) c;
            value = Cells.table(table, t.row, t.column).text;
            name = t.locator.column;
            target = t.locator.element();
            target.column = t.locator.column;
            Map<String, Object> others = isBlank(value) ? null : otherColumns(table, t.row, t.column, value);
            if (others != null) {
                target.row = others;
            } else if (table.getSelectedRow() == t.row) {
                selected = true;
            } else {
                target.row = Collections.<String, Object>singletonMap("index",
                        t.row == table.getRowCount() - 1 ? -1 : t.row);
            }
        } else if ((c instanceof JList || c instanceof JTree) && t.locator.item != null && t.row >= 0) {
            value = c instanceof JList ? Cells.list((JList<?>) c, t.row).text : Cells.tree((JTree) c, t.row).text;
            if (selectedItem(c) != t.row) return greyed(c instanceof JList ? SELECT_FIRST : SELECT_ITEM_FIRST);
            target = t.locator.element();
            name = nameOf(target, "item");
            selected = true;
        } else if (c instanceof JTabbedPane && t.tab != null) {
            JTabbedPane tabs = (JTabbedPane) c;
            value = Names.tabTitle(tabs, t.tab);
            if (tabs.getSelectedIndex() != t.tab || t.whole == null) return greyed(SELECT_TAB_FIRST);
            target = t.whole;
            name = nameOf(target, "tab");
            selected = true;
        } else if (c instanceof JPasswordField || c instanceof JCheckBox || c instanceof JToggleButton) {
            return null;
        } else if (c instanceof JTextComponent || c instanceof JComboBox || c instanceof AbstractButton || c instanceof JLabel) {
            value = ownText(c);
            target = t.locator;
            name = nameOf(target, "value");
        } else {
            return null;
        }
        if (isBlank(value)) return greyed("it is empty");
        if (holds(target, value)) return greyed("it is found by its own text");
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("action", "remember");
        step.put("target", target);
        step.put("as", name(name));
        if (selected) step.put("selected", true);
        return new Choice(REMEMBER, step);
    }

    private static Choice greyed(String why) {
        return new Choice(REMEMBER, null, why);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** The text a field, drop-down, button or label shows, as {@code remember} reads it. */
    private static String ownText(Component c) {
        if (c instanceof JTextComponent) return ((JTextComponent) c).getText();
        // as remember reads it at replay: the renderer's shown value
        if (c instanceof JComboBox) return ComboWidget.selectedText((JComboBox<?>) c);
        return Names.clean(Names.ownText(c));
    }

    /** The view row of list or tree {@code c} that {@code remember} with {@code selected} reads; -1 none. */
    private static int selectedItem(Component c) {
        if (c instanceof JList) return ((JList<?>) c).getSelectedIndex();
        JTree tree = (JTree) c;
        int[] rows = tree.getSelectionRows();
        if (rows == null || rows.length == 0) return -1;
        int lead = tree.getLeadSelectionRow();
        if (lead >= 0 && tree.isRowSelected(lead)) return lead;
        int min = rows[0];
        for (int r : rows) min = Math.min(min, r);
        return min;
    }

    /**
     * Row {@code row} of {@code table} by columns other than {@code skip} that tell it from every other row: one column,
     * else two, else all of them; null when they do not. A column that is empty, shares its header or holds {@code value}
     * is never one of them.
     */
    private static Map<String, Object> otherColumns(JTable table, int row, int skip, String value) {
        List<String> headers = TableWidget.headers(table);
        List<Integer> usable = new ArrayList<Integer>();
        List<String> texts = new ArrayList<String>();
        for (int k = 0; k < table.getColumnCount(); k++) {
            String v = Cells.table(table, row, k).text;
            if (k == skip || isBlank(v) || v.contains(value) || headers.indexOf(headers.get(k)) != k) continue;
            usable.add(k);
            texts.add(v);
        }
        int n = usable.size();
        for (int i = 0; i < n; i++) {
            Map<String, Object> key = key(headers, usable, texts, i);
            if (finds(table, key, row)) return key;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Map<String, Object> key = key(headers, usable, texts, i, j);
                if (finds(table, key, row)) return key;
            }
        }
        if (n > 2) {
            int[] all = new int[n];
            for (int i = 0; i < n; i++) all[i] = i;
            Map<String, Object> key = key(headers, usable, texts, all);
            if (finds(table, key, row)) return key;
        }
        return null;
    }

    private static Map<String, Object> key(List<String> headers, List<Integer> columns, List<String> texts, int... at) {
        Map<String, Object> key = new LinkedHashMap<String, Object>();
        for (int i : at) key.put(headers.get(columns.get(i)), texts.get(i));
        return key;
    }

    /** Whether {@code key} names row {@code row} of {@code table} and no other. */
    private static boolean finds(JTable table, Map<String, Object> key, int row) {
        try {
            return TableWidget.row(table, RowSpec.of(key)) == row;
        } catch (DriverException notOne) {
            return false;
        }
    }

    /**
     * Whether locator {@code l} finds its element by {@code value}: in its name, scope, row key values or item. A row by
     * its place ({@code {"index": -1}}) holds no value, whatever digits the value has.
     */
    private static boolean holds(Locator l, String value) {
        if (l.name != null && l.name.contains(value)) return true;
        if (l.scope != null) for (String s : l.scope) if (s.contains(value)) return true;
        if (l.row != null && !l.row.containsKey("index")) {
            for (Object v : l.row.values()) if (String.valueOf(v).contains(value)) return true;
        }
        if (l.item != null) for (String s : l.item) if (s.contains(value)) return true;
        return false;
    }

    /** What a locator calls its element, for a name: its name, else its id, else {@code otherwise}. */
    private static String nameOf(Locator l, String otherwise) {
        if (l.name != null && !l.name.trim().isEmpty()) return l.name;
        return l.id != null && !l.id.trim().isEmpty() ? l.id : otherwise;
    }

    /**
     * {@code text} as a name a later step can use ({@code ${name}}): its words joined, the first in lower case
     * ("Order total" → {@code orderTotal}), a letter first, at most 36 characters (room for a number after it);
     * {@code value} when it has no letter or digit.
     */
    static String name(String text) {
        StringBuilder out = new StringBuilder();
        for (String word : (text == null ? "" : text).split("[^A-Za-z0-9]+")) {
            if (word.isEmpty()) continue;
            out.append(out.length() == 0 ? word.substring(0, 1).toLowerCase(Locale.ROOT) + word.substring(1)
                    : word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1));
        }
        String name = out.toString();
        if (name.isEmpty()) return "value";
        if (!Character.isLetter(name.charAt(0))) name = "value" + name;
        return name.length() > 36 ? name.substring(0, 36) : name;
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
