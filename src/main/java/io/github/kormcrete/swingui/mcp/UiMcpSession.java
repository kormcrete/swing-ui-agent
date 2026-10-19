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
import io.github.kormcrete.swingui.driver.model.RowChoice;
import io.github.kormcrete.swingui.flow.Action;
import io.github.kormcrete.swingui.flow.DoAction;
import io.github.kormcrete.swingui.flow.StepNumbers;
import io.github.kormcrete.swingui.flow.Flow;
import io.github.kormcrete.swingui.flow.FlowExecutor;
import io.github.kormcrete.swingui.flow.RecordedTestFiles;
import io.github.kormcrete.swingui.flow.RecordedTestFolderNotWritable;
import io.github.kormcrete.swingui.flow.FailureDiagnostics;
import io.github.kormcrete.swingui.flow.FlowJson;
import io.github.kormcrete.swingui.flow.FlowReport;
import io.github.kormcrete.swingui.flow.StepDiagnostics;
import io.github.kormcrete.swingui.flow.verb.ScreenshotVerb;
import io.github.kormcrete.swingui.flow.FlowResult;
import io.github.kormcrete.swingui.flow.LoginAsAction;
import io.github.kormcrete.swingui.flow.NavigateAction;
import io.github.kormcrete.swingui.flow.StepResult;
import io.github.kormcrete.swingui.agent.PluginSlot;
import io.github.kormcrete.swingui.agent.Plugins;
import io.github.kormcrete.swingui.spi.DomainStep;
import io.github.kormcrete.swingui.spi.ExportDefaults;
import io.github.kormcrete.swingui.spi.StepHost;
import io.github.kormcrete.swingui.flow.verb.MenuVerb;
import io.github.kormcrete.swingui.flow.verb.PressVerb;
import io.github.kormcrete.swingui.flow.verb.VerbAction;
import io.github.kormcrete.swingui.spi.UiInventory;
import io.github.kormcrete.swingui.vocab.Matcher;
import io.github.kormcrete.swingui.vocab.Pattern;
import io.github.kormcrete.swingui.vocab.PatternCompiler;
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
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.function.Supplier;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs steps against the live client's {@link StepHost}, reports its {@link UiInventory} for
 * {@code describe_ui}, and records the steps that succeed and can be exported, for {@code export_recorded_test} to
 * write as a recorded test and {@code export_test} as a JUnit {@code @UiTest} class (a do step whose domain step has no
 * Java form is recorded too; only {@code export_test} refuses it).
 */
public class UiMcpSession {

    private final StepHost host;
    /** The host's plugins, when it is them (the agent's session): their vocabulary records, writes and checks do steps. */
    private final Plugins plugins;
    /** How a do step is written in Java: through {@link #plugins}' vocabulary, else refused. */
    private final JavaTestWriter.DoForms doForms;
    /**
     * {@link #doForms} for what the recording may hold: a do step with no Java form is kept (left out of the Java check),
     * as a recording is exported as a recorded test too. Only {@code export_test} uses {@link #doForms} itself.
     */
    private final JavaTestWriter.DoForms keptForms;
    private final UiInventory inventory;
    private final ObjectMapper mapper;
    private final FlowExecutor executor = new FlowExecutor();
    private final RecordingModel model = new RecordingModel();
    /** Folds the recording's raw steps into the vocabulary's domain steps, off the EDT and this lock ({@link #scheduleFolding}). */
    private final Folding folding = new Folding(this, this::applyFolds);
    /** Folding has looked at every record up to this id; a later raw step is fresh. */
    private int foldedUpTo;
    /** What folding noted where a run ends (by that step's record id): a tie, a suggestion, a run it could not decide. */
    private final Map<Integer, FoldNote> foldNotes = new HashMap<Integer, FoldNote>();
    /** What the steps at the end could become, as folding last found it; null when nothing. Shown while its record is last. */
    private FoldHint foldHint;
    /** The recording began at a reset of the app (or was shown to by a passing replay from it); {@code list} says so. */
    private boolean recordingFromReset;
    /**
     * The recording began fresh - a session reset, a recording start, the person's ● Record, a load, or a passing replay
     * or redo from the reset - where an exported test starts too; false after a clearing export until one of those.
     * export_test and export_recorded_test refuse it otherwise.
     */
    private boolean freshStart;
    /** That reset was an app plugin's, to a defined start state, not only dialogs closed. */
    private boolean definedStart;

    /**
     * The start of a recorded test: what {@code session reset} does to the app - dialogs closed, with an app plugin its reset -
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
    private Path recordedDir = RecordedTestFiles.dir(null, System.getProperty(RecordedTestFiles.DIR_PROP), Paths.get(""));
    /** The folder was chosen (agent option or system property), not the default. */
    private boolean recordedDirExplicit;
    private volatile boolean recordedReadOnly;
    /** The recorded test file the recording came from (loaded, or exported without clear): the one file a save may replace. */
    private Path loadedTest;
    /** The {@code name} and {@code description} written in {@link #loadedTest}: a save over it without them keeps them. */
    private String loadedName;
    private String loadedDescription;
    /** The folder the person last used in the palette, offered when no folder was set explicitly. */
    private FolderMemory folderMemory = FolderMemory.preferences(Paths.get("").toAbsolutePath());

    /**
     * Why a person is busy in the palette (recording, answering, replaying) or a recording runs, or null; a seam for
     * tests.
     */
    Supplier<String> personBusy = () -> {
        String person = Inspector.engagedReason();
        return person != null ? person : recordingReason();
    };

    /**
     * Why the person's palette replays or runs steps now (it addresses them by position), or null; a seam for tests. No
     * fold is made meanwhile: it would move the steps under the replay.
     */
    Supplier<String> paletteReplaying = Inspector::drivingReason;

    /**
     * The recording as {@code recording list} answers it and the palette's steps list shows it, rebuilt after every
     * change; read without the session's lock.
     */
    private static final class Published {
        final String json;
        final boolean recording;
        /** Why a running explicit recording blocks a reset, a test run and another start, or null. */
        final String reason;
        /** The steps as the palette lists them. */
        final List<Inspector.StepLine> lines;
        /** What undo would take back, or null. */
        final String undoLabel;
        /** What the steps at the end could become ({@link Inspector.Sink#foldHint}), or null. */
        final String hint;

        Published(String json, boolean recording, String reason, List<Inspector.StepLine> lines, String undoLabel,
                String hint) {
            this.json = json;
            this.recording = recording;
            this.reason = reason;
            this.lines = lines;
            this.undoLabel = undoLabel;
            this.hint = hint;
        }
    }

    private volatile Published published =
            new Published("{}", false, null, Collections.<Inspector.StepLine>emptyList(), null, null);

    /**
     * Who shows the recording besides the agent: the person's palette. Every call comes under the session's lock and
     * must return at once, never waiting for the EDT. Only {@link #changed} is heard with the palette off.
     */
    interface RecordingView {
        /**
         * The recording changed; {@code notice} is the agent's edit as the person should read it ("the agent deleted
         * step 7"), null for a step recorded and for the person's own edits.
         */
        void changed(String notice);

        /** The agent started a shared recording ({@code recording start}, or a redo that records on). */
        default void agentStarted() { }

        /** The agent stopped a shared recording. */
        default void agentStopped() { }

        /** The agent loaded the recorded test {@code name}, {@code steps} steps long, as the recording. */
        default void agentLoaded(String name, int steps) { }

        /** A fresh recording nobody shares replaced the one shown: a session reset's, or none after a clearing export. */
        default void replaced() { }

        /** The agent replays: what it does now ({@code reason}, again for each step); null when it ended. */
        default void agentReplaying(String reason) { }

        /** The agent's replay ran step {@code id}: passed or failed. */
        default void replayed(int id, boolean ok) { }

        /**
         * Folding recognised {@code steps} recorded steps as the domain step {@code sentence}, now the record {@code id}
         * (the last fold of one look): the palette says so, with an Undo ({@link Inspector.Sink#undoFold}).
         */
        default void recognised(int id, String sentence, int steps) { }
    }

    /** The palette's view: each call reaches the person's palette through {@code invokeLater}. */
    static final RecordingView PALETTE = new RecordingView() {
        @Override public void changed(String notice) { Inspector.recordingChanged(notice); }
        @Override public void agentStarted() { Inspector.agentStarted(); }
        @Override public void agentStopped() { Inspector.agentStopped(); }
        @Override public void agentLoaded(String name, int steps) { Inspector.agentLoaded(name, steps); }
        @Override public void replaced() { Inspector.recordingReplaced(); }
        @Override public void agentReplaying(String reason) { Inspector.agentReplaying(reason); }
        @Override public void replayed(int id, boolean ok) { Inspector.agentReplayed(id, ok); }
        @Override public void recognised(int id, String sentence, int steps) { Inspector.foldRecognised(id, sentence, steps); }
    };

    private volatile RecordingView view = notice -> { };
    /** A list with {@code wait} waits here, never on the session: the session must stay free to record. */
    private final Object stopSignal = new Object();

    private final Supplier<ExportDefaults> exportDefaults;
    /** The palette is on (the agent's default, with a screen): a person may record, check and answer in the app. */
    private volatile boolean palette;

    public UiMcpSession(StepHost host, UiInventory inventory, ObjectMapper mapper) {
        this(host, inventory, mapper, () -> Plugins.GENERIC_EXPORT);
    }

    public UiMcpSession(StepHost host, UiInventory inventory, ObjectMapper mapper, Supplier<ExportDefaults> exportDefaults) {
        this.host = host;
        this.plugins = host instanceof Plugins ? (Plugins) host : null;
        DoStepForms forms = plugins != null ? new DoStepForms(plugins) : null;
        this.doForms = forms != null ? forms : JavaTestWriter.DoForms.NONE;
        this.keptForms = forms != null ? forms.keeping() : JavaTestWriter.DoForms.NONE;
        this.inventory = inventory;
        this.mapper = FlowJson.registerDriverTypes(mapper);
        this.exportDefaults = exportDefaults;
        synchronized (this) {
            publish();
        }
    }

    /** How a recorded test starts (see {@link AppReset}); the agent sets the session's own reset. */
    public void appReset(AppReset reset) {
        this.appReset = reset;
    }

    public AppReset appReset() {
        return appReset;
    }

    /** Where recorded tests are saved and loaded from; {@code explicit}: set by the agent option or the system property. */
    public void recordedDir(Path dir, boolean explicit) {
        this.recordedDir = dir;
        this.recordedDirExplicit = explicit;
    }

    public Path recordedDir() {
        return recordedDir;
    }

    /** The recorded tests folder is read-only: nothing is exported or saved into it ({@code recordedReadOnly=on}). */
    public void recordedReadOnly(boolean readOnly) {
        this.recordedReadOnly = readOnly;
    }

    public boolean recordedReadOnly() {
        return recordedReadOnly;
    }

    public boolean recordedDirExplicit() {
        return recordedDirExplicit;
    }

    /** Where the palette remembers the last folder the person used (tests: {@link FolderMemory#inMemory}). */
    void folderMemory(FolderMemory memory) {
        this.folderMemory = memory;
    }

    /** The recorded test file the recording came from; null when it came from no file. */
    public synchronized Path loadedTest() {
        return loadedTest;
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
        if (!palette) return;
        view = PALETTE;
        Inspector.of(driver).enable(humanSink());
    }

