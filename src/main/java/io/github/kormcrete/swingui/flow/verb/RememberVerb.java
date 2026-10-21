package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@Doc("Remembers the text of an element under a name, for later steps to use as ${name}; retries until the element has "
        + "text or the timeout (default 10000 ms) passes. Fails when the element is missing or empty. A check-like entry: "
        + "it changes nothing in the app.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RememberVerb extends VerbAction {
    @Required @Doc("The element to read, as expect reads its text (a cell with row and column).")
    public Locator target;
    @Required @Doc("The name to remember it under: a letter, then letters, digits or _ (up to 40); not 'run'; unique in "
            + "the recorded test.")
    public String as;
    @Doc("true: remember the selected row's cell in the target's column (table), the selected item (list, tree) or the "
            + "selected tab's title (tabbed pane), instead of the target's own text.")
    public Boolean selected;

    @Override public String describe() { return "remember(" + target + " as " + as + (Boolean.TRUE.equals(selected) ? ", selected" : "") + ")"; }

    @Override public VerbOutcome run(Verbs verbs) {
        if (target == null) throw new DriverException(Reason.BAD_INPUT, "remember needs a 'target'");
        if (as == null || as.isEmpty()) throw new DriverException(Reason.BAD_INPUT, "remember needs 'as', the name to keep it under");
        VerbOutcome out = new VerbOutcome();
        out.note = verbs.remember(target, Boolean.TRUE.equals(selected), timeout);
        return out;
    }

    @Override public Map<String, Locator> targets() { return targetMap(target); }
}
