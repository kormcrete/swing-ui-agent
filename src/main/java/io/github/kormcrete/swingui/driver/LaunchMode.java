package io.github.kormcrete.swingui.driver;

import java.util.Locale;

/**
 * How the app under test launches. {@link #apply} touches no AWT class, so a launcher can
 * call it as its first statement: {@code apple.awt.UIElement} only works when set before the toolkit starts.
 */
public enum LaunchMode {
    NORMAL, BACKGROUND;

    public static final String PROPERTY = "swingui.mode";
    public static final String UI_ELEMENT = "apple.awt.UIElement";

    public static LaunchMode apply(String value) {
        LaunchMode mode = parse(value);
        if (mode == BACKGROUND) System.setProperty(UI_ELEMENT, "true");
        return mode;
    }

    public static LaunchMode parse(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || v.equals("normal")) return NORMAL;
        if (v.equals("background")) return BACKGROUND;
        throw new DriverException(Reason.BAD_INPUT, PROPERTY + " must be 'normal' or 'background', was '" + value + "'");
    }
}
