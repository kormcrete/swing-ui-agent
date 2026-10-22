package io.github.kormcrete.swingui.driver;

import java.util.Locale;

/** A wait as people read it, for messages that say how long something waited. */
public final class Seconds {

    private Seconds() {
    }

    /** Whole seconds from one second on ("10 s"), one decimal below ("0.3 s"). */
    public static String of(long millis) {
        if (millis >= 1_000L) return Math.round(millis / 1_000.0) + " s";
        return String.format(Locale.ROOT, "%.1f s", millis / 1_000.0);
    }
}
