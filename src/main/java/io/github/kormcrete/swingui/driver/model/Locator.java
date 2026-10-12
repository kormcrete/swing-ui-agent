package io.github.kormcrete.swingui.driver.model;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A serialisable address of one UI element: a snapshot {@code ref}, or a locator. Plain
 * public fields so the MCP, flow and JUnit layers can read and write it as JSON; the driver never serialises.
 * {@code row}, {@code column}, {@code item} and {@code point} are sub-targets inside the element.
 */
public final class Locator {

    public String ref;
    public String window;
    public List<String> scope;
    public String role;
    public String name;
    public String match;
    public String id;
    public Map<String, Object> row;
    public String column;
    public List<String> item;
    public Map<String, Double> point;
    public Integer nth;

    public static Locator ofRef(String ref) {
        Locator l = new Locator();
        l.ref = ref;
        return l;
    }

    public static Locator ofId(String id) {
        Locator l = new Locator();
        l.id = id;
        return l;
    }

    public static Locator of(String role, String name) {
        Locator l = new Locator();
        l.role = role;
        l.name = name;
        return l;
    }

    public Locator copy() {
        Locator l = new Locator();
        l.ref = ref;
        l.window = window;
        l.scope = scope == null ? null : new ArrayList<String>(scope);
        l.role = role;
        l.name = name;
        l.match = match;
        l.id = id;
        l.row = row == null ? null : new LinkedHashMap<String, Object>(row);
        l.column = column;
        l.item = item == null ? null : new ArrayList<String>(item);
        l.point = point == null ? null : new LinkedHashMap<String, Double>(point);
        l.nth = nth;
        return l;
    }

    /** The same address without its sub-target. */
    public Locator element() {
        Locator l = copy();
        l.row = null;
        l.column = null;
        l.item = null;
        l.point = null;
        return l;
    }

    public boolean containsMatch() {
        return "contains".equals(match);
    }

    public RowSpec rowSpec() {
        return row == null ? null : RowSpec.of(row);
    }

    /** Throws BAD_INPUT naming the first malformed field. */
    public void validate() {
        if (ref != null) {
            if (!ref.matches("e\\d+")) throw bad("ref must look like e12, was '" + ref + "'");
            if (window != null || scope != null || role != null || name != null || id != null || nth != null) {
                throw bad("a ref target takes no window, scope, role, name, id or nth");
            }
        } else if (role == null && name == null && id == null) {
            throw bad("a target needs a ref, an id, a role or a name");
        }
        if (match != null && !"exact".equals(match) && !"contains".equals(match)) {
            throw bad("match must be 'exact' or 'contains', was '" + match + "'");
        }
        if (role != null && !Roles.ALL.contains(role)) throw bad("unknown role '" + role + "'; roles are " + Roles.ALL);
        if (window != null) WindowRef.parse(window);
        if (scope != null) {
            for (String step : scope) ScopeStep.parse(step);
        }
        if (row != null) RowSpec.of(row);
        if (item != null && item.isEmpty()) throw bad("'item' must not be empty");
        if (point != null) {
            Double x = point.get("x");
            Double y = point.get("y");
            if (x == null || y == null || x < 0 || x > 1 || y < 0 || y > 1) {
                throw bad("'point' needs x and y between 0 and 1");
            }
        }
        if (nth != null && nth < 0) throw bad("'nth' must not be negative");
    }

    static DriverException bad(String message) {
        return new DriverException(Reason.BAD_INPUT, message);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Locator)) return false;
        Locator l = (Locator) o;
        return Objects.equals(ref, l.ref) && Objects.equals(window, l.window) && Objects.equals(scope, l.scope)
                && Objects.equals(role, l.role) && Objects.equals(name, l.name) && Objects.equals(match, l.match)
                && Objects.equals(id, l.id) && Objects.equals(row, l.row) && Objects.equals(column, l.column)
                && Objects.equals(item, l.item) && Objects.equals(point, l.point) && Objects.equals(nth, l.nth);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ref, window, scope, role, name, match, id, row, column, item, point, nth);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (ref != null) sb.append(ref);
        if (id != null) sb.append("#").append(id);
        if (role != null) sb.append(sb.length() > 0 ? " " : "").append(role);
        if (name != null) sb.append(" '").append(name).append("'").append(containsMatch() ? "~" : "");
        if (scope != null) sb.append(" ").append(scope);
        if (window != null) sb.append(" in ").append(window);
        if (row != null) sb.append(" row=").append(row);
        if (column != null) sb.append(" column=").append(column);
        if (item != null) sb.append(" item=").append(item);
        if (point != null) sb.append(" point=").append(point);
        if (nth != null) sb.append(" nth=").append(nth);
        return sb.toString().trim();
    }
}
