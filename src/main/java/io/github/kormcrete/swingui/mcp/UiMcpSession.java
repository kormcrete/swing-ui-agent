package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.Driver;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Edt;
import io.github.kormcrete.swingui.driver.LaunchMode;
import io.github.kormcrete.swingui.driver.EdtClock;
import io.github.kormcrete.swingui.driver.FileChooserState;
import io.github.kormcrete.swingui.driver.PendingDialog;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.Shot;
import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Node;
import io.github.kormcrete.swingui.driver.inspect.Inspector;
import io.github.kormcrete.swingui.driver.inspect.Target;
import io.github.kormcrete.swingui.driver.model.Recorder;
import io.github.kormcrete.swingui.driver.model.Resolved;
import io.github.kormcrete.swingui.flow.Action;
import io.github.kormcrete.swingui.flow.Flow;
import io.github.kormcrete.swingui.flow.FlowExecutor;
import io.github.kormcrete.swingui.flow.FlowFiles;
import io.github.kormcrete.swingui.flow.FlowFolderNotWritable;
import io.github.kormcrete.swingui.flow.FailureDiagnostics;
import io.github.kormcrete.swingui.flow.FlowJson;
import io.github.kormcrete.swingui.flow.FlowReport;
import io.github.kormcrete.swingui.flow.StepDiagnostics;
import io.github.kormcrete.swingui.flow.verb.ScreenshotVerb;
import io.github.kormcrete.swingui.flow.FlowResult;
import io.github.kormcrete.swingui.flow.LoginAsAction;
import io.github.kormcrete.swingui.flow.NavigateAction;
import io.github.kormcrete.swingui.flow.StepResult;
import io.github.kormcrete.swingui.agent.Plugins;
import io.github.kormcrete.swingui.spi.ExportDefaults;
import io.github.kormcrete.swingui.spi.StepHost;
import io.github.kormcrete.swingui.flow.verb.MenuVerb;
import io.github.kormcrete.swingui.flow.verb.PressVerb;
import io.github.kormcrete.swingui.flow.verb.VerbAction;
import io.github.kormcrete.swingui.spi.UiInventory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.Map;

/**
 * Runs steps against the live client's {@link StepHost}, reports its {@link UiInventory} for
 * {@code describe_ui}, and records the steps that succeed and have a Java form, for {@code export_test}
 * to write as a JUnit {@code @UiTest} class.
 */
public class UiMcpSession {

    private final StepHost host;
    private final UiInventory inventory;
    private final ObjectMapper mapper;
    private final FlowExecutor executor = new FlowExecutor();
    private final RecordingModel model = new RecordingModel();
    /** The recording began at a session reset, where an exported test starts too; export_test refuses it otherwise. */
    private boolean recordingFromReset;
    /** That reset was an app plugin's, to a defined start state, not only dialogs closed. */
    private boolean definedStart;

    /**
     * The start of a flow: what {@code session reset} does to the app - dialogs closed, with an app plugin its reset -
     * without touching the recording. {@link #defined()}: an app plugin resets the app to a defined state.
     */
    public interface AppReset {
        boolean defined();

        void run();
    }

    private AppReset appReset = new AppReset() {
        @Override public boolean defined() { return false; }

        @Override public void run() {
            throw new DriverException(Reason.CAPABILITY_GAP, "this session has no reset");
        }
    };
    private Path flowsDir = FlowFiles.dir(null, System.getProperty(FlowFiles.DIR_PROP), Paths.get(""));
    /** The folder was chosen (agent option or system property), not the default. */
    private boolean flowsDirExplicit;
    /** The flow file the recording came from (loaded, or exported without clear): the one file a save may replace. */
    private Path loadedFlow;
    /** The {@code name} and {@code description} written in {@link #loadedFlow}: a save over it without them keeps them. */
    private String loadedName;
    private String loadedDescription;
    /** The folder the person last used in the palette, offered when no folder was set explicitly. */
    private FolderMemory folderMemory = FolderMemory.preferences(Paths.get("").toAbsolutePath());

    /** Why a person is busy in the palette (recording, answering, replaying), or null; a seam for tests. */
    Supplier<String> personBusy = Inspector::engagedReason;

    private final Supplier<ExportDefaults> exportDefaults;
    /** The palette is on (the agent's default, with a screen): a person may record, check and answer in the app. */
    private volatile boolean palette;

    public UiMcpSession(StepHost host, UiInventory inventory, ObjectMapper mapper) {
        this(host, inventory, mapper, () -> Plugins.GENERIC_EXPORT);
    }

    public UiMcpSession(StepHost host, UiInventory inventory, ObjectMapper mapper, Supplier<ExportDefaults> exportDefaults) {
        this.host = host;
        this.inventory = inventory;
        this.mapper = FlowJson.registerDriverTypes(mapper);
        this.exportDefaults = exportDefaults;
    }

    /** How a flow starts (see {@link AppReset}); the agent sets the session's own reset. */
    public void appReset(AppReset reset) {
        this.appReset = reset;
    }

    public AppReset appReset() {
        return appReset;
    }

    /** Where flows are saved and loaded from; {@code explicit}: set by the agent option or the system property. */
    public void flowsDir(Path dir, boolean explicit) {
        this.flowsDir = dir;
        this.flowsDirExplicit = explicit;
    }

    public Path flowsDir() {
        return flowsDir;
    }

    public boolean flowsDirExplicit() {
        return flowsDirExplicit;
    }

    /** Where the palette remembers the last folder the person used (tests: {@link FolderMemory#inMemory}). */
    void folderMemory(FolderMemory memory) {
        this.folderMemory = memory;
    }

    /** The flow file the recording came from; null when it came from no file. */
    public synchronized Path loadedFlow() {
        return loadedFlow;
    }

    /** Lets {@code inspect} run: only while the palette is on. Off until the agent says so. */
    public void palette(boolean on) {
        palette = on;
    }

    /** Whether the palette is on: what the agent was started with, after a headless JVM or background mode turned it off. */
    public boolean paletteOn() {
        return palette;
    }

    /** Puts the person's palette into the app (● Record), its recordings going into this session. */
    public void startPalette(Driver driver) {
        if (palette) Inspector.of(driver).enable(humanSink());
    }

    /** The recording, one step per element, as export_test would read it. */
    private synchronized ArrayNode recordingJson() {
        ArrayNode out = mapper.createArrayNode();
        for (Action a : model.actions()) out.add(stripNulls(mapper.valueToTree(a)));
        return out;
    }

    /** An unscoped snapshot over this many bytes carries a hint to scope the next one. */
    static final int LARGE_SNAPSHOT_BYTES = 16_000;

    /** Runs {@code work} under this session's lock, so it never interleaves with a step or a read. */
    public synchronized <T> T exclusive(java.util.concurrent.Callable<T> work) throws Exception {
        return work.call();
    }

    /** The live client as an element tree with refs. */
    public synchronized String snapshot(JsonNode args) {
        return snapshotNode(args).toString();
    }

