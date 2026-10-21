package io.github.kormcrete.swingui.driver.outcome;

import io.github.kormcrete.swingui.driver.PendingDialog;
import io.github.kormcrete.swingui.driver.model.Names;
import io.github.kormcrete.swingui.driver.model.Roles;
import io.github.kormcrete.swingui.driver.model.Trail;
import io.github.kormcrete.swingui.driver.model.Visibility;
import io.github.kormcrete.swingui.driver.widgets.Cells;
import io.github.kormcrete.swingui.driver.widgets.ComboWidget;
import io.github.kormcrete.swingui.driver.widgets.Dialogs;
import io.github.kormcrete.swingui.driver.widgets.TableWidget;
import io.github.kormcrete.swingui.driver.widgets.TreeWidget;
import io.github.kormcrete.swingui.driver.window.AppWindows;

import javax.swing.AbstractButton;
import javax.swing.CellRendererPane;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuBar;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.table.JTableHeader;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.IntConsumer;

/**
 * Takes a {@link UiReading} of the app's windows, the way a person reads them and checks compare them: rendered cell
 * texts, field values, label texts. Every tab page is read, not only the one in front, so a key does not depend on
 * which tab shows.
 *
 * <p>Keys are the name a person reads (a field's label, a table's caption, a value label's caption), else the code
 * name ({@code setName}); a table, list or tree with neither is "table", "list" or "tree". A label or button keyed by
 * its own text is keyed by its code name instead when it has one, so a changed text keeps its key. When two of one kind
 * share a key, each is prefixed with the tabs and titled sections around it ("Billing › Name"; the tabs of a strip of
 * open documents, whose titles are run data, left out), then with its window's title, then numbered in reading order
 * ("Name #2").
 *
 * <p>Within {@link #budgetMillis} the tables, lists and trees are read whole. Past it, a table, list or tree is read
 * only around its view and its selection (five rows on either side) and the reading is {@link UiReading#partial()}.
 *
 * <p>Reads models and rendered values only: no listener, no accessibility context, nothing the app would see. Never
 * the palette's own parts or windows. EDT.
 */
public final class UiReader {

    /** Rows read on either side of a selected row once the budget is spent. */
    static final int AROUND = 5;
    /** At most this many selected rows are read around once the budget is spent (a whole table may be selected). */
    static final int MAX_SELECTED = 100;
    private static final String SEP = " › ";

    private final Clock clock;
    private final long budgetMillis;

    public UiReader(Clock clock, long budgetMillis) {
        this.clock = clock;
        this.budgetMillis = budgetMillis;
    }

    /** 40 ms on the system clock. */
    public static UiReader standard() {
        return new UiReader(Clock.systemUTC(), 40);
    }

