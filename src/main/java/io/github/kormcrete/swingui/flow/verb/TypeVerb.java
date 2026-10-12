package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@Doc("Types text into a field or a table cell as a keyboard would. A cell target (row and column) is clicked, "
        + "then edited; commit presses Enter after typing.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TypeVerb extends VerbAction {
    @Required @Doc("The field, or a table with row and column.")
    public Locator target;
    @Required @Doc("The text to type.")
    public String text;
    @Doc("Replace what is there (select all first); default true.")
    public Boolean replace;
    @Doc("Press Enter after typing; default false.")
    public Boolean commit;

    @Override public String describe() { return "type(" + target + " '" + text + "'" + (Boolean.TRUE.equals(commit) ? " +Enter" : "") + ")"; }
    @Override public VerbOutcome run(Verbs verbs) { return verbs.type(target, text, replace, commit, timeout); }
    @Override public Map<String, Locator> targets() { return targetMap(target); }
}