    /**
     * The snapshot tree plus a {@code size} block ({@code nodes}, {@code bytes} of the tree, whether it
     * was {@code scoped}), so a caller sees what it paid. {@code target} and {@code depth} scope it,
     * {@code compact} strips its bulk (see {@link Node#compact}) and defaults {@code maxRows} to 0.
     */
    private ObjectNode snapshotNode(JsonNode args) {
        JsonNode targetJson = args == null ? null : args.get("target");
        if (targetJson != null && !targetJson.isNull() && !targetJson.isObject()) {
            throw new DriverException(Reason.BAD_INPUT, "'target' must be a locator object");
        }
        Locator target = targetJson != null && targetJson.isObject() ? locator(targetJson) : null;
        boolean compact = args != null && args.path("compact").asBoolean(false);
        Integer maxRows = integer(args, "maxRows");
        if (compact && maxRows == null) maxRows = 0;
        String window = text(args, "window");
        String ref = text(args, "ref");
        Integer depth = integer(args, "depth");
        Node node = driver().snapshot(window, ref, target, depth, integer(args, "maxNodes"), maxRows);
        if (compact) node.compact();
        ObjectNode tree = mapper.valueToTree(node);
        int bytes = tree.toString().getBytes(StandardCharsets.UTF_8).length;
        boolean scoped = window != null || ref != null || target != null || depth != null;
        ObjectNode size = tree.putObject("size");
        size.put("nodes", node.count());
        size.put("bytes", bytes);
        size.put("scoped", scoped);
        if (!scoped && bytes > LARGE_SNAPSHOT_BYTES) {
            size.put("hint", "a full-tree snapshot: scope the next one with 'target' (or 'ref'/'window') and "
                    + "'depth', add compact:true, or read one element with 'read' / 'expect'");
        }
        return tree;
    }

    /**
     * The inspector, for a person at the app: {@code highlight} a target (numbered boxes; the person confirms one with its
     * check mark, or says none), {@code pick} (the person clicks what they mean), {@code recording} (what the person
     * recorded with the palette - they start and end it, the agent only reads it or waits for their Done; their steps go
     * into THIS recording, as if the agent had run them), {@code steps} (the recording so far), {@code hover} on/off,
     * {@code clear}. Only while the palette is on. Not synchronized: it waits for a person, and must not hold the
     * session meanwhile.
     */
    public String inspect(JsonNode args) {
        if (!palette) {
            throw new DriverException(Reason.CAPABILITY_GAP, "the palette is off (the app was started with palette=off, "
                    + "mode=background, or without a screen): there is no person at the app to work with");
        }
        Driver driver = Driver.current();
        if (driver == null) throw new DriverException(Reason.CAPABILITY_GAP, "no UI driver is installed in this client");
        if (driver.settings().mode == LaunchMode.BACKGROUND) {
            throw new DriverException(Reason.CAPABILITY_GAP, "the palette needs a person at the screen; this app runs in background mode");
        }
        Inspector inspector = Inspector.of(driver);
        String op = text(args, "op");
        if (op == null) throw new DriverException(Reason.BAD_INPUT, "inspect needs 'op': highlight, pick, recording, steps, hover or clear");
        Integer wait = integer(args, "wait");
        String question = text(args, "question");
        ObjectNode r = mapper.createObjectNode();
        Inspector.Answer answer;
        switch (op) {
            case "highlight": {
                long waitMs = wait == null ? 60_000L : Math.min(wait.longValue(), 300_000L);
                JsonNode targetJson = args.get("target");
                if (targetJson == null || !targetJson.isObject()) throw new DriverException(Reason.BAD_INPUT, "highlight needs a 'target'");
                Locator target = locator(targetJson);
                target.validate();
                List<Resolved> matches = Edt.call(
                        () -> target.ref != null
                                ? Collections.singletonList(driver.resolver().resolve(target))
                                : driver.resolver().matches(target));
                List<Target> targets = inspector.targets(matches);
                ArrayNode list = r.putArray("matches");
                for (int i = 0; i < targets.size(); i++) {
                    ObjectNode m = list.addObject();
                    m.put("n", i + 1);
                    m.put("element", targets.get(i).element);
                    m.set("locator", locatorJson(targets.get(i).locator));
                    if (targets.get(i).fragile) m.put("fragile", true);
                }
                if (targets.isEmpty()) {
                    r.put("outcome", "notFound");
                    return r.toString();
                }
                answer = inspector.highlight(targets, question, waitMs);
                break;
            }
            case "pick":
                answer = inspector.pick(question, wait == null ? 60_000L : Math.min(wait.longValue(), 300_000L));
                break;
            case "recording":
                answer = inspector.recording(wait == null ? 0L : Math.min(wait.longValue(), 540_000L));
                break;
            case "steps":
                r.set("recording", recordingJson());
                return r.toString();
            case "hover":
                inspector.hover(args.path("on").asBoolean(true));
                r.put("outcome", args.path("on").asBoolean(true) ? "hoverOn" : "hoverOff");
                return r.toString();
            case "clear":
                inspector.clear();
                r.put("outcome", "cleared");
                return r.toString();
            default:
                throw new DriverException(Reason.BAD_INPUT, "unknown inspect op '" + op + "' (highlight, pick, recording, steps, hover, clear)");
        }
        r.put("outcome", answer.outcome);
        if (answer.chosen != null) r.put("chosen", answer.chosen);
        if (answer.target != null) {
            r.put("element", answer.target.element);
            r.set("locator", locatorJson(answer.target.locator));
            if (answer.target.fragile) r.put("fragile", true);
        }
        if (answer.steps != null) {
            ArrayNode steps = r.putArray("steps");
            for (String line : answer.steps) steps.add(line);
            r.set("recording", recordingJson());
        }
        return r.toString();
    }

