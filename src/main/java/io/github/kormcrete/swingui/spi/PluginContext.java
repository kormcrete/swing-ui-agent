package io.github.kormcrete.swingui.spi;

import io.github.kormcrete.swingui.driver.Driver;

/** What the agent hands a starting plugin: the installed driver, and the name its slot reports. */
public interface PluginContext {
    Driver driver();
    String pluginName();
}
