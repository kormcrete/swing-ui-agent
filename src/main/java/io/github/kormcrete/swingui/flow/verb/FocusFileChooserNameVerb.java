package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@Doc("Moves the keyboard focus into the showing file chooser's file-name field.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FocusFileChooserNameVerb extends VerbAction {
    @Override public String describe() { return "focusFileChooserName()"; }
    @Override public VerbOutcome run(Verbs verbs) { return verbs.focusFileChooserName(timeout); }
    @Override public Map<String, Locator> targets() { return targetMap(null); }
}
