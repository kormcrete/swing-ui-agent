package io.github.kormcrete.swingui.driver;

import io.github.kormcrete.swingui.driver.model.Recorder;
import io.github.kormcrete.swingui.driver.model.RowChoice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a verb did: its input path, a modal it left waiting, and the locators to record. For a plugin's domain step
 * ({@code do}): its filled sentence and the steps its body ran.
 */
public final class VerbOutcome {

    /** {@code posted}, {@code api}, or null when there was nothing to do. */
    public String via;
    public PendingDialog pending;
    /** Locator per target field name ({@code target}, {@code from}, {@code to}). */
    public final Map<String, Recorder.Recorded> recorded = new LinkedHashMap<String, Recorder.Recorded>();
    /**
     * The table row the step names in a place that is no locator (a cell check's {@code cell.row}), both ways; else
     * null. A locator's own row is in its {@link Recorder.Recorded#row}.
     */
    public RowChoice row;
    /** What a passing step produced, worth reporting - e.g. where a screenshot step wrote its PNG; else null. */
    public String note;
    /** Set when the action ran but the app did not settle afterwards within the wait; the step still passed. */
    public String warning;
    /** A plugin's domain step: its sentence with the values filled in, e.g. "Add Gel pen, black to the cart"; else null. */
    public String sentence;
    /** A plugin's domain step: the steps its body ran, in order; null for a verb. */
    public List<StepCall> calls;
}
