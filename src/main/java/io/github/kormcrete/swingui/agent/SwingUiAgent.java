package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.driver.Driver;
import io.github.kormcrete.swingui.driver.DriverSettings;
import io.github.kormcrete.swingui.driver.LaunchMode;
import io.github.kormcrete.swingui.driver.platform.ModuleAccess;
import io.github.kormcrete.swingui.mcp.HttpMcpEndpoint;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code -javaagent:swing-ui-agent.jar=…}: drives the app's Swing UI over MCP on a loopback port. Order matters —
 * the launch mode first (apple.awt.UIElement must precede the toolkit), then on Java 9+ opening java.desktop to the
 * driver (the self-check reports what that left closed), then the driver, the endpoint, and only once
 * the endpoint listens, the plugin watch: an agent that could not start must not load its plugin into the app.
 * Never throws and never blocks: a failing agent leaves the app running without it.
 * Keep this class free of static fields that create AWT objects (SwingUiAgentStaticInitTest).
 */
public final class SwingUiAgent {

    private SwingUiAgent() {}

    public static void premain(String args, Instrumentation inst) {
        try {
            start(args, inst);
        } catch (Throwable t) {
            System.err.println("swing-ui-agent: failed to start (" + t + ") - the app runs without it");
        }
    }

    public static void agentmain(String args, Instrumentation inst) {
        try {
            start(args, inst);
        } catch (Throwable t) {
            System.err.println("swing-ui-agent: failed to start (" + t + ") - the app runs without it");
        }
    }

    /** Returns the listening endpoint, or null when the agent did not start. Never throws. */
    static synchronized HttpMcpEndpoint start(String rawArgs, Instrumentation inst) {
        AgentArgs args;
        try {
            args = AgentArgs.parse(rawArgs);
        } catch (IllegalArgumentException e) {
            System.err.println("swing-ui-agent: " + e.getMessage() + " - agent not started, the app runs without it");
            return null;
        }
        try {
            LaunchMode mode = LaunchMode.apply(args.mode);
            String closed = ModuleAccess.open(inst);
            if (closed != null) System.err.println("swing-ui-agent: " + closed);
            Driver driver = Driver.install(DriverSettings.defaults(mode));
            Plugins plugins = new Plugins(driver.verbs());

            AgentAssembly assembly = AgentAssembly.build(args, driver.verbs(), plugins, AgentInfo.load(),
                    ManagementFactory.getRuntimeMXBean().getStartTime(), new ObjectMapper());
            HttpMcpEndpoint endpoint = assembly.endpoint;
            try {
                endpoint.start();
            } catch (IOException e) {
                System.err.println("swing-ui-agent: cannot listen on " + args.bind + ":" + args.port + " (" + e
                        + ") - agent not started, the app runs without it");
                return null;
            }
            System.out.println("swing-ui-agent listening on http://" + args.bind + ":" + endpoint.port() + "/mcp");

            watchPlugins(args.plugins, driver, plugins, inst);
            return endpoint;
        } catch (Throwable t) {
            System.err.println("swing-ui-agent: failed to start (" + t + ") - the app runs without it");
            t.printStackTrace();
            return null;
        }
    }

    private static void watchPlugins(List<Path> paths, Driver driver, Plugins plugins, Instrumentation inst) {
        if (paths.isEmpty()) return;
        PluginWatch watch = new PluginWatch(inst);
        for (Path p : paths) {
            try {
                PluginDescriptor d = PluginDescriptor.read(p);
                PluginSlot slot = plugins.add(d.name, p);
                watch.watch(d.appClass, new PluginHost(driver, slot, d));
            } catch (IOException e) {
                plugins.add(String.valueOf(p.getFileName()), p).failed(p + ": " + e.getMessage());
                System.err.println("swing-ui-agent: " + e.getMessage());
            } catch (RuntimeException e) {
                // Properties.load refuses a malformed unicode escape with IllegalArgumentException
                plugins.add(String.valueOf(p.getFileName()), p).failed(p + ": " + e);
                System.err.println("swing-ui-agent: " + p + ": " + e);
            }
        }
        if (watch.isEmpty()) return;
        try {
            inst.addTransformer(watch);
            watch.scanLoaded();
        } catch (Throwable t) {
            for (PluginSlot slot : plugins.slots()) slot.failed("cannot watch class loading: " + t);
            System.err.println("swing-ui-agent: cannot watch class loading (" + t + ")");
        }
    }
}