    /**
     * Where the person's steps go. A step they did is parsed exactly as the agent's would be and appended to the
     * recording without running it again (it already happened); a check runs like the agent's expect and is recorded
     * when it passes.
     */
    Inspector.Sink humanSink() {
        return new Inspector.Sink() {
            @Override
            public void begin() {
                // the person's ● Record: a fresh recording from the screen they are on; nothing in the app is reset
                startRecording(false);
            }

            @Override
            public Inspector.Result done(Map<String, Object> step) {
                JsonNode json = stripNulls(mapper.valueToTree(step));
                try {
                    synchronized (UiMcpSession.this) {
                        Action action = action(json);
                        List<String> problems = exportProblems(action);
                        if (!problems.isEmpty()) {
                            return new Inspector.Result(false,
                                    action.describe() + " - " + String.join("; ", problems));
                        }
                        model.append(action, RecordedStep.Author.PERSON);
                        return new Inspector.Result(true, action.describe());
                    }
                } catch (RuntimeException e) {
                    return new Inspector.Result(false, json + " - " + e.getMessage());
                }
            }

            @Override
            public List<String> steps() {
                synchronized (UiMcpSession.this) {
                    List<String> out = new ArrayList<String>();
                    for (Action a : model.actions()) out.add(a.describe());
                    return out;
                }
            }

            @Override
            public Locator target(int index) {
                synchronized (UiMcpSession.this) {
                    if (index < 0 || index >= model.size()) return null;
                    JsonNode step = mapper.valueToTree(model.at(index + 1).action);
                    JsonNode t = step.get("target");
                    if (t == null || !t.isObject()) return null;
                    Locator l = locator(stripNulls(t));
                    // a cell check targets the table; its box belongs on the cell
                    JsonNode cell = step.get("cell");
                    if (cell != null && cell.isObject()) {
                        if (cell.get("row") != null && cell.get("row").isObject()) {
                            l.row = mapper.convertValue(cell.get("row"), Map.class);
                        }
                        if (cell.hasNonNull("column")) l.column = cell.get("column").asText();
                    }
                    return l;
                }
            }

            @Override
            public Inspector.Result replay(int index) {
                JsonNode json;
                synchronized (UiMcpSession.this) {
                    if (index < 0 || index >= model.size()) {
                        return new Inspector.Result(false, "no step " + (index + 1));
                    }
                    json = stripNulls(mapper.valueToTree(model.at(index + 1).action));
                }
                try {
                    JsonNode result = mapper.readTree(doAction(json, false));
                    boolean passed = result.path("passed").asBoolean(false);
                    String line = result.path("action").asText(json.toString());
                    if (!passed) line += " - " + result.path("message").asText("");
                    return new Inspector.Result(passed, line);
                } catch (Exception e) {
                    return new Inspector.Result(false, e.getMessage());
                }
            }

            @Override
            public void truncate(int keep) {
                synchronized (UiMcpSession.this) {
                    model.truncate(keep);
                }
            }

            @Override
            public void remove(int index) {
                synchronized (UiMcpSession.this) {
                    if (index >= 0 && index < model.size()) model.remove(index + 1);
                }
            }

            @Override
            public String exportPackage() {
                ExportDefaults defaults = exportDefaults.get();
                return defaults != Plugins.GENERIC_EXPORT ? defaults.packageName
                        : mainPackage(System.getProperty("sun.java.command"));
            }

            @Override
            public Inspector.Result export(String className, String pkg) {
                try {
                    // kept: the person may still replay, redo, or export again under another name
                    return new Inspector.Result(true, wrote(mapper.readTree(exportTest(className, null, pkg, false))));
                } catch (Exception e) {
                    return new Inspector.Result(false, e.getMessage());
                }
            }

            @Override
            public Path flowsFolder() {
                if (flowsDirExplicit) return flowsDir;
                Path remembered = folderMemory.get();
                return remembered != null ? remembered : flowsDir;
            }

            @Override
            public Path loadedFlow() {
                return UiMcpSession.this.loadedFlow();
            }

            @Override
            public Inspector.Result exportFlow(String name, Path folder) {
                try {
                    // kept, like a test export; the file becomes the one a further export saves over
                    JsonNode result = mapper.readTree(UiMcpSession.this.exportFlow(name, folder.toString(), null, false));
                    folderMemory.put(Paths.get(result.path("savedTo").asText()).getParent());
                    return new Inspector.Result(true, wrote(result));
                } catch (FlowFolderNotWritable e) {
                    return new Inspector.Result(false, "Cannot write to " + e.folder() + ": " + e.why(), true);
                } catch (ExportRefused e) {
                    return new Inspector.Result(false, String.join("; ", e.problems()));
                } catch (Exception e) {
                    return new Inspector.Result(false, e.getMessage());
                }
            }

            @Override
            public List<Inspector.FlowEntry> flows(Path folder) {
                List<Inspector.FlowEntry> out = new ArrayList<Inspector.FlowEntry>();
                for (Path file : FlowFiles.list(folder)) {
                    Path abs = file.toAbsolutePath().normalize();
                    String base = abs.getFileName().toString();
                    String name = base.substring(0, base.length() - FlowFiles.SUFFIX.length());
                    try {
                        Flow flow = FlowFiles.read(abs);
                        out.add(new Inspector.FlowEntry(name, flow.description, flow.steps.size(), null, abs));
                    } catch (RuntimeException e) {
                        out.add(new Inspector.FlowEntry(name, null, 0, message(e), abs));
                    }
                }
                return out;
            }

            @Override
            public Inspector.Result loadFlow(Path file) {
                try {
                    JsonNode loaded = mapper.readTree(UiMcpSession.this.loadFlow(file));
                    // the folder the person loaded from is the one Load flow… and Export… offer next
                    folderMemory.put(Paths.get(loaded.path("loaded").asText()).getParent());
                    return new Inspector.Result(true, loaded.path("name").asText());
                } catch (Exception e) {
                    return new Inspector.Result(false, message(e));
                }
            }

            @Override
            public Inspector.Result reset() {
                try {
                    // a step of the person's run: one at a time with the agent's, the recording untouched
                    synchronized (UiMcpSession.this) {
                        appReset.run();
                    }
                    return new Inspector.Result(true, "reset");
                } catch (RuntimeException e) {
                    return new Inspector.Result(false, message(e));
                }
            }

            /** The message the palette shows: without the reason prefix the agent's errors carry. */
            private String message(Exception e) {
                if (e instanceof DriverException) {
                    return DriverException.stripReason(e.getMessage(), ((DriverException) e).reason());
                }
                return String.valueOf(e.getMessage());
            }

            @Override
            public Inspector.Result check(Map<String, Object> step) {
                JsonNode json = stripNulls(mapper.valueToTree(step));
                try {
                    JsonNode result = mapper.readTree(doAction(json, true, RecordedStep.Author.PERSON));
                    boolean passed = result.path("passed").asBoolean(false) && result.has("recorded");
                    String line = result.path("action").asText(json.toString());
                    if (!passed) line += " - " + result.path("message").asText(result.path("notRecorded").asText(""));
                    return new Inspector.Result(passed, line);
                } catch (Exception e) {
                    return new Inspector.Result(false, json + " - " + e.getMessage());
                }
            }
        };
    }

    /** The palette's line for a written export: where it went (relative to the working directory when below it). */
    private static String wrote(JsonNode result) {
        Path saved = Paths.get(result.path("savedTo").asText());
        Path here = Paths.get("").toAbsolutePath();
        String line = "wrote " + (saved.startsWith(here) ? here.relativize(saved) : saved);
        if (result.has("startWarning")) line += " - " + result.path("startWarning").asText();
        return line;
    }

    private static JsonNode stripNulls(JsonNode n) {
        if (n.isObject()) {
            ObjectNode o = (ObjectNode) n;
            List<String> empty = new ArrayList<String>();
            o.fieldNames().forEachRemaining(f -> { if (o.get(f).isNull()) empty.add(f); else stripNulls(o.get(f)); });
            o.remove(empty);
        } else if (n.isArray()) {
            for (JsonNode c : n) stripNulls(c);
        }
        return n;
    }

    private ObjectNode locatorJson(Locator l) {
        ObjectNode n = mapper.valueToTree(l);
        List<String> empty = new ArrayList<String>();
        n.fieldNames().forEachRemaining(f -> { if (n.get(f).isNull()) empty.add(f); });
        n.remove(empty);
        return n;
    }

    /** One element, read without acting. A target that does not resolve is an answer, not a tool error. */
    public synchronized String read(JsonNode args) {
        return readNode(args).toString();
    }

    private ObjectNode readNode(JsonNode args) {
        String form = text(args, "form");
        if (form == null) form = "node";
        if (!Arrays.asList("node", "all", "geometry", "candidates", "menuItems").contains(form)) {
            throw new DriverException(Reason.BAD_INPUT, "unknown read form '" + form
                    + "' (known: node, all, geometry, candidates, menuItems)");
        }
        boolean menuItems = "menuItems".equals(form);
        Locator target = null;
        if (!menuItems) {
            JsonNode targetJson = args == null ? null : args.get("target");
            if (targetJson == null || !targetJson.isObject()) {
                throw new DriverException(Reason.BAD_INPUT, "read needs a 'target' object");
            }
            target = locator(targetJson);
        }
        List<String> path = null;
        if (menuItems) {
            JsonNode p = args == null ? null : args.get("path");
            if (p == null || !p.isArray()) {
                throw new DriverException(Reason.BAD_INPUT, "read form menuItems needs 'path'");
            }
            path = new ArrayList<String>();
            for (JsonNode n : p) path.add(n.asText());
        }
        ObjectNode r = mapper.createObjectNode();
        try {
            if ("node".equals(form)) {
                Node node = driver().read(target, integer(args, "maxRows"));
                r.put("found", true);
                r.set("node", mapper.valueToTree(node));
            } else if ("all".equals(form)) {
                List<Node> nodes = driver().readAll(target, integer(args, "maxRows"));
                r.put("found", true);
                r.set("nodes", mapper.valueToTree(nodes));
            } else if ("geometry".equals(form)) {
                r.put("found", true);
                r.set("geometry", mapper.valueToTree(driver().geometry(target)));
            } else if ("candidates".equals(form)) {
                r.set("candidates", mapper.valueToTree(driver().candidates(target)));
            } else {
                r.put("found", true);
                r.set("items", mapper.valueToTree(driver().menuItems(path)));
            }
        } catch (DriverException e) {
            if (e.reason().isToolError()) throw e;
            r.removeAll();
            r.put("found", false);
            r.put("reason", e.reason().name());
            r.put("message", e.getMessage());
            ArrayNode candidates = r.putArray("candidates");
            for (String d : e.details()) candidates.add(d);
        }
        return r;
    }

