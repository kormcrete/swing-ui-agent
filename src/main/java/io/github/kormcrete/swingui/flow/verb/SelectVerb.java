package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Doc("Selects by clicking: a combo box item (opens its popup, clicks the item), list items (the first plain, "
        + "the rest with Mod), or a tab of a tab strip.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SelectVerb extends VerbAction {
    @Required @Doc("The combo box, list or tab strip.")
    public Locator target;
    @Doc("The item's text as shown.")
    public String item;
    @Doc("Several list items, as shown.")
    public List<String> items;

    @Override public String describe() { return "select(" + target + " " + (item != null ? item : items) + ")"; }
    @Override public VerbOutcome run(Verbs verbs) {
        if ((item == null) == (items == null)) {
            throw new DriverException(Reason.BAD_INPUT, "select takes 'item' or 'items', exactly one");
        }
        return verbs.select(target, item != null ? Collections.singletonList(item) : items, timeout);
    }
    @Override public Map<String, Locator> targets() { return targetMap(target); }
}