    /** Who hears of every change to the recording; a seam for tests. */
    void recordingView(RecordingView view) {
        this.view = view;
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
     * check mark, or says none), {@code pick} (the person clicks what they mean), {@code hover} on/off,
     * {@code clear}. The recording itself is the {@link #recording} tool's. Only while the palette is on. Not synchronized: it waits for a person, and must not hold the
     * session meanwhile.
     */
    public String inspect(JsonNode args) {
        String movedOp = text(args, "op");
        if ("recording".equals(movedOp) || "steps".equals(movedOp)) {
            throw new DriverException(Reason.BAD_INPUT, "inspect '" + movedOp + "' moved to the recording tool: "
                    + "recording {\"op\":\"list\"} returns the steps; with \"wait\" (ms) it waits for the person's Done");
        }
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
        if (op == null) throw new DriverException(Reason.BAD_INPUT, "inspect needs 'op': highlight, pick, hover or clear");
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
            case "hover":
                inspector.hover(args.path("on").asBoolean(true));
                r.put("outcome", args.path("on").asBoolean(true) ? "hoverOn" : "hoverOff");
                return r.toString();
            case "clear":
                inspector.clear();
                r.put("outcome", "cleared");
                return r.toString();
            default:
                throw new DriverException(Reason.BAD_INPUT, "unknown inspect op '" + op + "' (highlight, pick, hover, clear)");
        }
        r.put("outcome", answer.outcome);
        if (answer.chosen != null) r.put("chosen", answer.chosen);
        if (answer.target != null) {
            r.put("element", answer.target.element);
            r.set("locator", locatorJson(answer.target.locator));
            if (answer.target.fragile) r.put("fragile", true);
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
            public Inspector.Result begin() {
                if (agentReplaying != null) return agentReplayingResult(); // read before the monitor: the agent's replay holds it
                synchronized (UiMcpSession.this) {
                    // a recording someone started on purpose is not wiped: the palette shows that one instead
                    if (model.state() == RecordingModel.State.RECORDING && model.explicit()) {
                        return new Inspector.Result(false, model.startedBy() == RecordedStep.Author.AGENT
                                ? Inspector.AGENTS_RECORDING_RUNS : "a recording is running: Done ends it");
                    }
                    // the person's ● Record: a fresh recording from the screen they are on; nothing in the app is reset
                    startRecording(false, false, RecordedStep.Author.PERSON, true);
                    return new Inspector.Result(true, "recording");
                }
            }

            @Override
            public void end() {
                synchronized (UiMcpSession.this) {
                    if (model.state() != RecordingModel.State.RECORDING) return;
                    model.stop();
                    publish();
                }
            }

            @Override
            public Inspector.Result done(Map<String, Object> step) {
                Map<String, Object> plain = new LinkedHashMap<String, Object>(step);
                Object viewIndex = plain.remove(Inspector.ROW_INDEX);
                JsonNode seen = seen(plain.remove(Inspector.SEEN));
                JsonNode json = stripNulls(mapper.valueToTree(plain));
                try {
                    synchronized (UiMcpSession.this) {
                        if (model.state() == RecordingModel.State.STOPPED) {
                            return new Inspector.Result(false, "the recording is stopped");
                        }
                        Action action = action(json);
                        List<String> problems = exportProblems(action);
                        if (!problems.isEmpty()) {
                            String line = action.describe() + " - " + String.join("; ", problems);
                            model.refused(line);
                            publish();
                            return new Inspector.Result(false, line);
                        }
                        model.append(action, RecordedStep.Author.PERSON, personRow(json, viewIndex), seen);
                        publish();
                        scheduleFolding();
                        return new Inspector.Result(true, action.describe());
                    }
                } catch (RuntimeException e) {
                    return new Inspector.Result(false, json + " - " + e.getMessage());
                }
            }

            @Override
            public Inspector.Result refused(String line) {
                synchronized (UiMcpSession.this) {
                    if (model.state() == RecordingModel.State.STOPPED) return new Inspector.Result(false, "the recording is stopped");
                    model.refused(line);
                    publish();
                    return new Inspector.Result(false, line);
                }
            }

            @Override
            public List<Inspector.StepLine> steps() {
                // the published copy: the steps panel must list during the agent's replay, which holds the session
                return published.lines;
            }

            @Override
            public String undoLabel() {
                return published.undoLabel;
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
                if (agentReplaying != null) return agentReplayingResult();
                JsonNode json;
                int id;
                synchronized (UiMcpSession.this) {
                    if (index < 0 || index >= model.size()) {
                        return new Inspector.Result(false, "no step " + (index + 1));
                    }
                    RecordedStep step = model.at(index + 1);
                    id = step.id;
                    json = stripNulls(mapper.valueToTree(step.action));
                    model.replayStarted();   // nothing recorded before it folds with what follows
                }
                try {
                    StepRun run = replayStep(json);
                    JsonNode result = run.result;
                    boolean passed = result.path("passed").asBoolean(false);
                    String line = result.path("action").asText(json.toString());
                    if (!passed) line += " - " + result.path("message").asText("");
                    // a step the agent inserted or edited is proven by passing a replay: its edit mark goes, and the
                    // table row it names is known both ways
                    if (passed) {
                        synchronized (UiMcpSession.this) {
                            boolean learned = model.rowKnown(id, run.row);
                            if (model.markReplayed(id) || learned) publish();
                        }
                    }
                    return new Inspector.Result(passed, line);
                } catch (Exception e) {
                    return new Inspector.Result(false, e.getMessage());
                }
            }

            @Override
            public Inspector.Result cutFrom(int id) {
                if (agentReplaying != null) return agentReplayingResult(); // read before the monitor: the agent's replay holds it
                synchronized (UiMcpSession.this) {
                    StepAddress at = model.addressOf(id);
                    if (at == null) return gone("nothing cut");
                    RecordingModel scratch = model.copy();
                    scratch.cutFromStep(at.step, RecordedStep.Author.PERSON);
                    Inspector.Result refused = refusedEdit(scratch.actions());
                    if (refused != null) return refused;
                    String did = model.cutFromStep(at.step, RecordedStep.Author.PERSON);
                    model.replayStarted();   // a redo: the steps before the cut replay, the next ones are new
                    publish();
                    return new Inspector.Result(true, did);
                }
            }

            @Override
            public void resume() {
                if (agentReplaying != null) return;
                synchronized (UiMcpSession.this) {
                    if (model.state() != RecordingModel.State.STOPPED) return;
                    model.resume(RecordedStep.Author.PERSON, true, palette, System.currentTimeMillis());
                    publish();
                }
            }

            @Override
            public Inspector.Result delete(int id) {
                if (agentReplaying != null) return agentReplayingResult();
                synchronized (UiMcpSession.this) {
                    StepAddress at = model.addressOf(id);
                    if (at == null) return gone("nothing deleted");
                    RecordingModel scratch = model.copy();
                    scratch.delete(at, RecordedStep.Author.PERSON);
                    Inspector.Result refused = refusedEdit(scratch.actions());
                    if (refused != null) return refused;
                    String did = model.delete(at, RecordedStep.Author.PERSON);
                    publish();
                    return new Inspector.Result(true, did);
                }
            }

            @Override
            public Inspector.Result describe(int fromId, int toId, String say) {
                if (agentReplaying != null) return agentReplayingResult(); // read before the monitor: the agent's replay holds it
                synchronized (UiMcpSession.this) {
                    StepAddress a = model.addressOf(fromId);
                    StepAddress b = model.addressOf(toId);
                    if (a == null || b == null) return gone("nothing described");
                    try {
                        String did;
                        if (a.step == b.step) {
                            // one check: its own sentence; anything else within the step: the step's
                            StepAddress at = a.isCheck() && a.equals(b) ? a : StepAddress.ofStep(a.step);
                            did = model.say(at, say, RecordedStep.Author.PERSON);
                            if (did == null) return new Inspector.Result(true, (at.isCheck() ? "check " : "step ") + at + " has no sentence");
                        } else {
                            did = model.group(Math.min(a.step, b.step), Math.max(a.step, b.step), say,
                                    RecordedStep.Author.PERSON);
                        }
                        publish();
                        return new Inspector.Result(true, did);
                    } catch (DriverException e) {
                        return new Inspector.Result(false, message(e));
                    }
                }
            }

            @Override
            public Inspector.Result ungroup(int id) {
                if (agentReplaying != null) return agentReplayingResult();
                synchronized (UiMcpSession.this) {
                    StepAddress at = model.addressOf(id);
                    if (at == null) return gone("nothing ungrouped");
                    try {
                        String did = model.ungroup(at.step, RecordedStep.Author.PERSON);
                        publish();
                        return new Inspector.Result(true, did);
                    } catch (DriverException e) {
                        return new Inspector.Result(false, message(e));
                    }
                }
            }

            @Override
            public Inspector.Result rowBy(int id, String by) {
                if (agentReplaying != null) return agentReplayingResult();
                synchronized (UiMcpSession.this) {
                    StepAddress at = model.addressOf(id);
                    if (at == null) return gone("row not changed");
                    try {
                        String did = changeRow(at, by, RecordedStep.Author.PERSON);
                        publish();
                        return new Inspector.Result(true, did);
                    } catch (DriverException e) {
                        return new Inspector.Result(false, message(e));
                    }
                }
            }

            @Override
            public Inspector.Result undo() {
                if (agentReplaying != null) return agentReplayingResult();
                synchronized (UiMcpSession.this) {
                    Flow after = model.undoPreview();
                    if (after == null) return new Inspector.Result(false, "nothing to undo in this recording");
                    Inspector.Result refused = refusedEdit(after.steps);
                    if (refused != null) return refused;
                    String did = model.undo(RecordedStep.Author.PERSON);
                    publish();
                    // what was taken back, as Undo's tooltip named it (and how many steps a cut's undo dropped)
                    String undid = "undid the ";
                    return new Inspector.Result(true, did.startsWith(undid) ? did.substring(undid.length()) : did);
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
            public Path recordedFolder() {
                if (recordedDirExplicit) return recordedDir;
                Path remembered = folderMemory.get();
                return remembered != null ? remembered : recordedDir;
            }

            @Override
            public Path loadedTest() {
                return UiMcpSession.this.loadedTest();
            }

            @Override
            public Inspector.Result exportRecordedTest(String name, Path folder) {
                try {
                    // kept, like a test export; the file becomes the one a further export saves over
                    JsonNode result = mapper.readTree(UiMcpSession.this.exportRecordedTest(name, folder.toString(), null, false));
                    folderMemory.put(Paths.get(result.path("savedTo").asText()).getParent());
                    return new Inspector.Result(true, wrote(result));
                } catch (RecordedTestFolderNotWritable e) {
                    return new Inspector.Result(false, "Cannot write to " + e.folder() + ": " + e.why(), true);
                } catch (ExportRefused e) {
                    return new Inspector.Result(false, String.join("; ", e.problems()));
                } catch (Exception e) {
                    return new Inspector.Result(false, e.getMessage());
                }
            }

            @Override
            public List<Inspector.RecordedTestEntry> recordedTests(Path folder) {
                List<Inspector.RecordedTestEntry> out = new ArrayList<Inspector.RecordedTestEntry>();
                for (Path file : RecordedTestFiles.list(folder)) {
                    Path abs = file.toAbsolutePath().normalize();
                    String base = abs.getFileName().toString();
                    String name = base.substring(0, base.length() - RecordedTestFiles.SUFFIX.length());
                    try {
                        Flow flow = RecordedTestFiles.read(abs);
                        out.add(new Inspector.RecordedTestEntry(name, flow.description, StepNumbers.of(flow.steps).size(), null, abs));
                    } catch (RuntimeException e) {
                        out.add(new Inspector.RecordedTestEntry(name, null, 0, message(e), abs));
                    }
                }
                return out;
            }

            @Override
            public Inspector.Result loadRecordedTest(Path file) {
                try {
                    JsonNode loaded = mapper.readTree(UiMcpSession.this.loadRecordedTest(file));
                    // the folder the person loaded from is the one Load recorded test… and Export… offer next
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

            @Override
            public Inspector.Result unfold(int id) {
                if (agentReplaying != null) return agentReplayingResult();
                synchronized (UiMcpSession.this) {
                    StepAddress at = model.addressOf(id);
                    if (at == null) return gone("nothing unfolded");
                    try {
                        String did = model.unfold(at.step, RecordedStep.Author.PERSON);
                        publish();
                        return new Inspector.Result(true, did);
                    } catch (DriverException e) {
                        return new Inspector.Result(false, message(e));
                    }
                }
            }

            @Override
            public Inspector.Result undoFold(int id) {
                if (agentReplaying != null) return agentReplayingResult();
                synchronized (UiMcpSession.this) {
                    if (!model.lastEditFolded(id)) {
                        return new Inspector.Result(false, "the recording changed since: Unfold on its row takes the fold back");
                    }
                    // checked as the palette's ↶ Undo checks what it would leave
                    Inspector.Result refused = refusedEdit(model.undoPreview().steps);
                    if (refused != null) return refused;
                    String did = model.undoFold(id, RecordedStep.Author.PERSON);
                    publish();
                    String undid = "undid the ";
                    return new Inspector.Result(true, did.startsWith(undid) ? did.substring(undid.length()) : did);
                }
            }

            @Override
            public String foldHint() {
                return published.hint;
            }

            @Override
            public Inspector.StepsView stepsView() {
                Published p = published;
                return new Inspector.StepsView(p.lines, p.undoLabel, p.hint);
            }

            @Override
            public List<Inspector.DomainCheck> domainChecks(List<Map<String, Object>> checks, Locator seen) {
                return UiMcpSession.this.domainChecks(checks, seen);
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
                // a check runs on the screen as it is: not on one the agent's replay is changing
                if (agentReplaying != null) return agentReplayingResult();
                Map<String, Object> plain = new LinkedHashMap<String, Object>(step);
                Object viewIndex = plain.remove(Inspector.ROW_INDEX);
                plain.remove(Inspector.SEEN);
                JsonNode json = stripNulls(mapper.valueToTree(plain));
                try {
                    JsonNode result = runStepLocked(json, personRow(json, viewIndex));
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
        Boolean palette = bool(args, "palette");
        Shot shot = driver().screenshot(target, region(args), integer(args, "maxSize"), number(args, "scale"),
                palette != null && palette);

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

    /** A step number or check label argument ({@code 7}, {@code "7.2"}); null when absent. */
    private static StepAddress address(JsonNode args, String field) {
        return args != null && args.hasNonNull(field) ? StepAddress.parse(args.get(field)) : null;
    }

    private static Integer integer(JsonNode args, String field) {
        if (args == null || !args.hasNonNull(field)) return null;
        JsonNode v = args.get(field);
        if (!v.canConvertToInt()) throw new DriverException(Reason.BAD_INPUT, "'" + field + "' must be an integer");
        return v.asInt();
    }

    private static Boolean bool(JsonNode args, String field) {
        if (args == null || !args.hasNonNull(field)) return null;
        JsonNode v = args.get(field);
        if (!v.isBoolean()) throw new DriverException(Reason.BAD_INPUT, "'" + field + "' must be true or false");
        return v.asBoolean();
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
        // The plugins' domain steps as sentences (id, kind, sentence); the vocabulary tool has the rest.
        r.set("vocabulary", new VocabularyTool(plugins, mapper).sentences());
        return r;
    }

    /** The {@code vocabulary} tool: {@code list}, {@code describe {step}} and {@code match {from,to}}. Read-only. */
    public String vocabulary(JsonNode args) {
        if (args != null && "match".equals(args.path("op").asText(""))) return vocabularyMatch(integer(args, "from"), integer(args, "to"));
        return new VocabularyTool(plugins, mapper).run(args);
    }

    /**
     * Runs one action through the same {@link FlowExecutor} replay uses, records it when it
     * passes, and reports it. Every step ({@code do_action}, {@code do_actions}, a verb tool, a {@code batch}
     * step) goes through here, so a batched step and a single step cannot behave differently.
     *
     * <p>A step that passes but has no Java form (a {@code loginAs} after the first step, a target that
     * needs a ref) is not recorded, and the result says why ({@code notRecorded}): recorded, it would make
     * every later {@code export_test} refuse, and nothing else ever empties the recording. So the
     * recording is always exportable, and such steps stay usable for exploring. The exception is a do step whose
     * domain step has no Java form: it is recorded (a recorded test holds it), and only {@code export_test} refuses.
     */
    private ObjectNode runAndRecord(JsonNode actionJson, boolean record, RecordedStep.Author author) {
        return runStep(actionJson, record, author, null).result;
    }

    /** What one step did: its report, and the alternatives of the table row it names (null when it names none). */
    private static final class StepRun {
        final ObjectNode result;
        final RowChoice row;

        StepRun(ObjectNode result, RowChoice row) {
            this.result = result;
            this.row = row;
        }
    }

    /**
     * {@link #runAndRecord}, with the row alternatives too. {@code given} is what the person's palette said of the row, used
     * when the run's own outcome has none.
     */
    private StepRun runStep(JsonNode actionJson, boolean record, RecordedStep.Author author, RowChoice given) {
        Action action = sentenced(action(actionJson), "the step's sentence");
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
            // "The harness can't do this" is a tool error, not a failed step; a do step's details list the steps it ran.
            Reason reason = Reason.valueOf(step.reason);
            List<String> details = new ArrayList<String>();
            if (step.details != null) details.addAll(step.details);
            if (step.steps != null) {
                for (StepResult c : step.steps) details.add((c.index + 1) + ". " + c.action + (c.passed ? " passed" : " failed"));
            }
            throw new DriverException(reason, DriverException.stripReason(step.message, reason), details);
        }
        boolean passed = step != null && step.passed;
        ObjectNode r = mapper.createObjectNode();
        RowChoice row = null;
        r.put("passed", passed);
        r.put("action", step != null ? step.action : action.describe());
        if (step != null && step.reason != null) r.put("reason", step.reason);
        r.put("message", step != null ? step.message : "no step executed");
        if (step != null && step.details != null) r.set("details", mapper.valueToTree(step.details));
        r.put("ms", elapsed);
        if (step != null && step.steps != null) r.set("steps", children(step.steps));
        if (passed && record) {
            if (model.state() == RecordingModel.State.STOPPED) {
                r.put("notRecorded", "the recording is stopped (Done in the palette, or recording stop): recording redo "
                        + "continues it, recording start begins a new one");
            } else {
                Action recordedAction = action instanceof DoAction ? recordedDo((DoAction) action, step.action)
                        : recordable(action, step.outcome);
                row = rowOf(recordedAction, step.outcome, given);
                List<String> problems = exportProblems(recordedAction);
                if (problems.isEmpty()) {
                    model.append(recordedAction, author, row, action instanceof DoAction ? null : seenOf(step.outcome));
                    r.set("recorded", mapper.valueToTree(recordedAction));
                    scheduleFolding();
                } else {
                    model.refused(recordedAction.describe() + " - " + String.join("; ", problems));
                    r.put("notRecorded", "it ran, but export_test has no Java form for it (" + String.join("; ", problems)
                            + "); write it by hand into the exported test with the app plugin's test helpers or the Client API");
                }
                publish();
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
        if (passed && !record) row = rowOf(action, step.outcome, given);
        return new StepRun(r, row);
    }

    /**
     * The do step {@code d} as a recording keeps it: ONE step, by {@link #namedDo}, its params as given (concrete values),
     * and its sentence: the one given, else {@code sentence}, the step's own filled in.
     */
    private Action recordedDo(DoAction d, String sentence) {
        DoAction kept = namedDo(d);
        if (kept.say == null && sentence != null && !sentence.isEmpty()) kept.say = sentence;
        return kept;
    }

    /**
     * A copy of {@code d} that names its plugin ({@code <plugin>:<id>}, so a plugin added later cannot make it ambiguous)
     * and is a check when its domain step is one; {@code d} unchanged without plugins (its export is refused then).
     * BAD_INPUT when no plugin offers it with those params.
     */
    private DoAction namedDo(DoAction d) {
        DoAction kept = (DoAction) FlowJson.mapper().convertValue(d, Action.class);
        if (plugins == null) return kept;
        Plugins.DoStep found = plugins.lookUp(d.step, d.params == null ? Collections.<String, String>emptyMap() : d.params);
        kept.step = found.qualified();
        kept.check = found.found.step.kind() == DomainStep.Kind.CHECK ? Boolean.TRUE : null;
        return kept;
    }

    /** A {@code do} step's child results, numbered from 1, each with its step as do_action takes it. */
    private ArrayNode children(List<StepResult> steps) {
        ArrayNode out = mapper.createArrayNode();
        for (StepResult c : steps) {
            ObjectNode o = stepJson(c, String.valueOf(c.index + 1), null);
            try {
                if (c.json != null) o.set("step", mapper.readTree(c.json));
            } catch (IOException e) {
                o.put("step", c.json);
            }
            if (c.pending != null) o.set("pending", mapper.valueToTree(c.pending));
            out.add(o);
        }
        return out;
    }

    /**
     * The alternatives of the table row {@code action} names: those the run saw (a locator's row, or a cell check's),
     * else {@code given}; null when the action names no row. "This value" is the row as the step names it when it names
     * it by value (any column); the table's first unique column only for a step by position.
     */
    private RowChoice rowOf(Action action, VerbOutcome outcome, RowChoice given) {
        ObjectNode holder = rowHolder(mapper.valueToTree(action));
        if (holder == null) return null;
        RowChoice seen = outcome == null ? null : outcome.row;
        if (seen == null && outcome != null) {
            Recorder.Recorded target = outcome.recorded.get("target");
            if (target != null) seen = target.row;
        }
        RowChoice choice = seen != null ? seen : given;
        if (choice == null) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> row = mapper.convertValue(holder.get("row"), Map.class);
        return byPosition(row, choice) || row.equals(choice.byValue()) ? choice : new RowChoice(choice.index, row);
    }

    /** The node of {@code step} that holds a table row ({@code cell.row} of a cell check, else {@code target.row}), or null. */
    private static ObjectNode rowHolder(JsonNode step) {
        JsonNode cell = step.path("cell");
        if (cell.path("row").isObject()) return (ObjectNode) cell;
        JsonNode target = step.path("target");
        if (target.path("row").isObject()) return (ObjectNode) target;
        return null;
    }

    /** True when {@code row} (as recorded in a step) names its row by position, else by value; see {@link RowChoice}. */
    private static boolean byPosition(Map<String, Object> row, RowChoice choice) {
        if (choice != null) {
            if (choice.byPosition().equals(row)) return true;
            if (row.equals(choice.byValue())) return false;
        }
        return row.size() == 1 && row.get("index") instanceof Number;
    }

    /** The choice for a row the person's palette named: its view index and the row the step carries (null: not known). */
    private RowChoice personRow(JsonNode step, Object viewIndex) {
        if (!(viewIndex instanceof Number)) return null;
        ObjectNode holder = rowHolder(step);
        if (holder == null) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> row = mapper.convertValue(holder.get("row"), Map.class);
        int index = ((Number) viewIndex).intValue();
        return new RowChoice(index, row.size() == 1 && row.get("index") instanceof Number ? null : row);
    }

    /** The number of checks (expect steps) among {@code actions}. */
    private static int checkCount(List<Action> actions) {
        int n = 0;
        for (Action a : actions) if (StepNumbers.isCheck(a)) n++;
        return n;
    }

    /**
     * A sentence given inside a step object: trimmed, a blank one dropped, otherwise one line of at most 200
     * characters (BAD_INPUT naming {@code what} when not).
     */
    private static Action sentenced(Action action, String what) {
        if (action.say == null) return action;
        String say = action.say.trim();
        if (say.isEmpty()) {
            action.say = null;
        } else {
            RecordedTestFiles.checkSay(say, what);
            action.say = say;
        }
        return action;
    }

    private Action action(JsonNode json) {
        try {
            return fragileByTarget(mapper.treeToValue(json, Action.class));
        } catch (InvalidTypeIdException e) {
            // An unknown step type, or none.
            throw FlowJson.unknownStepType(e);
        } catch (JsonProcessingException e) {
            throw new DriverException(Reason.BAD_INPUT, "malformed action: " + e.getOriginalMessage());
        } catch (IllegalArgumentException e) {
            throw new DriverException(Reason.BAD_INPUT, "malformed action: " + e.getMessage());
        }
    }

    /**
     * {@code a}, marked {@code fragile} when one of its targets needs {@code nth} or is a point: the one breaks when a
     * match is added before it, the other when the layout moves. Every step recorded, inserted or edited is parsed here,
     * the agent's and the person's alike.
     */
    private static Action fragileByTarget(Action a) {
        if (!(a instanceof VerbAction)) return a;
        for (Locator l : ((VerbAction) a).targets().values()) {
            if (l != null && (l.point != null || l.nth != null)) ((VerbAction) a).fragile = true;
        }
        return a;
    }

    /** Names the export check uses; valid, so every problem it finds belongs to the step checked. */
    private static final String CHECK_PACKAGE = "recorded";
    private static final String CHECK_CLASS = "RecordingCheckUiIT";
    private static final String CHECK_METHOD = "recorded";

    /**
     * What {@link JavaTestWriter} would refuse if {@code next} followed the steps recorded so far, and does not refuse
     * without it; empty when it would write it, leaving out do steps with no Java form ({@link #keptForms}). Only
     * {@code next}'s problems count: whatever the steps before have (a fold puts in steps nothing checked), they never
     * make the steps after them refused.
     */
    private List<String> exportProblems(Action next) {
        List<Action> before = model.actions();
        List<Action> steps = new ArrayList<Action>(before);
        steps.add(next);
        List<String> problems = writeProblems(steps);
        if (problems.isEmpty()) return problems;
        List<String> fresh = new ArrayList<String>(problems);
        fresh.removeAll(writeProblems(before));
        return fresh;
    }

    /** What {@link JavaTestWriter} refuses in {@code steps} as a recording ({@link #keptForms}); empty when it writes them. */
    private List<String> writeProblems(List<Action> steps) {
        try {
            JavaTestWriter.write(steps, CHECK_PACKAGE, CHECK_CLASS, CHECK_METHOD, keptForms);
            return Collections.emptyList();
        } catch (ExportRefused e) {
            return e.problems();
        }
    }

    /**
     * The step as export_test records it: a ref becomes the recorder's locator,
     * {@code via:"api"} marks a direct Swing call, {@code fragile:true} a target that needed nth or is a point.
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

    /** The palette's replay of a recorded step: it runs, nothing is recorded. */
    private synchronized StepRun replayStep(JsonNode json) {
        return runStep(json, false, RecordedStep.Author.AGENT, null);
    }

    /** The person's check: it runs and is recorded when it passes. */
    private synchronized ObjectNode runStepLocked(JsonNode json, RowChoice given) {
        return runStep(json, true, RecordedStep.Author.PERSON, given).result;
    }

    public synchronized String doAction(JsonNode actionJson) {
        return doAction(actionJson, true);
    }

    /** As {@link #doAction(JsonNode)}; with {@code record} false the step runs and nothing is recorded or reported as such. */
    public synchronized String doAction(JsonNode actionJson, boolean record) {
        return runAndRecord(actionJson, record, RecordedStep.Author.AGENT).toString();
    }

    private synchronized String doAction(JsonNode actionJson, boolean record, RecordedStep.Author author) {
        return runAndRecord(actionJson, record, author).toString();
    }

    /**
     * Runs several actions in ONE round trip, each through {@link #runAndRecord}, and reports every step that ran.
     * It stops at the first failure: after it the UI is no longer in the state the remaining steps expect.
     * A harness error ends it as a tool error naming the step.
     */
    public synchronized String doActions(JsonNode actionsJson) throws Exception {
        // one run: the palette that steps aside for one of its steps stays aside until the last one ends
        Inspector.agentRun(true);
        try {
            return runActions(actionsJson);
        } finally {
            Inspector.agentRun(false);
        }
    }

    private String runActions(JsonNode actionsJson) throws Exception {
        if (actionsJson == null || !actionsJson.isArray()) {
            throw new IllegalArgumentException("'actions' must be an array of step types");
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

    /** The step verbs {@code batch} takes besides the verb tools' names and the step types' names. */
    static final String READ_STEP = "read";

    /**
     * Several steps in ONE call, in order, stopping at the first that does not pass - the verb-tool
     * shaped sibling of {@link #doActions}. Each step is {@code {"verb": <name>, ...that tool's
     * arguments}}: a verb tool's name ({@code click}, {@code type}, {@code choose_file}, ...) runs
     * exactly as that tool would ({@link #verbStep}), {@code read} reads without recording, and any
     * other name is taken as a step type ({@code navigate}, {@code close}, {@code loginAs},
     * {@code screenshot}, ...) and runs as {@code do_actions} runs it; a name that is none is BAD_INPUT. So what is recorded, and what {@code export_test}
     * writes, is the same as issuing the steps one by one.
     *
     * <p>A step that fails ({@code passed:false}, or a {@code read} that finds nothing) ends the batch
     * and is the last one reported. A harness error (CAPABILITY_GAP, BAD_INPUT, BUSY) ends it as a tool
     * error naming the step; the steps before it ran and were recorded. {@code snapshot} ({@code true},
     * or the snapshot tool's arguments) adds one after the last step that ran, pass or fail.
     */
    public synchronized String batch(JsonNode args) {
        // one run, as do_actions
        Inspector.agentRun(true);
        try {
            return runBatch(args);
        } finally {
            Inspector.agentRun(false);
        }
    }

    private String runBatch(JsonNode args) {
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
            try {
                r.set("snapshot", snapshotNode(snapshot.isObject() ? snapshot : null));
            } catch (DriverException e) {
                ObjectNode error = r.putObject("snapshotError");
                error.put("errorType", ToolErrors.typeOf(e.reason()).name());
                error.put("message", DriverException.stripReason(e.getMessage(), e.reason()));
                ArrayNode details = error.putArray("details");
                for (String d : e.details()) details.add(d);
            }
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
     * reaches the recording ({@link #runAndRecord}) - except a do step whose domain step has none, refused here naming
     * the step - and a refusal leaves the recording as it was, so the
     * export can be retried with other names. {@code clear} empties the recording after a write; the next one
     * needs a reset first, and is refused while a recording someone started runs ({@link #refuseClearWhileRecording}).
     */
    public String exportTest(String className, String method, String pkg, boolean clear) throws Exception {
        return exportTest(className, method, pkg, clear, null);
    }

    /**
     * As {@link #exportTest(String, String, String, boolean)}, writing under {@code dir} (the test sources root) when it
     * is not empty: it wins over {@code -Dswingui.testsDir} and the plugin's module. When none of the three decides and the
     * working directory holds no {@code pom.xml}, {@code build.gradle} or {@code build.gradle.kts}, the file is still
     * written and the result's {@code warning} names where, suggesting {@code dir}.
     */
    public String exportTest(String className, String method, String pkg, boolean clear, String dir) throws Exception {
        // the last steps' folds are in: waited for without the lock, which the matcher thread needs to apply them
        folding.settle(FOLD_WAIT_MS);
        return exportTestLocked(className, method, pkg, clear, dir);
    }

    private Path workingDir = Paths.get("");

    /** Tests: the directory the default export folder and the build-file check are seen from. */
    synchronized void workingDir(Path dir) {
        this.workingDir = dir;
    }

    private static boolean hasBuildFile(Path dir) {
        return Files.isRegularFile(dir.resolve("pom.xml")) || Files.isRegularFile(dir.resolve("build.gradle"))
                || Files.isRegularFile(dir.resolve("build.gradle.kts"));
    }

    private synchronized String exportTestLocked(String className, String method, String pkg, boolean clear, String dir)
            throws Exception {
        if (clear) refuseClearWhileRecording();
        requireResetStart();
        String methodName = method == null || method.isEmpty() ? "recorded" : method;
        ExportDefaults defaults = exportDefaults.get();
        String packageName = pkg == null || pkg.isEmpty() ? defaults.packageName : pkg;
        String source = JavaTestWriter.write(model.flow(), packageName, className, methodName, doForms);

        String configured = System.getProperty(TESTS_DIR_PROP);
        boolean dirGiven = dir != null && !dir.trim().isEmpty();
        // dir names the place itself: then no location is generic
        boolean genericLocation = !dirGiven && defaults == Plugins.GENERIC_EXPORT && (pkg == null || pkg.isEmpty())
                && configured == null;
        Path root = dirGiven ? workingDir.resolve(dir.trim())
                : configured != null ? Paths.get(configured) : testsDir(workingDir, defaults.moduleDir);
        if (Files.isRegularFile(root)) {
            throw new IllegalArgumentException("dir " + root.toAbsolutePath().normalize() + " is a file, not a folder");
        }
        Path file = root.resolve(packageName.replace('.', '/')).resolve(className + ".java");
        if (Files.exists(file)) {
            throw new IllegalArgumentException(file.toAbsolutePath().normalize() + " already exists; edit it instead");
        }
        Files.createDirectories(file.getParent());
        Files.write(file, source.getBytes(StandardCharsets.UTF_8));

        int steps = model.steps();
        int checks = checkCount(model.actions());
        int fragile = fragileSteps();
        String startWarning = startWarning();
        if (clear) {
            model.clear();
            recordingFromReset = false;
            freshStart = false;
            forgetLoadedTest();
            publish();
            if (palette) view.replaced();
        }

        ObjectNode result = mapper.createObjectNode();
        result.put("savedTo", file.toAbsolutePath().normalize().toString());
        result.put("steps", steps);
        result.put("checks", checks);
        result.put("fragile", fragile);
        if (genericLocation) {
            result.put("warning", "no app plugin is ready, so the file went to the generic location "
                    + "(src/test/java/" + packageName.replace('.', '/') + " under the working directory); name a package, pass dir "
                    + "(the test sources root) or set -D" + TESTS_DIR_PROP + " to place it");
        }
        if (!dirGiven && configured == null && defaults.moduleDir == null && !hasBuildFile(workingDir.toAbsolutePath())) {
            String where = file.toAbsolutePath().normalize().toString();
            String note = "the working directory " + workingDir.toAbsolutePath().normalize() + " holds no pom.xml, build.gradle "
                    + "or build.gradle.kts, so the test went to " + where + "; pass dir (the test sources root, e.g. "
                    + "<project>/src/test/java) to place it";
            String old = result.path("warning").asText("");
            result.put("warning", old.isEmpty() ? note : old + "; " + note);
        }
        if (startWarning != null) result.put("startWarning", startWarning);
        result.put("source", source);
        return result.toString();
    }

    private void requireResetStart() {
        if (!freshStart) {
            throw new IllegalArgumentException("the recording did not begin at a session reset or a recording start, "
                    + "where the test will start: reset (session op reset), get to the starting screen, redo the steps, "
                    + "then export; or replay it from step 1 (recording replay) and export once that passes");
        }
    }

    /**
     * A clearing export would end a recording someone started on purpose (the person's ● Record, recording start)
     * under them, as a session reset would: refused, nothing written.
     */
    private void refuseClearWhileRecording() {
        String busy = recordingReason();
        if (busy != null) {
            throw new DriverException(Reason.BUSY, "" + busy + "; export without clear, or stop it first");
        }
    }

    /** The recording with ids and authors, for tests. */
    synchronized List<RecordedStep> recordedSteps() {
        return model.records();
    }

    private int fragileSteps() {
        int fragile = 0;
        for (Action a : model.actions()) {
            if (a instanceof VerbAction && Boolean.TRUE.equals(((VerbAction) a).fragile)) fragile++;
        }
        return fragile;
    }

    /**
     * Writes the steps recorded since the last {@link #startRecording} as a recorded test file {@code <folder>/<name>.json}
     * ({@link RecordedTestFiles}) and returns a JSON summary ({@code savedTo}, {@code steps}, {@code checks},
     * {@code fragile}, {@code overwrote}, {@code startWarning} when it applies, {@code test}). The refusals are export_test's (no reset
     * start; an empty recording and a bad name together, in one {@link ExportRefused}), plus a folder that cannot be
     * written ({@code RecordedTestFolderNotWritable}); an existing file is refused unless it is the recorded test the recording came
     * from, which keeps its own {@code name} and, when no {@code description} is given, its description. A refusal
     * leaves the recording as it was. {@code clear} empties the recording; otherwise the written file becomes the one
     * a further export saves over; a clearing one is refused while a recording someone started runs.
     */
    public String exportRecordedTest(String name, String dir, String description, boolean clear) throws Exception {
        // the last steps' folds are in: waited for without the lock, which the matcher thread needs to apply them
        folding.settle(FOLD_WAIT_MS);
        return exportRecordedTestLocked(name, dir, description, clear);
    }

    private synchronized String exportRecordedTestLocked(String name, String dir, String description, boolean clear)
            throws Exception {
        if (clear) refuseClearWhileRecording();
        requireResetStart();
        List<String> problems = new ArrayList<String>();
        if (model.size() == 0) problems.add("nothing was recorded");
        try {
            RecordedTestFiles.checkName(name);
        } catch (DriverException e) {
            problems.add(e.getMessage());
        }
        if (!problems.isEmpty()) throw new ExportRefused(problems);

        Path folder = dir == null || dir.trim().isEmpty() ? recordedDir
                : Paths.get("").toAbsolutePath().resolve(dir.trim()).normalize();
        if (recordedReadOnly && folder.toAbsolutePath().normalize().equals(recordedDir.toAbsolutePath().normalize())) {
            throw new RecordedTestFolderNotWritable(folder.toAbsolutePath(),
                    "the recorded tests that came with the app are read-only; save yours in another folder", null);
        }
        folder = RecordedTestFiles.writableDir(folder);
        Path file = RecordedTestFiles.file(folder, name).toAbsolutePath().normalize();
        boolean exists = Files.exists(file);
        if (exists && !file.equals(loadedTest)) {
            throw new IllegalArgumentException(file + " already exists; choose another name (only the recorded test the "
                    + "recording came from is saved over)");
        }
        // saved over the recorded test the recording came from: its own name and, unless a new one is given, its description stay
        Flow flow = new Flow();
        flow.name = exists && loadedName != null ? loadedName : RecordedTestFiles.checkName(name);
        flow.description = description != null && !description.trim().isEmpty() ? description.trim()
                : exists ? loadedDescription : null;
        flow.start = RecordedTestFiles.START_RESET;
        Flow recorded = model.flow();
        flow.steps = recorded.steps;
        flow.groups = recorded.groups;
        flow.version = RecordedTestFiles.versionFor(flow);
        String text = RecordedTestFiles.write(flow);
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));

        String startWarning = startWarning();
        ObjectNode result = mapper.createObjectNode();
        result.put("savedTo", file.toString());
        result.put("steps", model.steps());
        result.put("checks", checkCount(model.actions()));
        result.put("fragile", fragileSteps());
        result.put("overwrote", exists);
        if (startWarning != null) result.put("startWarning", startWarning);
        result.set("test", mapper.readTree(text));
        if (clear) {
            model.clear();
            recordingFromReset = false;
            freshStart = false;
            forgetLoadedTest();
        } else {
            loadedTest = file;
            loadedName = flow.name;
            loadedDescription = flow.description;
        }
        publish();
        if (clear && palette) view.replaced();
        return result.toString();
    }

    /**
     * Runs a recorded test file on the live app and reports it ({@code passed}, {@code test}, {@code file}, {@code ran},
     * {@code total}, {@code ms}, {@code steps}, {@code report}). Exactly one of {@code name} (in the recorded tests folder),
     * {@code path} or {@code test} (the file's JSON inline). It begins with the app reset, runs every step as
     * {@code do_action} would but records nothing, and stops at the first failing step, which carries its diagnosis
     * (picture under {@code evidence}). A recorded test that fails is a result; one that cannot start (bad file, no reset) is an
     * error. Refused (BUSY) while a person records, answers or replays in the palette.
     */
    public synchronized String runRecordedTest(JsonNode args) {
        JsonNode name = args == null ? null : args.get("name");
        JsonNode path = args == null ? null : args.get("path");
        JsonNode inline = args == null ? null : args.get("test");
        int given = (name != null && !name.isNull() ? 1 : 0) + (path != null && !path.isNull() ? 1 : 0)
                + (inline != null && !inline.isNull() ? 1 : 0);
        if (given != 1) {
            throw new DriverException(Reason.BAD_INPUT, "run_recorded_test takes one of 'name', 'path' or 'test'");
        }
        Flow flow;
        Path file = null;
        if (name != null && !name.isNull()) {
            file = recordedTestFile(name.asText(), null);
            flow = RecordedTestFiles.read(file);
        } else if (path != null && !path.isNull()) {
            file = recordedTestFile(null, path.asText());
            flow = RecordedTestFiles.read(file);
        } else {
            // the file's JSON object, or that JSON as text (agents often send it quoted)
            String text = inline.isObject() ? inline.toString() : inline.isTextual() ? inline.textValue() : null;
            if (text == null) throw new DriverException(Reason.BAD_INPUT, "'test' must be a recorded test file's JSON object");
            flow = RecordedTestFiles.parse(text, "the 'test' argument");
            if (flow.name == null || flow.name.isEmpty()) flow.name = "inline";
        }
        checkDoSteps(flow, file != null ? file.toString() : "the 'test' argument");
        String busy = personBusy.get();
        if (busy != null) throw new DriverException(Reason.BUSY, "" + busy);
        if (host.verbs() == null) throw new DriverException(Reason.CAPABILITY_GAP, "no UI driver is installed: nothing to run the recorded test on");
        JsonNode ev = args.get("evidence");
        Path evidence = ev != null && ev.isTextual() && !ev.asText().trim().isEmpty()
                ? Paths.get("").toAbsolutePath().resolve(ev.asText().trim()).normalize()
                : ScreenshotVerb.screenshotDir(Paths.get("")).resolve("failures");

        long started = System.nanoTime();
        FlowResult result;
        // the reset is the agent's too, as in the palette's own runs: the person's recorder ignores it
        Inspector.agentActing(true);
        if (palette) view.agentReplaying("running recorded test " + flow.name);
        agentReplaying = "running recorded test " + flow.name;
        try {
            appReset.run();
            result = new FlowExecutor().diagnoseFailures(new FailureDiagnostics(evidence)).run(flow, host);
        } finally {
            if (palette) view.agentReplaying(null);
            agentReplaying = null;
            Inspector.agentActing(false);
        }

        ObjectNode r = mapper.createObjectNode();
        r.put("passed", result.passed());
        r.put("test", flow.name);
        if (file != null) r.put("file", file.toString());
        List<String> labels = StepNumbers.labels(flow.steps);
        java.util.Set<String> reached = new java.util.HashSet<String>();
        for (StepResult s : result.steps) reached.add(stepNumber(labels.get(s.index)));
        r.put("ran", reached.size());   // steps, not records: a step that failed in a check counts
        r.put("total", StepNumbers.of(flow.steps).size());
        r.put("ms", (System.nanoTime() - started) / 1_000_000L);
        ArrayNode steps = r.putArray("steps");
        for (StepResult s : result.steps) steps.add(stepJson(s, labels.get(s.index), flow.steps.get(s.index).say));
        r.put("report", FlowReport.format(flow, result));
        return r.toString();
    }

    /** The step a label belongs to: "7" for "7" and "7.2". */
    private static String stepNumber(String label) {
        int dot = label.indexOf('.');
        return dot < 0 ? label : label.substring(0, dot);
    }

    /** The recorded test file {@code name} (in the recorded tests folder, which must have it) or {@code path} (relative to the working directory) names. */
    private Path recordedTestFile(String name, String path) {
        if (name != null) {
            Path file = RecordedTestFiles.file(recordedDir, name).toAbsolutePath().normalize();
            if (!Files.isRegularFile(file)) {
                List<String> there = new ArrayList<String>();
                for (Path p : RecordedTestFiles.list(recordedDir)) {
                    String base = p.getFileName().toString();
                    there.add(base.endsWith(RecordedTestFiles.SUFFIX) ? base.substring(0, base.length() - RecordedTestFiles.SUFFIX.length()) : base);
                }
                throw new DriverException(Reason.BAD_INPUT, "no recorded test '" + name + "' in " + recordedDir + "; the recorded tests there: " + there);
            }
            return file;
        }
        return Paths.get("").toAbsolutePath().resolve(path).normalize();
    }

    /** {@code n}: an integer for a step, the string {@code "7.2"} for a check. */
    private static void putLabel(ObjectNode o, String n) {
        if (n.indexOf('.') >= 0) o.put("n", n);
        else o.put("n", Integer.parseInt(n));
    }

    /** One step's report; {@code say}: its sentence, null when none. A {@code do} step's children come under steps. */
    private ObjectNode stepJson(StepResult s, String n, String say) {
        ObjectNode o = mapper.createObjectNode();
        putLabel(o, n);
        if (say != null && !say.isEmpty()) o.put("say", say);
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
        if (s.steps != null) o.set("steps", children(s.steps));
        return o;
    }

    /**
     * Refuses a recorded test whose do steps cannot run here: a plugin its {@code requires} names that is not ready
     * (CAPABILITY_GAP naming it), a step or param that plugin does not offer, or a domain check and an action in each
     * other's place (BAD_INPUT naming the step, its line in the file and the plugin). Each do step then names its plugin
     * ({@code <plugin>:<id>}), as a recording does. {@code source} names the file.
     */
    private void checkDoSteps(Flow flow, String source) {
        List<Integer> dos = new ArrayList<Integer>();
        for (int i = 0; i < flow.steps.size(); i++) if (flow.steps.get(i) instanceof DoAction) dos.add(i);
        if (dos.isEmpty()) return;
        List<String> requires = RecordedTestFiles.requiresOf(flow);
        String needs = source + " requires the plugin" + (requires.size() == 1 ? " " : "s ");
        if (plugins == null) {
            throw new DriverException(Reason.CAPABILITY_GAP, needs + String.join(", ", requires)
                    + "; no plugin configured (agent argument plugins=…)");
        }
        List<String> configured = new ArrayList<String>();
        for (PluginSlot slot : plugins.slots()) configured.add(slot.name());
        List<String> missing = new ArrayList<String>();
        for (String name : requires) {
            PluginSlot slot = plugins.slotNamed(name);
            if (slot == null) {
                missing.add(name + ", which is not configured");
            } else if (slot.state() != PluginSlot.State.READY) {
                missing.add(name + ", which is " + slot.state().name().toLowerCase(java.util.Locale.ROOT) + " (" + slot.detail() + ")");
            }
        }
        if (!missing.isEmpty()) {
            throw new DriverException(Reason.CAPABILITY_GAP, needs + String.join(", and ", missing) + " (the plugins: "
                    + (configured.isEmpty() ? "none" : String.join(", ", configured)) + ")");
        }
        List<Plugins.VocabularyStep> vocabulary = plugins.vocabulary();
        List<String> labels = StepNumbers.labels(flow.steps);
        for (int i : dos) {
            DoAction d = (DoAction) flow.steps.get(i);
            Integer line = i < flow.lines.size() ? flow.lines.get(i) : null;
            String where = source + ": " + (StepNumbers.isCheck(d) ? "check " : "step ") + labels.get(i)
                    + (line != null ? " (line " + line + ")" : "");
            String plugin = RecordedTestFiles.pluginOf(d);
            String id = plugin == null ? d.step : d.step.substring(plugin.length() + 1);
            List<String> among = plugin != null ? Collections.singletonList(plugin) : requires;
            List<Plugins.VocabularyStep> offered = new ArrayList<Plugins.VocabularyStep>();
            List<String> ids = new ArrayList<String>();
            for (Plugins.VocabularyStep v : vocabulary) {
                if (!among.contains(v.plugin)) continue;
                ids.add(v.step.id());
                if (v.step.id().equals(id)) offered.add(v);
            }
            String by = ", plugin" + (among.size() == 1 ? " " : "s ") + String.join(", ", among) + ": ";
            if (offered.isEmpty()) {
                throw new DriverException(Reason.BAD_INPUT, where + by + (among.size() == 1 ? among.get(0) + " has no"
                        : "none of them has a") + " domain step '" + id + "' (" + (among.size() == 1 ? "its" : "their")
                        + " steps: " + (ids.isEmpty() ? "none" : String.join(", ", ids)) + ")");
            }
            if (offered.size() > 1) {
                List<String> names = new ArrayList<String>();
                for (Plugins.VocabularyStep v : offered) names.add(v.plugin + ":" + id);
                throw new DriverException(Reason.BAD_INPUT, where + by + "'" + id + "' is offered by more than one of them; "
                        + "name one: " + String.join(", ", names));
            }
            Plugins.VocabularyStep v = offered.get(0);
            String at = where + ", plugin " + v.plugin + ": ";
            try {
                plugins.lookUp(v.plugin + ":" + id, d.params == null ? Collections.<String, String>emptyMap() : d.params);
            } catch (DriverException e) {
                throw new DriverException(e.reason(), at + DriverException.stripReason(e.getMessage(), e.reason()));
            }
            boolean check = v.step.kind() == DomainStep.Kind.CHECK;
            if (check && !StepNumbers.isCheck(d)) {
                throw new DriverException(Reason.BAD_INPUT, at + id + " is a check: it goes under the step it checks (in its \"checks\")");
            }
            if (!check && StepNumbers.isCheck(d)) {
                throw new DriverException(Reason.BAD_INPUT, at + id + " is an action: it is a step of its own, not in \"checks\"");
            }
            d.step = v.plugin + ":" + id;
        }
    }

    /**
     * Makes the recording the steps of a recorded test file, as if they had just been run after a reset: every step needs a Java
     * form (the recording stays exportable as a test), except a do step whose domain step has none, else this refuses and
     * changes nothing. A broken file fails
     * with {@link RecordedTestFiles}' message. Returns {@code {loaded, name, description, steps}}.
     */
    public synchronized String loadRecordedTest(Path file) {
        Flow flow = RecordedTestFiles.read(file);
        checkDoSteps(flow, file.toAbsolutePath().normalize().toString());
        try {
            JavaTestWriter.write(flow, CHECK_PACKAGE, CHECK_CLASS, CHECK_METHOD, keptForms);
        } catch (ExportRefused e) {
            throw new IllegalArgumentException(flow.name + " cannot be loaded into the recording: "
                    + String.join("; ", e.problems()) + "; run it with run_recorded_test or the JUnit runner instead");
        }
        Path abs = file.toAbsolutePath().normalize();
        model.load(flow, RecordedStep.Author.LOADED);
        model.loaded();
        foldNotes.clear();
        foldHint = null;
        // a loaded test is as it was saved: its steps never fold among themselves (vocabulary match shows what would)
        for (RecordedStep r : model.records()) foldedUpTo = Math.max(foldedUpTo, r.id);
        recordingFromReset = true;
        freshStart = true;
        definedStart = appReset.defined();
        loadedTest = abs;
        loadedName = flow.name;
        loadedDescription = flow.description;
        publish();
        ObjectNode result = mapper.createObjectNode();
        result.put("loaded", abs.toString());
        result.put("name", flow.name);
        if (flow.description == null) result.putNull("description"); else result.put("description", flow.description);
        result.put("steps", StepNumbers.of(flow.steps).size());
        result.put("checks", checkCount(flow.steps));
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
        startRecording(true, definedStart, RecordedStep.Author.AGENT, false);
        if (palette) view.replaced();
    }

    /**
     * As {@link #startRecording(boolean)}, started by {@code by}; {@code fromReset}: a reset of the app ran just before.
     * Every start is a fresh one an export may write, reset or not (without one the test must get to its screen
     * itself: startWarning). An {@code explicit} recording (recording start, the person's Record)
     * blocks a reset, a test run and another start until it stops ({@link #recordingReason}) and, with the palette on,
     * is shared with the person; the implicit one of a session reset blocks nothing.
     */
    synchronized void startRecording(boolean fromReset, boolean definedStart, RecordedStep.Author by, boolean explicit) {
        model.clear();
        foldNotes.clear();
        foldHint = null;
        model.start(by, explicit, explicit && palette, System.currentTimeMillis());
        recordingFromReset = fromReset;
        freshStart = true;
        this.definedStart = definedStart;
        forgetLoadedTest();
        publish();
    }

    /** Longest a {@code recording list} may wait for the stop, in milliseconds. */
    private static final long MAX_RECORDING_WAIT_MS = 540_000L;

    /**
     * Why a recording that someone started on purpose is running, or null: the reset, test runs, another start and
     * recorded test runs wait for it, since each would wipe or derail the steps being made. A recording that only follows a
     * session reset never blocks anything. Read without the session's lock.
     */
    public String recordingReason() {
        return published.reason;
    }

    /**
     * The recording tool: {@code start {reset?}} begins a fresh recording the person's palette joins, {@code stop} ends
     * it, {@code list {wait?}} says its state and every step. Not synchronized: {@code list} must answer while a step
     * or a replay holds the session, and {@code wait} must not hold it.
     */
    public String recording(JsonNode args) throws Exception {
        String op = text(args, "op");
        if (op == null) throw new DriverException(Reason.BAD_INPUT, "recording needs 'op': " + OPS);
        if (EDIT_OPS.contains(op)) {
            refuseWhileReplaying();
            folding.settle(FOLD_WAIT_MS);   // an edit sees the folds of the steps recorded so far, as list does
        }
        switch (op) {
            case "start": {
                String busy = recordingReason();
                if (busy == null) busy = Inspector.engagedReason();
                if (busy != null) throw new DriverException(Reason.BUSY, "" + busy + "; stop it first");
                boolean defined = false;
                boolean reset = args.path("reset").asBoolean(false);
                if (reset) {
                    Inspector.agentActing(true);
                    try {
                        appReset.run();
                    } finally {
                        Inspector.agentActing(false);
                    }
                    defined = appReset.defined();
                }
                synchronized (this) {
                    startRecording(reset, defined, RecordedStep.Author.AGENT, true);
                    if (model.shared()) view.agentStarted();
                }
                return published.json;
            }
            case "stop": {
                synchronized (this) {
                    if (model.state() != RecordingModel.State.RECORDING) {
                        throw new DriverException(Reason.BAD_INPUT,
                                "no recording is running (it is " + model.state().json + ")");
                    }
                    boolean shared = model.shared();
                    model.stop();
                    publish();
                    if (shared) view.agentStopped();
                }
                return published.json;
            }
            case "list": {
                folding.settle(FOLD_WAIT_MS);   // folds of the steps recorded so far are in the list
                Integer wait = integer(args, "wait");
                long waitMs = wait == null ? 0L : Math.max(0L, Math.min(wait.longValue(), MAX_RECORDING_WAIT_MS));
                long deadline = System.currentTimeMillis() + waitMs;
                synchronized (stopSignal) {
                    long left;
                    // only an explicit recording (reason set) is waited for: a session reset's has nobody else to stop it
                    while (published.reason != null && (left = deadline - System.currentTimeMillis()) > 0) {
                        stopSignal.wait(left);
                    }
                }
                return published.json;
            }
            case "insert": {
                if (!args.hasNonNull("at")) {
                    throw new DriverException(Reason.BAD_INPUT, "insert needs 'at' (a step position, or a check label like \"7.2\") and 'step'");
                }
                if (!args.path("step").isObject()) throw new DriverException(Reason.BAD_INPUT, "insert needs 'step': a step");
                return recordingInsert(StepAddress.parse(args.get("at")), args.get("step"), integer(args, "rev"));
            }
            case "delete":
                return recordingDelete(address(args, "n"), integer(args, "from"), integer(args, "to"), integer(args, "rev"));
            case "move": {
                StepAddress n = address(args, "n");
                Integer to = integer(args, "to");
                if (n == null || to == null) throw new DriverException(Reason.BAD_INPUT, "move needs 'n' and 'to'");
                return recordingMove(n, to, integer(args, "rev"));
            }
            case "edit": {
                StepAddress n = address(args, "n");
                if (n == null) throw new DriverException(Reason.BAD_INPUT, "edit needs 'n' and target, text, step or say");
                return recordingEdit(n, args.get("target"), text(args, "text"), args.get("step"), integer(args, "rev"),
                        text(args, "say"));
            }
            case "group": {
                Integer from = integer(args, "from");
                Integer to = integer(args, "to");
                String say = text(args, "say");
                if (from == null || to == null || say == null) {
                    throw new DriverException(Reason.BAD_INPUT, "group needs 'from', 'to' (step numbers) and 'say' (its sentence)");
                }
                return recordingGroup(from, to, say, integer(args, "rev"));
            }
            case "ungroup": {
                StepAddress n = address(args, "n");
                if (n == null) throw new DriverException(Reason.BAD_INPUT, "ungroup needs 'n' (a step of the group)");
                return recordingUngroup(n.step, integer(args, "rev"));
            }
            case "row": {
                StepAddress n = address(args, "n");
                String by = text(args, "by");
                if (n == null || by == null) {
                    throw new DriverException(Reason.BAD_INPUT, "row needs 'n' (a step or check naming a table row) and 'by': value or position");
                }
                return recordingRow(n, by, integer(args, "rev"));
            }
            case "undo":
                return recordingUndo(integer(args, "rev"));
            case "fold": {
                Integer from = integer(args, "from");
                Integer to = integer(args, "to");
                if (from == null || to == null) {
                    throw new DriverException(Reason.BAD_INPUT, "fold needs 'from' and 'to' (step numbers) and may name the 'step'");
                }
                JsonNode step = args.get("step");
                if (step != null && !step.isNull() && !step.isTextual()) {
                    throw new DriverException(Reason.BAD_INPUT, "fold's 'step' is the id of a domain step (from vocabulary list)");
                }
                return recordingFold(from, to, text(args, "step"), integer(args, "rev"));
            }
            case "unfold": {
                StepAddress n = address(args, "n");
                if (n == null || n.isCheck()) throw new DriverException(Reason.BAD_INPUT, "unfold needs 'n': the number of a folded step");
                return recordingUnfold(n.step, integer(args, "rev"));
            }
            case "keepRaw": {
                Integer from = integer(args, "from");
                Integer to = integer(args, "to");
                if (from == null || to == null) throw new DriverException(Reason.BAD_INPUT, "keepRaw needs 'from' and 'to' (step numbers)");
                return recordingKeepRaw(from, to, integer(args, "rev"));
            }
            case "replay":
                return recordingReplay(integer(args, "from"), integer(args, "to"), text(args, "speed"));
            case "redo": {
                Integer from = integer(args, "from");
                if (from == null) throw new DriverException(Reason.BAD_INPUT, "redo needs 'from' (the step to record again)");
                return recordingRedo(from, integer(args, "rev"));
            }
            case "export":
                return recordingExport(args);
            case "load": {
                String name = text(args, "name");
                String path = text(args, "path");
                if ((name == null) == (path == null)) {
                    throw new DriverException(Reason.BAD_INPUT, "load takes one of 'name' (a recorded test in the recorded tests folder) or 'path'");
                }
                return recordingLoad(name, path);
            }
            default:
                throw new DriverException(Reason.BAD_INPUT, "unknown recording op '" + op + "' (" + OPS + ")");
        }
    }

    /** Every op of the recording tool, as its errors list them. */
    private static final String OPS = "start, stop, list, insert, delete, move, edit, group, ungroup, row, undo, fold, "
            + "unfold, keepRaw, replay, redo, export, load";

    private static final java.util.Set<String> EDIT_OPS = new java.util.HashSet<String>(
            java.util.Arrays.asList("insert", "delete", "move", "edit", "group", "ungroup", "row", "undo", "fold", "unfold",
                    "keepRaw"));

    /** "steps a-b" while the agent's replay or redo runs (it holds the session), else null; read without the lock. */
    private volatile String agentReplaying;

    /**
     * The palette's edits while the agent replays or runs a recorded test: refused, like the agent's own (see
     * {@link #agentReplaying}), saying which.
     */
    private Inspector.Result agentReplayingResult() {
        String replaying = agentReplaying;
        return new Inspector.Result(false, replaying != null && replaying.startsWith("running ")
                ? "the agent is " + replaying : "the agent is replaying the recording");
    }

    /** The palette's edit of a step that is no longer in the recording (the agent deleted it, or an undo took it). */
    private static Inspector.Result gone(String what) {
        return new Inspector.Result(false, "that step is gone: the recording changed since (" + what + ")");
    }

    /** Why the palette's edit, leaving {@code after}, is refused - export_test could not write it - or null. */
    private Inspector.Result refusedEdit(List<Action> after) {
        try {
            validateEdit(after);
            return null;
        } catch (DriverException e) {
            return new Inspector.Result(false, DriverException.stripReason(e.getMessage(), e.reason()));
        }
    }

    /** Refuses (BUSY) while the agent replays the recording or runs a recorded test; the state is read once, without the lock. */
    public void refuseWhileReplaying() {
        String replaying = agentReplaying;
        if (replaying != null) {
            throw new DriverException(Reason.BUSY, replaying.startsWith("running ")
                    ? "the agent is " + replaying
                    : "the agent is replaying the recording (" + replaying + ")");
        }
    }

    /** Pause before each action group, in milliseconds, for a person watching. */
    private static long pauseFor(String speed) {
        if (speed == null || speed.equals("fast")) return 0L;
        if (speed.equals("normal")) return 700L;
        if (speed.equals("slow")) return 1500L;
        if (speed.startsWith("step")) {
            throw new DriverException(Reason.BAD_INPUT,
                    "step by step is the person's: Step by step in the palette's Steps panel");
        }
        throw new DriverException(Reason.BAD_INPUT, "unknown speed '" + speed + "' (fast, normal, slow)");
    }

    /**
     * Runs steps {@code from} to {@code to} (default: all; step numbers, a step with its checks) again on the live app
     * without recording them. From step 1 the app is reset first, as the exported test starts. Stops at the first
     * failing record. A record that passes no longer counts as edited. Result: {@code passed, ran, total, reset, rev,
     * steps:[{n, action, passed, ms, message?, reason?}]}, n a step number or a check label like "7.2"; {@code ran} (the
     * steps reached) and {@code total} (the steps in the range) count steps, never checks.
     */
    public synchronized String recordingReplay(Integer from, Integer to, String speed) {
        long pause = pauseFor(speed);
        int count = model.steps();
        ObjectNode r;
        if (model.size() == 0) {
            r = mapper.createObjectNode();
            r.put("passed", true);
            r.put("ran", 0);
            r.put("total", 0);
            r.put("reset", false);
            r.put("message", "nothing to replay");
            r.putArray("steps");
        } else {
            int a = from == null ? 1 : from;
            int b = to == null ? count : to;
            r = replay(a, b, pause);
            // only a replay of the whole recording from the reset shows that it starts there
            if (a == 1 && b == count && r.path("passed").asBoolean()) beginsAtReset();
        }
        r.put("rev", model.rev());
        return r.toString();
    }

    /** Replays steps {@code a} to {@code b} under the agent's hold (see {@link #agentReplaying}); resets first from step 1. */
    private ObjectNode replay(int a, int b, long pause) {
        int[] range = model.flatRange(a, b);   // refuses a number that is no step
        model.replayStarted();   // nothing recorded before it folds with what follows
        boolean reset = a == 1;
        ObjectNode r = mapper.createObjectNode();
        ArrayNode results = mapper.createArrayNode();
        // the palette hears of it before a refused palette call can: a ● Record refused for it finds its bar there
        String steps = "steps " + a + "-" + b;
        if (palette) view.agentReplaying(reset ? "resetting the app, then replaying " + steps : "replaying " + steps);
        agentReplaying = steps;
        Inspector.agentActing(true);
        try {
            if (reset) appReset.run();
            boolean passed = true;
            java.util.Set<String> reached = new java.util.HashSet<String>();
            java.util.Set<String> inRange = new java.util.HashSet<String>();
            List<String> labels = StepNumbers.labels(model.actions());
            for (int i = range[0]; i <= range[1]; i++) inRange.add(stepNumber(labels.get(i)));
            for (int i = range[0]; i <= range[1]; i++) {
                RecordedStep recorded = model.at(i + 1);
                boolean check = StepNumbers.isCheck(recorded.action);
                String what = (check ? "check " : "step ") + labels.get(i);
                if (pause > 0 && (i == range[0] || !check)) sleep(pause);
                if (palette) {
                    // the counter counts steps only; a check is named after it, as the palette's own replay does
                    String label = labels.get(i);
                    String text = recorded.action.describe();
                    view.agentReplaying("replaying " + (check ? label.substring(0, label.indexOf('.')) + "/" + b + "  check " + label + ": "
                            + (text.startsWith("expect(") && text.endsWith(")") ? text.substring(7, text.length() - 1) : text)
                            : label + "/" + b + "  " + text));
                }
                JsonNode json = stripNulls(mapper.valueToTree(recorded.action));
                ObjectNode step;
                RowChoice row;
                try {
                    StepRun run = runStep(json, false, RecordedStep.Author.AGENT, null);
                    step = run.result;
                    row = run.row;
                } catch (DriverException e) {
                    if (palette) view.replayed(recorded.id, false);
                    throw new DriverException(e.reason(), what + ": "
                            + DriverException.stripReason(e.getMessage(), e.reason()), e.details());
                }
                reached.add(stepNumber(labels.get(i)));
                boolean ok = step.path("passed").asBoolean();
                if (palette) view.replayed(recorded.id, ok);
                ObjectNode o = results.addObject();
                putLabel(o, labels.get(i));
                o.put("action", recorded.action.describe());
                o.put("passed", ok);
                o.put("ms", step.path("ms").asLong());
                if (!ok) {
                    o.put("message", step.path("message").asText(""));
                    if (step.has("reason")) o.put("reason", step.path("reason").asText());
                    passed = false;
                    break;
                }
                model.markReplayed(recorded.id);
                model.rowKnown(recorded.id, row);
                publish();
            }
            r.put("passed", passed);
            r.put("ran", reached.size());   // steps, not records
            r.put("total", inRange.size());
            r.put("reset", reset);
            r.set("steps", results);
            return r;
        } finally {
            // the palette's own bar is back before the palette may move again (it waits for agentActing to end)
            if (palette) view.agentReplaying(null);
            Inspector.agentActing(false);
            agentReplaying = null;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Cuts step {@code from} and everything after it, replays the steps before it (with their checks) from a reset and,
     * when they pass, records on (the recording is running again, started by the agent). {@code from} may be the number
     * after the last step: nothing is cut and a stopped recording continues. When the replay fails the cut stays (undo
     * brings it back).
     */
    public synchronized String recordingRedo(int from, Integer rev) {
        checkRev(rev);
        int count = model.steps();
        if (from < 1 || from > count + 1) {
            throw new DriverException(Reason.BAD_INPUT, "no step " + from + " to record from (the recording has " + count
                    + (count == 1 ? " step" : " steps") + "; " + (count + 1) + " continues it)");
        }
        boolean wasRecording = model.state() == RecordingModel.State.RECORDING;
        String cut = from > count ? null : model.cutFromStep(from, RecordedStep.Author.AGENT);
        publish(cut == null ? null : "the agent " + cut);
        ObjectNode r = null;
        try {
            int kept = model.steps();
            r = kept == 0 ? resetOnly() : replay(1, kept, 0L);
        } finally {
            // as the palette's redo: a running recording stops, its next steps would be on a screen the replay missed
            if (wasRecording && (r == null || !r.path("passed").asBoolean())) stopAfterFailedRedo();
        }
        boolean passed = r.path("passed").asBoolean();
        if (passed) {
            beginsAtReset(); // the steps before the cut just ran from the reset; the later ones follow live
            model.resume(RecordedStep.Author.AGENT, true, palette, System.currentTimeMillis());
        }
        publish();
        if (passed && model.shared()) view.agentStarted();
        r.put("recording", passed);
        r.put("rev", model.rev());
        r.set("list", readTree(published.json));
        return r.toString();
    }

    /** A redo that failed or broke while the recording ran stops it, like the palette's (and its Done). */
    private void stopAfterFailedRedo() {
        boolean shared = model.shared();
        model.stop();
        publish();
        if (shared) view.agentStopped();
    }

    private void beginsAtReset() {
        recordingFromReset = true;
        freshStart = true;
        definedStart = appReset.defined();
        publish();
    }

    /** A redo from the first step has nothing to replay: only the reset. */
    private ObjectNode resetOnly() {
        model.replayStarted();
        if (palette) view.agentReplaying("resetting the app");
        agentReplaying = "reset";
        Inspector.agentActing(true);
        try {
            appReset.run();
        } finally {
            if (palette) view.agentReplaying(null);
            Inspector.agentActing(false);
            agentReplaying = null;
        }
        ObjectNode r = mapper.createObjectNode();
        r.put("passed", true);
        r.put("ran", 0);
        r.put("total", 0);
        r.put("reset", true);
        r.putArray("steps");
        return r;
    }

    /** As export_recorded_test / export_test ({@code as} recorded | java), but the recording stays unless {@code clear}. */
    public String recordingExport(JsonNode args) throws Exception {
        String as = text(args, "as");
        boolean clear = args.path("clear").asBoolean(false);
        if ("java".equals(as)) {
            return exportTest(args.path("class").asText(""), args.path("method").asText(""),
                    args.path("package").asText(""), clear, args.path("dir").asText(""));
        }
        if ("recorded".equals(as)) {
            return exportRecordedTest(args.path("name").asText(""), args.path("dir").asText(""),
                    args.path("description").asText(""), clear);
        }
        throw new DriverException(Reason.BAD_INPUT, "export needs 'as': recorded (name, dir?, description?) or java (class, method?, package?, dir?)");
    }

    /** Makes the recorded test file the recording (stopped); refused while a recording runs or a person is busy. */
    public synchronized String recordingLoad(String name, String path) {
        String busy = personBusy.get();
        if (busy != null) throw new DriverException(Reason.BUSY, "" + busy);
        loadRecordedTest(recordedTestFile(name, path));
        if (palette) view.agentLoaded(loadedName, model.steps());
        return published.json;
    }

    /** Refuses an edit made on a recording that changed since the revision the caller listed. */
    private void checkRev(Integer rev) {
        if (rev != null && rev.intValue() != model.rev()) {
            throw new DriverException(Reason.BUSY, "the recording changed since revision " + rev + " (now "
                    + model.rev() + "; last change: " + model.lastChange() + "): list it again");
        }
    }

    /** Refuses an edit that would leave a recording export_test could not write; an empty one is allowed. */
    private void validateEdit(List<Action> after) {
        if (after.isEmpty()) return;
        try {
            JavaTestWriter.write(after, CHECK_PACKAGE, CHECK_CLASS, CHECK_METHOD, keptForms);
        } catch (ExportRefused e) {
            throw new DriverException(Reason.BAD_INPUT, String.join("; ", e.problems()) + " (the recording is unchanged)");
        }
    }

    /** The agent's edit {@code did} is made: published, the palette told, and the list returned with it. */
    private String edited(String did) {
        publish("the agent " + did);
        ObjectNode r = (ObjectNode) readTree(published.json);
        r.put("did", did);
        return r.toString();
    }

    private JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // Each edit below is first tried on a copy of the recording (RecordingModel.copy): the model refuses a bad address
    // or structure there (nothing is placed or widened for it), validateEdit refuses a result with no Java form (a do step
    // whose domain step has none excepted), and only
    // then is the real recording edited.

    /** Puts {@code step} at {@code at} (a step position for an action, a check label for a check) without running it; it counts as edited until a replay passes it. */
    synchronized String recordingInsert(StepAddress at, JsonNode step, Integer rev) {
        checkRev(rev);
        Action action = action(step);
        if (action instanceof DoAction) action = namedDo((DoAction) action);
        sentenced(action, (StepNumbers.isCheck(action) ? "check " : "step ") + at);
        RecordingModel scratch = model.copy();
        scratch.insert(at, action, RecordedStep.Author.AGENT);
        validateEdit(scratch.actions());
        return edited(model.insert(at, action, RecordedStep.Author.AGENT));
    }

    /**
     * Deletes what {@code n} names (a step with its checks, or one check), or steps {@code from} to {@code to} with their
     * checks; the later steps close up.
     */
    synchronized String recordingDelete(StepAddress n, Integer from, Integer to, Integer rev) {
        checkRev(rev);
        RecordingModel scratch = model.copy();
        String did;
        if (n != null && from == null && to == null) {
            scratch.delete(n, RecordedStep.Author.AGENT);
            validateEdit(scratch.actions());
            did = model.delete(n, RecordedStep.Author.AGENT);
        } else if (n == null && from != null && to != null) {
            if (to < from) throw new DriverException(Reason.BAD_INPUT, "delete: 'to' (" + to + ") is before 'from' (" + from + ")");
            scratch.deleteSteps(from, to, RecordedStep.Author.AGENT);
            validateEdit(scratch.actions());
            did = model.deleteSteps(from, to, RecordedStep.Author.AGENT);
        } else {
            throw new DriverException(Reason.BAD_INPUT, "delete takes 'n', or 'from' and 'to'");
        }
        return edited(did);
    }

    /** Moves step {@code n}, with its checks, so that it is step {@code to} afterwards. A check is not moved on its own. */
    synchronized String recordingMove(StepAddress n, int to, Integer rev) {
        checkRev(rev);
        if (n.isCheck()) {
            throw new DriverException(Reason.BAD_INPUT, "check " + n + " cannot be moved on its own: move its step (" + n.step
                    + ") with its checks, or delete the check and insert it again");
        }
        RecordingModel scratch = model.copy();
        scratch.moveStep(n.step, to, RecordedStep.Author.AGENT);
        validateEdit(scratch.actions());
        return edited(model.moveStep(n.step, to, RecordedStep.Author.AGENT));
    }

    /**
     * Changes the step or check {@code n}: its {@code target}, its {@code text}, the whole {@code step}, and/or its sentence
     * {@code say} ({@code ""} removes it). A change of target, text or step is not run again and counts as edited until
     * a replay passes it; with {@code say} too it is still one edit. A sentence alone is no change of behaviour.
     */
    synchronized String recordingEdit(StepAddress n, JsonNode target, String text, JsonNode step, Integer rev, String say) {
        checkRev(rev);
        String what = (n.isCheck() ? "check " : "step ") + n;
        Action old = model.at(n).action;
        boolean hasTarget = target != null && !target.isNull();
        boolean whole = step != null && !step.isNull();
        if (whole && (hasTarget || text != null)) {
            throw new DriverException(Reason.BAD_INPUT, "edit takes 'step', or 'target' and/or 'text', not both");
        }
        boolean changes = whole || hasTarget || text != null;
        if (!changes && say == null) {
            throw new DriverException(Reason.BAD_INPUT, "edit needs target, text, step or say");
        }
        String sentence = say == null ? null : say.trim();
        if (sentence != null && !sentence.isEmpty()) RecordedTestFiles.checkSay(sentence, what);
        if (!changes) {
            String did = model.say(n, sentence, RecordedStep.Author.AGENT);
            if (did != null) return edited(did);
            ObjectNode unchanged = (ObjectNode) readTree(published.json);
            unchanged.put("did", what + " has no sentence");
            return unchanged.toString();
        }
        ObjectNode tree;
        if (whole) {
            if (!step.isObject()) throw new DriverException(Reason.BAD_INPUT, "edit 'step' must be a step object");
            tree = (ObjectNode) step.deepCopy();
            if (sentence == null && !tree.has("say") && old.say != null) tree.put("say", old.say);   // its sentence stays
        } else {
            tree = (ObjectNode) stripNulls(mapper.valueToTree(old));
            if (hasTarget) {
                if (!(old instanceof VerbAction) || !((VerbAction) old).targets().containsKey("target")) {
                    throw new DriverException(Reason.BAD_INPUT, what + " (" + tree.path("action").asText() + ") has no target");
                }
                tree.set("target", target);
                if (!target.hasNonNull("nth")) tree.remove("fragile");
            }
            if (text != null) {
                if (!tree.hasNonNull("text")) {
                    throw new DriverException(Reason.BAD_INPUT, what + " (" + tree.path("action").asText() + ") has no text");
                }
                tree.put("text", text);
            }
        }
        if (sentence != null) {
            if (sentence.isEmpty()) tree.remove("say");
            else tree.put("say", sentence);
        }
        Action edited = action(tree);
        Action replaced = sentenced(edited instanceof DoAction ? namedDo((DoAction) edited) : edited, what);
        RecordingModel scratch = model.copy();
        scratch.replace(n, replaced, RecordedStep.Author.AGENT);
        validateEdit(scratch.actions());
        return edited(model.replace(n, replaced, RecordedStep.Author.AGENT));
    }

    /** Makes steps {@code from} to {@code to} one group with the sentence {@code say}; the same range again renames it. */
    synchronized String recordingGroup(int from, int to, String say, Integer rev) {
        checkRev(rev);
        return edited(model.group(from, to, say, RecordedStep.Author.AGENT));
    }

    /** Ends the group that holds step {@code n}; its steps stay. */
    synchronized String recordingUngroup(int n, Integer rev) {
        checkRev(rev);
        return edited(model.ungroup(n, RecordedStep.Author.AGENT));
    }

    /**
     * Names the table row of step or check {@code at} another way: {@code by} "value" (the key column's text, as the row
     * was recorded when a column told rows apart) or "position" (its index). Counts as an edit, like a change of target.
     */
    synchronized String recordingRow(StepAddress at, String by, Integer rev) {
        checkRev(rev);
        return edited(changeRow(at, by, RecordedStep.Author.AGENT));
    }

    /** The row of {@code at} by {@code by} ("value" or "position"); BAD_INPUT says why not. The caller publishes. */
    private String changeRow(StepAddress at, String by, RecordedStep.Author author) {
        if (!"value".equals(by) && !"position".equals(by)) {
            throw new DriverException(Reason.BAD_INPUT, "'by' must be value or position, not '" + by + "'");
        }
        boolean toPosition = "position".equals(by);
        RecordedStep old = model.at(at);
        ObjectNode tree = (ObjectNode) stripNulls(mapper.valueToTree(old.action));
        String what = (at.isCheck() ? "check " : "step ") + at;
        ObjectNode holder = rowHolder(tree);
        if (holder == null) throw new DriverException(Reason.BAD_INPUT, what + " (" + tree.path("action").asText() + ") has no row");
        @SuppressWarnings("unchecked")
        Map<String, Object> current = mapper.convertValue(holder.get("row"), Map.class);
        if (byPosition(current, old.row) == toPosition) {
            throw new DriverException(Reason.BAD_INPUT, what + "'s row is already by " + by);
        }
        Map<String, Object> to = old.row == null ? null : toPosition ? old.row.byPosition() : old.row.byValue();
        if (to == null) {
            throw new DriverException(Reason.BAD_INPUT, old.row == null
                    ? what + "'s row by " + by + " is not known yet: replay the step once"
                    : what + "'s row by " + by + " is not known: no column tells the rows of its table apart");
        }
        holder.set("row", mapper.valueToTree(to));
        Action rewritten = action(tree);
        RecordingModel scratch = model.copy();
        scratch.row(at, rewritten, author, toPosition);
        validateEdit(scratch.actions());
        return model.row(at, rewritten, author, toPosition);
    }

    /** Takes back the last edit, whoever made it. */
    synchronized String recordingUndo(Integer rev) {
        checkRev(rev);
        Flow after = model.undoPreview();
        if (after == null) throw new DriverException(Reason.BAD_INPUT, "nothing to undo in this recording");
        validateEdit(after.steps);
        return edited(model.undo(RecordedStep.Author.AGENT));
    }

    // ---- folding: a vocabulary's domain steps from the recording's raw steps -------------------------------------

    /** Longest a reader (list, an export) waits for folding to finish with the steps recorded so far. */
    static final long FOLD_WAIT_MS = Folding.BUDGET_MS + 1000;

    /** What folding noted about a run (see {@link Matcher.Note}), by the record ids of its steps. */
    private static final class FoldNote {
        final Matcher.Note.Kind kind;
        final List<Integer> ids;
        final List<Matcher.Choice> choices;
        final String why;

        FoldNote(Matcher.Note.Kind kind, List<Integer> ids, List<Matcher.Choice> choices, String why) {
            this.kind = kind;
            this.ids = ids;
            this.choices = choices;
            this.why = why;
        }
    }

    /** {@link Matcher.Hint} as the palette shows it, for the record {@code id} it was found at. */
    private static final class FoldHint {
        final int id;
        final String text;

        FoldHint(int id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    /** The domain steps a recording may fold into: every ready plugin's act steps that have patterns. Empty without. */
    private List<Matcher.Candidate> foldingVocabulary() {
        List<Matcher.Candidate> out = new ArrayList<Matcher.Candidate>();
        if (plugins == null) return out;
        for (Plugins.VocabularyStep v : plugins.vocabulary()) {
            if (v.step.kind() != DomainStep.Kind.ACT) continue;   // a domain check is the person's Check, never a fold
            PatternCompiler.Compiled c = plugins.patternsOf(v);
            if (c == null || c.patterns().isEmpty()) continue;
            out.add(new Matcher.Candidate(v.plugin + ":" + v.step.id(), c.patterns(), v.step.autoFold()));
        }
        return out;
    }

    /**
     * Hands the recording to folding when it has raw steps it has not looked at and the plugins have patterns. Under the
     * lock; the matching itself happens on folding's thread ({@link #applyFolds} applies it).
     */
    private void scheduleFolding() {
        List<Matcher.Candidate> vocabulary = foldingVocabulary();
        if (vocabulary.isEmpty()) return;
        List<RecordedStep> records = model.records();
        List<Matcher.Item> items = foldItems(records, foldedUpTo, true);
        int newest = foldedUpTo;
        boolean fresh = false;
        for (Matcher.Item i : items) {
            if (i.fresh) {
                fresh = true;
                newest = Math.max(newest, (Integer) i.key);
            }
        }
        if (!fresh) return;
        folding.schedule(new Folding.Job(model.rev(), model.replayFloor(), items, vocabulary, newest));
    }

    /**
     * {@code records} as folding sees them, each keyed by its id: a raw step is fresh when its id is above {@code freshAfter}.
     * A step kept raw, in a group or with a sentence of its own is a barrier, and with {@code replayFloor} one recorded
     * before the last replay started (a fold: all its raw steps were).
     */
    private List<Matcher.Item> foldItems(List<RecordedStep> records, int freshAfter, boolean replayFloor) {
        List<Matcher.Item> items = new ArrayList<Matcher.Item>(records.size());
        int floor = replayFloor ? model.replayFloor() : 0;
        for (RecordedStep r : records) {
            String author = r.author.json;
            int newest = r.id;
            if (r.folded()) {
                newest = 0;
                for (RecordedStep x : r.raw) newest = Math.max(newest, x.id);
            }
            boolean barrier = r.keepRaw || newest <= floor || r.group != null;
            if (StepNumbers.isCheck(r.action)) {
                items.add(Matcher.Item.check(r.id, author));
            } else if (r.folded()) {
                List<Matcher.Item> raw = new ArrayList<Matcher.Item>();
                for (RecordedStep s : r.raw) raw.add(Matcher.Item.raw(s.id, author, matchForm(s), false, false));
                items.add(Matcher.Item.folded(r.id, author, raw, barrier));
            } else if (r.action instanceof DoAction) {
                items.add(Matcher.Item.doStep(r.id, author));
            } else {
                // a step the person or the agent named is theirs to keep as it is
                items.add(Matcher.Item.raw(r.id, author, matchForm(r), barrier || r.action.say != null, r.id > freshAfter));
            }
        }
        return items;
    }

    /** A raw step as a pattern matches it: as its elements were seen, its row by position when it names it so. */
    private JsonNode matchForm(RecordedStep r) {
        JsonNode json = stripNulls(mapper.valueToTree(r.action));
        ObjectNode holder = rowHolder(json);
        boolean position = false;
        if (holder != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = mapper.convertValue(holder.get("row"), Map.class);
            position = byPosition(row, r.row);
        }
        return Matcher.matchForm(json, r.seen, position);
    }

    /** The person's step's target as its element was seen ({@link Inspector#SEEN}), as a recording keeps it; null when none. */
    private JsonNode seen(Object seen) {
        if (!(seen instanceof Locator)) return null;
        ObjectNode out = mapper.createObjectNode();
        out.set("target", locatorJson((Locator) seen));
        return out;
    }

    /** Each target field of a step that ran as its element was seen ({@link Recorder.Recorded#seen}); null when none. */
    private JsonNode seenOf(VerbOutcome outcome) {
        if (outcome == null) return null;
        ObjectNode out = null;
        for (Map.Entry<String, Recorder.Recorded> e : outcome.recorded.entrySet()) {
            if (e.getValue().seen == null) continue;
            if (out == null) out = mapper.createObjectNode();
            out.set(e.getKey(), locatorJson(e.getValue().seen));
        }
        return out;
    }

    /**
     * Applies what folding found, under the lock: each fold as one edit (undo takes it back), and its notes. A result for
     * a recording that changed meanwhile, or that a replay started on, is dropped and folding looks again; while the
     * palette replays, it is dropped.
     */
    private void applyFolds(Folding.Job job, Matcher.Result result) {
        // a replay started since the job was made: what it looked at may now be under the replay's floor
        if (model.rev() != job.rev || model.replayFloor() != job.floor) {
            scheduleFolding();
            return;
        }
        // the palette replays by position: nothing folds under it. Looked at again with the next change; what was there
        // before the replay started is under its floor by then and stays as it is.
        if (paletteReplaying.get() != null) return;
        foldedUpTo = Math.max(foldedUpTo, job.newest);
        if (result == null) return;
        Map<Matcher.Fold, Integer> made = new HashMap<Matcher.Fold, Integer>();
        boolean changed = false;
        RecordedStep lastFold = null;
        try {
            for (Matcher.Fold f : result.folds) {
                DoAction action = foldAction(f.choice);
                if (action == null) break;   // its plugin went: a later fold may build on this one, so none is made
                // folding recognised the author's steps by itself: no one asked for it (null editor)
                lastFold = model.fold(ids(f.span, made), action, author(f.item.author), null).step;
                made.put(f, lastFold.id);
                changed = true;
            }
            for (Matcher.Note n : result.notes) {
                Object last = n.span.get(n.span.size() - 1).key;
                if (!(last instanceof Integer)) continue;
                foldNotes.put((Integer) last, new FoldNote(n.kind, ids(n.span, made), n.choices, n.why));
                changed = true;
            }
            FoldHint hint = hintOf(result.hint, made);
            if (hint != null || foldHint != null) {
                foldHint = hint;
                changed = true;
            }
        } finally {
            // the folds made stay made, also when a later one could not be: the list and the palette show them
            if (changed) publish();
            // the last fold is the last edit: the palette's note may take it back
            if (lastFold != null) {
                DoAction d = (DoAction) lastFold.action;
                view.recognised(lastFold.id, d.say != null ? d.say : d.describe(), lastFold.raw.size());
            }
        }
    }

    /** The palette's hint for {@code h} (null: none), at the record its last item is now; null when it is gone. */
    private FoldHint hintOf(Matcher.Hint h, Map<Matcher.Fold, Integer> made) {
        if (h == null) return null;
        Integer id = h.key instanceof Matcher.Fold ? made.get(h.key) : h.key instanceof Integer ? (Integer) h.key : null;
        if (id == null) return null;
        String sentence = sentenceSoFar(h.step, h.params);
        if (sentence == null) return null;
        return new FoldHint(id, "… could become: " + sentence + " (" + h.steps + " of " + h.of + ")");
    }

    /**
     * The sentence of the domain step {@code name} with the values known so far, "…" for the others; null when no ready
     * plugin offers it now.
     */
    private String sentenceSoFar(String name, Map<String, String> params) {
        if (plugins == null) return null;
        for (Plugins.VocabularyStep v : plugins.vocabulary()) {
            if (!name.equals(v.plugin + ":" + v.step.id())) continue;
            java.util.regex.Matcher m = PLACEHOLDER.matcher(v.step.sentence());
            StringBuffer out = new StringBuffer();
            while (m.find()) {
                String value = params.get(m.group(1));
                m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(value != null ? value : "…"));
            }
            m.appendTail(out);
            return out.toString();
        }
        return null;
    }

    private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\{([A-Za-z]\\w*)\\}");

    /**
     * The domain checks of the ready plugins whose pattern is one step and fits one of {@code checks} (the Check menu's
     * expect steps for one element, {@code seen} how it was seen), each with its values, in the order of the checks they
     * fit; a check fitted twice is offered once. Matches only: no lock, no step runs.
     */
    List<Inspector.DomainCheck> domainChecks(List<Map<String, Object>> checks, Locator seen) {
        List<Inspector.DomainCheck> out = new ArrayList<Inspector.DomainCheck>();
        if (plugins == null || checks.isEmpty()) return out;
        List<Matcher.Candidate> vocabulary = new ArrayList<Matcher.Candidate>();
        for (Plugins.VocabularyStep v : plugins.vocabulary()) {
            if (v.step.kind() != DomainStep.Kind.CHECK) continue;
            PatternCompiler.Compiled c = plugins.patternsOf(v);
            if (c == null) continue;
            List<Pattern> one = new ArrayList<Pattern>();
            for (Pattern p : c.patterns()) if (Matcher.oneStep(p)) one.add(p);
            if (!one.isEmpty()) vocabulary.add(new Matcher.Candidate(v.plugin + ":" + v.step.id(), one, false));
        }
        if (vocabulary.isEmpty()) return out;
        JsonNode seenJson = seen(seen);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Folding.BUDGET_MS);
        List<String> offered = new ArrayList<String>();
        for (Map<String, Object> check : checks) {
            Map<String, Object> plain = new LinkedHashMap<String, Object>(check);
            plain.remove(Inspector.ROW_INDEX);
            plain.remove(Inspector.SEEN);
            JsonNode form = Matcher.matchForm(stripNulls(mapper.valueToTree(plain)), seenJson, false);
            List<Matcher.Choice> choices;
            try {
                choices = Matcher.matches(Collections.singletonList(Matcher.Item.raw(0, "person", form, false, false)),
                        vocabulary, deadline);
            } catch (Pattern.TooAmbiguous | Matcher.OutOfTime undecided) {
                continue;
            }
            for (Matcher.Choice c : choices) {
                String key = c.step + c.params;
                if (offered.contains(key)) continue;
                Plugins.DoStep d;
                try {
                    d = plugins.lookUp(c.step, c.params);
                } catch (DriverException gone) {
                    continue;
                }
                offered.add(key);
                Map<String, Object> step = new LinkedHashMap<String, Object>();
                step.put("action", "do");
                step.put("step", d.qualified());
                step.put("params", new LinkedHashMap<String, String>(c.params));
                out.add(new Inspector.DomainCheck(d.sentence, step));
            }
        }
        return out;
    }

    /** The record ids of {@code span}: a fold made in the same look by the id it got ({@code made}). */
    private static List<Integer> ids(List<Matcher.Item> span, Map<Matcher.Fold, Integer> made) {
        List<Integer> ids = new ArrayList<Integer>();
        for (Matcher.Item i : span) {
            Integer id = i.key instanceof Matcher.Fold ? made.get(i.key) : (Integer) i.key;
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private static RecordedStep.Author author(String json) {
        for (RecordedStep.Author a : RecordedStep.Author.values()) if (a.json.equals(json)) return a;
        return RecordedStep.Author.AGENT;
    }

    /** The do step a fold into {@code c} records: the step by its plugin, the values the steps gave, its sentence; null when no plugin offers it now. */
    private DoAction foldAction(Matcher.Choice c) {
        Plugins.DoStep d;
        try {
            d = plugins.lookUp(c.step, c.params);
        } catch (DriverException gone) {
            return null;
        }
        DoAction action = new DoAction();
        action.step = d.qualified();
        action.params = new LinkedHashMap<String, String>(c.params);
        action.say = d.sentence;
        return action;
    }

    /**
     * Folds steps {@code from} to {@code to} into one domain step, the agent asking: the steps (whole, one author's, no
     * check between them, in no group) must be a domain step's pattern; {@code step} names which one when more fit. A
     * step kept raw may be folded so. The steps are matched without the session's lock; a recording that changed
     * meanwhile is refused (BUSY).
     */
    String recordingFold(int from, int to, String step, Integer rev) {
        List<Matcher.Candidate> vocabulary;
        List<Matcher.Item> span = new ArrayList<Matcher.Item>();
        List<Integer> ids = new ArrayList<Integer>();
        RecordedStep.Author by;
        int seenRev;
        synchronized (this) {
            checkRev(rev);
            vocabulary = foldingVocabulary();
            if (vocabulary.isEmpty()) {
                throw new DriverException(Reason.CAPABILITY_GAP, "fold needs a plugin whose vocabulary has patterns; "
                        + (plugins == null ? "no plugin configured (agent argument plugins=…)" : "none has"));
            }
            by = foldSpan(from, to, span, ids);
            seenRev = model.rev();
        }
        String what = from == to ? "step " + from : "steps " + from + "-" + to;
        List<Matcher.Choice> choices;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Folding.BUDGET_MS);
        try {
            // on a stack as deep as the matcher thread's, with the time live folding has
            choices = Folding.onMatcherStack(() -> Matcher.matches(span, vocabulary, deadline));
        } catch (Pattern.TooAmbiguous e) {
            throw new DriverException(Reason.BAD_INPUT, what + ": " + Matcher.couldNotDecide(e.stepId()));
        } catch (Matcher.OutOfTime e) {
            throw new DriverException(Reason.BAD_INPUT, what + ": " + e.getMessage() + "; fold fewer steps");
        }
        Matcher.Choice chosen = choose(choices, step, what, from == to);
        DoAction action = foldAction(chosen);
        if (action == null) throw new DriverException(Reason.CAPABILITY_GAP, "no ready plugin offers '" + chosen.step + "' now");
        synchronized (this) {
            checkRev(seenRev);
            return edited(model.fold(ids, action, by, RecordedStep.Author.AGENT).did);
        }
    }

    /**
     * Steps {@code from} to {@code to} as a fold by name takes them: their action records as folding sees them into
     * {@code span}, their ids into {@code ids}; their one author. BAD_INPUT when they cannot fold. Under the lock.
     */
    private RecordedStep.Author foldSpan(int from, int to, List<Matcher.Item> span, List<Integer> ids) {
        model.flatRange(from, to);   // refuses a number that is no step
        List<StepNumbers.Step> st = StepNumbers.of(model.actions());
        List<RecordedStep> records = model.records();
        RecordedStep.Author by = null;
        for (int n = from; n <= to; n++) {
            StepNumbers.Step s = st.get(n - 1);
            if (s.checkOnly()) throw new DriverException(Reason.BAD_INPUT, "step " + n + " holds only checks: a fold takes actions");
            if (n < to && !s.checks.isEmpty()) {
                throw new DriverException(Reason.BAD_INPUT, "check " + n + ".1 sits between the steps: a fold takes no "
                        + "check between its steps (checks after the last one stay under it)");
            }
            RecordedStep r = records.get(s.action);
            if (r.action instanceof DoAction && !r.folded()) {
                throw new DriverException(Reason.BAD_INPUT, "step " + n + " is a do step: it folds into nothing");
            }
            if (r.group != null) throw new DriverException(Reason.BAD_INPUT, "step " + n + " is in a group: ungroup it first");
            if (by == null) by = r.author;
            if (r.author != by) {
                throw new DriverException(Reason.BAD_INPUT, "steps " + from + "-" + to + " are not one author's: the "
                        + "person's and the agent's steps never fold together");
            }
            if (r.folded()) {
                List<Matcher.Item> raw = new ArrayList<Matcher.Item>();
                for (RecordedStep x : r.raw) raw.add(Matcher.Item.raw(x.id, r.author.json, matchForm(x), false, false));
                span.add(Matcher.Item.folded(r.id, r.author.json, raw, false));
            } else {
                span.add(Matcher.Item.raw(r.id, r.author.json, matchForm(r), false, false));
            }
            ids.add(r.id);
        }
        return by;
    }

    /** The choice a fold by name takes: the one {@code step} names, else the only best one; {@code one}: a single step. */
    private static Matcher.Choice choose(List<Matcher.Choice> choices, String step, String what, boolean one) {
        List<String> names = new ArrayList<String>();
        for (Matcher.Choice c : choices) names.add(c.step + c.params);
        String match = one ? " matches" : " match";
        if (choices.isEmpty()) throw new DriverException(Reason.BAD_INPUT, what + match + " no domain step's pattern");
        if (step != null) {
            for (Matcher.Choice c : choices) if (c.step.equals(step) || c.step.endsWith(":" + step)) return c;
            throw new DriverException(Reason.BAD_INPUT, what + (one ? " is not '" : " are not '") + step + "'; "
                    + (one ? "it" : "they") + match + " " + String.join(", ", names));
        }
        if (choices.size() > 1) {
            throw new DriverException(Reason.BAD_INPUT, what + match + " more than one domain step: " + String.join(", ", names)
                    + "; name one with 'step'");
        }
        return choices.get(0);
    }

    /** Puts the raw steps of fold {@code n} back, kept raw (folding leaves them as they are). */
    synchronized String recordingUnfold(int n, Integer rev) {
        checkRev(rev);
        return edited(model.unfold(n, RecordedStep.Author.AGENT));
    }

    /** Keeps steps {@code from} to {@code to} raw: folding never takes them into a fold nor folds across them. */
    synchronized String recordingKeepRaw(int from, int to, Integer rev) {
        checkRev(rev);
        String did = model.keepRaw(from, to, RecordedStep.Author.AGENT);
        if (did != null) return edited(did);
        ObjectNode unchanged = (ObjectNode) readTree(published.json);
        unchanged.put("did", (from == to ? "step " + from + " is" : "steps " + from + "-" + to + " are") + " kept raw already");
        return unchanged.toString();
    }

    /**
     * The {@code vocabulary} tool's {@code match {from, to}}: what folding would make of steps {@code from} to
     * {@code to}, every raw step looked at as if just recorded (barriers as folding has them, but a replay's start is
     * none: the range is named), without changing the recording. {@code folds: [{from, to, step, params, say}]} in step numbers, {@code notes} for runs that did not fold.
     */
    String vocabularyMatch(Integer from, Integer to) {
        if (from == null || to == null) throw new DriverException(Reason.BAD_INPUT, "match needs 'from' and 'to' (step numbers)");
        List<Matcher.Item> items;
        List<Matcher.Candidate> vocabulary;
        Map<Integer, Integer> numbers = new HashMap<Integer, Integer>();
        ObjectNode r = mapper.createObjectNode();
        r.put("from", from);
        r.put("to", to);
        ArrayNode folds = r.putArray("folds");
        ArrayNode notes = r.putArray("notes");
        synchronized (this) {
            vocabulary = foldingVocabulary();
            if (vocabulary.isEmpty()) {
                r.put("hint", plugins == null ? "no plugin configured (agent argument plugins=…): nothing folds"
                        : "no ready plugin's vocabulary has a pattern: nothing folds");
                return r.toString();
            }
            int[] flat = model.flatRange(from, to);
            List<RecordedStep> records = model.records().subList(flat[0], flat[1] + 1);
            items = foldItems(records, Integer.MIN_VALUE, false);
            for (RecordedStep x : records) numbers.put(x.id, model.addressOf(x.id).step);
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Folding.BUDGET_MS);
        List<Matcher.Item> look = items;
        List<Matcher.Candidate> with = vocabulary;
        // on a stack as deep as the matcher thread's, with the time live folding has
        Matcher.Result result = Folding.onMatcherStack(() -> Matcher.fold(look, with, deadline));
        for (Matcher.Item i : result.items) {
            if (!(i.key instanceof Matcher.Fold)) continue;
            Matcher.Fold f = (Matcher.Fold) i.key;
            ObjectNode o = folds.addObject();
            int[] range = range(f.span, numbers);
            o.put("from", range[0]);
            o.put("to", range[1]);
            putChoice(o, f.choice);
        }
        for (Matcher.Note n : result.notes) {
            ObjectNode o = notes.addObject();
            int[] range = range(n.span, numbers);
            o.put("from", range[0]);
            o.put("to", range[1]);
            o.put("kind", n.kind.name().toLowerCase(java.util.Locale.ROOT));
            ArrayNode steps = o.putArray("steps");
            for (Matcher.Choice c : n.choices) putChoice(steps.addObject(), c);
            if (n.why != null) o.put("why", n.why);
        }
        if (result.outOfTime) r.put("outOfTime", true);
        return r.toString();
    }

    /** The first and last step number {@code span} covers (a fold made in the same look by its own span). */
    private static int[] range(List<Matcher.Item> span, Map<Integer, Integer> numbers) {
        int a = Integer.MAX_VALUE;
        int b = Integer.MIN_VALUE;
        for (Matcher.Item i : span) {
            int[] own = i.key instanceof Matcher.Fold ? range(((Matcher.Fold) i.key).span, numbers)
                    : new int[] {numbers.get(i.key), numbers.get(i.key)};
            a = Math.min(a, own[0]);
            b = Math.max(b, own[1]);
        }
        return new int[] {a, b};
    }

    /** {@code step}, {@code params} and (when a plugin offers it) {@code say} of a domain step a run matches. */
    private void putChoice(ObjectNode o, Matcher.Choice c) {
        o.put("step", c.step);
        o.set("params", mapper.valueToTree(c.params));
        DoAction a = foldAction(c);
        if (a != null) o.put("say", a.say);
    }

    /** {@link #publish(String)} for a change the palette needs no note about. */
    private void publish() {
        publish(null);
    }

    /**
     * Rebuilds what {@code recording list} answers and the palette lists from the model, tells the waiters when it is
     * no longer recording, and tells the palette, with {@code notice} for the person when the agent edited the steps
     * (null otherwise). Call it under the session's lock (or from the constructor) after every change to the model.
     */
    private void publish(String notice) {
        RecordingModel.Snapshot snap = model.snapshot();
        Flow flow = RecordingModel.viewOf(snap.steps);
        List<Action> actions = flow.steps;
        List<String> java = null;
        if (!actions.isEmpty()) {
            try {
                java = JavaTestWriter.lines(flow, keptForms);
                if (java.size() != actions.size()) java = null;
            } catch (ExportRefused e) {
                // the recording as a whole has no Java form (a step the writer refuses): list the steps without it
                java = null;
            }
        }
        ObjectNode r = mapper.createObjectNode();
        r.put("state", snap.state.json);
        if (snap.state != RecordingModel.State.IDLE) {
            r.put("startedBy", snap.startedBy.json);
            if (snap.startedAt > 0) {
                r.put("startedAt", Instant.ofEpochMilli(snap.startedAt).truncatedTo(ChronoUnit.SECONDS).toString());
            }
        }
        r.put("shared", snap.shared);
        r.put("fromReset", recordingFromReset);
        r.put("definedStart", definedStart);
        if (loadedTest != null) r.put("loadedFrom", loadedTest.toString());
        r.put("rev", snap.rev);
        // the groups in the order they first appear, numbered from 1; each with its first and last step number
        Map<Integer, Integer> groupNumbers = new LinkedHashMap<Integer, Integer>();
        Map<Integer, int[]> groupSteps = new LinkedHashMap<Integer, int[]>();
        Map<Integer, String> groupSays = new LinkedHashMap<Integer, String>();
        List<StepNumbers.Step> numbered = StepNumbers.of(actions);
        for (StepNumbers.Step st : numbered) {
            RecordedStep.Group g = snap.steps.get(st.first()).group;
            if (g == null) continue;
            if (!groupNumbers.containsKey(g.id)) {
                groupNumbers.put(g.id, groupNumbers.size() + 1);
                groupSteps.put(g.id, new int[] {st.number, st.number});
                groupSays.put(g.id, g.say);
            }
            groupSteps.get(g.id)[1] = st.number;
        }
        List<String> labels = StepNumbers.labels(actions);
        ArrayNode steps = r.putArray("steps");
        for (StepNumbers.Step st : numbered) {
            ObjectNode o = steps.addObject();
            o.put("n", st.number);
            RecordedStep first = snap.steps.get(st.first());
            o.put("author", first.author.json);
            if (!st.checkOnly()) {
                RecordedStep a = first;
                o.put("line", a.action.describe());
                putJava(o, java, a.action, st.action);
                o.set("step", stripNulls(mapper.valueToTree(a.action)));
                if (a.action.say != null) o.put("say", a.action.say);
                if (a.edited) o.put("edited", true);
                putRow(o, rowLine(a));
                putFolding(o, a);
            }
            if (first.group != null) o.put("group", groupNumbers.get(first.group.id));
            ArrayNode checks = o.putArray("checks");
            for (int flat : st.checks) {
                RecordedStep c = snap.steps.get(flat);
                ObjectNode co = checks.addObject();
                co.put("n", labels.get(flat));
                co.put("author", c.author.json);
                co.put("line", c.action.describe());
                putJava(co, java, c.action, flat);
                co.set("step", stripNulls(mapper.valueToTree(c.action)));
                if (c.action.say != null) co.put("say", c.action.say);
                if (c.edited) co.put("edited", true);
                putRow(co, rowLine(c));
            }
        }
        ArrayNode groups = r.putArray("groups");
        for (Map.Entry<Integer, int[]> e : groupSteps.entrySet()) {
            ObjectNode g = groups.addObject();
            g.put("from", e.getValue()[0]);
            g.put("to", e.getValue()[1]);
            g.put("say", groupSays.get(e.getKey()));
        }
        List<Inspector.StepLine> listed = new ArrayList<Inspector.StepLine>(snap.steps.size());
        for (int i = 0; i < snap.steps.size(); i++) {
            RecordedStep s = snap.steps.get(i);
            List<String> raw = new ArrayList<String>();
            if (s.folded()) for (RecordedStep x : s.raw) raw.add(x.action.describe());
            Map<String, String> params = s.action instanceof DoAction && ((DoAction) s.action).params != null
                    ? ((DoAction) s.action).params : Collections.<String, String>emptyMap();
            listed.add(new Inspector.StepLine(s.id, s.author.json, s.action.describe(), s.edited, labels.get(i), s.action.say,
                    StepNumbers.isCheck(s.action), s.group == null ? 0 : s.group.id, s.group == null ? null : s.group.say,
                    rowLine(s), raw, params));
        }
        if (!snap.notRecorded.isEmpty()) {
            ArrayNode lines = r.putArray("notRecorded");
            for (String line : snap.notRecorded) lines.add(line);
        }
        String warning = definedStart ? null : startWarning(actions, appReset.defined());
        if (warning != null) r.put("startWarning", warning);
        boolean recording = snap.state == RecordingModel.State.RECORDING;
        String reason = null;
        if (recording && snap.explicit) {
            reason = "a recording is running in the app (started by the " + snap.startedBy.json + " at "
                    + new SimpleDateFormat("HH:mm:ss").format(new Date(snap.startedAt))
                    + "): Done in the palette or recording {\"op\":\"stop\"} ends it";
        }
        // a hint is about the last steps: shown while the record it was found at is the last one and steps are recorded
        RecordedStep last = snap.steps.isEmpty() ? null : snap.steps.get(snap.steps.size() - 1);
        String hint = foldHint != null && recording && last != null && last.id == foldHint.id ? foldHint.text : null;
        published = new Published(r.toString(), recording, reason, Collections.unmodifiableList(listed),
                model.undoLabel(), hint);
        if (!recording) {
            synchronized (stopSignal) {
                stopSignal.notifyAll();
            }
        }
        view.changed(notice);
    }

    /**
     * What folding knows of a step's action record: {@code folded} (how many raw steps it stands for) and {@code raw}
     * (their lines) for a fold, {@code keepRaw}, and a note where a run ending at it did not fold: {@code foldCandidates}
     * (a tie), {@code foldSuggested} (a step that only suggests), {@code foldUndecided} (why).
     */
    private void putFolding(ObjectNode into, RecordedStep a) {
        if (a.folded()) {
            into.put("folded", a.raw.size());
            ArrayNode raw = into.putArray("raw");
            for (RecordedStep r : a.raw) raw.add(r.action.describe());
        }
        if (a.keepRaw) into.put("keepRaw", true);
        FoldNote note = a.folded() ? null : foldNotes.get(a.id);
        if (note == null) return;
        StepAddress first = model.addressOf(note.ids.get(0));
        StepAddress last = model.addressOf(a.id);
        if (first == null || last == null) return;
        if (note.kind == Matcher.Note.Kind.UNDECIDED) {
            into.put("foldUndecided", note.why);
            return;
        }
        ObjectNode o = into.putObject(note.kind == Matcher.Note.Kind.TIE ? "foldCandidates" : "foldSuggested");
        o.put("from", first.step);
        o.put("to", last.step);
        ArrayNode steps = o.putArray("steps");
        for (Matcher.Choice c : note.choices) putChoice(steps.addObject(), c);
    }

    /**
     * The record's {@code java} line, from {@code java} (null: the recording has none); a do step with no Java form gets
     * {@code noJava}, why, instead.
     */
    private void putJava(ObjectNode into, List<String> java, Action action, int flat) {
        if (java == null) return;
        if (java.get(flat) != null) {
            into.put("java", java.get(flat));
        } else if (action instanceof DoAction) {
            String why = doForms.of((DoAction) action).why();
            if (why == null) why = unwritable((DoAction) action);
            if (why != null) into.put("noJava", why);
        }
    }

    /**
     * Why the writer refuses do step {@code d} though it has a form (a step it makes that has no Java form), as the Java
     * export says it ("step 1" becomes "this step"); null when that is not it.
     */
    private String unwritable(DoAction d) {
        List<String> problems = new ArrayList<String>();
        try {
            JavaTestWriter.write(Collections.<Action>singletonList(d), CHECK_PACKAGE, CHECK_CLASS, CHECK_METHOD, doForms);
        } catch (ExportRefused e) {
            for (String p : e.problems()) {
                if (p.startsWith("step 1")) problems.add("this step" + p.substring("step 1".length()));
                else if (p.startsWith("check 1.")) problems.add("this check" + p.substring(p.indexOf(':')));
            }
        }
        return problems.isEmpty() ? null : String.join("; ", problems);
    }

    /** What the table row a record names is, and how it is named now; null for a record with no row. */
    private Inspector.RowLine rowLine(RecordedStep s) {
        ObjectNode holder = rowHolder(mapper.valueToTree(s.action));
        if (holder == null) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> row = mapper.convertValue(holder.get("row"), Map.class);
        boolean position = byPosition(row, s.row);
        if (position) {
            Integer index = s.row != null ? Integer.valueOf(s.row.index) : ((Number) row.get("index")).intValue();
            return new Inspector.RowLine("position", s.row == null ? null : s.row.byValue(), index);
        }
        return new Inspector.RowLine("value", row, s.row == null ? null : Integer.valueOf(s.row.index));
    }

    private void putRow(ObjectNode into, Inspector.RowLine line) {
        if (line == null) return;
        ObjectNode r = into.putObject("row");
        r.put("by", line.by);
        if (line.value != null) r.set("value", mapper.valueToTree(line.value));
        if (line.index != null) r.put("index", line.index.intValue());
    }

    private void forgetLoadedTest() {
        loadedTest = null;
        loadedName = null;
        loadedDescription = null;
    }

    /** {@link #startWarning(List, boolean)} for the recording, null when it began at a defined start. */
    private String startWarning() {
        return definedStart ? null : startWarning(model.actions(), appReset.defined());
    }

    /**
     * Without a defined start state the test must get to its starting screen from anywhere: null when its first step
     * (after a leading loginAs) is navigate, menu or press, or a domain step that begins with one, else why that step
     * may not be there. {@code plugin}: an app plugin could reset to a defined state, but the recording did not begin
     * at its reset.
     */
    private String startWarning(List<Action> steps, boolean plugin) {
        for (Action a : steps) {
            if (a instanceof LoginAsAction) continue;
            if (a instanceof NavigateAction || a instanceof MenuVerb || a instanceof PressVerb) return null;
            if (a instanceof DoAction && opensWithAGetToStep((DoAction) a)) return null;
            String where = plugin
                    ? "the screen the recording began on (it did not begin at the app plugin's reset)"
                    : "the screen the app happens to show after a reset (no app plugin: a reset only closes dialogs)";
            return "the test begins with '" + a.describe() + "', which works only on " + where + "; begin with a "
                    + "menu, a key shortcut or navigate so the test gets to its own starting screen";
        }
        return null;
    }

    /**
     * True when the domain step {@code d} stands for begins with a menu, press or navigate step, as its own pattern
     * says (the ready-time trace, the declared steps or its {@code match}; the body is not run). A {@code matchAlso}
     * pattern is another way a person may do it and does not count; a step with no own pattern never opens a test.
     */
    private boolean opensWithAGetToStep(DoAction d) {
        if (plugins == null) return false;
        for (Plugins.VocabularyStep v : plugins.vocabulary()) {
            if (!(v.plugin + ":" + v.step.id()).equals(d.step) && !v.step.id().equals(d.step)) continue;
            PatternCompiler.Compiled c = plugins.patternsOf(v);
            if (c == null || c.why() != null || c.patterns().isEmpty()) return false;
            List<ObjectNode> own = c.patterns().get(0).source();
            if (own.isEmpty() || own.get(0).path("optional").asBoolean()) return false;
            String action = own.get(0).path("action").asText();
            return "menu".equals(action) || "press".equals(action) || "navigate".equals(action);
        }
        return false;
    }
}
