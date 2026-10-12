package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@Doc("Drops files or text onto an element's drop target without the OS (recorded as via:api).")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DropVerb extends VerbAction {
    @Required @Doc("The element to drop onto.")
    public Locator target;
    @Doc("Absolute paths of existing files.")
    public List<String> files;
    @Doc("Text to drop instead of files.")
    public String text;

    @Override public String describe() { return "drop(" + (files != null ? files : "'" + text + "'") + " on " + target + ")"; }
    @Override public VerbOutcome run(Verbs verbs) { return verbs.drop(target, files, text, timeout); }
    @Override public Map<String, Locator> targets() { return targetMap(target); }
}
