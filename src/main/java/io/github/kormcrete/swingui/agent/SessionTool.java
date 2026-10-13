package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.flow.FailureDiagnostics;
import io.github.kormcrete.swingui.mcp.UiMcpSession;
import io.github.kormcrete.swingui.mcp.rpc.McpToolDefinition;
import io.github.kormcrete.swingui.spi.AppHooks;
import io.github.kormcrete.swingui.spi.AppInfo;
import io.github.kormcrete.swingui.spi.PluginParts;
import io.github.kormcrete.swingui.spi.ResetSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.function.IntSupplier;

/**
 * The {@code session} tool: what this agent is, who holds it, and the start of every test. Seven ops: info, lease,
 * release, renew, reset, login, edt. reset and login are mutating, so they enter the lease themselves (the tool is
 * registered outside {@link LeaseGate}, whose read-only rule would let them through).
 */
final class SessionTool implements McpToolDefinition {

    private final Plugins plugins;
    private final Lease lease;
    private final UiMcpSession session;
    private final Verbs verbs;
    private final AgentInfo info;
    private final AgentArgs args;
    private final IntSupplier boundPort;
    private final long appStartedAt;
    private final ObjectMapper m = new ObjectMapper();

    SessionTool(Plugins plugins, Lease lease, UiMcpSession session, Verbs verbs, AgentInfo info, AgentArgs args,
                IntSupplier boundPort, long appStartedAt) {
        this.plugins = plugins;
        this.lease = lease;
        this.session = session;
        this.verbs = verbs;
        this.info = info;
        this.args = args;
        this.boundPort = boundPort;
        this.appStartedAt = appStartedAt;
    }

    @Override public String name() { return "session"; }

    @Override public ObjectNode descriptor(ObjectMapper mapper) {
        ObjectNode d = mapper.createObjectNode();
        d.put("name", "session");
        d.put("description", "The agent itself. info (default): versions, app start, lease, plugins (the plugins array "
                + "lists every plugin with its state). lease {owner, ttlMs}: take the agent for one owner; pass the token "
                + "as _lease on every mutating call. release {token}. renew {token}: keep the lease alive while the test makes no call; answers renewed: false once the lease is gone. reset {alias | user+password}: the start of every "
                + "test: close modal dialogs; with an app plugin also its reset (tabs, login as alias or user). "
                + "Starts a fresh recording for export_test. login {alias | user+password | relogin:true}: switch users "
                + "mid-test. edt: the client's event-thread stack.");
        ObjectNode schema = d.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode p = schema.putObject("properties");
        ArrayNode ops = p.putObject("op").put("type", "string").putArray("enum");
        for (String o : new String[]{"info", "lease", "release", "renew", "reset", "login", "edt"}) ops.add(o);
        p.putObject("owner").put("type", "string");
        p.putObject("ttlMs").put("type", "integer");
        p.putObject("token").put("type", "string");
        p.putObject("alias").put("type", "string");
        p.putObject("user").put("type", "string");
        p.putObject("password").put("type", "string");
        p.putObject("relogin").put("type", "boolean");
        p.putObject(LeaseGate.TOKEN_FIELD).put("type", "string");
        d.putObject("annotations").put("readOnlyHint", true);
        return d;
    }

