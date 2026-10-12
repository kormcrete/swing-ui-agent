package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@Doc("Resizes a window to width x height pixels.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResizeWindowVerb extends VerbAction {
    @Required @Doc("The window: main, dialog, popup, dialog:<title> or window:<title>.")
    public String window;
    @Required @Doc("New width in pixels.")
    public Integer width;
    @Required @Doc("New height in pixels.")
    public Integer height;

    @Override public String describe() { return "resizeWindow(" + window + " to " + width + "x" + height + ")"; }
    @Override public VerbOutcome run(Verbs verbs) {
        if (width == null) throw new DriverException(Reason.BAD_INPUT, "resizeWindow needs 'width'");
        if (height == null) throw new DriverException(Reason.BAD_INPUT, "resizeWindow needs 'height'");
        return verbs.resizeWindow(window, width, height, timeout);
    }
    @Override public Map<String, Locator> targets() { return targetMap(null); }
}