    /** The clipboard's text as {text}; null when it holds none. */
    public synchronized String readClipboard() {
        ObjectNode r = mapper.createObjectNode();
        r.put("text", driver().clipboardText());
        return r.toString();
    }

    /**
     * The showing file chooser as {found, folder, name, nameFocused, lookAndFeel}, waiting the driver's default;
     * found false with the reason when none shows.
     */
    public synchronized String readFileChooser() {
        return readFileChooser(null);
    }

    /** As {@link #readFileChooser()}, waiting {@code args.timeout} ms instead when given (0: one look). */
    public synchronized String readFileChooser(JsonNode args) {
        ObjectNode r = mapper.createObjectNode();
        try {
            FileChooserState s = driver().fileChooser(args != null && args.hasNonNull("timeout") ? Long.valueOf(args.get("timeout").asLong()) : null);
            r.put("found", true);
            r.put("folder", s.folder);
            r.put("name", s.name);
            r.put("nameFocused", s.nameFocused);
            r.put("lookAndFeel", s.lookAndFeel);
        } catch (DriverException e) {
            if (e.reason().isToolError()) throw e;
            r.put("found", false);
            r.put("reason", e.reason().name());
            r.put("message", e.getMessage());
        }
        return r.toString();
    }

    /**
     * A PNG of one element, or of the current window. Returned as MCP image content rather than text:
     * the body carries {@code _mcpContent}, which {@link UiMcpDispatcher} emits verbatim.
     */
    public synchronized String screenshot(JsonNode args) {
        JsonNode targetJson = args == null ? null : args.get("target");
        Locator target = targetJson != null && targetJson.isObject() ? locator(targetJson) : null;
        String window = text(args, "window");
        if (target == null && window != null) {
            target = new Locator();   // a whole window, as snapshot takes it
            target.window = window;
        }
        Shot shot = driver().screenshot(target, region(args), integer(args, "maxSize"), number(args, "scale"));

        ObjectNode r = mapper.createObjectNode();
        ArrayNode content = r.putArray("_mcpContent");
        ObjectNode image = content.addObject();
        image.put("type", "image");
        image.put("mimeType", "image/png");
        image.put("data", Base64.getEncoder().encodeToString(shot.png));
        ObjectNode note = content.addObject();
        note.put("type", "text");
        note.put("text", "{\"width\":" + shot.width + ",\"height\":" + shot.height
                + ",\"sourceWidth\":" + shot.sourceWidth + ",\"sourceHeight\":" + shot.sourceHeight
                + ",\"scaled\":" + shot.scaled + ",\"bytes\":" + shot.png.length + "}");
        return r.toString();
    }

    /** {x, y, w, h} as fractions 0..1, or null. All four are required together. */
    private double[] region(JsonNode args) {
        if (args == null || !args.hasNonNull("region")) return null;
        JsonNode r = args.get("region");
        if (!r.isObject()) throw new DriverException(Reason.BAD_INPUT, "'region' must be an object");
        String[] keys = {"x", "y", "w", "h"};
        double[] out = new double[4];
        for (int i = 0; i < keys.length; i++) {
            JsonNode v = r.get(keys[i]);
            if (v == null || !v.isNumber()) {
                throw new DriverException(Reason.BAD_INPUT, "'region' needs numeric x, y, w and h");
            }
            out[i] = v.asDouble();
        }
        if (out[2] <= 0 || out[3] <= 0) {
            throw new DriverException(Reason.BAD_INPUT, "'region' needs a positive w and h");
        }
        return out;
    }

    private Verbs driver() {
        Verbs verbs = host.verbs();
        if (verbs == null) throw new DriverException(Reason.CAPABILITY_GAP, "no UI driver is installed in this client");
        return verbs;
    }

    Locator locator(JsonNode json) {
        try {
            return mapper.treeToValue(json, Locator.class);
        } catch (JsonProcessingException e) {
            throw new DriverException(Reason.BAD_INPUT, "malformed target: " + e.getOriginalMessage());
        } catch (IllegalArgumentException e) {
            throw new DriverException(Reason.BAD_INPUT, "malformed target: " + e.getMessage());
        }
    }

    private static String text(JsonNode args, String field) {
        return args != null && args.hasNonNull(field) ? args.get(field).asText() : null;
    }

    private static Integer integer(JsonNode args, String field) {
        if (args == null || !args.hasNonNull(field)) return null;
        JsonNode v = args.get(field);
        if (!v.canConvertToInt()) throw new DriverException(Reason.BAD_INPUT, "'" + field + "' must be an integer");
        return v.asInt();
    }

    private static Double number(JsonNode args, String field) {
        if (args == null || !args.hasNonNull(field)) return null;
        JsonNode v = args.get(field);
        if (!v.isNumber()) throw new DriverException(Reason.BAD_INPUT, "'" + field + "' must be a number");
        return v.asDouble();
    }

    public synchronized String describe() {
        return describeNode().toString();
    }

    /**
     * {@link #describe()} cut to the named top-level fields (e.g. {@code sections}, {@code dialog}), so a
     * caller that needs one inventory does not pay for the menu paths too. An unknown name is BAD_INPUT,
     * naming the fields there are.
     */
    public synchronized String describe(List<String> fields) {
        ObjectNode all = describeNode();
        if (fields == null || fields.isEmpty()) return all.toString();
        ObjectNode r = mapper.createObjectNode();
        for (String f : fields) {
            if (!all.has(f)) {
                List<String> known = new ArrayList<String>();
                all.fieldNames().forEachRemaining(known::add);
                throw new DriverException(Reason.BAD_INPUT, "describe_ui has no field '" + f + "'; it has " + known);
            }
            r.set(f, all.get(f));
        }
        return r.toString();
    }

    private ObjectNode describeNode() {
        ObjectNode r = mapper.createObjectNode();
        r.put("activeTab", inventory.activeTabTitle());
        r.set("navTargets", mapper.valueToTree(inventory.navTargets()));
        // Every entry-point id is a navigate target: an id or name the app plugin knows.
        ArrayNode entryPointTargets = r.putArray("navTargetEntryPointIds");
        for (String id : inventory.entryPointIds()) entryPointTargets.add(id);
        r.set("toolbarActions", mapper.valueToTree(inventory.toolbarActions()));
        // Flat leaf paths, not the tree: smaller, and a menu verb wants a path anyway. Disabled leaves carry a
        // [disabled] marker.
        r.set("menuPaths", mapper.valueToTree(inventory.menuPaths()));
        r.set("navEntryPoints", mapper.valueToTree(inventory.navEntryPoints()));
        r.set("viewTabs", mapper.valueToTree(inventory.viewTabs()));
        // The flat registered-section inventory: includes a row for a section whose
        // content was never built, which the snapshot's tree walk cannot show at all. Same producer
        // as the snapshot's section nodes; any problems it recorded ride alongside, not swallowed.
        r.set("sections", mapper.valueToTree(inventory.viewSections()));
        r.set("sectionProblems", mapper.valueToTree(inventory.viewSectionProblems()));
        // A message or confirmation waiting for an answer: what it says and which buttons answer it.
        PendingDialog question = host.openDialog();
        if (question != null) r.set("dialog", mapper.valueToTree(question)); else r.putNull("dialog");
        return r;
    }

