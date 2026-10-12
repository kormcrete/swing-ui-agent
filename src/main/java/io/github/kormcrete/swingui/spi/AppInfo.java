package io.github.kormcrete.swingui.spi;

/** The app as its plugin names it; {@code version} is null when the app does not know its own. */
public final class AppInfo {
    public final String name;
    public final String version;

    public AppInfo(String name, String version) {
        this.name = name;
        this.version = version;
    }
}
