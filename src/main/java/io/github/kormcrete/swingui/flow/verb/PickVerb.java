package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Doc("Clicks down a path of items in the showing popup (a dropdown button's menu, a context menu), opening "
        + "submenus on the way.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PickVerb extends VerbAction {
    @Required @Doc("Item labels from the popup down; a submenu's label comes before its items.")
    public List<String> path;

    @Override public String describe() { return "pick(" + path + ")"; }
    @Override public VerbOutcome run(Verbs verbs) { return verbs.pick(path, timeout); }
    @Override public Map<String, Locator> targets() { return Collections.emptyMap(); }
}
