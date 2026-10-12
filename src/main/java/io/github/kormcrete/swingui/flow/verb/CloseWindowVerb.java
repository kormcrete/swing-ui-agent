package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@Doc("Closes a window the way its title-bar close button would.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CloseWindowVerb extends VerbAction {
    @Required @Doc("The window: main, dialog, popup, dialog:<title> or window:<title>.")
    public String window;

    @Override public String describe() { return "closeWindow(" + window + ")"; }
    @Override public VerbOutcome run(Verbs verbs) { return verbs.closeWindow(window, timeout); }
    @Override public Map<String, Locator> targets() { return targetMap(null); }
}
