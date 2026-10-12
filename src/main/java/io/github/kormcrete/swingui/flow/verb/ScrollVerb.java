package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@Doc("Scrolls a table, tree, list or panel: to an end with 'to', by whole screens with 'pages', or until a "
        + "row is in view by giving the target a 'row'. Exactly one of the three. It moves the viewport "
        + "directly rather than posting wheel input, so it is exact and it fires the same change a scroll bar "
        + "does - which is what continuous paging listens for. Read where it ended up from the element's "
        + "'scroll' block (x, y, width, height, maxX, maxY) or its atTop/atBottom states.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScrollVerb extends VerbAction {
    @Required @Doc("The scrollable element; add a 'row' to it to scroll that row into view.")
    public Locator target;
    @OneOf({"top", "bottom", "left", "right"})
    @Doc("Scroll all the way to this end.")
    public String to;
    @Doc("Scroll by this many screens; negative scrolls back up.")
    public Integer pages;

    @Override public String describe() {
        return "scroll(" + target + (to != null ? " to " + to : "") + (pages != null ? " " + pages + " pages" : "") + ")";
    }
    @Override public VerbOutcome run(Verbs verbs) { return verbs.scroll(target, to, pages, timeout); }
    @Override public Map<String, Locator> targets() { return targetMap(target); }
}
