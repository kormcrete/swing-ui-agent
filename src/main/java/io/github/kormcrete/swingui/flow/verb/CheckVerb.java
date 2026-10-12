package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@Doc("Sets a check box, toggle or Boolean table cell to the given state by clicking it; does nothing when it "
        + "is already in that state.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CheckVerb extends VerbAction {
    @Required @Doc("The check box, or a table with row and column of a Boolean cell.")
    public Locator target;
    @Required @Doc("The wanted state.")
    public Boolean checked;

    @Override public String describe() { return "check(" + target + " = " + checked + ")"; }
    @Override public VerbOutcome run(Verbs verbs) { return verbs.check(target, checked, timeout); }
    @Override public Map<String, Locator> targets() { return targetMap(target); }
}
