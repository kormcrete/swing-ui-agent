package io.github.kormcrete.swingui.spi;

/**
 * An app's vocabulary, loaded into the app's JVM by the agent once the descriptor's {@code app-class} loads.
 * The plugin jar or folder holds {@code META-INF/swing-ui-agent/plugin.properties} ({@code app-class}, optional
 * {@code name} and {@code build-match}) and names its implementation in
 * {@code META-INF/services/io.github.kormcrete.swingui.spi.UiPlugin}; it is loaded in a child of the class loader
 * that defined {@code app-class}. {@link #start} runs once, on a daemon thread whose context class loader is the
 * plugin's own; it may block until the app is ready. A throw marks the plugin failed; the generic tools keep working.
 */
public interface UiPlugin {
    PluginParts start(PluginContext context) throws Exception;
}
