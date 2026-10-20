package io.github.kormcrete.swingui.flow;

import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.flow.verb.Doc;
import io.github.kormcrete.swingui.flow.verb.Required;
import io.github.kormcrete.swingui.flow.verb.VerbAction;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/** The generic contextMenu verb: right-click {@code target}, then pick {@code path}. */
@Doc("Right-clicks an element (with row/column/item/point) and clicks down 'path' in the context menu that opens. A table row, list item or tree row is selected first unless it is already part of the selection, as a right-click does in most apps.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContextMenuAction extends VerbAction {
    @Required @Doc("Labels from the context menu down to the item.")
    public List<String> path;
    @Required @Doc("The element to right-click.")
    public Locator target;

    @Override
    public VerbOutcome run(Verbs verbs) {
        return verbs.contextMenu(target, path, timeout);
    }

    @Override
    public Map<String, Locator> targets() {
        return targetMap(target);
    }

    @Override
    public String describe() {
        return "contextMenu(" + target + " > " + path + ")";
    }
}