    @Override public String invoke(JsonNode a) throws Exception {
        String op = a == null ? "info" : a.path("op").asText("info");
        switch (op) {
            case "info": return m.writeValueAsString(info());
            case "lease": {
                Lease.Grant g = lease.acquire(text(a, "owner"), a.hasNonNull("ttlMs") ? Long.valueOf(a.get("ttlMs").asLong()) : null);
                ObjectNode r = m.createObjectNode();
                r.put("token", g.token).put("owner", g.owner).put("since", Instant.ofEpochMilli(g.since).toString())
                        .put("ttlMs", g.ttlMs);
                return m.writeValueAsString(r);
            }
            case "release": {
                ObjectNode r = m.createObjectNode();
                r.put("released", lease.release(text(a, "token")));
                return m.writeValueAsString(r);
            }
            case "renew": {
                ObjectNode r = m.createObjectNode();
                r.put("renewed", lease.renewHeld(text(a, "token")));
                return m.writeValueAsString(r);
            }
            case "reset": {
                String person = io.github.kormcrete.swingui.driver.inspect.Inspector.engagedReason();
                if (person != null) {
                    // a reset starts a fresh recording: it must not wipe what the person is recording or answering
                    throw new DriverException(Reason.BUSY, "busy: " + person);
                }
                final AppHooks app = resetHooks(a);
                final ResetSpec spec = app == null ? null : who(a);
                gated(a, new Callable<Object>() {
                    @Override public Object call() {
                        AgentReset.run(verbs, app, spec);
                        session.startRecording(app != null);
                        return null;
                    }
                });
                String done = spec == null ? "dialogs" : spec.toString();
                return m.writeValueAsString(m.createObjectNode().put("reset", done));
            }
            case "login": {
                final AppHooks app = app("login");
                final boolean relogin = a.path("relogin").asBoolean(false);
                final ResetSpec spec = relogin ? null : who(a);
                gated(a, new Callable<Object>() {
                    @Override public Object call() {
                        if (relogin) app.login(null, true); else app.login(spec, false);
                        return null;
                    }
                });
                return m.writeValueAsString(m.createObjectNode().put("login", relogin ? "relogin" : spec.toString()));
            }
            case "edt":
                return m.writeValueAsString(m.createObjectNode().put("stack", FailureDiagnostics.edtStack()));
            default:
                throw new DriverException(Reason.BAD_INPUT,
                        "unknown op '" + op + "' (known: info, lease, release, renew, reset, login, edt)");
        }
    }

    private void gated(JsonNode a, Callable<Object> work) throws Exception {
        String t = LeaseGate.token(a);
        lease.enter(t);
        try {
            session.exclusive(work);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new DriverException(Reason.APP_ERROR, String.valueOf(e));
        } finally {
            lease.exit(t);
        }
    }

    private ObjectNode info() {
        ObjectNode r = m.createObjectNode();
        ObjectNode agent = r.putObject("agent");
        agent.put("version", info.version).put("built", info.built);
        Path jar = jarPath();
        if (jar == null) {
            agent.putNull("jar");
            agent.putNull("jarTime");
        } else {
            agent.put("jar", jar.toString());
            long t = jar.toFile().lastModified();
            if (t > 0) agent.put("jarTime", Instant.ofEpochMilli(t).toString()); else agent.putNull("jarTime");
        }
        r.putObject("endpoint").put("bind", args.bind).put("port", boundPort.getAsInt());
        r.put("mode", args.mode);
        r.put("inspector", args.inspector);
        ObjectNode app = r.putObject("app");
        app.put("startedAtMillis", appStartedAt).put("startedAt", Instant.ofEpochMilli(appStartedAt).toString());
        ArrayNode classpath = app.putArray("classpath");
        for (String dir : classpathDirectories(String.valueOf(System.getProperty("java.class.path")))) classpath.add(dir);
        PluginParts parts = plugins.app();
        AppInfo ai = null;
        if (parts != null && parts.app != null) {
            try {
                ai = parts.app.info();
            } catch (RuntimeException e) {
                // an app that cannot name itself is reported without a name
            }
        }
        if (ai != null && ai.name != null) app.put("name", ai.name); else app.putNull("name");
        if (ai != null && ai.version != null) app.put("version", ai.version); else app.putNull("version");
        Lease.Grant g = lease.holder();
        if (g == null) {
            r.putNull("lease");
        } else {
            r.putObject("lease").put("owner", g.owner).put("since", Instant.ofEpochMilli(g.since).toString());
        }
        ArrayNode all = r.putArray("plugins");
        for (PluginSlot s : plugins.slots()) {
            ObjectNode o = all.addObject();
            o.put("name", s.name()).put("state", s.stateName()).put("detail", s.detail());
            o.put("location", String.valueOf(s.location()));
            long t = s.locationTime();
            if (t > 0) o.put("locationTime", Instant.ofEpochMilli(t).toString()); else o.putNull("locationTime");
        }
        PluginSlot slot = plugins.appSlot();
        if (slot == null) {
            r.putObject("plugin").put("state", "none").put("detail", "no plugin configured (agent argument plugins=…)");
        } else {
            r.putObject("plugin").put("state", slot.stateName()).put("detail", slot.detail());
        }
        return r;
    }

