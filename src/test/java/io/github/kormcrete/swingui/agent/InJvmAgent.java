package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.spi.PluginParts;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/** The real endpoint, tools and lease over a given {@code Verbs} and plugin parts, in this JVM. For tests. */
public final class InJvmAgent {

    private final AgentAssembly assembly;

    private InJvmAgent(AgentAssembly assembly) {
        this.assembly = assembly;
    }

    /** Starts the agent on a free loopback port; {@code parts}, when not null, is one ready plugin named "fixture". */
    public static InJvmAgent start(Verbs verbs, PluginParts parts) {
        Plugins plugins = new Plugins(verbs);
        if (parts != null) plugins.add("fixture", java.nio.file.Paths.get("fixture")).ready(parts, "fixture");
        AgentAssembly a = AgentAssembly.build(AgentArgs.parse("port=0"), verbs, plugins, AgentInfo.load(),
                1_700_000_000_000L, new ObjectMapper());
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
