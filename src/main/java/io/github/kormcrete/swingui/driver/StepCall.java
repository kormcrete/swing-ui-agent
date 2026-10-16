package io.github.kormcrete.swingui.driver;

import java.util.List;

/**
 * One step a plugin's domain step ran while it ran ({@link VerbOutcome#calls}): a verb or an app step, as do_action
 * takes it, and how it went.
 */
public final class StepCall {

    /** The step as do_action takes it, as JSON text, e.g. {@code {"action":"click","target":{...}}}. */
    public final String json;
    /** Its short label, e.g. {@code click(button 'Add to cart')}. */
    public final String action;
    public boolean passed;
    /** The failure's message; null when passed. */
    public String message;
    /** The failure's reason; null when passed or unclassified. */
    public Reason reason;
    /** The failure's details (candidates, ...); null when none. */
    public List<String> details;
    public long ms;
    /** What the verb did; null for an app step and for a failure. */
    public VerbOutcome outcome;

    public StepCall(String json, String action) {
        this.json = json;
        this.action = action;
    }
}
