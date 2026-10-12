package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.Map;

@Doc("Puts text on the system clipboard, as a copy in another application would - e.g. a block copied from "
        + "Excel: a tab between cells, a line break between rows, each value as displayed. Paste it into the app "
        + "with press \"Mod+V\" on the focused element (click the anchor cell first). It REPLACES what the user "
        + "had on the clipboard. Read it back with read_clipboard.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SetClipboardVerb extends VerbAction {
    @Required @Doc("The text. For a spreadsheet block: \\t between cells, \\n between rows.")
    public String text;

    @Override public String describe() {
        String shown = text == null ? "null" : text.replace("\t", "\\t").replace("\n", "\\n");
        return "setClipboard(" + (shown.length() > 40 ? shown.substring(0, 40) + "..." : shown) + ")";
    }
    @Override public VerbOutcome run(Verbs verbs) { return verbs.setClipboard(text, timeout); }
    @Override public Map<String, Locator> targets() { return Collections.emptyMap(); }
}
