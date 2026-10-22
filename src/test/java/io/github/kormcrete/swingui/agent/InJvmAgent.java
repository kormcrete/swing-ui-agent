package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.spi.PluginParts;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.function.Supplier;

/** The real endpoint, tools and lease over a given {@code Verbs} and plugin parts, in this JVM. For tests. */
public final class InJvmAgent {

    private final AgentAssembly assembly;

    private InJvmAgent(AgentAssembly assembly) {
        this.assembly = assembly;
    }

    /** Starts the agent on a free loopback port; {@code parts}, when not null, is one ready plugin named "fixture". */
    public static InJvmAgent start(Verbs verbs, PluginParts parts) {
        return start(verbs, parts, null);
    }

    /**
     * As {@link #start(Verbs, PluginParts)}, with what {@code session} sees of the app's main window: TRUE showing,
     * FALSE not yet, null unknown; a null supplier keeps the default (unknown without an installed driver).
     */
    public static InJvmAgent start(Verbs verbs, PluginParts parts, Supplier<Boolean> mainWindow) {
        Plugins plugins = new Plugins(verbs);
        if (parts != null) plugins.add("fixture", java.nio.file.Paths.get("fixture")).ready(parts, "fixture");
        AgentAssembly a = AgentAssembly.build(AgentArgs.parse("port=0"), verbs, plugins, AgentInfo.load(),
                1_700_000_000_000L, new ObjectMapper());
        if (mainWindow != null) a.sessionTool.mainWindow(mainWindow, 10_000L);
        try {
            a.endpoint.start();
        } catch (IOException e) {
            throw new IllegalStateException("cannot start the in-JVM agent", e);
        }
        return new InJvmAgent(a);
    }

    /** As {@code start(verbs, null)}, with an app whose event thread never answers: {@code session} sees it busy. */
    public static InJvmAgent startBusy(Verbs verbs) {
        Plugins plugins = new Plugins(verbs);
        AgentAssembly a = AgentAssembly.build(AgentArgs.parse("port=0"), verbs, plugins, AgentInfo.load(),
                1_700_000_000_000L, new ObjectMapper());
        a.sessionTool.windows(() -> SessionTool.Seen.BUSY, 10_000L);
        try {
            a.endpoint.start();
        } catch (IOException e) {
            throw new IllegalStateException("cannot start the in-JVM agent", e);
        }
        return new InJvmAgent(a);
    }

    public int port() { return assembly.endpoint.port(); }

    Lease lease() { return assembly.lease; }

    public void stop() { assembly.endpoint.stop(); }
}
