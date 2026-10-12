package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@Doc("Drags a table column's right header edge by delta pixels (negative narrows it).")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResizeColumnVerb extends VerbAction {
    @Required @Doc("A table target with a column and no row.")
    public Locator target;
    @Required @Doc("Pixels to widen (positive) or narrow (negative).")
    public Integer delta;

    @Override public String describe() { return "resizeColumn(" + target + " by " + delta + ")"; }
    @Override public VerbOutcome run(Verbs verbs) {
        if (delta == null) throw new DriverException(Reason.BAD_INPUT, "resizeColumn needs 'delta'");
        return verbs.resizeColumn(target, delta, timeout);
    }
    @Override public Map<String, Locator> targets() { return targetMap(target); }
}