    private static Path jarPath() {
        try {
            return Paths.get(SwingUiAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    private static ResetSpec who(JsonNode a) {
        return a.hasNonNull("user") ? ResetSpec.credentials(a.get("user").asText(), text(a, "password"))
                : ResetSpec.alias(text(a, "alias"));
    }

    private static String text(JsonNode a, String field) {
        return a != null && a.hasNonNull(field) ? a.get(field).asText() : null;
    }

    /**
     * The app plugin's hooks for a reset; null when no plugin is configured or the app plugin has no AppHooks, and then
     * a reset that names a user is a CAPABILITY_GAP. A configured app plugin that is not ready fails as {@link #app}.
     */
    private AppHooks resetHooks(JsonNode a) {
        AppHooks app = null;
        if (!plugins.slots().isEmpty()) {
            PluginParts p = plugins.app();
            if (p == null) throw new DriverException(Reason.CAPABILITY_GAP, plugins.unavailable("reset"));
            app = p.app;
        }
        if (app == null && a != null && (a.hasNonNull("alias") || a.hasNonNull("user"))) {
            throw new DriverException(Reason.CAPABILITY_GAP, "logging in as " + who(a)
                    + " needs an app plugin with AppHooks; without one a reset only closes dialogs");
        }
        return app;
    }

    private AppHooks app(String what) {
        PluginParts p = plugins.app();
        if (p == null) throw new DriverException(Reason.CAPABILITY_GAP, plugins.unavailable(what));
        if (p.app == null) {
            throw new DriverException(Reason.CAPABILITY_GAP, what + " needs the app plugin's AppHooks; it offers none");
        }
        return p.app;
    }

    /**
     * The directories the client loads classes from: directory entries of the class path, plus, for each jar entry,
     * the directories its manifest Class-Path names (IntelliJ's shortened class path is such a jar). One level only;
     * an unreadable jar is skipped.
     */
    static java.util.List<String> classpathDirectories(String classPath) {
        java.util.Set<String> out = new java.util.LinkedHashSet<String>();
        for (String entry : classPath.split(java.io.File.pathSeparator)) {
            if (entry.isEmpty()) continue;
            java.io.File f = new java.io.File(entry);
            if (f.isDirectory()) {
                out.add(f.getAbsolutePath());
            } else if (f.isFile()) {
                addManifestDirectories(f, out);
            }
        }
        return new java.util.ArrayList<String>(out);
    }

    private static void addManifestDirectories(java.io.File jar, java.util.Set<String> out) {
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar)) {
            java.util.jar.Manifest mf = jf.getManifest();
            String cp = mf == null ? null : mf.getMainAttributes().getValue("Class-Path");
            if (cp == null) return;
            java.io.File base = jar.getAbsoluteFile().getParentFile();
            for (String ref : cp.trim().split("\\s+")) {
                if (ref.isEmpty()) continue;
                try {
                    java.net.URI uri = new java.net.URI(ref);
                    java.io.File t;
                    if (uri.isAbsolute()) {
                        t = "file".equals(uri.getScheme()) ? new java.io.File(uri) : null;
                    } else {
                        t = new java.io.File(base, java.net.URLDecoder.decode(ref.replace("+", "%2B"), "UTF-8"));
                    }
                    if (t != null && t.isDirectory()) out.add(t.getCanonicalFile().getAbsolutePath());
                } catch (Exception badEntry) {
                    // an entry we cannot resolve is skipped
                }
            }
        } catch (Exception unreadable) {
            // a jar we cannot read is skipped; info must not fail over it
        }
    }
}
