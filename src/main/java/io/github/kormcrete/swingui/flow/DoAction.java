package io.github.kormcrete.swingui.flow;

import java.util.Map;

/**
 * The {@code do} step: one of a plugin's domain steps, by id, with its values as concrete text, e.g.
 * {@code {"action":"do","step":"addToCart","params":{"product":"Gel pen, black"}}}. It runs through
 * {@link io.github.kormcrete.swingui.spi.StepHost#runStep}; {@code <plugin>:<id>} names the plugin when two offer the id,
 * and a recording always names it so.
 */
public class DoAction extends Action {
    /** The domain step's id. */
    public String step;
    /** Its values by param name; an optional param left out holds its default. */
    public Map<String, String> params;
    /**
     * True for a domain check (a step of kind check): like an expect, a check of the step before it
     * ({@link StepNumbers#isCheck}). Set when the step is recorded; a recorded test file holds a do check in its step's
     * {@code checks} and never writes this.
     */
    public Boolean check;

    @Override public String describe() { return "do(" + step + ")"; }
}
