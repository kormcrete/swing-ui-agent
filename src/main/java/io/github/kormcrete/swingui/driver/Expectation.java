package io.github.kormcrete.swingui.driver;

import io.github.kormcrete.swingui.driver.model.RowSpec;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** What {@code expect} checks: exactly one of state, text, value, tooltip, rows, cell, selected, absent. */
public final class Expectation {

    static final List<String> OPS = Arrays.asList("eq", "gt", "gte", "lt", "lte");

    /** Each entry must be present; an entry starting with {@code !} must be absent. */
    public List<String> state;
    public String text;
    public String value;
    /** The tooltip shown on hover, HTML stripped; {@code ""} means none. */
    public String tooltip;
    public Count rows;
    public Cell cell;
    /** The rows that must be selected, and only those; order does not matter. */
    public List<Integer> selected;
    /** {@code true}: the element is not there (does not resolve), waiting for it to go (the Java API's {@code isAbsent()}). */
    public Boolean absent;

    public static final class Count {
        public String op;
        public Integer value;
    }

    public static final class Cell {
        public Map<String, Object> row;
        public String column;
        public String expected;
    }

    public void validate() {
        int given = (state != null ? 1 : 0) + (text != null ? 1 : 0) + (value != null ? 1 : 0)
                + (tooltip != null ? 1 : 0) + (rows != null ? 1 : 0) + (cell != null ? 1 : 0) + (selected != null ? 1 : 0)
                + (absent != null ? 1 : 0);
        if (given != 1) {
            throw bad("expect takes exactly one of state, text, value, tooltip, rows, cell, selected, absent (" + given + " given)");
        }
        if (selected != null && selected.contains(null)) throw bad("'selected' must not contain null");
        if (absent != null && !absent) throw bad("'absent' is true or left out; to say the element is there give 'shown': true");
        if (state != null && state.isEmpty()) throw bad("'state' must not be empty");
        if (rows != null) {
            if (!OPS.contains(rows.op)) throw bad("rows.op must be one of " + OPS + ", was '" + rows.op + "'");
            if (rows.value == null) throw bad("rows needs a 'value'");
        }
        if (cell != null) {
            if (cell.row == null || cell.column == null || cell.expected == null) {
                throw bad("'cell' needs row, column and expected");
            }
            RowSpec.of(cell.row);
        }
    }

    public static boolean compare(int actual, String op, int expected) {
        switch (op) {
            case "eq": return actual == expected;
            case "gt": return actual > expected;
            case "gte": return actual >= expected;
            case "lt": return actual < expected;
            case "lte": return actual <= expected;
            default: throw bad("unknown comparison '" + op + "'");
        }
    }

    private static DriverException bad(String message) {
        return new DriverException(Reason.BAD_INPUT, message);
    }
}
