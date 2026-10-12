package io.github.kormcrete.swingui.driver.platform;

import java.util.Locale;

/** The desktop platforms the driver runs on, from {@code os.name}. */
public enum Platform {
    MAC("macOS"), WINDOWS("Windows"), LINUX("Linux");

    public final String label;

    Platform(String label) {
        this.label = label;
    }

    /** Null for any other OS. */
    public static Platform of(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.startsWith("mac")) return MAC;
        if (os.startsWith("windows")) return WINDOWS;
        if (os.startsWith("linux")) return LINUX;
        return null;
    }

    public static Platform current() {
        return of(System.getProperty("os.name", ""));
    }
}
