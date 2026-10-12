package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

@Doc("Posts a mouse drag gesture from one element to another in the same window. A native drag is never "
        + "started; use drop to deliver data.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DragVerb extends VerbAction {
    @Required @Doc("Where the drag starts.")
    public Locator from;
    @Required @Doc("Where it ends.")
    public Locator to;

    @Override public String describe() { return "drag(" + from + " -> " + to + ")"; }
    @Override public VerbOutcome run(Verbs verbs) { return verbs.drag(from, to, timeout); }
    @Override public Map<String, Locator> targets() {
        Map<String, Locator> out = new LinkedHashMap<String, Locator>();
        out.put("from", from);
        out.put("to", to);
        return out;
    }
}
