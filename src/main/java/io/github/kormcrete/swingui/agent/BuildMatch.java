package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.spi.BuildStamp;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A plugin runs only next to the build it was built with, so a hook from another build can never fail
 * half-way. Opt-in through {@code build-match=<manifest attribute>} in plugin.properties.
 */
final class BuildMatch {

    private BuildMatch() {}

    /** Null when the plugin may start, else why not. Both unstamped passes: class folders, as an IDE runs them. */
    static String check(String attribute, Path plugin, Path app) {
        if (attribute == null) return null;
        String p;
        try {
            p = BuildStamp.read(plugin, attribute);
        } catch (IOException e) {
            return "cannot read " + attribute + " of the plugin at " + plugin + ": " + e;
        }
        String a;
        try {
            a = BuildStamp.read(app, attribute);
        } catch (IOException e) {
            return "cannot read " + attribute + " of the app at " + app + ": " + e;
        }
        if (p == null && a == null) return null;
        if (p != null && p.equals(a)) return null;
        return "the plugin and the app come from different builds: plugin " + attribute + "=" + shown(p) + " (" + plugin
                + "), app " + attribute + "=" + shown(a) + " (" + app + ") - build both in one build";
    }

    private static String shown(String stamp) {
        return stamp == null ? "none" : stamp;
    }
}