    /** A reading of {@code windows}: the app's, as the caller lists them (shown or not). EDT. */
    public UiReading read(Collection<? extends Window> windows) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("the UI is read on the event dispatch thread");
        }
        return new Pass(clock.millis() + budgetMillis).read(windows);
    }

    /** What one element was read as, before its key is settled. */
    private static final class Entry {
        final String name;
        final List<String> scopes;
        final String window;
        final Object value;

        Entry(String name, List<String> scopes, String window, Object value) {
            this.name = name;
            this.scopes = new ArrayList<String>(scopes);
            this.window = window;
            this.value = value;
        }
    }

    /** One reading: the deadline and what was found so far. */
    private final class Pass {
        private final long deadline;
        private boolean partial;
        private final List<Entry> fields = new ArrayList<Entry>();
        private final List<Entry> texts = new ArrayList<Entry>();
        private final List<Entry> tables = new ArrayList<Entry>();
        /** Lists and trees: one kind, as the outcome's {@code items} holds them. */
        private final List<Entry> items = new ArrayList<Entry>();
        private final UiReading.Builder out = UiReading.builder();
        private String window;

        Pass(long deadline) {
            this.deadline = deadline;
        }

        UiReading read(Collection<? extends Window> windows) {
            for (Window w : windows) {
                if (Roles.agentOwn(w) || AppWindows.isPopupWindow(w)) continue;
                window = AppWindows.titleOf(w);
                if (w instanceof Dialog) out.dialog(window);
                else out.window(window);
                Names names = new Names(within(w));
                PendingDialog message = w instanceof Dialog ? optionPane(w, names) : null;
                if (message != null) {
                    // a message dialog says one thing: its own labels and buttons are not the app's texts
                    if (!message.message.isEmpty()) out.message(message.message);
                    continue;
                }
                walk(w, w, names, new ArrayList<String>());
            }
            List<String> keys = keys(fields);
            for (int i = 0; i < keys.size(); i++) out.field(keys.get(i), (String) fields.get(i).value);
            keys = keys(texts);
            for (int i = 0; i < keys.size(); i++) out.text(keys.get(i), (String) texts.get(i).value);
            keys = keys(tables);
            for (int i = 0; i < keys.size(); i++) out.rows(keys.get(i), true, (UiReading.Rows) tables.get(i).value);
            keys = keys(items);
            for (int i = 0; i < keys.size(); i++) out.rows(keys.get(i), false, (UiReading.Rows) items.get(i).value);
            return out.partial(partial).build();
        }

        private PendingDialog optionPane(Window w, Names names) {
            try {
                return Dialogs.optionPane(w, names);
            } catch (RuntimeException unreadable) {
                return null;
            }
        }

        private void walk(Component c, Container labelRoot, Names names, List<String> scopes) {
            if (c != labelRoot && !c.isVisible()) return;
            if (c instanceof JPopupMenu || c instanceof JMenuBar || c instanceof JTableHeader
                    || c instanceof CellRendererPane || Roles.skips(c)) {
                return;
            }
            if (c instanceof Window) {
                children(c, labelRoot, names, scopes);
                return;
            }
            String role = Roles.of(c);
            try {
                if (read(c, role, labelRoot, names, scopes)) return;
            } catch (RuntimeException unreadable) {
                // an app renderer or model that fails is the app's own business: the element is left out
                return;
            }
            String caption = Names.sectionTitle(c);
            if (caption != null) scopes.add(caption);
            if (c instanceof JTabbedPane) {
                tabs((JTabbedPane) c, labelRoot, names, scopes);
            } else if (role == null || "clickable".equals(role)) {
                children(c, labelRoot, names, scopes);
            }
            if (caption != null) scopes.remove(scopes.size() - 1);
        }

        private void children(Component c, Container labelRoot, Names names, List<String> scopes) {
            if (!(c instanceof Container)) return;
            for (Component child : ((Container) c).getComponents()) walk(child, labelRoot, names, scopes);
        }

        /** Reads {@code c} when it is something read; true when nothing inside it is. */
        private boolean read(Component c, String role, Container labelRoot, Names names, List<String> scopes) {
            if (role == null) return false;
            switch (role) {
                case "text":
                    String field = name(c, labelRoot, names);
                    if (field != null) fields.add(new Entry(field, scopes, window, ((JTextComponent) c).getText()));
                    return true;
                case "combo":
                    String combo = name(c, labelRoot, names);
                    if (combo != null) {
                        fields.add(new Entry(combo, scopes, window, ComboWidget.selectedText((JComboBox<?>) c)));
                    }
                    return true;
                case "checkbox":
                case "radio":
                case "toggle":
                    String toggle = name(c, labelRoot, names);
                    if (toggle != null) {
                        fields.add(new Entry(toggle, scopes, window, String.valueOf(((JToggleButton) c).isSelected())));
                    }
                    return true;
                case "label":
                    if (names.namesAnInput(c, labelRoot)) return true;
                    text(c, Names.clean(((JLabel) c).getText()), labelRoot, names, scopes);
                    return true;
                case "button":
                    text(c, Names.clean(((AbstractButton) c).getText()), labelRoot, names, scopes);
                    return true;
                case "table":
                    tables.add(new Entry(name(c, labelRoot, names, "table"), scopes, window, table((JTable) c)));
                    return true;
                case "list":
                    items.add(new Entry(name(c, labelRoot, names, "list"), scopes, window, list((JList<?>) c)));
                    return true;
                case "tree":
                    items.add(new Entry(name(c, labelRoot, names, "tree"), scopes, window, tree((JTree) c)));
                    return true;
                case "password":
                case "menu":
                case "menuitem":
                case "menubar":
                    return true;
                default:
                    return false;
            }
        }

        private void text(Component c, String text, Container labelRoot, Names names, List<String> scopes) {
            if (text == null) return;
            String seen = names.seenName(c, labelRoot);
            String code = Names.clean(c.getName());
            String key = text.equals(seen) && code != null ? code : seen != null ? seen : text;
            texts.add(new Entry(key, scopes, window, text));
        }

        /** Every page, the one in front and the others: a hidden page's labels are searched from the page itself. */
        private void tabs(JTabbedPane tabs, Container labelRoot, Names names, List<String> scopes) {
            boolean documents = Trail.isDocumentHost(tabs);
            for (int i = 0; i < tabs.getTabCount(); i++) {
                String title = Names.tabTitle(tabs, i);
                if (title != null) out.tab(title);
                Component page = tabs.getComponentAt(i);
                if (page == null) continue;
                boolean scoped = title != null && !documents;
                if (scoped) scopes.add(title);
                if (page.isVisible() || !(page instanceof Container)) {
                    walk(page, labelRoot, names, scopes);
                } else {
                    walk(page, (Container) page, new Names(within((Container) page)), scopes);
                }
                if (scoped) scopes.remove(scopes.size() - 1);
            }
        }

        private String name(Component c, Container labelRoot, Names names) {
            String seen = names.seenName(c, labelRoot);
            return seen != null ? seen : Names.clean(c.getName());
        }

        private String name(Component c, Container labelRoot, Names names, String fallback) {
            String n = name(c, labelRoot, names);
            return n != null ? n : fallback;
        }

        private boolean over() {
            return clock.millis() > deadline;
        }

        private UiReading.Rows table(final JTable t) {
            final List<String> headers = TableWidget.headers(t);
            final Rows rows = new Rows(t.getRowCount(), t.getSelectedRows(), view(t, t.getRowCount()));
            rows.read(r -> {
                String[] cells = new String[t.getColumnCount()];
                for (int c = 0; c < cells.length; c++) cells[c] = Cells.table(t, r, c).text;
                rows.add(r, UiReading.row(headers, cells), Arrays.hashCode(cells));
            });
            return rows.done(headers);
        }

        private UiReading.Rows list(final JList<?> list) {
            int first = list.getFirstVisibleIndex();
            int[] view = first < 0 ? new int[0] : new int[] {first, list.getLastVisibleIndex()};
            final Rows rows = new Rows(list.getModel().getSize(), list.getSelectedIndices(), view);
            rows.read(i -> {
                String text = Cells.list(list, i).text;
                rows.add(i, text, text.hashCode());
            });
            return rows.done(null);
        }

        private UiReading.Rows tree(final JTree tree) {
            int[] selected = tree.getSelectionRows();
            Rectangle visible = tree.getVisibleRect();
            int[] view = visible.isEmpty() || tree.getRowCount() == 0 ? new int[0] : new int[] {
                    tree.getClosestRowForLocation(visible.x, visible.y),
                    tree.getClosestRowForLocation(visible.x, visible.y + visible.height - 1)};
            final Rows rows = new Rows(tree.getRowCount(), selected == null ? new int[0] : selected, view);
            rows.read(r -> {
                String path = TreeWidget.pathText(tree, r);
                rows.add(r, path, path.hashCode());
            });
            return rows.done(null);
        }

        /** The first and last row a table shows in its view; empty when it shows none. */
        private int[] view(JTable t, int count) {
            Rectangle visible = t.getVisibleRect();
            if (visible.isEmpty() || count == 0) return new int[0];
            int first = t.rowAtPoint(new Point(visible.x, visible.y));
            if (first < 0) return new int[0];
            int last = t.rowAtPoint(new Point(visible.x, visible.y + visible.height - 1));
            return new int[] {first, last < 0 ? count - 1 : last};
        }

        /** The rows of one table, list or tree, as far as they are read. */
        private final class Rows {
            final int count;
            final int[] selected;
            final int[] view;
            /** The rows still read once the budget is spent; null while it is not. */
            TreeSet<Integer> limited;
            final List<Integer> indices = new ArrayList<Integer>();
            final List<Object> values = new ArrayList<Object>();
            final List<Integer> hashes = new ArrayList<Integer>();

            Rows(int count, int[] selected, int[] view) {
                this.count = count;
                this.selected = selected;
                this.view = view;
                if (over()) limit();
            }

            /** From here on only the rows in view and around the selection; those read already and outside go. */
            void limit() {
                partial = true;
                limited = new TreeSet<Integer>();
                if (view.length == 2) {
                    for (int r = view[0]; r <= view[1] && r < count; r++) limited.add(r);
                }
                for (int k = 0; k < selected.length && k < MAX_SELECTED; k++) {
                    for (int r = selected[k] - AROUND; r <= selected[k] + AROUND; r++) {
                        if (r >= 0 && r < count) limited.add(r);
                    }
                }
                for (int k = indices.size() - 1; k >= 0; k--) {
                    if (!limited.contains(indices.get(k))) {
                        indices.remove(k);
                        values.remove(k);
                        hashes.remove(k);
                    }
                }
            }

            /** Reads every row while the budget lasts, then only those {@link #limit} keeps. */
            void read(IntConsumer row) {
                for (int r = 0; r < count; r++) {
                    if (limited == null && over()) limit();
                    if (limited != null) {
                        for (int kept : new ArrayList<Integer>(limited.tailSet(r))) row.accept(kept);
                        return;
                    }
                    row.accept(r);
                }
            }

            void add(int index, Object value, int hash) {
                indices.add(index);
                values.add(value);
                hashes.add(hash);
            }

            UiReading.Rows done(List<String> headers) {
                List<Integer> sel = new ArrayList<Integer>();
                for (int s : selected) sel.add(s);
                return new UiReading.Rows(headers, indices, values, hashes, sel, count, limited == null);
            }
        }
    }

    /**
     * One key per entry, in order: the name when no other entry of the kind shares it, else the name prefixed with the
     * scopes around it, then also with the window title, then numbered.
     */
    static List<String> keys(List<Entry> entries) {
        List<String> keys = new ArrayList<String>();
        for (Entry e : entries) keys.add(e.name);
        for (int level = 1; level <= 3; level++) {
            Map<String, Integer> seen = count(keys);
            for (int i = 0; i < entries.size(); i++) {
                if (seen.get(keys.get(i)) < 2) continue;
                Entry e = entries.get(i);
                if (level == 1) keys.set(i, join(e.scopes, e.name));
                else if (level == 2) keys.set(i, e.window.isEmpty() ? keys.get(i) : e.window + SEP + keys.get(i));
            }
            if (level == 3) {
                Map<String, Integer> nth = new LinkedHashMap<String, Integer>();
                List<String> settled = new ArrayList<String>(keys);
                for (int i = 0; i < entries.size(); i++) {
                    if (seen.get(keys.get(i)) < 2) continue;
                    Integer n = nth.get(keys.get(i));
                    n = n == null ? 1 : n + 1;
                    nth.put(keys.get(i), n);
                    settled.set(i, keys.get(i) + " #" + n);
                }
                return settled;
            }
        }
        return keys;
    }

    private static Map<String, Integer> count(List<String> keys) {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (String k : keys) {
            Integer n = out.get(k);
            out.put(k, n == null ? 1 : n + 1);
        }
        return out;
    }

    private static String join(List<String> scopes, String name) {
        StringBuilder sb = new StringBuilder();
        for (String s : scopes) sb.append(s).append(SEP);
        return sb.append(name).toString();
    }

    /**
     * What a label search from {@code root} sees: what is visible up to {@code root}, and {@code root} itself even
     * when hidden - an unshown window in a test, or a tab page behind the one in front.
     */
    static Visibility within(final Container root) {
        return new Visibility() {
            @Override
            public boolean isVisible(Component c) {
                for (Component x = c; x != null && x != root; x = x.getParent()) {
                    if (!x.isVisible()) return false;
                }
                return true;
            }

            @Override
            public boolean walks(Component c) {
                return c == root || c.isVisible();
            }
        };
    }
}
