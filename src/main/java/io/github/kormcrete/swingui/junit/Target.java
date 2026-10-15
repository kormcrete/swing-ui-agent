package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.ScopeStep;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable description of a UI element: every method returns a new Target. Chaining into a tab,
 * a section or a bare {@link #byId id} makes it a scope ("inside"); any other leaf can only be narrowed
 * by row, cell, column, item (a tree row), nth or contains.
 */
public final class Target {

    private final Client client;
    private final Locator locator;
    private final Long timeoutMillis;
    /** True when the locator names a leaf (role/name/id), false for a bare root or scope. */
    private final boolean hasLeaf;

    Target(Client client, Locator locator, Long timeoutMillis, boolean hasLeaf) {
        this.client = client;
        this.locator = locator;
        this.timeoutMillis = timeoutMillis;
        this.hasLeaf = hasLeaf;
    }

    public Locator locator() { return locator.copy(); }
    Client client() { return client; }
    Long timeoutMillis() { return timeoutMillis != null ? timeoutMillis : client.timeoutMillis(); }

    static Long millis(Duration d) {
        if (d == null || d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException("a timeout must be a positive duration, was " + d);
        }
        return d.toMillis();
    }

    // ---- leaves
    public Target button(String name) { return leaf("button", name); }
    public Target field(String name) { return leaf("text", name); }
    public Target password(String name) { return leaf("password", name); }
    public Target combo(String name) { return leaf("combo", name); }
    public Target checkbox(String name) { return leaf("checkbox", name); }
    public Target radio(String name) { return leaf("radio", name); }
    public Target toggle(String name) { return leaf("toggle", name); }
    public Target list(String name) { return leaf("list", name); }
    public Target tree(String name) { return leaf("tree", name); }
    public Target table(String name) { return leaf("table", name); }
    public Target table() { return leaf("table", null); }
    public Target tab(String title) { return leaf("tab", title); }
    public Target section(String caption) { return leaf("section", caption); }
    public Target label(String name) { return leaf("label", name); }
    public Target clickable(String name) { return leaf("clickable", name); }
    public Target menuitem(String name) { return leaf("menuitem", name); }
    public Target role(String role, String name) { return leaf(role, name); }

    /** The component name of a clearable text field's clear X. */
    private static final String CLEAR_BUTTON = "clear";

    /**
     * The element whose {@code getName()} - or, as a target, the section id a registered source
     * contributed - is {@code id}. Chained into (a bare id with no role, row, column, item, point or
     * nth), it becomes an {@code id:<name>} scope step: the next leaf must lie strictly inside a
     * component <em>named</em> {@code id}, never that component itself. An id scope matches the
     * component name only, not a contributed section id.
     */
    public Target byId(String id) {
        Locator l = container();
        l.id = id;
        return new Target(client, l, timeoutMillis, true);
    }

    private Target leaf(String role, String name) {
        Locator l = container();
        l.role = role;
        l.name = name;
        return new Target(client, l, timeoutMillis, true);
    }

    /** This target as the container of a new leaf: a tab, a section or a bare id becomes a scope step. */
    private Locator container() {
        Locator l = locator.copy();
        if (!hasLeaf) return l;
        if (l.id != null && l.role == null && l.row == null && l.column == null && l.item == null
                && l.point == null && l.nth == null) {
            List<String> scope = l.scope == null ? new ArrayList<String>() : new ArrayList<String>(l.scope);
            scope.add(ScopeStep.id(l.id));
            l.scope = scope;
            l.id = null;
            l.name = null;
            l.match = null;
            return l;
        }
        if (l.row != null || l.column != null || l.nth != null || l.id != null
                || !("tab".equals(l.role) || "section".equals(l.role))) {
            throw new IllegalStateException(describeLeaf() + " cannot contain other elements; only a tab, a section or a bare id can");
        }
        List<String> scope = l.scope == null ? new ArrayList<String>() : new ArrayList<String>(l.scope);
        scope.add(l.role + ":" + l.name);
        l.scope = scope;
        l.role = null;
        l.name = null;
        l.match = null;
        return l;
    }

    private String describeLeaf() {
        return (locator.role != null ? locator.role : "element") + " '" + (locator.name != null ? locator.name : locator.id) + "'";
    }

    // ---- narrowing
    public Target row(String column, Object value) { return row(Rows.row(column, value)); }
    public Target row(Map<String, Object> row) {
        Locator l = locator.copy();
        l.row = new LinkedHashMap<String, Object>(row);
        return with(l);
    }
    public Target rowAt(int index) { return row(Rows.index(index)); }
    public Target cell(String column) { Locator l = locator.copy(); l.column = column; return with(l); }
    /**
     * One of several columns that share the header {@code column} - a pivot table heads every order column
     * "Quantity" - told apart by the column's identifier (here: the order number), i.e.
     * {@code cell(column + "@" + identity)}.
     */
    public Target cell(String column, String identity) { return cell(column + "@" + identity); }
    public Target column(String name) { return cell(name); }
    public Target nth(int n) { Locator l = locator.copy(); l.nth = n; return with(l); }
    public Target contains() { Locator l = locator.copy(); l.match = "contains"; return with(l); }

    /**
     * A TREE row by its path of labels, as {@link #items()} shows it split on " > " (only expanded
     * rows are found). Read as an {@code item} node named by that path, with the states
     * {@code selected}, {@code expanded} and {@code leaf}. A list entry is not read this way.
     */
    public Target item(String... path) {
        Locator l = locator.copy();
        l.item = Arrays.asList(path);
        return with(l);
    }
    public Target timeout(Duration d) { return new Target(client, locator.copy(), millis(d), hasLeaf); }

    private Target with(Locator l) { return new Target(client, l, timeoutMillis, hasLeaf); }

    // ---- actions
    public void click() { click(null, 0); }

    public void click(String button, int count, String... modifiers) {
        final Integer n = count <= 0 ? null : count;
        final List<String> mods = modifiers.length == 0 ? null : Arrays.asList(modifiers);
        client.act("click", locator, () -> client.verbs().click(locator.copy(), button, n, mods, timeoutMillis()));
    }

    /**
     * A left click at a point inside the element, as fractions (0..1) of its width and height — for a
     * spot no locator names, such as the opener drawn inside a table cell. Fragile by nature: prefer a
     * named target wherever one resolves.
     */
    public void clickAt(double fx, double fy) {
        final Locator at = locator.copy();
        Map<String, Double> point = new LinkedHashMap<String, Double>();
        point.put("x", fx);
        point.put("y", fy);
        at.point = point;
        client.act("click", at, () -> client.verbs().click(at.copy(), null, null, null, timeoutMillis()));
    }

    /**
     * The clear X inside this text field: the component named {@code clear} strictly inside it. Absent while the X
     * does not show — assert {@code isAbsent()} for "no X".
     */
    public Target clearButton() { return byId(CLEAR_BUTTON); }

    /** Empties this text field with its clear X, as a user does. */
    public void clearValue() { clearButton().click(); }

    public void doubleClick() { click(null, 2); }
    public void open() { doubleClick(); }

    public void rightClick(String... menuPath) {
        if (menuPath.length == 0) { click("right", 0); return; }
        client.act("contextMenu " + Arrays.asList(menuPath), locator,
                () -> client.verbs().contextMenu(locator.copy(), Arrays.asList(menuPath), timeoutMillis()));
    }

    public void fill(String text) { type(text, true, true); }
    public void type(String text) { type(text, null, null); }

    public void type(String text, Boolean replace, Boolean commit) {
        client.act("type", locator, () -> client.verbs().type(locator.copy(), text, replace, commit, timeoutMillis()));
    }

    public void press(String keys) {
        client.act("press " + keys, locator, () -> client.verbs().press(keys, locator.copy(), timeoutMillis()));
    }

    public void check() { setChecked(true); }
    public void uncheck() { setChecked(false); }

    private void setChecked(boolean on) {
        client.act(on ? "check" : "uncheck", locator, () -> client.verbs().check(locator.copy(), on, timeoutMillis()));
    }

    public void select(String... items) {
        client.act("select " + Arrays.asList(items), locator,
                () -> client.verbs().select(locator.copy(), Arrays.asList(items), timeoutMillis()));
    }

    /** Scrolls to an end ("top"/"bottom"/…); the driver takes exactly one of to/pages/row. */
    public void scrollTo(String to) {
        client.act("scroll to " + to, locator, () -> client.verbs().scroll(locator.copy(), to, null, timeoutMillis()));
    }

    /** Scrolls by whole pages; the driver takes exactly one of to/pages/row. */
    public void scrollBy(int pages) {
        client.act("scroll by " + pages, locator, () -> client.verbs().scroll(locator.copy(), null, pages, timeoutMillis()));
    }

    /** Scrolls until this target (typically a row) is in view; the driver takes exactly one of to/pages/row. */
    public void scrollIntoView() {
        client.act("scroll into view", locator, () -> client.verbs().scroll(locator.copy(), null, null, timeoutMillis()));
    }

    public void dragTo(Target to) {
        client.act("drag to " + to.locator, locator,
                () -> client.verbs().drag(locator.copy(), to.locator.copy(), timeoutMillis()));
    }

    public void drop(String... files) {
        client.act("drop " + Arrays.asList(files), locator,
                () -> client.verbs().drop(locator.copy(), Arrays.asList(files), null, timeoutMillis()));
    }

    public void dropText(String text) {
        client.act("drop text", locator, () -> client.verbs().drop(locator.copy(), null, text, timeoutMillis()));
    }

    /** Drags this column's header border by {@code deltaPx} (negative narrows): a table(..).column(name) target. */
    public void resizeColumn(int deltaPx) {
        if (locator.column == null || locator.row != null) {
            throw new IllegalStateException("resizeColumn needs a column target: table(..).column(name), no row");
        }
        client.act("resizeColumn " + deltaPx, locator, () -> client.verbs().resizeColumn(locator.copy(), deltaPx, timeoutMillis()));
    }

    /** Resizes the window this target names: ui.main() or ui.window(title). */
    public void resizeTo(int width, int height) {
        if (hasLeaf || locator.scope != null || locator.window == null) {
            throw new IllegalStateException("resizeTo needs a window target: ui.main() or ui.window(title)");
        }
        client.act("resizeTo " + width + "x" + height, null,
                () -> client.verbs().resizeWindow(locator.window, width, height, timeoutMillis()));
    }

    /**
     * Closes the window this target names, as its title bar's close button does: ui.dialog(title) or
     * ui.window(title), never ui.main(). For a dialog with no button and no Escape to close it.
     */
    public void close() {
        if (hasLeaf || locator.scope != null || locator.window == null) {
            throw new IllegalStateException("close needs a window target: ui.dialog(title) or ui.window(title)");
        }
        client.act("close " + locator.window, null, () -> client.verbs().closeWindow(locator.window, timeoutMillis()));
    }

    /**
     * The pointer rests on the element (a posted move: the element sees MOUSE_ENTERED). Pair it with
     * {@link #unhover()} in a {@code finally}: a test that fails in between would leave the element rolled
     * over, and its tooltip armed.
     */
    public void hover() { client.act("hover", locator, () -> client.verbs().hover(locator.copy(), true, timeoutMillis())); }

    /** The pointer leaves the window the last hover was in. Cleanup: the element need not still be showing. */
    public void unhover() { client.act("unhover", locator, () -> client.verbs().hover(locator.copy(), false, timeoutMillis())); }

    public void saveScreenshot(String path) { client.write(path, screenshot()); }

    /** {@link #saveScreenshot(String)} at {@code scale} (1 to 4; 2 for images people will look at). */
    public void saveScreenshot(String path, double scale) { client.write(path, screenshot(scale)); }

    /** {@link #saveScreenshot(String, double)}, with the palette and open menus kept when {@code palette} is true. */
    public void saveScreenshot(String path, double scale, boolean palette) { client.write(path, screenshot(scale, palette)); }

    public byte[] screenshot() { return shoot(null, false); }

    /** A PNG of this element at {@code scale} image pixels per screen pixel, 1 to 4. */
    public byte[] screenshot(double scale) { return shoot(scale, false); }

    /**
     * {@link #screenshot(double)} that keeps the person's palette and the menus open over this element in the image,
     * for documentation pictures; without it they are left out.
     */
    public byte[] screenshot(double scale, boolean palette) { return shoot(scale, palette); }

    private byte[] shoot(Double scale, boolean palette) {
        try {
            return client.verbs().screenshot(locator.copy(), null, null, scale, palette).png;
        } catch (io.github.kormcrete.swingui.driver.DriverException e) {
            throw new UiActionFailed("screenshot", locator, e, Collections.<String>emptyList());
        }
    }

    // ---- reads (no retry; use an assertion to wait for a value)

    /** Where the element is, in its window's coordinates (no retry). */
    public Bounds bounds() {
        try {
            return Bounds.of(client.verbs().geometry(locator.copy()));
        } catch (io.github.kormcrete.swingui.driver.DriverException e) {
            throw new UiActionFailed("bounds", locator, e, client.candidatesFor(locator, e));
        }
    }

    public enum Presence { NONE, ONE, MANY }

    /** One read, no retry: is the target there now, and is it unique? */
    public Presence presence() {
        try {
            client.verbs().read(locator.copy(), 0);
            return Presence.ONE;
        } catch (io.github.kormcrete.swingui.driver.DriverException e) {
            if (e.reason() == io.github.kormcrete.swingui.driver.Reason.NOT_FOUND) return Presence.NONE;
            if (e.reason() == io.github.kormcrete.swingui.driver.Reason.AMBIGUOUS) return Presence.MANY;
            throw new UiActionFailed("presence", locator, e, Collections.<String>emptyList());
        }
    }

    /** One read, no retry: true when the target resolves now, once or several times. */
    public boolean exists() { return presence() != Presence.NONE; }

    /** One read, no retry. For capturing a value; to wait for one, use an assertion. */
    io.github.kormcrete.swingui.driver.model.Node read() {
        try {
            return client.verbs().read(locator.copy(), null);
        } catch (io.github.kormcrete.swingui.driver.DriverException e) {
            throw new UiActionFailed("read", locator, e, client.candidatesFor(locator, e));
        }
    }

    /**
     * The element's subtree, one line per node, as a failure's evidence prints it (compact, at most {@code maxNodes}):
     * for dumping a screen while a test is written, to take its locators from what is there rather than guess them.
     * Never a check.
     */
    public String tree(int maxNodes) {
        io.github.kormcrete.swingui.driver.model.Node root;
        try {
            root = !hasLeaf && locator.scope == null
                    ? client.verbs().snapshot(locator.window, null, null, null, maxNodes, 0).compact()
                    : client.verbs().snapshot(null, null, locator.copy(), null, maxNodes, 0).compact();
        } catch (io.github.kormcrete.swingui.driver.DriverException e) {
            throw new UiActionFailed("tree", locator, e, client.candidatesFor(locator, e));
        }
        List<String> lines = new ArrayList<String>();
        io.github.kormcrete.swingui.flow.FailureDiagnostics.render(root, -1, lines);
        return String.join("\n", lines);
    }

    /** The element's name as the driver reads it: a label's or button's text, a field's caption. */
    public String text() { return read().name; }

    /** The element's current value: a field's content, a combo's selection. */
    public String value() { return read().value; }

    /** The tooltip the element - a cell: its rendered cell - shows on hover, HTML stripped; null when none. */
    public String tooltip() { return read().tooltip; }

    public List<String> columns() {
        List<String> c = read().columns;
        return c == null ? Collections.<String>emptyList() : c;
    }

    public List<Map<String, String>> rows() {
        io.github.kormcrete.swingui.driver.model.Node n = read();
        List<Map<String, String>> out = new ArrayList<Map<String, String>>();
        if (n.rows == null || n.columns == null) return out;
        for (List<String> r : n.rows) {
            Map<String, String> m = new LinkedHashMap<String, String>();
            for (int i = 0; i < n.columns.size() && i < r.size(); i++) m.put(n.columns.get(i), r.get(i));
            out.add(m);
        }
        return out;
    }

    public int rowCount() {
        io.github.kormcrete.swingui.driver.model.Node n = read();
        if (n.rowCount != null) return n.rowCount;
        return n.rows == null ? 0 : n.rows.size();
    }

    /** How many elements match now (no retry). */
    public int count() { return readAll().size(); }

    /**
     * The values of every element this locator matches, in order — for a named label, button or tick box the text the
     * reader sees (its name being its component name), for a field its content.
     */
    public List<String> values() {
        List<String> out = new ArrayList<String>();
        for (io.github.kormcrete.swingui.driver.model.Node n : readAll()) out.add(n.value);
        return out;
    }

    /** Every match's name, in tree order (no retry): a popup's entries, a strip's cell labels. */
    public List<String> texts() {
        List<String> out = new ArrayList<String>();
        for (io.github.kormcrete.swingui.driver.model.Node n : readAll()) out.add(n.name);
        return out;
    }

    public List<Integer> selectedRows() { List<Integer> s = read().selected; return s == null ? Collections.<Integer>emptyList() : s; }
    public List<String> items() { List<String> s = read().items; return s == null ? Collections.<String>emptyList() : s; }
    public List<String> expandedItems() { List<String> s = read().expanded; return s == null ? Collections.<String>emptyList() : s; }

    private List<io.github.kormcrete.swingui.driver.model.Node> readAll() {
        if (locator.nth != null) throw new IllegalStateException("count()/texts() read every match; drop nth(" + locator.nth + ")");
        try {
            return client.verbs().readAll(locator.copy(), 0);
        } catch (io.github.kormcrete.swingui.driver.DriverException e) {
            throw new UiActionFailed("readAll", locator, e, Collections.<String>emptyList());
        }
    }

    public List<String> states() {
        List<String> s = read().state;
        return s == null ? Collections.<String>emptyList() : s;
    }

    @Override
    public String toString() { return locator.toString(); }
}
