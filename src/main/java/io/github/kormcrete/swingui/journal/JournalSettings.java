package io.github.kormcrete.swingui.journal;

import java.util.Optional;
import java.util.function.Function;

/**
 * The run journal's settings: JUnit configuration parameters of these names, which fall back to system properties of
 * the same names. A value that cannot be used gives one {@code [ui-warning]} line and its default.
 */
public final class JournalSettings {

    /** {@code off} (any case) writes no step journal; anything else, or nothing, writes one. */
    public static final String JOURNAL = "swingui.journal";
    /** How many run folders to keep; {@code 0} keeps all. */
    public static final String KEEP = "swingui.journal.keep";
    /** The run folder's name instead of the JVM's run id. */
    public static final String RUN_ID = "swingui.runId";
    /** The app version a run names. */
    public static final String APP_VERSION = "swingui.appVersion";

    private static final int DEFAULT_KEEP = 10;

    /** False only for {@code off}. */
    public final boolean journal;
    /** Run folders to keep, 0 for all; 10 when not given or not usable. */
    public final int keep;
    /** The run id given; null when not given or not a safe name. */
    public final String runId;
    /** The app version given; null when not given. */
    public final String appVersion;

    private JournalSettings(boolean journal, int keep, String runId, String appVersion) {
        this.journal = journal;
        this.keep = keep;
        this.runId = runId;
        this.appVersion = appVersion;
    }

    /** Journal on, keep 10, no run id, no app version. */
    public static JournalSettings defaults() {
        return new JournalSettings(true, DEFAULT_KEEP, null, null);
    }

    /** The settings {@code params} gives (e.g. {@code ExtensionContext::getConfigurationParameter}). */
    public static JournalSettings from(Function<String, Optional<String>> params) {
        String journal = given(params, JOURNAL);
        String keepText = given(params, KEEP);
        int keep = DEFAULT_KEEP;
        if (keepText != null) {
            try {
                keep = Integer.parseInt(keepText);
                if (keep < 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                keep = DEFAULT_KEEP;
                warn(KEEP + " '" + keepText + "' is not a whole number of runs of 0 or more; keeping " + DEFAULT_KEEP);
            }
        }
        String runId = given(params, RUN_ID);
        if (runId != null && !RunIds.isSafeName(runId)) {
            warn(RUN_ID + " '" + runId + "' may use only A-Z a-z 0-9 . _ - (not . or ..); using the run's own id");
            runId = null;
        }
        return new JournalSettings(!"off".equalsIgnoreCase(journal), keep, runId, given(params, APP_VERSION));
    }

    /** The trimmed value of {@code key}; null when not given or blank. */
    private static String given(Function<String, Optional<String>> params, String key) {
        Optional<String> v = params.apply(key);
        if (v == null || !v.isPresent()) return null;
        String s = v.get().trim();
        return s.isEmpty() ? null : s;
    }

    private static void warn(String text) {
        System.err.println("[ui-warning] " + text);
    }
}
