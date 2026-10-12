package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@Doc("Clicks an element as a mouse would: the click is posted into the client's event queue at the element "
        + "(or its row/column/item/point), where AWT decides who receives it. A disabled, hidden, covered or "
        + "modal-blocked target is refused.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClickVerb extends VerbAction {
    @Required @Doc("The element to click.")
    public Locator target;
    @OneOf({"left", "right"}) @Doc("Mouse button; default left.")
    public String button;
    @Doc("1, 2 or 3 clicks; default 1.")
    public Integer count;
    @Doc("Keys held while clicking: Mod (Cmd on macOS, Ctrl elsewhere), Shift, Ctrl, Alt, Meta. "
            + "Mod toggles a row into the selection; Shift extends it.")
    public List<String> modifiers;

    @Override public String describe() {
        return "click(" + target + (button == null ? "" : " " + button) + (count == null ? "" : " x" + count)
                + (modifiers == null ? "" : " " + modifiers) + ")";
    }
    @Override public VerbOutcome run(Verbs verbs) { return verbs.click(target, button, count, modifiers, timeout); }
    @Override public Map<String, Locator> targets() { return targetMap(target); }
}
