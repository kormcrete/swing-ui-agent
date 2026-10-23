package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

@Doc("Posts a mouse drag gesture from one element to another in the same window. Where the press starts the app's "
        + "own drag and drop - AWT's (a drag gesture recognizer) or Swing's (drag enabled, with a TransferHandler) - "
        + "that drag and drop runs inside the app instead of as a native drag: the app starts its drag, the drop target "
        + "where it ends gets the drop with the app's own data, and the message says what the drop target did. A refused "
        + "drag or a rejected drop fails the step (NOT_ACTIONABLE); a drag the driver cannot stand in for (one the app's "
        + "drag gesture listener starts on a DragSource it holds itself, or on a DragSource subclass of the app's) is a "
        + "CAPABILITY_GAP; a gesture the app starts no drag for passes as the mouse drag it was, and the message says "
        + "so. Elsewhere the drag is plain posted mouse input: a drag the app starts from its own mouse listener (no "
        + "drag gesture recognizer, drag not enabled) is not stood in for and may start a real native drag. Use drop to "
        + "deliver files or text from outside the app.")
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
