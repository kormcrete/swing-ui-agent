package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@Doc("Presses a key chord, e.g. \"Enter\", \"Escape\", \"Mod+O\", \"Shift+Tab\". With a target, focuses it first; "
        + "without one the keys go to the showing modal dialog, else the focused window.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PressVerb extends VerbAction {
    @Required @Doc("The key chord.")
    public String keys;
    @Doc("The element to focus first.")
    public Locator target;

    @Override public String describe() { return "press(" + keys + (target == null ? "" : " on " + target) + ")"; }
    @Override public VerbOutcome run(Verbs verbs) { return verbs.press(keys, target, timeout); }
    @Override public Map<String, Locator> targets() { return targetMap(target); }
}
