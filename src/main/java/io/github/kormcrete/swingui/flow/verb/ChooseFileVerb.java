package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.Map;

@Doc("Answers the showing file chooser: types the path and presses Enter (falling back to the chooser's own "
        + "approve, recorded as via:api), or with approve:false presses Escape.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChooseFileVerb extends VerbAction {
    @Doc("The file to choose; required unless approve is false.")
    public String path;
    @Doc("Approve (default true) or cancel.")
    public Boolean approve;

    @Override public String describe() { return "chooseFile(" + (Boolean.FALSE.equals(approve) ? "cancel" : path) + ")"; }
    @Override public VerbOutcome run(Verbs verbs) { return verbs.chooseFile(path, approve, timeout); }
    @Override public Map<String, Locator> targets() { return Collections.emptyMap(); }
}
