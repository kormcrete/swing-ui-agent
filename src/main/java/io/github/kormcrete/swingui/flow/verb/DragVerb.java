package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

@Doc("Posts a mouse drag gesture from one element to another in the same window. When the app does AWT drag and "
        + "drop there (a drag gesture recognizer where the drag starts, a drop target where it ends), the drag and drop "
        + "runs in the app itself: the app starts its drag, its drop target gets the drop with the app's own data, and "
        + "the message says what the drop target did; a refused or rejected drop fails the step (NOT_ACTIONABLE), a "
        + "drag the driver cannot stand in for is a CAPABILITY_GAP. A native drag is never started; use drop to "
        + "deliver files or text.")
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
