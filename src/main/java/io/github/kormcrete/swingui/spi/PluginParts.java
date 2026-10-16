package io.github.kormcrete.swingui.spi;

/**
 * What a started plugin gives the agent; any part may be null. The first ready plugin with a {@code host} is the
 * app plugin: its {@code host} serves the app steps, its {@code inventory} describe_ui, its {@code app} session
 * reset and login, its {@code export} the default file locations. Other plugins' parts of those kinds are unused.
 * {@code hooks}, of any plugin, is what {@code plugin_call} invokes: it implements the interface {@code hooksType},
 * whose method names must be unique (an overloaded name is refused); arguments and result go through Jackson.
 * {@code vocabulary}, of any plugin, adds its domain steps to the agent's vocabulary (a plugin may supply only that).
 * The constructor throws IllegalArgumentException unless {@code hooksType} and {@code hooks} are both null or
 * both set, and {@code hooksType} is an interface.
 */
public final class PluginParts {
    public final StepHost host;
    public final UiInventory inventory;
    public final AppHooks app;
    public final Class<?> hooksType;
    public final Object hooks;
    public final ExportDefaults export;
    public final Vocabulary vocabulary;

    public PluginParts(StepHost host, UiInventory inventory) {
        this(host, inventory, null, null, null, null, null);
    }

    /** A plugin that offers a vocabulary only. */
    public PluginParts(Vocabulary vocabulary) {
        this(null, null, null, null, null, null, vocabulary);
    }

    public <T> PluginParts(StepHost host, UiInventory inventory, AppHooks app, Class<T> hooksType, T hooks,
                           ExportDefaults export) {
        this(host, inventory, app, hooksType, hooks, export, null);
    }

    public <T> PluginParts(StepHost host, UiInventory inventory, AppHooks app, Class<T> hooksType, T hooks,
                           ExportDefaults export, Vocabulary vocabulary) {
        if ((hooksType == null) != (hooks == null)) {
            throw new IllegalArgumentException("hooksType and hooks come together");
        }
        if (hooksType != null && !hooksType.isInterface()) {
            throw new IllegalArgumentException("hooksType must be an interface: " + hooksType.getName());
        }
        this.host = host;
        this.inventory = inventory;
        this.app = app;
        this.hooksType = hooksType;
        this.hooks = hooks;
        this.export = export;
        this.vocabulary = vocabulary;
    }
}