    /**
     * Runs one action through the same {@link FlowExecutor} replay uses, records it when it
     * passes, and reports it. Every step ({@code do_action}, {@code do_actions}, a verb tool, a {@code batch}
     * step) goes through here, so a batched step and a single step cannot behave differently.
     *
     * <p>A step that passes but has no Java form (a {@code loginAs} after the first step, a target that
     * needs a ref) is not recorded, and the result says why ({@code notRecorded}): recorded, it would make
     * every later {@code export_test} refuse, and nothing else ever empties the recording. So the
     * recording is always exportable, and such steps stay usable for exploring.
     */
    private ObjectNode runAndRecord(JsonNode actionJson, boolean record, RecordedStep.Author author) {
        Action action = action(actionJson);
        Flow flow = new Flow();
        flow.name = "step";
        flow.steps = new ArrayList<Action>();
        flow.steps.add(action);
        long started = System.currentTimeMillis();
        FlowResult result;
        Inspector.agentActing(true);
        try {
            result = executor.run(flow, host);
        } finally {
            Inspector.agentActing(false);
        }
        long elapsed = System.currentTimeMillis() - started;
        StepResult step = result.steps.isEmpty() ? null : result.steps.get(0);
        if (step != null && !step.passed && step.reason != null && Reason.valueOf(step.reason).isToolError()) {
            // "The harness can't do this" is a tool error, not a failed step.
            Reason reason = Reason.valueOf(step.reason);
            throw new DriverException(reason, DriverException.stripReason(step.message, reason),
                    step.details == null ? Collections.<String>emptyList() : step.details);
        }
        boolean passed = step != null && step.passed;
        ObjectNode r = mapper.createObjectNode();
        r.put("passed", passed);
        r.put("action", step != null ? step.action : action.describe());
        if (step != null && step.reason != null) r.put("reason", step.reason);
        r.put("message", step != null ? step.message : "no step executed");
        if (step != null && step.details != null) r.set("details", mapper.valueToTree(step.details));
        r.put("ms", elapsed);
        if (passed && record) {
            Action recordedAction = recordable(action, step.outcome);
            List<String> problems = exportProblems(recordedAction);
            if (problems.isEmpty()) {
                model.append(recordedAction, author);
                r.set("recorded", mapper.valueToTree(recordedAction));
            } else {
                r.put("notRecorded", "it ran, but export_test has no Java form for it (" + String.join("; ", problems)
                        + "); write it by hand into the exported test with the app plugin's test helpers or the Client API");
            }
        }
        if (step != null && step.pending != null) r.set("pending", mapper.valueToTree(step.pending));
        if (step != null && step.warning != null) r.put("warning", step.warning);
        if (step != null && step.realInput != null) r.set("realInput", mapper.valueToTree(step.realInput));
        if (step != null && step.activations != null) r.set("activations", mapper.valueToTree(step.activations));
        // A step that raised a message or confirmation returns at once; this is how the caller
        // learns what it says and which answers it offers, to choose the next step.
        PendingDialog dialog = host.openDialog();
        if (dialog != null) r.set("dialog", mapper.valueToTree(dialog));
        return r;
    }

    private Action action(JsonNode json) {
        try {
            return mapper.treeToValue(json, Action.class);
        } catch (InvalidTypeIdException e) {
            // An unknown step type, or none.
            throw FlowJson.unknownStepType(e);
        } catch (JsonProcessingException e) {
            throw new DriverException(Reason.BAD_INPUT, "malformed action: " + e.getOriginalMessage());
        } catch (IllegalArgumentException e) {
            throw new DriverException(Reason.BAD_INPUT, "malformed action: " + e.getMessage());
        }
    }

    /** Names the export check uses; valid, so every problem it finds belongs to the step checked. */
    private static final String CHECK_PACKAGE = "recorded";
    private static final String CHECK_CLASS = "RecordingCheckUiIT";
    private static final String CHECK_METHOD = "recorded";

    /**
     * What {@link JavaTestWriter} would refuse if {@code next} followed the steps recorded so far; empty
     * when it would write it. The recording itself is always exportable, so every problem is {@code next}'s.
     */
    private List<String> exportProblems(Action next) {
        List<Action> steps = model.actions();
        steps.add(next);
        try {
            JavaTestWriter.write(steps, CHECK_PACKAGE, CHECK_CLASS, CHECK_METHOD);
            return Collections.emptyList();
        } catch (ExportRefused e) {
            return e.problems();
        }
    }

    /**
     * The step as export_test records it: a ref becomes the recorder's locator,
     * {@code via:"api"} marks a direct Swing call, {@code fragile:true} a target that needed nth.
     */
    private Action recordable(Action action, VerbOutcome outcome) {
        if (!(action instanceof VerbAction) || outcome == null) return action;
        ObjectNode tree = mapper.valueToTree(action);
        tree.remove("via");
        tree.remove("fragile");
        boolean fragile = false;
        for (Map.Entry<String, Recorder.Recorded> e : outcome.recorded.entrySet()) {
            tree.set(e.getKey(), mapper.valueToTree(e.getValue().locator));
            fragile |= e.getValue().fragile;
        }
        if ("api".equals(outcome.via)) tree.put("via", "api");
        if (fragile) tree.put("fragile", true);
        return action(tree);
    }

    public synchronized String doAction(JsonNode actionJson) {
        return doAction(actionJson, true);
    }

    /** As {@link #doAction(JsonNode)}; with {@code record} false the step runs and nothing is recorded or reported as such. */
    public synchronized String doAction(JsonNode actionJson, boolean record) {
        return runAndRecord(actionJson, record, RecordedStep.Author.AGENT).toString();
    }

    private String doAction(JsonNode actionJson, boolean record, RecordedStep.Author author) {
        return runAndRecord(actionJson, record, author).toString();
    }

    /**
     * Runs several actions in ONE round trip, each through {@link #runAndRecord}, and reports every step that ran.
     * It stops at the first failure: after it the UI is no longer in the state the remaining steps expect.
     * A harness error ends it as a tool error naming the step.
     */
    public synchronized String doActions(JsonNode actionsJson) throws Exception {
        if (actionsJson == null || !actionsJson.isArray()) {
            throw new IllegalArgumentException("'actions' must be an array of flow actions");
        }
        if (actionsJson.size() == 0) {
            throw new IllegalArgumentException("'actions' must be a non-empty array");
        }
        ObjectNode r = mapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode steps = r.putArray("steps");
        boolean allPassed = true;
        int ran = 0;
        for (JsonNode node : actionsJson) {
            ObjectNode step;
            String label = (ran + 1) + "/" + actionsJson.size();
            StepClock clock = new StepClock();
            try {
                step = runAndRecord(node, true, RecordedStep.Author.AGENT);
            } catch (DriverException e) {
                clock.log("do_actions." + node.path("action").asText("?"), label, "error:" + ToolErrors.typeOf(e.reason()), node);
                throw new DriverException(e.reason(), "step " + (ran + 1) + " of " + actionsJson.size() + ": "
                        + DriverException.stripReason(e.getMessage(), e.reason()), e.details());
            }
            clock.log("do_actions." + node.path("action").asText("?"), label,
                    step.path("passed").asBoolean() ? "ok" : "failed", node);
            steps.add(step);
            ran++;
            if (!step.path("passed").asBoolean()) { allPassed = false; break; }
        }
        r.put("passed", allPassed);
        r.put("ran", ran);
        r.put("total", actionsJson.size());
        return r.toString();
    }

