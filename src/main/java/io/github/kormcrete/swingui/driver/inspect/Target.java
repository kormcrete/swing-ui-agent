package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.model.Locator;

import java.awt.Component;
import java.util.Map;

/**
 * One addressable element as the inspector shows it: the locator the recorder writes for it, how it reads to the
 * agent ({@link #element}) and to the person ({@link #shown}), and where to draw its box. A table cell has a row and a
 * column, a whole column only a column (view indices, -1 none).
 */
public final class Target {
    /**
     * The longest label the person reads, in characters: a box label or the Check menu's title stays one short line,
     * so the menu is never wider than its checks.
     */
    static final int SHOWN_MAX = 40;

    public final Locator locator;
    public final boolean fragile;
    /** The locator as a line, for the agent: role, name, row, column, id or scope, nth. */
    public final String element;
    /** What the person sees there ({@link #shown(String, String, Locator)}), for the palette's boxes and menus. */
    public final String shown;
    public final String role;
    final Component component;
    final Integer tab;
    final int row;
    final int column;
    /** The element as it was seen when this target was made ({@code Recorder.seen}); null when not known. */
    final Locator seen;

    Target(Locator locator, boolean fragile, String element, String shown, String role, Component component,
           Integer tab, int row, int column) {
        this(locator, fragile, element, shown, role, component, tab, row, column, null);
    }

    Target(Locator locator, boolean fragile, String element, String shown, String role, Component component,
           Integer tab, int row, int column, Locator seen) {
        this.seen = seen;
        this.locator = locator;
        this.fragile = fragile;
        this.element = element;
        this.shown = shown;
        this.role = role;
        this.component = component;
        this.tab = tab;
        this.row = row;
        this.column = column;
    }

    boolean cell() {
        return row >= 0 && column >= 0;
    }

    boolean wholeColumn() {
        return row < 0 && column >= 0;
    }

    boolean same(Target o) {
        return o != null && o.component == component && o.row == row && o.column == column
                && (tab == null ? o.tab == null : tab.equals(o.tab));
    }

    /**
     * What the person sees of an element with {@code role} and the visible {@code name} (null: none), aimed at by
     * {@code sub}'s row, column or item: "Gel pen, black · Product" for a cell (its row's key values, then the column),
     * "Qty column", an item's last part, else the name, else what kind of element it is ("table", "text field"). Never how a locator finds it: no id,
     * scope or nth, which mean nothing on screen. At most {@link #SHOWN_MAX} characters, cut with an ellipsis; a cell
     * keeps its column whole.
     */
    static String shown(String role, String name, Locator sub) {
        String part = part(sub);
        if (sub.row != null) {
            String tail = sub.column != null ? " · " + sub.column : "";
            return cut(rowShown(sub.row), Math.max(12, SHOWN_MAX - tail.length())) + tail;
        }
        if (sub.column != null) return cut(sub.column, SHOWN_MAX - " column".length()) + " column";
        if (part != null) return cut(part, SHOWN_MAX);
        return cut(name != null && !name.trim().isEmpty() ? name.trim() : kind(role), SHOWN_MAX);
    }

    /** A role in the words the person uses for it: "text" is a locator's word, "text field" theirs. */
    private static String kind(String role) {
        if (role == null) return "element";
        switch (role) {
            case "text": return "text field";
            case "password": return "password field";
            case "checkbox": return "check box";
            case "radio": return "radio button";
            case "toggle": return "toggle button";
            case "combo": return "drop-down";
            case "menuitem": return "menu item";
            case "menubar": return "menu bar";
            case "clickable": return "element";
            default: return role;
        }
    }

    /**
     * The part of an element {@code l}'s sub-target names, as the person reads it: a table column, else the last step
     * of an item path; null for none. The replay bar names a step's element with it too.
     */
    static String part(Locator l) {
        if (l == null) return null;
        if (l.column != null) return l.column;
        return l.item != null && !l.item.isEmpty() ? l.item.get(l.item.size() - 1) : null;
    }

    /** A row as the person reads it: its key values ("Gel pen, black"), or its place counted from 1 ("row 3"). */
    private static String rowShown(Map<String, Object> row) {
        StringBuilder s = new StringBuilder();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (s.length() > 0) s.append(", ");
            Object v = e.getValue();
            s.append("index".equals(e.getKey()) && v instanceof Number ? "row " + (((Number) v).intValue() + 1) : v);
        }
        return s.toString();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1).trim() + "…";
    }
}
