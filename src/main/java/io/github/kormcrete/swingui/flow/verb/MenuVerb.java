package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Doc("Invokes a main-window menu-bar item by its label path. An in-window menu bar is clicked; the native "
        + "macOS screen menu bar has no input path, so its item is invoked directly (recorded as via:api).")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MenuVerb extends VerbAction {
    @Required @Doc("Menu, submenus and item, e.g. [\"File\", \"Export\", \"PDF\"].")
    public List<String> path;

    @Override public String describe() { return "menu(" + path + ")"; }
    @Override public VerbOutcome run(Verbs verbs) { return verbs.menu(path, timeout); }
    @Override public Map<String, Locator> targets() { return Collections.emptyMap(); }
}