    /** One verb tool call: the arguments are the verb's fields; {@code snapshot:true} adds one after. */
    public synchronized String verb(String action, JsonNode args) {
        return verbStep(action, args).toString();
    }

    /**
     * The one path a verb takes, whether it came as its own tool call or as a {@code batch} step - so
     * the two cannot run, answer or record differently.
     */
    private ObjectNode verbStep(String action, JsonNode args) {
        ObjectNode step = args != null && args.isObject() ? ((ObjectNode) args).deepCopy() : mapper.createObjectNode();
        boolean withSnapshot = step.path("snapshot").asBoolean(false);
        step.remove("snapshot");
        step.put("action", action);
        ObjectNode result = runAndRecord(step, true, RecordedStep.Author.AGENT);
        if (withSnapshot) result.set("snapshot", mapper.valueToTree(driver().snapshot(null, null, null, null)));
        return result;
    }

    /** The step verbs {@code batch} takes besides the verb tools' names and the flow actions' names. */
    static final String READ_STEP = "read";

    /**
     * Several steps in ONE call, in order, stopping at the first that does not pass - the verb-tool
     * shaped sibling of {@link #doActions}. Each step is {@code {"verb": <name>, ...that tool's
     * arguments}}: a verb tool's name ({@code click}, {@code type}, {@code choose_file}, ...) runs
     * exactly as that tool would ({@link #verbStep}), {@code read} reads without recording, and any
     * other name is taken as a flow step type ({@code navigate}, {@code close}, {@code loginAs},
     * {@code screenshot}, ...) and runs as {@code do_actions} runs it; a name that is none is BAD_INPUT. So what is recorded, and what {@code export_test}
     * writes, is the same as issuing the steps one by one.
     *
     * <p>A step that fails ({@code passed:false}, or a {@code read} that finds nothing) ends the batch
     * and is the last one reported. A harness error (CAPABILITY_GAP, BAD_INPUT, BUSY) ends it as a tool
     * error naming the step; the steps before it ran and were recorded. {@code snapshot} ({@code true},
     * or the snapshot tool's arguments) adds one after the last step that ran, pass or fail.
     */
    public synchronized String batch(JsonNode args) {
        JsonNode steps = args == null ? null : args.get("steps");
        if (steps == null || !steps.isArray() || steps.size() == 0) {
            throw new DriverException(Reason.BAD_INPUT, "'steps' must be a non-empty array of {\"verb\": ..., ...} objects");
        }
        long started = System.nanoTime();
        ObjectNode r = mapper.createObjectNode();
        r.put("passed", true);
        ArrayNode results = mapper.createArrayNode();
        int ran = 0;
        boolean allPassed = true;
        for (JsonNode step : steps) {
            String label = (ran + 1) + "/" + steps.size();
            if (!step.isObject() || !step.hasNonNull("verb") || !step.get("verb").isTextual()) {
                throw new DriverException(Reason.BAD_INPUT, "step " + label + " needs a 'verb' string");
            }
            String verb = step.get("verb").asText();
            ObjectNode stepArgs = ((ObjectNode) step).deepCopy();
            stepArgs.remove("verb");
            StepClock clock = new StepClock();
            ObjectNode result;
            try {
                result = batchStep(verb, stepArgs);
            } catch (DriverException e) {
                clock.log("batch." + verb, label, "error:" + ToolErrors.typeOf(e.reason()), stepArgs);
                List<String> details = new ArrayList<String>(e.details());
                if (ran > 0) details.add("steps 1-" + ran + " passed and were recorded");
                throw new DriverException(e.reason(), "step " + (ran + 1) + " of " + steps.size() + " (" + verb + "): "
                        + DriverException.stripReason(e.getMessage(), e.reason()), details);
            }
            boolean passed = result.path("passed").asBoolean(false);
            clock.log("batch." + verb, label, passed ? "ok" : "failed", stepArgs);
            ObjectNode out = mapper.createObjectNode();
            out.put("verb", verb);
            out.setAll(result);
            results.add(out);
            ran++;
            if (!passed) {
                allPassed = false;
                break;
            }
        }
        r.put("passed", allPassed);
        r.put("ran", ran);
        r.put("total", steps.size());
        r.put("ms", (System.nanoTime() - started) / 1_000_000L);
        r.set("steps", results);
        JsonNode snapshot = args.get("snapshot");
        if (snapshot != null && (snapshot.isObject() || snapshot.asBoolean(false))) {
            r.set("snapshot", snapshotNode(snapshot.isObject() ? snapshot : null));
        }
        return r.toString();
    }

    private ObjectNode batchStep(String verb, ObjectNode args) {
        if (READ_STEP.equals(verb)) {
            long started = System.currentTimeMillis();
            ObjectNode read = readNode(args);
            ObjectNode r = mapper.createObjectNode();
            r.put("passed", read.path("found").asBoolean(false));
            r.setAll(read);
            r.put("ms", System.currentTimeMillis() - started);
            return r;
        }
        Class<? extends VerbAction> tool = VerbSchemas.TOOLS.get(verb);
        if (tool != null) return verbStep(VerbSchemas.actionName(tool), args);
        if (args.has("action")) {
            throw new DriverException(Reason.BAD_INPUT, "a batch step names its action in 'verb', not 'action'");
        }
        args.put("action", verb);
        return runAndRecord(args, true, RecordedStep.Author.AGENT);
    }

    /** Times one batched step against the EDT clock and logs its {@link UiMcpTiming#stepLine}. */
    private static final class StepClock {
        private final long started = System.nanoTime();
        private final EdtClock.Reading edt = EdtClock.read();

        void log(String tool, String step, String status, JsonNode args) {
            try {
                UiMcpTiming.log(UiMcpTiming.stepLine(tool, step, status, (System.nanoTime() - started) / 1_000_000L,
                        EdtClock.read().since(edt), UiMcpTiming.target(tool, args)));
            } catch (RuntimeException ignored) {
                // measuring must never cost the caller its answer
            }
        }
    }

    /** Where exported tests are written; the module's test sources by default. Override with -D. */
    static final String TESTS_DIR_PROP = "swingui.testsDir";
    static final String DEFAULT_TESTS_DIR = "src/test/java";

    /**
     * The module's test sources seen from {@code workingDir}: {@code <moduleDir>/src/test/java} when
     * {@code moduleDir} is non-null and exists (the client started in the repo root), else
     * {@code src/test/java} of the working directory (started in the module).
     */
    static Path testsDir(Path workingDir, String moduleDir) {
        if (moduleDir != null) {
            Path fromRoot = workingDir.resolve(moduleDir).resolve(DEFAULT_TESTS_DIR);
            if (Files.isDirectory(fromRoot)) return fromRoot;
        }
        return workingDir.resolve(DEFAULT_TESTS_DIR);
    }

