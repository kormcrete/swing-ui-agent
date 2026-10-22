package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.driver.Driver;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

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
    /** TRUE when the app's main window shows, FALSE while it does not, null when that cannot be known. */
    private volatile Supplier<Boolean> mainWindow = SessionTool::mainWindowShowing;
    /** How long a reset waits for the main window; below 0: the driver's expect timeout. */
    private volatile long mainWindowWaitMs = -1;

    /** How long a read of the main window waits for a busy event thread before it says "not showing". */
    static final long EDT_READ_MS = 1_000L;
    static final long POLL_MS = 100L;

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

    /**
     * What this tool sees of the app's main window (TRUE showing, FALSE not yet, null unknown) and how long a reset
     * waits for it. For tests; the default reads the installed driver's main window.
     */
    SessionTool mainWindow(Supplier<Boolean> showing, long waitMs) {
        this.mainWindow = showing;
        this.mainWindowWaitMs = waitMs;
        return this;
    }

    @Override public String name() { return "session"; }

    @Override public ObjectNode descriptor(ObjectMapper mapper) {
        ObjectNode d = mapper.createObjectNode();
        d.put("name", "session");
        d.put("description", "The agent itself. info (default): versions, app start, lease, plugins (the plugins array "
                + "lists every plugin with its state). lease {owner, ttlMs}: take the agent for one owner; pass the token "
                + "as _lease on every mutating call. release {token}. renew {token}: keep the lease alive while the test makes no call; answers renewed: false once the lease is gone. reset {alias | user+password}: the start of every "
                + "test: close modal dialogs; with an app plugin also its reset (tabs, login as alias or user). "
                + "Starts a fresh recording for export_test; refused while a recording runs (recording start or ● Record): "
                + "recording stop or Done ends it. login {alias | user+password | relogin:true}: switch users "
                + "mid-test. edt: the client's event-thread stack. ready: true once the app plugin (if any) is ready "
                + "and the app's main window shows; reset waits for that window");
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
                // a new owner reads no outcome another one asked for
                if (session != null) session.forgetOutcomeHandles();
                ObjectNode r = m.createObjectNode();
                r.put("token", g.token).put("owner", g.owner).put("since", Instant.ofEpochMilli(g.since).toString())
                        .put("ttlMs", g.ttlMs);
                return m.writeValueAsString(r);
            }
            case "release": {
                ObjectNode r = m.createObjectNode();
                r.put("released", lease.release(text(a, "token")));
                if (session != null) session.forgetOutcomeHandles();
                return m.writeValueAsString(r);
            }
            case "renew": {
                ObjectNode r = m.createObjectNode();
                r.put("renewed", lease.renewHeld(text(a, "token")));
                return m.writeValueAsString(r);
            }
            case "reset": {
                String person = io.github.kormcrete.swingui.driver.inspect.Inspector.engagedReason();
                if (person == null) person = session.recordingReason();
                if (person != null) {
                    // a reset starts a fresh recording: it must not wipe what is being recorded or what the person answers
                    throw new DriverException(Reason.BUSY, "" + person);
                }
                // a replay or a recorded test run is using the app and the recording: a reset under it would derail both
                session.refuseWhileReplaying();
                final AppHooks app = resetHooks(a);
                final ResetSpec spec = app == null ? null : who(a);
                gated(a, new Callable<Object>() {
                    @Override public Object call() {
                        awaitMainWindow();
                        AgentReset.run(verbs, app, spec);
                        session.startRecording(app != null);
                        session.forgetOutcomeHandles();
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

    /**
     * The recorded test start: what {@code session reset} does to the app, without a new recording. No lease handling here: the
     * callers (run_recorded_test, the palette) are already inside the lease gate or hold the person's engagement.
     */
    UiMcpSession.AppReset appReset() {
        return new UiMcpSession.AppReset() {
            @Override public boolean defined() {
                try {
                    return plugins.app() != null && plugins.app().app != null;
                } catch (RuntimeException e) {
                    return false;
                }
            }

            @Override public void run() {
                AppHooks hooks = resetHooks(null);
                awaitMainWindow();
                // as `session reset` without an alias: the plugin's default user
                AgentReset.run(verbs, hooks, hooks == null ? null : ResetSpec.alias(null));
            }
        };
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
        r.put("palette", session.paletteOn());
        r.put("recordedDir", session.recordedDir().toAbsolutePath().normalize().toString());
        r.put("recordedReadOnly", session.recordedReadOnly());
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
        Boolean showing = mainWindowNow();
        app.put("mainWindow", showing == null ? "unknown" : showing ? "showing" : "not showing");
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
        ArrayNode vocabulary = r.putArray("vocabulary");
        for (Plugins.VocabularyStep v : plugins.vocabulary()) vocabulary.add(v.name);
        ArrayNode problems = r.putArray("vocabularyProblems");
        for (String problem : plugins.vocabularyProblems()) problems.add(problem);
        PluginSlot slot = plugins.appSlot();
        if (slot == null) {
            r.putObject("plugin").put("state", "none").put("detail", "no plugin configured (agent argument plugins=…)");
        } else {
            r.putObject("plugin").put("state", slot.stateName()).put("detail", slot.detail());
        }
        boolean pluginUsable = slot == null || slot.state() == PluginSlot.State.READY;
        r.put("ready", pluginUsable && !Boolean.FALSE.equals(showing));
        return r;
    }

    /**
     * Waits while the main window is known not to show; fails NOT_FOUND when it never came. An unknown window (no
     * driver, a headless JVM) never waits.
     */
    private void awaitMainWindow() {
        if (!Boolean.FALSE.equals(mainWindowNow())) return;
        long wait = mainWindowWaitMs;
        if (wait < 0) {
            Driver d = Driver.current();
            wait = d == null ? 0 : d.settings().expectMillis;
        }
        long deadline = System.currentTimeMillis() + wait;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (!Boolean.FALSE.equals(mainWindowNow())) return;
        }
        throw new DriverException(Reason.NOT_FOUND,
                "reset: the app's main window is not showing (waited " + seconds(wait) + ")");
    }

    private Boolean mainWindowNow() {
        try {
            return mainWindow.get();
        } catch (RuntimeException e) {
            // a main-window supplier that fails cannot say: nothing waits on it
            return null;
        }
    }

    /** A wait as people read it: whole seconds from one second on ("10 s"), one decimal below ("0.3 s"). */
    static String seconds(long millis) {
        if (millis >= 1_000L) return Math.round(millis / 1_000.0) + " s";
        return String.format(java.util.Locale.ROOT, "%.1f s", millis / 1_000.0);
    }

    /**
     * The installed driver's main window ({@code Driver.setMainWindow}, else the first showing frame), read on the
     * event thread as everywhere else: TRUE showing, FALSE not; null without a driver or a screen. An event thread busy
     * longer than {@link #EDT_READ_MS} (the app still building its window) counts as not showing, so {@code session}
     * never hangs on it.
     */
    static Boolean mainWindowShowing() {
        final Driver d = Driver.current();
        if (d == null || java.awt.GraphicsEnvironment.isHeadless()) return null;
        if (javax.swing.SwingUtilities.isEventDispatchThread()) return showing(d);
        final AtomicReference<Boolean> answer = new AtomicReference<Boolean>();
        final CountDownLatch done = new CountDownLatch(1);
        javax.swing.SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                try {
                    answer.set(showing(d));
                } finally {
                    done.countDown();
                }
            }
        });
        try {
            return done.await(EDT_READ_MS, TimeUnit.MILLISECONDS) ? answer.get() : Boolean.FALSE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static Boolean showing(Driver d) {
        try {
            java.awt.Component main = d.windows().main();
            return main != null && main.isShowing();
        } catch (RuntimeException e) {
            // a plugin's main-window supplier that fails cannot say, and must not fail on the app's event thread
            return null;
        }
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
     * The app plugin's hooks for a reset; null when no plugin is configured, the plugins are ready and none has app
     * hooks (a vocabulary only: the plugin-less reset), or the app plugin has no AppHooks, and then a reset that names
     * a user is a CAPABILITY_GAP. A plugin that is not ready (it may be the app plugin) fails as {@link #app}.
     */
    private AppHooks resetHooks(JsonNode a) {
        AppHooks app = null;
        if (!plugins.slots().isEmpty()) {
            PluginParts p = plugins.app();
            if (p != null) {
                app = p.app;
            } else if (plugins.appSlot().state() != PluginSlot.State.READY) {
                // loading or failed: it may be the app plugin. All ready and none with a host: a vocabulary only
                throw new DriverException(Reason.CAPABILITY_GAP, plugins.unavailable("reset"));
            }
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
