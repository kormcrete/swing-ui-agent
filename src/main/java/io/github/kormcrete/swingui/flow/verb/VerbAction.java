package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.flow.Action;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

/** A generic driver verb as a step. Replay and live authoring run it through the same executor. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class VerbAction extends Action {

    @Doc("Milliseconds this step may wait for its target to become actionable and for the app to settle.")
    public Long timeout;

    /** Written by the recorder when the step needed a direct Swing call. */
    public String via;

    /** Written by the recorder when the target needed nth to be unique, or is a point (it breaks when the layout moves). */
    public Boolean fragile;

    public abstract VerbOutcome run(Verbs verbs);

    /** The locator fields by JSON name, so recording can replace a ref with a locator. */
    public abstract Map<String, Locator> targets();

    public static Map<String, Locator> targetMap(Locator target) {
        Map<String, Locator> out = new LinkedHashMap<String, Locator>();
        if (target != null) out.put("target", target);
        return out;
    }
}