    /**
     * Writes the steps recorded since the last {@link #startRecording} as a JUnit {@code @UiTest} class (via
     * {@link JavaTestWriter}) at {@code <testsDir>/<package>/<class>.java}, and returns a JSON
     * summary ({@code savedTo}, {@code steps}, {@code fragile}, {@code source}, and {@code warning} /
     * {@code startWarning} when they apply). Refuses (and writes nothing) for a recording that did not begin at a
     * reset, an empty recording or a bad class/method/package name - {@link JavaTestWriter#write}
     * collects every such problem into one {@link ExportRefused} - and refuses (naming the path) when the
     * file already exists, so a hand-edited test is never overwritten. A step with no Java form never
     * reaches the recording ({@link #runAndRecord}), and a refusal leaves the recording as it was, so the
     * export can be retried with other names. {@code clear} empties the recording after a write; the next one
     * needs a reset first.
     */
    public synchronized String exportTest(String className, String method, String pkg, boolean clear) throws Exception {
        requireResetStart();
        String methodName = method == null || method.isEmpty() ? "recorded" : method;
        ExportDefaults defaults = exportDefaults.get();
        String packageName = pkg == null || pkg.isEmpty() ? defaults.packageName : pkg;
        String source = JavaTestWriter.write(model.actions(), packageName, className, methodName);

        String configured = System.getProperty(TESTS_DIR_PROP);
        boolean genericLocation = defaults == Plugins.GENERIC_EXPORT && (pkg == null || pkg.isEmpty()) && configured == null;
        Path root = configured != null ? Paths.get(configured) : testsDir(Paths.get(""), defaults.moduleDir);
        Path file = root.resolve(packageName.replace('.', '/')).resolve(className + ".java");
        if (Files.exists(file)) {
            throw new IllegalArgumentException(file.toAbsolutePath().normalize() + " already exists; edit it instead");
        }
        Files.createDirectories(file.getParent());
        Files.write(file, source.getBytes(StandardCharsets.UTF_8));

        int steps = model.size();
        int fragile = fragileSteps();
        String startWarning = definedStart ? null : startWarning(model.actions());
        if (clear) {
            model.clear();
            recordingFromReset = false;
            forgetLoadedFlow();
        }

        ObjectNode result = mapper.createObjectNode();
        result.put("savedTo", file.toAbsolutePath().normalize().toString());
        result.put("steps", steps);
        result.put("fragile", fragile);
        if (genericLocation) {
            result.put("warning", "no app plugin is ready, so the file went to the generic location "
                    + "(src/test/java/" + packageName.replace('.', '/') + " under the working directory); name a package or set -D"
                    + TESTS_DIR_PROP + " to place it");
        }
        if (startWarning != null) result.put("startWarning", startWarning);
        result.put("source", source);
        return result.toString();
    }

    private void requireResetStart() {
        if (!recordingFromReset) {
            throw new IllegalArgumentException("the recording did not begin at a session reset, where the test will "
                    + "start: reset (session op reset), get to the starting screen, redo the steps, then export");
        }
    }

    /** The recording with ids and authors, for tests. */
    synchronized List<RecordedStep> recordedSteps() {
        return model.steps();
    }

    private int fragileSteps() {
        int fragile = 0;
        for (Action a : model.actions()) {
            if (a instanceof VerbAction && Boolean.TRUE.equals(((VerbAction) a).fragile)) fragile++;
        }
        return fragile;
    }

