package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.flow.FlowFiles;
import io.github.kormcrete.swingui.flow.verb.ScreenshotVerb;
import io.github.kormcrete.swingui.mcp.HttpMcpEndpoint;
import io.github.kormcrete.swingui.mcp.UiMcpDispatcher;
import io.github.kormcrete.swingui.mcp.UiMcpSession;
import io.github.kormcrete.swingui.mcp.UiMcpTools;
import io.github.kormcrete.swingui.mcp.rpc.McpToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** The agent's parts, built the same way by premain and by tests (InJvmAgent). */
final class AgentAssembly {

    final Lease lease;
    final UiMcpSession session;
    final HttpMcpEndpoint endpoint;

    private AgentAssembly(Lease lease, UiMcpSession session, HttpMcpEndpoint endpoint) {
        this.lease = lease;
        this.session = session;
        this.endpoint = endpoint;
    }

    /** The endpoint is built, not started. */
    static AgentAssembly build(AgentArgs args, Verbs verbs, Plugins plugins, AgentInfo info, long appStartedAt,
                               ObjectMapper mapper) {
        Lease lease = new Lease(System::currentTimeMillis);
        // a test run must not start while a person records, picks or answers in the app
        lease.blockedBy(io.github.kormcrete.swingui.driver.inspect.Inspector::engagedReason);
        // and while one runs, the person's palette leaves the window so it can never cover what the test clicks
        lease.watchedBy(io.github.kormcrete.swingui.driver.inspect.Inspector::testRun);
        UiMcpSession session = new UiMcpSession(plugins, plugins, mapper, plugins::exportDefaults);
        String property = System.getProperty(FlowFiles.DIR_PROP);
        session.flowsDir(flowsDir(args, property, Paths.get("")),
                args.flows != null || (property != null && !property.trim().isEmpty()));
        session.palette(paletteOn(args, GraphicsEnvironment.isHeadless()));
        ScreenshotVerb.moduleDir = () -> plugins.exportDefaults().moduleDir;
        List<McpToolDefinition> raw = new ArrayList<McpToolDefinition>(UiMcpTools.build(session, mapper));
        raw.add(pluginCallTool(session, plugins, mapper));
        List<McpToolDefinition> tools = new ArrayList<McpToolDefinition>(LeaseGate.gate(raw, lease, mapper));
        final AtomicReference<HttpMcpEndpoint> holder = new AtomicReference<HttpMcpEndpoint>();
        SessionTool sessionTool = new SessionTool(plugins, lease, session, verbs, info, args,
                () -> holder.get() == null ? args.port : holder.get().port(), appStartedAt);
        tools.add(sessionTool);
        session.appReset(sessionTool.appReset());
        HttpMcpEndpoint endpoint = new HttpMcpEndpoint(new UiMcpDispatcher(mapper, tools), mapper, args.bind, "/mcp", args.port);
        holder.set(endpoint);
        return new AgentAssembly(lease, session, endpoint);
    }

    /** The flows folder: agent option {@code flows=}, else the system property, else {@code uitest} under {@code cwd}. */
    static Path flowsDir(AgentArgs a, String property, Path cwd) {
        return FlowFiles.dir(a.flows, property, cwd);
    }

    /** The palette as asked for (on unless {@code palette=off} or {@code mode=background}), and never without a screen. */
    static boolean paletteOn(AgentArgs args, boolean headless) {
        return args.palette && !headless;
    }

    private static McpToolDefinition pluginCallTool(final UiMcpSession session, final Plugins plugins,
                                                    final ObjectMapper mapper) {
        return new McpToolDefinition() {
            @Override public String name() { return "plugin_call"; }

            @Override public ObjectNode descriptor(ObjectMapper mp) {
                ObjectNode d = mp.createObjectNode();
                d.put("name", "plugin_call");
                d.put("description", "Calls one method of a plugin's hooks by name. args is a JSON array, converted to the "
                        + "method's parameter types. Returns {\"value\": …}. Without 'plugin', the only plugin.");
                ObjectNode schema = d.putObject("inputSchema");
                schema.put("type", "object");
                ObjectNode p = schema.putObject("properties");
                p.putObject("plugin").put("type", "string");
                p.putObject("method").put("type", "string");
                p.putObject("args").put("type", "array");
                schema.putArray("required").add("method");
                return d;
            }

            @Override public String invoke(final JsonNode a) throws Exception {
                return session.exclusive(() -> PluginCalls.call(plugins, text(a, "plugin"), text(a, "method"),
                        a.get("args"), mapper)).toString();
            }
        };
    }

    private static String text(JsonNode a, String f) {
        return a != null && a.hasNonNull(f) ? a.get(f).asText() : null;
    }
}
