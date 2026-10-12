package io.github.kormcrete.swingui.agent.fixture;

import io.github.kormcrete.swingui.spi.PluginContext;
import io.github.kormcrete.swingui.spi.PluginParts;
import io.github.kormcrete.swingui.spi.UiPlugin;

/** A plugin for PluginHostTest: starts at once and hands back no parts beyond nulls. */
public final class FixturePlugin implements UiPlugin {

    public static volatile boolean started;

    @Override
    public PluginParts start(PluginContext context) {
        started = true;
        return new PluginParts(null, null);
    }
}