    /**
     * Writes the steps recorded since the last {@link #startRecording} as a flow file {@code <folder>/<name>.json}
     * ({@link FlowFiles}) and returns a JSON summary ({@code savedTo}, {@code steps}, {@code fragile},
     * {@code overwrote}, {@code startWarning} when it applies, {@code flow}). The refusals are export_test's (no reset
     * start; an empty recording and a bad name together, in one {@link ExportRefused}), plus a folder that cannot be
     * written ({@code FlowFolderNotWritable}); an existing file is refused unless it is the flow the recording came
     * from, which keeps its own {@code name} and, when no {@code description} is given, its description. A refusal
     * leaves the recording as it was. {@code clear} empties the recording; otherwise the written file becomes the one
     * a further export saves over.
     */
    public synchronized String exportFlow(String name, String dir, String description, boolean clear) throws Exception {
        requireResetStart();
        List<String> problems = new ArrayList<String>();
        if (model.size() == 0) problems.add("nothing was recorded");
        try {
            FlowFiles.checkName(name);
        } catch (DriverException e) {
            problems.add(e.getMessage());
        }
        if (!problems.isEmpty()) throw new ExportRefused(problems);

        Path folder = dir == null || dir.trim().isEmpty() ? flowsDir
                : Paths.get("").toAbsolutePath().resolve(dir.trim()).normalize();
        folder = FlowFiles.writableDir(folder);
        Path file = FlowFiles.file(folder, name).toAbsolutePath().normalize();
        boolean exists = Files.exists(file);
        if (exists && !file.equals(loadedFlow)) {
            throw new IllegalArgumentException(file + " already exists; choose another name (only the flow the "
                    + "recording came from is saved over)");
        }
        // saved over the flow the recording came from: its own name and, unless a new one is given, its description stay
        Flow flow = new Flow();
        flow.name = exists && loadedName != null ? loadedName : FlowFiles.checkName(name);
        flow.description = description != null && !description.trim().isEmpty() ? description.trim()
                : exists ? loadedDescription : null;
        flow.start = FlowFiles.START_RESET;
        flow.steps = model.actions();
        flow.version = FlowFiles.versionFor(flow);
        String text = FlowFiles.write(flow);
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));

        String startWarning = definedStart ? null : startWarning(model.actions());
        ObjectNode result = mapper.createObjectNode();
        result.put("savedTo", file.toString());
        result.put("steps", model.size());
        result.put("fragile", fragileSteps());
        result.put("overwrote", exists);
        if (startWarning != null) result.put("startWarning", startWarning);
        result.set("flow", mapper.readTree(text));
        if (clear) {
            model.clear();
            recordingFromReset = false;
            forgetLoadedFlow();
        } else {
            loadedFlow = file;
            loadedName = flow.name;
            loadedDescription = flow.description;
        }
        return result.toString();
    }

    /**
     * Runs a flow file on the live app and reports it ({@code passed}, {@code flow}, {@code file}, {@code ran},
     * {@code total}, {@code ms}, {@code steps}, {@code report}). Exactly one of {@code name} (in the flows folder),
     * {@code path} or {@code flow} (the file's JSON inline). It begins with the app reset, runs every step as
     * {@code do_action} would but records nothing, and stops at the first failing step, which carries its diagnosis
     * (picture under {@code evidence}). A flow that fails is a result; one that cannot start (bad file, no reset) is an
     * error. Refused (BUSY) while a person records, answers or replays in the palette.
     */
    public synchronized String runFlow(JsonNode args) {
        JsonNode name = args == null ? null : args.get("name");
        JsonNode path = args == null ? null : args.get("path");
        JsonNode inline = args == null ? null : args.get("flow");
        int given = (name != null && !name.isNull() ? 1 : 0) + (path != null && !path.isNull() ? 1 : 0)
                + (inline != null && !inline.isNull() ? 1 : 0);
        if (given != 1) {
            throw new DriverException(Reason.BAD_INPUT, "run_flow takes one of 'name', 'path' or 'flow'");
        }
        Flow flow;
        Path file = null;
        if (name != null && !name.isNull()) {
            file = FlowFiles.file(flowsDir, name.asText()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(file)) {
                List<String> there = new ArrayList<String>();
                for (Path p : FlowFiles.list(flowsDir)) {
                    String base = p.getFileName().toString();
                    there.add(base.endsWith(FlowFiles.SUFFIX) ? base.substring(0, base.length() - FlowFiles.SUFFIX.length()) : base);
                }
                throw new DriverException(Reason.BAD_INPUT, "no flow '" + name.asText() + "' in " + flowsDir + "; the flows there: " + there);
            }
            flow = FlowFiles.read(file);
        } else if (path != null && !path.isNull()) {
            file = Paths.get("").toAbsolutePath().resolve(path.asText()).normalize();
            flow = FlowFiles.read(file);
        } else {
            // the file's JSON object, or that JSON as text (agents often send it quoted)
            String text = inline.isObject() ? inline.toString() : inline.isTextual() ? inline.textValue() : null;
            if (text == null) throw new DriverException(Reason.BAD_INPUT, "'flow' must be a flow file's JSON object");
            flow = FlowFiles.parse(text, "the 'flow' argument");
            if (flow.name == null || flow.name.isEmpty()) flow.name = "flow";
        }
        String busy = personBusy.get();
        if (busy != null) throw new DriverException(Reason.BUSY, "busy: " + busy);
        if (host.verbs() == null) throw new DriverException(Reason.CAPABILITY_GAP, "no UI driver is installed: nothing to run the flow on");
        JsonNode ev = args.get("evidence");
        Path evidence = ev != null && ev.isTextual() && !ev.asText().trim().isEmpty()
                ? Paths.get("").toAbsolutePath().resolve(ev.asText().trim()).normalize()
                : ScreenshotVerb.screenshotDir(Paths.get("")).resolve("failures");

        long started = System.nanoTime();
        FlowResult result;
        // the reset is the agent's too, as in the palette's own runs: the person's recorder ignores it
        Inspector.agentActing(true);
        try {
            appReset.run();
            result = new FlowExecutor().diagnoseFailures(new FailureDiagnostics(evidence)).run(flow, host);
        } finally {
            Inspector.agentActing(false);
        }

        ObjectNode r = mapper.createObjectNode();
        r.put("passed", result.passed());
        r.put("flow", flow.name);
        if (file != null) r.put("file", file.toString());
        r.put("ran", result.steps.size());
        r.put("total", flow.steps.size());
        r.put("ms", (System.nanoTime() - started) / 1_000_000L);
        ArrayNode steps = r.putArray("steps");
        for (StepResult s : result.steps) steps.add(stepJson(s));
        r.put("report", FlowReport.format(flow, result));
        return r.toString();
    }

    private ObjectNode stepJson(StepResult s) {
        ObjectNode o = mapper.createObjectNode();
        o.put("index", s.index);
        o.put("action", s.action);
        o.put("passed", s.passed);
        o.put("ms", s.ms);
        if (s.message != null && !s.message.isEmpty()) o.put("message", s.message);
        if (s.reason != null) o.put("reason", s.reason);
        if (s.details != null && !s.details.isEmpty()) {
            ArrayNode a = o.putArray("details");
            for (String d : s.details) a.add(d);
        }
        if (s.warning != null) o.put("warning", s.warning);
        StepDiagnostics d = s.diagnostics;
        if (d != null) {
            ObjectNode dj = o.putObject("diagnostics");
            ArrayNode matches = dj.putArray("matches");
            for (StepDiagnostics.Match m : d.matches) {
                ObjectNode mj = matches.addObject();
                mj.put("field", m.field);
                mj.put("locator", m.locator);
                if (m.candidates == null) mj.putNull("candidates");
                else {
                    ArrayNode c = mj.putArray("candidates");
                    for (String x : m.candidates) c.add(x);
                }
            }
            if (d.scope != null) dj.put("scope", d.scope);
            ArrayNode tree = dj.putArray("tree");
            for (String line : d.tree) tree.add(line);
            if (d.screenshot != null) dj.put("screenshot", d.screenshot);
            ArrayNode problems = dj.putArray("problems");
            for (String p : d.problems) problems.add(p);
        }
        return o;
    }

    /**
     * Makes the recording the steps of a flow file, as if they had just been run after a reset: every step needs a Java
     * form (the recording stays exportable as a test), else this refuses and changes nothing. A broken file fails
     * with {@link FlowFiles}' message. Returns {@code {loaded, name, description, steps}}.
     */
    public synchronized String loadFlow(Path file) {
        Flow flow = FlowFiles.read(file);
        try {
            JavaTestWriter.write(new ArrayList<Action>(flow.steps), CHECK_PACKAGE, CHECK_CLASS, CHECK_METHOD);
        } catch (ExportRefused e) {
            throw new IllegalArgumentException(flow.name + " cannot be loaded into the recording: "
                    + String.join("; ", e.problems()) + "; run it with run_flow or the JUnit runner instead");
        }
        Path abs = file.toAbsolutePath().normalize();
        model.load(flow.steps, RecordedStep.Author.FLOW);
        recordingFromReset = true;
        definedStart = appReset.defined();
        loadedFlow = abs;
        loadedName = flow.name;
        loadedDescription = flow.description;
        ObjectNode result = mapper.createObjectNode();
        result.put("loaded", abs.toString());
        result.put("name", flow.name);
        if (flow.description == null) result.putNull("description"); else result.put("description", flow.description);
        result.put("steps", flow.steps.size());
        return result.toString();
    }

    /**
     * The package of the app's main class, from the JVM's start command ({@code sun.java.command}: a main class, or a
     * jar whose manifest names one), where a person's export goes first when no app plugin names a package; the
     * generic package when there is none.
     */
    static String mainPackage(String javaCommand) {
        String generic = Plugins.GENERIC_EXPORT.packageName;
        if (javaCommand == null || javaCommand.trim().isEmpty()) return generic;
        String first = javaCommand.trim().split("\\s+")[0];
        String main = first;
        if (first.endsWith(".jar")) {
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(first)) {
                java.util.jar.Manifest m = jar.getManifest();
                main = m == null ? null : m.getMainAttributes().getValue(java.util.jar.Attributes.Name.MAIN_CLASS);
            } catch (IOException e) {
                main = null;
            }
        }
        if (main == null || main.lastIndexOf('.') < 1) return generic;
        return main.substring(0, main.lastIndexOf('.'));
    }

    /**
     * Starts a fresh recording at a session reset. {@code definedStart}: an app plugin reset the app to a defined
     * state; otherwise only dialogs were closed and the app may be on any screen.
     */
    public synchronized void startRecording(boolean definedStart) {
        model.clear();
        recordingFromReset = true;
        this.definedStart = definedStart;
        forgetLoadedFlow();
    }

    private void forgetLoadedFlow() {
        loadedFlow = null;
        loadedName = null;
        loadedDescription = null;
    }

    /**
     * Without a defined start state the test must get to its starting screen from anywhere: null when its first step
     * (after a leading loginAs) is navigate, menu or press, else why that step may not be there.
     */
    static String startWarning(List<Action> steps) {
        for (Action a : steps) {
            if (a instanceof LoginAsAction) continue;
            if (a instanceof NavigateAction || a instanceof MenuVerb || a instanceof PressVerb) return null;
            return "the test begins with '" + a.describe() + "', which works only on the screen the app happens to "
                    + "show after a reset (no app plugin: a reset only closes dialogs); begin with a menu, a key "
                    + "shortcut or navigate so the test gets to its own starting screen";
        }
        return null;
    }
}
