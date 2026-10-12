package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.Map;

@Doc("Answers the showing modal dialog: clicks the button with this label, or with \"escape\" presses Escape "
        + "and requires the dialog to close.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnswerVerb extends VerbAction {
    @Required @Doc("The button's label as shown (\"Yes\", \"OK\", \"Assign\"), or \"escape\".")
    public String button;

    @Override public String describe() { return "answer(" + button + ")"; }
    @Override public VerbOutcome run(Verbs verbs) { return verbs.answer(button, timeout); }
    @Override public Map<String, Locator> targets() { return Collections.emptyMap(); }
}
