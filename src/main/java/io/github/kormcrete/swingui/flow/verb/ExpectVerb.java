package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Expectation;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Doc("Checks an element without acting, retrying until it holds or the timeout (default 10000 ms) passes. "
        + "Give exactly one of state, text, value, tooltip, rows, cell, selected, shown, absent.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExpectVerb extends VerbAction {
    @Required @Doc("The element to check.")
    public Locator target;
    @Doc("States that must hold; \"!x\" must not. The tokens: disabled, focused, selected, checked, readonly, "
            + "editable, rollover, editing (a table with an open cell editor), atTop and atBottom (a scrollable "
            + "element's viewport), expanded and leaf (a tree row), and any state the app reports for a section (e.g. hidden, incomplete). On a table target with a 'row' and no 'column' this is that "
            + "ROW's selection (and focused); on a tree target with an 'item' it is that ROW's selected, expanded, leaf.")
    public List<String> state;
    @Doc("The element's text as shown (a cell with row and column).")
    public String text;
    @Doc("A field's or combo's value, a toggle's true/false.")
    public String value;
    @Doc("The tooltip shown on hover, HTML stripped (a cell's with row and column); \"\" for none.")
    public String tooltip;
    @Doc("Row count of a table, list, tree or combo: {op: eq|gt|gte|lt|lte, value}.")
    public Expectation.Count rows;
    @Doc("One table cell: {row, column, expected}.")
    public Expectation.Cell cell;
    @Doc("The rows of a table, list or tree that must be selected, and only those, e.g. [0, 1]; "
            + "order does not matter. Use [] for nothing selected.")
    public List<Integer> selected;
    @Doc("true: the element is there and shown (not hidden); the same as state [\"!hidden\"] and the Java isVisible().")
    public Boolean shown;
    @Doc("true: the element is not there, e.g. a closed tab or dialog; waits until it is gone. Needs a locator, not a ref.")
    public Boolean absent;

    @Override public String describe() {
        Object what = state != null ? "state " + state : text != null ? "text '" + text + "'"
                : value != null ? "value '" + value + "'" : tooltip != null ? "tooltip '" + tooltip + "'" : rows != null ? "rows " + rows.op + " " + rows.value
                : cell != null ? "cell " + cell.column + "='" + cell.expected + "'"
                : selected != null ? "selected " + selected
                : shown != null ? "shown" : absent != null ? "absent" : "?";
        return "expect(" + target + " " + what + ")";
    }
    @Override public VerbOutcome run(Verbs verbs) {
        Expectation e = new Expectation();
        e.state = state;
        e.text = text;
        e.value = value;
        e.tooltip = tooltip;
        e.rows = rows;
        e.cell = cell;
        e.selected = selected;
        e.absent = absent;
        int given = (state != null ? 1 : 0) + (text != null ? 1 : 0) + (value != null ? 1 : 0) + (tooltip != null ? 1 : 0)
                + (rows != null ? 1 : 0) + (cell != null ? 1 : 0) + (selected != null ? 1 : 0) + (shown != null ? 1 : 0)
                + (absent != null ? 1 : 0);
        if (shown != null && given != 1) {
            throw new DriverException(Reason.BAD_INPUT, "expect takes exactly one of state, text, value, tooltip, rows, cell, "
                    + "selected, shown, absent (" + given + " given)");
        }
        if (shown != null) {
            if (!shown) throw new DriverException(Reason.BAD_INPUT, "'shown' is true or left out; to say it is not there give 'absent': true");
            e.state = Collections.singletonList("!hidden");
        }
        return verbs.expect(target, e, timeout);
    }
    @Override public Map<String, Locator> targets() { return targetMap(target); }
}
