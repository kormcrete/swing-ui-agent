package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@Doc("Moves the pointer onto an element (inside true, the default) or off it again, so parts that show only on hover, "
        + "such as a row's inline buttons, show or hide.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HoverVerb extends VerbAction {
    @Required @Doc("The element to hover.")
    public Locator target;
    @Doc("false moves the pointer off the element.")
    public Boolean inside;

    @Override public String describe() { return (Boolean.FALSE.equals(inside) ? "unhover(" : "hover(") + target + ")"; }
    @Override public VerbOutcome run(Verbs verbs) { return verbs.hover(target, inside == null ? Boolean.TRUE : inside, timeout); }
    @Override public Map<String, Locator> targets() { return targetMap(target); }
}
