package io.github.kormcrete.swingui.driver;

import io.github.kormcrete.swingui.driver.model.Recorder;

import java.util.LinkedHashMap;
import java.util.Map;

/** What a verb did: its input path, a modal it left waiting, and the locators to record. */
public final class VerbOutcome {

    /** {@code posted}, {@code api}, or null when there was nothing to do. */
    public String via;
    public PendingDialog pending;
    /** Locator per target field name ({@code target}, {@code from}, {@code to}). */
    public final Map<String, Recorder.Recorded> recorded = new LinkedHashMap<String, Recorder.Recorded>();
    /** What a passing step produced, worth reporting - e.g. where a screenshot step wrote its PNG; else null. */
    public String note;
    /** Set when the action ran but the app did not settle afterwards within the wait; the step still passed. */
    public String warning;
}
