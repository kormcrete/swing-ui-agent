package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.Driver;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Edt;
import io.github.kormcrete.swingui.driver.input.HitCheck;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Recorder;
import io.github.kormcrete.swingui.driver.model.Refs;
import io.github.kormcrete.swingui.driver.model.Resolved;
import io.github.kormcrete.swingui.driver.model.Resolver;
import io.github.kormcrete.swingui.driver.model.RowSpec;
import io.github.kormcrete.swingui.driver.widgets.Cells;
import io.github.kormcrete.swingui.driver.widgets.ListWidget;
import io.github.kormcrete.swingui.driver.widgets.TableWidget;
import io.github.kormcrete.swingui.driver.widgets.TreeWidget;
import io.github.kormcrete.swingui.driver.window.Modality;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.JList;
import javax.swing.JRootPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.JTableHeader;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Work between the agent and the person at the app, in the app window (while the palette is on, the agent's default):
 * <ul>
 *   <li>The person records: a palette in the main window (in the open modal dialog while one is open) starts a
 *       {@link Recording} with ● Record and ends it with Done. The agent reads it, ends it and exports it
 *       with the {@code recording} tool.</li>
 *   <li>The palette follows the agent: a recording the agent starts shows at once (the person's steps join it), and
 *       so do its stop, its load, a reset that replaces the recording, and its replay ({@link #agentStarted} …).</li>
 *   <li>The person loads a recorded test file (Load recorded test…) into the recording and runs it: the app reset, then every step
 *       replayed. While a run or replay goes on no other call drives the app ({@link #drivingReason}).</li>
 *   <li>{@link #highlight}: the agent shows what a locator matches - numbered boxes when several - with a question; the
 *       person answers with the check mark on a box, or "None of these" on the question bar.</li>
 *   <li>{@link #pick}: the agent asks the person to click what they mean; the click goes to the inspector, not the app,
 *       and comes back as the locator the recorder writes.</li>
 *   <li>{@link #hover}: passive; a box follows the pointer and names what the person sees under it (never the
 *       locator). The app keeps every event.</li>
 * </ul>
 * Boxes snap to what a locator can address: walking up from the deepest component, a table cell (row by its first
 * unique column), a whole column from its header, one tab of a tab strip. While a test run holds the agent the palette
 * leaves the window; while the person records, picks, answers or replays, no test run can take the agent.
 */
public final class Inspector {

    /** What the person answered. {@code chosen} is 1-based. */
    public static final class Answer {
        public final String outcome;
        public final Integer chosen;
        public final Target target;

        Answer(String outcome, Integer chosen, Target target) {
            this.outcome = outcome;
            this.chosen = chosen;
            this.target = target;
        }
    }

    /**
     * A key beside the person's step map (never part of the step): the view index of the table row the step names, so
     * the session can keep both ways to name the row. It is removed before the step is parsed.
     */
    public static final String ROW_INDEX = "rowIndex";

    /**
     * A key beside the person's step map (never part of the step): the step's target as the element was seen
     * ({@link io.github.kormcrete.swingui.driver.model.Recorder#seen}), which the session keeps inside the recording
     * for a vocabulary's patterns. It is removed before the step is parsed.
     */
    public static final String SEEN = "seen";

    /** Where the person's steps and checks go: the session that records the agent's steps too. Never called on the EDT. */
    public interface Sink {
        /**
         * The person pressed ● Record: a fresh recording starts here, on the screen they are on. Refused while a
         * recording someone started on purpose runs ({@link #AGENTS_RECORDING_RUNS} when it is the agent's) or the
         * agent replays; the result's line says why.
         */
        Result begin();

        /** The person pressed Done: the recording stops, the agent's steps no longer join it. */
        void end();

        /** A step the person already did: record it. */
        Result done(Map<String, Object> step);

        /** A check the person chose: run it, record it when it passes. */
        Result check(Map<String, Object> step);

        /**
         * Every step of the recording so far, agent's and person's, as one line each with its id and author. Never
         * waits for a step or a replay the session is running.
         */
        List<StepLine> steps();

        /** Step {@code index}'s target, or null for a step without one (menu, press). */
        Locator target(int index);

        /** Runs step {@code index} again, without recording it. */
        Result replay(int index);

        /**
         * Drops the STEP of the record with {@code id} (a check id means its step) and every step after it (a redo's cut).
         * Refused when that record is gone or the agent is replaying the recording; the result's line says what was cut,
         * or why not.
         */
        Result cutFrom(int id);

        /** The palette records on after a redo (also after Done): a stopped recording takes steps again. */
        void resume();

        /**
         * Deletes the record with {@code id}: an action id deletes its step together with the step's checks, a check id
         * deletes that check alone. Refused when it is gone or the agent is replaying the recording.
         */
        Result delete(int id);

        /**
         * Sets the sentence of what the records {@code fromId} to {@code toId} (inclusive, either order) cover: one
         * check id is that check's sentence, ids within one step are the step's sentence, ids spanning steps make a
         * group of the whole steps they touch. An empty {@code say} removes a sentence. Refused when an id is gone,
         * the sentence is bad, or the agent is replaying.
         */
        Result describe(int fromId, int toId, String say);

        /** Ends the group that holds the step of the record with {@code id}; its steps stay. Refused like {@link #delete}. */
        Result ungroup(int id);

        /**
         * Names the table row of the step or check with {@code id} another way: {@code by} is "value" (the row's key
         * column text) or "position" (its index). Counts as an edit of the step. Refused when the record is gone, has no
         * row, the other way is not known yet (a loaded step knows it after one passing replay), or the agent is replaying.
         */
        Result rowBy(int id, String by);

        /**
         * Takes back the last edit, the agent's or the person's. The result's line is what was undone (as
         * {@link #undoLabel} said), or why nothing was.
         */
        Result undo();

        /** What {@link #undo} would take back, e.g. "delete of step 7 by the person"; null when nothing. */
        String undoLabel();

        /** The package an export offers first: the app plugin's export package, else that of the app's main class. */
        String exportPackage();

        /**
         * Writes the recording as a JUnit {@code @UiTest} class, as {@code export_test} does, and keeps it (the person
         * may replay, redo or export again). The result's line says where it went, or why nothing was written.
         */
        Result export(String className, String pkg);

        /**
         * The folder Export… offers for a recorded test: one set explicitly (agent option, system property), else the folder
         * the person last used, else the default recorded tests folder. Absolute.
         */
        Path recordedFolder();

        /** The recorded test file the recording came from (loaded, or exported before), or null: the one a save goes over. */
        Path loadedTest();

        /**
         * Writes the recording as the recorded test {@code <folder>/<name>.json}, as {@code export_recorded_test} does, and keeps it. A
         * folder that cannot be written comes back with {@link Result#folder} set, so the palette asks for another.
         */
        Result exportRecordedTest(String name, Path folder);

        /** The recorded test files in {@code folder}, sorted; one that cannot be read comes with its problem. */
        List<RecordedTestEntry> recordedTests(Path folder);

        /**
         * Makes the recorded test file the recording, as if its steps had just been run after a reset, and remembers its folder.
         * The result's line is the recorded test's name, or why it was not loaded (the recording is then unchanged).
         */
        Result loadRecordedTest(Path file);

        /** Resets the app as a recorded test starts ({@code session reset} without touching the recording). */
        Result reset();

        /**
         * Puts the raw steps of the fold with record {@code id} back in its place, kept raw so they do not fold again at
         * once. Refused when it is gone, is no fold, or the agent is replaying; the result's line says what was done.
         */
        Result unfold(int id);

        /**
         * The bar's "recognised … — Undo": takes back the fold that made record {@code id}, as {@link #undo} would, while
         * it is still the last edit; refused otherwise (Unfold on its row still takes it back).
         */
        Result undoFold(int id);

        /**
         * What the steps at the end of the recording could become, e.g. "… could become: Add A4 paper, 500 sheets to the
         * cart (1 of 2)"; null when they begin no domain step. The published copy: never waits for folding.
         */
        String foldHint();

        /**
         * {@link #steps}, {@link #undoLabel} and {@link #foldHint} as of one change of the recording, read together: the
         * steps panel shows them as one. Never waits, as {@link #steps}.
         */
        StepsView stepsView();

        /**
         * The app plugin's domain checks that {@code checks} (the expect steps the Check menu offers for one element, from
         * what it shows now; {@code seen}: the element as it was seen, or null) fit, each filled with the values they
         * show; empty without a vocabulary. Off the EDT and never under the session's lock: it only matches.
         */
        List<DomainCheck> domainChecks(List<Map<String, Object>> checks, Locator seen);
    }

    /** What the steps panel shows of the recording, as of one change: its steps, what undo would take back, the hint. */
    public static final class StepsView {
        public final List<StepLine> steps;
        /** Null when nothing. */
        public final String undoLabel;
        /** Null when none. */
        public final String hint;

        public StepsView(List<StepLine> steps, String undoLabel, String hint) {
            this.steps = steps;
            this.undoLabel = undoLabel;
            this.hint = hint;
        }
    }

    /** A domain check the Check menu offers: its sentence with the values filled in, and the {@code do} step that is it. */
    public static final class DomainCheck {
        public final String sentence;
        public final Map<String, Object> step;

        public DomainCheck(String sentence, Map<String, Object> step) {
            this.sentence = sentence;
            this.step = step;
        }
    }

    /**
     * One step as the steps panel lists it: its id, which it keeps for the whole recording (the person's delete and
     * redo name it, so a step the agent put in meanwhile never shifts them onto another step), who made it ("person",
     * "agent" or "recorded test"), its line, and whether it was changed after it was recorded and no replay passed it since.
     */
    public static final class StepLine {
        public final int id;
        public final String author;
        public final String line;
        public final boolean edited;
        /** "7" for a step's action, "7.1" for a check; null when the list was built without numbers. */
        public final String label;
        /** The sentence, or null. */
        public final String say;
        /** True for a check (it belongs to the step before it). */
        public final boolean check;
        /** The group this record's step is in (the same number for every record of its steps), 0 for none. */
        public final int groupId;
        /** The group's sentence; null when there is no group. */
        public final String groupSay;
        /** The table row this record names and how (by value or by position); null for a record with no row. */
        public final RowLine row;
        /** For a fold (a do step folding made of recorded steps): the lines of the steps it stands for; else empty. */
        public final List<String> raw;
        /** For a do step: its values by param name (shown bold in its sentence); else empty. */
        public final Map<String, String> params;

        public StepLine(int id, String author, String line, boolean edited) {
            this(id, author, line, edited, null, null, line.startsWith("expect("), 0, null, null);
        }

        public StepLine(int id, String author, String line, boolean edited, String label, String say, boolean check,
                int groupId, String groupSay) {
            this(id, author, line, edited, label, say, check, groupId, groupSay, null);
        }

        public StepLine(int id, String author, String line, boolean edited, String label, String say, boolean check,
                int groupId, String groupSay, RowLine row) {
            this(id, author, line, edited, label, say, check, groupId, groupSay, row, Collections.<String>emptyList(),
                    Collections.<String, String>emptyMap());
        }

        public StepLine(int id, String author, String line, boolean edited, String label, String say, boolean check,
                int groupId, String groupSay, RowLine row, List<String> raw, Map<String, String> params) {
            this.raw = Collections.unmodifiableList(new ArrayList<String>(raw));
            this.params = Collections.unmodifiableMap(new LinkedHashMap<String, String>(params));
            this.id = id;
            this.author = author;
            this.line = line;
            this.edited = edited;
            this.label = label;
            this.say = say;
            this.check = check;
            this.groupId = groupId;
            this.groupSay = groupSay;
            this.row = row;
        }

        /** A check: it belongs to the action before it. */
        boolean check() {
            return check;
        }

        /** A fold: a do step that stands for the recorded steps {@link #raw}. */
        boolean folded() {
            return !raw.isEmpty();
        }

        /** A step of the plugin's vocabulary (a do step), not a check: shown with ◆. */
        boolean domain() {
            return !check && line.startsWith("do(");
        }
    }

    /**
     * The table row a step or check names: {@code by} is how it is named now, "value" or "position"; {@code value}
     * (the key column's {@code {column: text}}) and {@code index} (the row's position) are both ways as far as they are
     * known, the current one and the other ({@code valueKnown}/{@code indexKnown}): null for a way not known yet.
     */
    public static final class RowLine {
        public final String by;
        public final Map<String, Object> value;
        public final Integer index;
        public final boolean valueKnown;
        public final boolean indexKnown;

        public RowLine(String by, Map<String, Object> value, Integer index) {
            this.by = by;
            this.value = value;
            this.index = index;
            this.valueKnown = value != null;
            this.indexKnown = index != null;
        }
    }

    /** A recorded test file as Load recorded test… lists it. */
    public static final class RecordedTestEntry {
        public final String name;
        /** Null when it has none. */
        public final String description;
        public final int steps;
        /** Why the file cannot be read, or null; a file with a problem cannot be loaded. */
        public final String problem;
        public final Path file;

        public RecordedTestEntry(String name, String description, int steps, String problem, Path file) {
            this.name = name;
            this.description = description;
            this.steps = steps;
            this.problem = problem;
            this.file = file;
        }
    }

    public static final class Result {
        public final boolean ok;
        public final String line;
        /** Refused because of the folder: it cannot be created or written. */
        public final boolean folder;

        public Result(boolean ok, String line) {
            this(ok, line, false);
        }

        public Result(boolean ok, String line, boolean folder) {
            this.ok = ok;
            this.line = line;
            this.folder = folder;
        }
    }

    /** Why ● Record is refused while the agent's recording runs: the palette then shows that recording. */
    public static final String AGENTS_RECORDING_RUNS = "the agent's recording is running: Done ends it";

    private static volatile Inspector instance;

    private final Refs refs;
    private final Resolver resolver;
    private final Recorder recorder;
    /** Why a test run must wait: the agent's question or pick is open. Read off the EDT by the agent's lease. */
    private volatile String asking;
    /** Why a test run must wait: the person records or replays. */
    private volatile String recordingBusy;
    /** Why no other call may drive the app: the person runs a recorded test or replays steps from the palette. */
    private volatile String driving;

    /** EDT only. */
    private final Map<JRootPane, Overlay> overlays = new WeakHashMap<JRootPane, Overlay>();
    private CompletableFuture<Answer> pending;
    private AWTEventListener hoverListener;
    private Sink sink;
    private Bar idle;
    private Overlay paletteHome;
    /** The palette's window: the open modal dialog nothing blocks, else the main window ({@link #attachPalette}). */
    Supplier<JRootPane> paletteRoot = Inspector::liveRoot;
    /** The component that has the keyboard now: a sentence typed in the Steps list gives it back ({@link #giveFocus}). */
    Supplier<Component> focusOwner = () -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
    /**
     * Gives the keyboard back to an app component; false when it cannot have it: gone with a closed dialog, or in a
     * window that is not the focused one.
     */
    Predicate<Component> giveFocus = c -> c.isShowing() && c.requestFocusInWindow();
    /** The app's windows, which Check mode covers. */
    Supplier<List<JRootPane>> roots = Inspector::appRoots;
    /** The person's drag in each window the palette left (null: none there); outlives the window's overlay. */
    private final Map<JRootPane, Point> spots = new WeakHashMap<JRootPane, Point>();
    /**
     * The palette's default spot in each window it showed in ({@link Overlay#spot}), picked once there and kept while
     * the app runs, also after the window's overlay went; never saved, so the next start picks again.
     */
    private final Map<JRootPane, double[]> homes = new WeakHashMap<JRootPane, double[]>();
    private Recording recording;
    /** While the agent replays: its bar, without buttons, in place of the palette's. EDT. */
    private Bar agentBar;
    /** Load recorded test…'s panel while it is open. EDT. */
    RecordedTestsPanel testsPanel;
    private boolean testRun;
    /**
     * The kind Export… opens on when no recorded test is loaded: the one the person used last in this JVM, a recorded
     * test until they choose otherwise. EDT.
     */
    String exportKind = ExportPanel.RECORDED;
    private Component snappedAt;
    private Integer snappedTab;
    private int snappedRow = -2;
    private int snappedColumn = -2;
    private Target snapped;
    private final ExecutorService sinkThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "swingui-inspect");
        t.setDaemon(true);
        return t;
    });

    Inspector(Refs refs, Resolver resolver, Recorder recorder) {
        this.refs = refs;
        this.resolver = resolver;
        this.recorder = recorder;
    }

    /** The one inspector of this JVM, over the driver's own model. */
    public static synchronized Inspector of(Driver driver) {
        if (instance == null) instance = new Inspector(driver.refs(), driver.resolver(), driver.recorder());
        return instance;
    }

    /** Makes {@code inspector} this JVM's one (null: none), returning the one before; for tests. */
    static synchronized Inspector swap(Inspector inspector) {
        Inspector before = instance;
        instance = inspector;
        return before;
    }

    /** Why a test run must wait - a person is busy in the app - or null; null too when no inspector was ever made. */
    public static String engagedReason() {
        Inspector i = instance;
        return i == null ? null : i.engaged();
    }

    /** Why a test run must wait on this inspector, or null. */
    String engaged() {
        String a = asking;
        return a != null ? a : recordingBusy;
    }

    /**
     * Why every mutating call from elsewhere must wait - a person runs a recorded test or replays steps from the palette, whose
     * own steps go to the session directly - or null; null too when no inspector was ever made.
     */
    public static String drivingReason() {
        Inspector i = instance;
        return i == null ? null : i.driving();
    }

    /** {@link #drivingReason} of this inspector. */
    String driving() {
        return driving;
    }

    /** Set when a palette replay or run starts, cleared (null) when it ends. */
    void driving(String reason) {
        driving = reason;
    }

    /** How many of the agent's own steps are running now: the person's recorder ignores input meanwhile. */
    private static final java.util.concurrent.atomic.AtomicInteger agentActing = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * The agent starts ({@code true}) or ends one of its own steps. Its input reaches the app as events the person's
     * recorder would also see; the agent's side records the step already, so the recorder must not record it twice.
     */
    public static void agentActing(boolean on) {
        if (on) {
            agentActing.incrementAndGet();
            return;
        }
        Inspector i = instance;
        if (agentActing.decrementAndGet() == 0 && i != null) SwingUtilities.invokeLater(i::agentDone);
    }

    /** How many runs of several agent steps are going on now (a batch, a replay, a recorded test's run). */
    private static final java.util.concurrent.atomic.AtomicInteger agentRuns = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * A run of several agent steps starts ({@code true}) or ends: a batch, {@code do_actions}, a palette replay. A
     * palette bar that steps aside for one of its steps stays aside until the run ends, then goes back to its spot.
     */
    public static void agentRun(boolean on) {
        if (on) {
            agentRuns.incrementAndGet();
            return;
        }
        Inspector i = instance;
        if (agentRuns.decrementAndGet() == 0 && i != null) SwingUtilities.invokeLater(i::runDone);
    }

    /** Whether a run of several agent steps is going on ({@link #agentRun}). */
    public static boolean agentIsRunning() {
        return agentRuns.get() > 0;
    }

    /** The last run ended: a bar that stepped aside during it goes back to its spot. EDT. */
    void runDone() {
        if (agentIsRunning()) return;
        for (Overlay o : overlays.values()) o.endYield(false);
    }

    /** Whether one of the agent's own steps is running. */
    static boolean agentIsActing() {
        return agentActing.get() > 0;
    }

    /** A test run took ({@code true}) or gave back the agent: the palette leaves the window meanwhile, then returns. */
    public static void testRun(boolean on) {
        Inspector i = instance;
        if (i == null) return;
        SwingUtilities.invokeLater(() -> i.testRunNow(on));
    }

    /** {@link #testRun} on this inspector. EDT. */
    void testRunNow(boolean on) {
        testRun = on;
        for (Overlay o : overlays.values()) o.hidePalette(on);
    }

    /**
     * The agent is about to press {@code p} in {@code target} and {@code cover} is in the way: when it is the person's
     * palette in the target's window, the palette steps off (see {@link Overlay#makeRoom}). The catcher and the agent's
     * own check-mark badges never move. EDT.
     */
    boolean stepOff(Component cover, Component target, Point p) {
        // the cover's window: a target in an internal frame has a root pane of its own, without an overlay
        Overlay o = overlays.get(SwingUtilities.getRootPane(cover));
        if (o == null || !o.holds(cover)) return false;
        JLayeredPane layers = o.layers();
        Rectangle seen = target instanceof JComponent
                ? ((JComponent) target).getVisibleRect()
                : new Rectangle(0, 0, target.getWidth(), target.getHeight());
        Rectangle area = SwingUtilities.convertRectangle(target, seen, layers);
        // a big target (a table) keeps only the point clear, or the palette would never find a spot
        if (area.width > layers.getWidth() / 2 || area.height > layers.getHeight() / 2) {
            Point at = SwingUtilities.convertPoint(target, p, layers);
            area = new Rectangle(at.x - 12, at.y - 12, 24, 24);
        }
        return o.makeRoom(area);
    }

    /**
     * The agent's last step ended: a palette that left a window for it comes back; a bar that stepped aside for it goes
     * back to its spot, unless the step was one of a run ({@link #agentRun}). EDT.
     */
    void agentDone() {
        boolean inRun = agentIsRunning();
        for (Overlay o : overlays.values()) o.endYield(inRun);
        attachPalette();
    }

    void recordingBusy(String reason) {
        recordingBusy = reason;
    }

    /**
     * The session's recording changed - a step, an edit, either author's: the person's recording re-reads it at once
     * and shows {@code notice} (the agent's edit, e.g. "the agent deleted step 7"; null: nothing to say) on its bar
     * for a few seconds. Returns at once: the session calls it under its lock, and the EDT may be waiting for that lock.
     */
    public static void recordingChanged(String notice) {
        Inspector i = instance;
        if (i == null) return;
        SwingUtilities.invokeLater(() -> {
            if (i.recording != null) i.recording.changed(notice);
        });
    }

    /**
     * The agent started a recording the person shares: the palette switches to it at once, saying who started it, and
     * the person's steps join it. Like every call from the agent's side here, it returns at once ({@code invokeLater})
     * and does nothing without a palette.
     */
    public static void agentStarted() {
        later(Inspector::agentStartedNow);
    }

    /** The agent stopped the shared recording: the palette shows it stopped, as after the person's Done. */
    public static void agentStopped() {
        Inspector i = instance;
        if (i == null) return;
        // the session stopped already: a test run need not wait for the EDT to hear it
        String busy = i.recordingBusy;
        if (Recording.PERSON_BUSY.equals(busy) || Recording.AGENT_BUSY.equals(busy)) i.recordingBusy = null;
        later(Inspector::agentStoppedNow);
    }

    /** The agent loaded the recorded test {@code name} ({@code steps} steps): shown as the palette's own load shows it. */
    public static void agentLoaded(String name, int steps) {
        later(i -> i.agentLoadedNow(name, steps));
    }

    /** The recording was replaced by one the person does not share (a session reset, a clearing export): idle again. */
    public static void recordingReplaced() {
        later(Inspector::recordingReplacedNow);
    }

    /**
     * The agent replays steps: a bar without buttons says what it does ({@code reason}, updated per step), the steps
     * panel's controls go and the person's input is not recorded; null when it ended: the palette is back as before.
     */
    public static void agentReplaying(String reason) {
        later(i -> i.agentReplayingNow(reason));
    }

    /**
     * Folding recognised the steps that record {@code id} now stands for ({@code steps} of them) as the domain step
     * {@code sentence}: the bar says so for a while, with an Undo.
     */
    public static void foldRecognised(int id, String sentence, int steps) {
        later(i -> {
            if (i.recording != null) i.recording.recognised(id, sentence, steps);
        });
    }

    /** The agent's replay ran step {@code id}: the steps panel marks it ✓ or ✗. */
    public static void agentReplayed(int id, boolean ok) {
        later(i -> {
            if (i.recording != null) i.recording.replayed(id, ok);
        });
    }

    /** Runs {@code work} on the EDT later, on the JVM's inspector, once it has a palette (a sink); else nothing. */
    private static void later(Consumer<Inspector> work) {
        Inspector i = instance;
        if (i == null) return;
        SwingUtilities.invokeLater(() -> {
            if (i.sink != null) work.accept(i);
        });
    }

    /** {@link #agentStarted} on this inspector: Load recorded test… closes, the old recording goes, the agent's shows. EDT. */
    void agentStartedNow() {
        if (sink == null || paletteHome == null) return;
        closeLoad();
        if (recording != null) recording.dispose();
        recording = new Recording(this, sink, sinkThread, paletteHome, this::startRecording);
        recording.startedByAgent();
        if (agentBar != null) recording.agentReplay(agentBar);
    }

    /** {@link #agentStopped} on this inspector. EDT. */
    void agentStoppedNow() {
        if (recording != null) recording.stoppedByAgent();
    }

    /** {@link #agentLoaded} on this inspector. EDT. */
    void agentLoadedNow(String name, int steps) {
        if (sink == null || paletteHome == null) return;
        closeLoad();
        if (recording != null) recording.dispose();
        recording = Recording.loaded(this, sink, sinkThread, paletteHome, this::startRecording, name, steps);
    }

    /** {@link #recordingReplaced} on this inspector: the recording goes, the idle palette comes back. EDT. */
    void recordingReplacedNow() {
        if (recording == null) return;
        recording.dispose();
        recording = null;
        recordingBusy(null);
        if (paletteHome != null && agentBar == null) paletteHome.bar(idle);
    }

    /** {@link #agentReplaying} on this inspector. EDT. */
    void agentReplayingNow(String reason) {
        if (reason == null) {
            if (agentBar == null) return;
            agentBar = null;
            if (recording != null) recording.agentReplayEnded();
            else if (paletteHome != null) paletteHome.bar(idle);
            return;
        }
        if (agentBar != null) {
            agentBar.text(reason);
            return;
        }
        agentBar = new Bar("Agent", reason).dot(Overlay.HOVER, false);
        // the person's panels wait too: nothing they press there could run meanwhile
        closeLoad();
        if (recording != null) recording.agentReplay(agentBar);
        else if (paletteHome != null) paletteHome.bar(agentBar);
    }

    // ---- the person records ---------------------------------------------------------------------------------------

    /**
     * Puts the palette (● Record) into the app's main window - into the open modal dialog while one is open - now and
     * whenever the windows change. The person's recordings go to {@code sink}. Call once, when the palette is on
     * ({@code palette=off}, {@code mode=background} and a headless JVM leave it off).
     */
    public void enable(Sink sink) {
        SwingUtilities.invokeLater(() -> {
            if (this.sink != null) return;
            install(sink);
            // a backstop: a window that changes without an event (hidden, resized) is followed within a second
            new Timer(1000, e -> attachPalette()).start();
            attachPalette();
        });
    }

    /**
     * Everything {@link #enable} sets up but the timer: the sink, the idle palette, the palette stepping off what the
     * agent acts on, and a window that opens, closes or comes to the front moving the palette at once. EDT.
     */
    void install(Sink sink) {
        this.sink = sink;
        idle = new Bar("swing-ui-agent", "").button("● Record", Overlay.NO, this::startRecording)
                .button("Load recorded test…", Overlay.HOVER, this::toggleLoad);
        HitCheck.cover((c, t, p) -> instance != null && instance.stepOff(c, t, p));
        Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
            int id = e.getID();
            if (id == WindowEvent.WINDOW_OPENED || id == WindowEvent.WINDOW_CLOSED
                    || id == WindowEvent.WINDOW_ACTIVATED) {
                SwingUtilities.invokeLater(this::attachPalette);
            }
        }, AWTEvent.WINDOW_EVENT_MASK);
    }

    /**
     * The palette lives where the person can press it: in the open modal dialog nothing blocks ({@link #paletteRoot})
     * when that has room for it, else in the main window (the first showing frame). It follows as dialogs open and
     * close, during a replay too. Not while the agent acts: a move between its hit check and its posted press could put
     * a palette button under the press. EDT.
     */
    void attachPalette() {
        if (sink == null || agentIsActing()) return;
        Bar want = agentBar != null ? agentBar : recording != null ? recording.bar : idle;
        JRootPane root = paletteRoot.get();
        if (root != null && !fits(root, want)) {
            JRootPane main = mainRoot();
            root = main != null ? main : paletteHome != null ? paletteHome.root : root;
        }
        if (root == null) return;
        Overlay o = overlay(root);
        if (paletteHome != o) movePalette(o, want);
        o.hidePalette(testRun);
        // a replay shows its own bar until it ends
        if (recording == null || !recording.replaying) o.bar(want);
        // the window's controls changed (a dialog's next page): the agent's question avoids them; the palette keeps its spot
        o.recheck();
    }

    /**
     * Moves the palette from its window to {@code to}, with the person's recording - its panel and a running replay's
     * bar too. The bar takes the spot the person dragged it to in {@code to} before ({@link #spotIn}), else its default
     * spot there: picked off the window's buttons, fields and labels the first time the palette shows there, the same
     * one every time after ({@link #homes}). A test run's palette moves in hidden. EDT.
     */
    private void movePalette(Overlay to, Bar want) {
        Overlay from = paletteHome;
        // the person's drag stays with the window it was made in; every bar shown in `to` takes `to`'s (Overlay.bar)
        if (from != null) spots.put(from.root, from.dragged());
        to.hidePalette(testRun);
        paletteHome = to;
        if (recording != null) recording.rehome(to); else to.bar(agentBar != null ? agentBar : idle);
        if (testsPanel != null) {
            // out of the old window first: while it still holds the panel, any change there would take it back
            if (from != null) from.panel(null);
            to.panel(testsPanel);
        }
        if (from == null) return;
        from.bar(null);
        from.panel(null);
        from.endYield(false);
        if (from.empty()) {
            from.dispose();
            if (overlays.get(from.root) == from) overlays.remove(from.root);
        }
    }

    /**
     * Where the palette's bar goes in {@code o}'s window: where the person dragged it there before it last left the
     * window - their spot wins, even over the window's controls; null (no drag there): its default spot, picked once
     * clear of the window's small components ({@link Overlay#smallIn}, {@link #homes}).
     */
    private Point spotIn(Overlay o) {
        return spots.get(o.root);
    }

    /** Whether {@code root} has room for the palette: its buttons in a row, and three bars' height. */
    private static boolean fits(JRootPane root, Bar bar) {
        JLayeredPane layers = root.getLayeredPane();
        return layers.getWidth() >= bar.minimumWidth() + 16 && layers.getHeight() >= 3 * bar.getPreferredSize().height;
    }

    /**
     * ● Record: a fresh recording, from the screen the person is on. Refused while the agent's recording runs: the
     * palette then shows that one. EDT.
     */
    private void startRecording() {
        if (sink == null || paletteHome == null || agentBar != null) return;
        if (recording != null && recording.replaying) return;
        closeLoad();
        if (recording != null) recording.dispose();
        recording = null;
        sinkThread.submit(() -> {
            Result begun = sink.begin();
            SwingUtilities.invokeLater(() -> {
                if (!begun.ok) {
                    if (AGENTS_RECORDING_RUNS.equals(begun.line)) {
                        agentStartedNow();
                    } else {
                        idle.text("✗ not recording: " + begun.line);
                        if (paletteHome != null && agentBar == null) paletteHome.bar(idle);
                    }
                    return;
                }
                idle.text("");
                // where the palette is now: an agent step may have opened or closed a dialog while begin() waited
                Overlay home = paletteHome;
                if (home == null) return;
                // a recorded test loaded while begin() waited goes: this ● Record came after it
                if (recording != null) recording.dispose();
                recording = new Recording(this, sink, sinkThread, home, this::startRecording);
                recording.start();
            });
        });
    }

    /**
     * Load recorded test…: opens (or closes) the panel with the recorded tests in the recorded tests folder; it replaces the steps or export panel.
     * Not while the person records or replays. EDT.
     */
    void toggleLoad() {
        if (testsPanel != null) {
            closeLoad();
            return;
        }
        if (sink == null || paletteHome == null || busyRecording()) return;
        if (recording != null) recording.closePanels();
        sinkThread.submit(() -> {
            Path folder = sink.recordedFolder();
            List<RecordedTestEntry> tests = listed(folder);
            int current = sink.steps().size();
            SwingUtilities.invokeLater(() -> {
                if (testsPanel != null || paletteHome == null || busyRecording()) return;
                testsPanel = new RecordedTestsPanel(folder, tests, current, this::listTests, this::loadTest, this::closeLoad);
                paletteHome.panel(testsPanel);
            });
        });
    }

    /** The person records or replays, or the agent replays: no recorded test is loaded meanwhile. EDT. */
    private boolean busyRecording() {
        return agentBar != null || recording != null && (recording.active() || recording.replaying);
    }

    /** Load recorded test…'s panel goes, with its folder dialog. EDT. */
    void closeLoad() {
        if (testsPanel == null) return;
        testsPanel.closeChooser();
        if (paletteHome != null && paletteHome.holds(testsPanel)) paletteHome.panel(null);
        testsPanel = null;
    }

    /** The recorded tests in {@code folder}; none when it cannot be listed. Sink thread. */
    private List<RecordedTestEntry> listed(Path folder) {
        try {
            return sink.recordedTests(folder);
        } catch (RuntimeException e) {
            return new ArrayList<RecordedTestEntry>();
        }
    }

    /** The person picked another folder in the panel: its recorded tests. EDT. */
    private void listTests(Path folder) {
        RecordedTestsPanel panel = testsPanel;
        if (panel == null) return;
        sinkThread.submit(() -> {
            List<RecordedTestEntry> tests = listed(folder);
            SwingUtilities.invokeLater(() -> { if (testsPanel == panel) panel.show(folder, tests); });
        });
    }

    /**
     * Load: the recorded test becomes the recording, shown as a loaded {@link Recording} with its steps ready to run; the
     * previous recording goes. A refused load says why in the panel and changes nothing. EDT.
     */
    private void loadTest(Path file) {
        RecordedTestsPanel panel = testsPanel;
        if (panel == null || busyRecording()) return;
        sinkThread.submit(() -> {
            Result r = sink.loadRecordedTest(file);
            int n = r.ok ? sink.steps().size() : 0;
            SwingUtilities.invokeLater(() -> {
                if (!r.ok) {
                    if (testsPanel == panel) panel.refused(r.line);
                    return;
                }
                closeLoad();
                Overlay home = paletteHome;
                if (home == null) return;
                if (recording != null) recording.dispose();
                recording = Recording.loaded(this, sink, sinkThread, home, this::startRecording, r.line, n);
            });
        });
    }

    /** The person's recording - or the recorded test they loaded - or null before their first ● Record or load. EDT. */
    Recording recording() {
        return recording;
    }

    // ---- the agent asks -------------------------------------------------------------------------------------------

    /** The recorder's locator for every match, as the box labels and the answer show it. */
    public List<Target> targets(List<Resolved> matches) {
        return Edt.call(() -> {
            List<Target> out = new ArrayList<Target>();
            for (Resolved r : matches) out.add(target(r));
            return out;
        });
    }

    /**
     * Boxes every target, numbered when there are several, with {@code question} on a bar. Waits up to {@code waitMs}
     * for the answer; 0 shows them and returns at once, and they stay until {@link #clear}.
     */
    public Answer highlight(List<Target> targets, String question, long waitMs) {
        CompletableFuture<Answer> answer = new CompletableFuture<Answer>();
        Edt.run(() -> {
            endQuestion(answer);
            Overlay first = null;
            for (int i = 0; i < targets.size(); i++) {
                Target t = targets.get(i);
                Overlay o = overlayFor(t.component);
                if (o == null) continue;
                if (first == null) first = o;
                final int n = i + 1;
                Overlay.Mark m = Overlay.Mark.of(t, targets.size() > 1 ? String.valueOf(n) : t.shown, Overlay.ASK, false);
                o.mark(m, waitMs <= 0 ? null : () -> {
                    m.color = Overlay.YES;
                    answer.complete(new Answer("confirmed", n, t));
                });
            }
            if (first != null && waitMs > 0) {
                asking = "the agent is asking the person at the app a question (they answer in its bar)";
                String q = question != null ? question : targets.size() > 1 ? "Which one do you mean?" : "This one?";
                first.ask(new Bar("Agent", q).button(targets.size() > 1 ? "None of these" : "No", Overlay.NO,
                        () -> answer.complete(new Answer("rejected", null, null))));
            }
        });
        if (waitMs <= 0) return new Answer("shown", null, null);
        return await(answer, waitMs);
    }

    /** The person clicks what they mean, in the focused app window (the main one when none is focused). */
    public Answer pick(String question, long waitMs) {
        CompletableFuture<Answer> answer = new CompletableFuture<Answer>();
        Edt.run(() -> {
            if (recording != null && (recording.state == Recording.State.CHECKING || recording.replaying)) {
                answer.complete(new Answer("personIsBusy", null, null));
                return;
            }
            endQuestion(answer);
            Overlay o = focusedOverlay();
            if (o == null) {
                answer.complete(new Answer("noWindow", null, null));
                return;
            }
            asking = "a person is picking an element in the app (Cancel in its bar ends it)";
            o.ask(new Bar("Agent", question == null ? "Click what you mean" : question)
                    .button("Cancel", Overlay.NO, () -> answer.complete(new Answer("cancelled", null, null))));
            o.catcher(e -> hoverIn(o, e), e -> {
                Target t = snapIn(o, e);
                if (t == null) return;
                o.hover(Overlay.Mark.of(t, t.shown, Overlay.YES, false));
                answer.complete(new Answer("picked", null, t));
            });
        });
        if (answer.isDone()) return answer.join();
        return await(answer, waitMs);
    }

    /** Passive: a dashed box follows the pointer and names what is under it. The app still gets every event. */
    public void hover(boolean on) {
        Edt.run(() -> {
            if (on && hoverListener == null) {
                hoverListener = this::onHoverEvent;
                Toolkit.getDefaultToolkit().addAWTEventListener(hoverListener, AWTEvent.MOUSE_MOTION_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK);
            } else if (!on && hoverListener != null) {
                Toolkit.getDefaultToolkit().removeAWTEventListener(hoverListener);
                hoverListener = null;
                for (Overlay o : overlays.values()) o.hover(null);
                sweep();
            }
        });
    }

    /** Removes the agent's boxes and question; a waiting question is answered "cleared". The person's recording stays. */
    public void clear() {
        Edt.run(() -> endQuestion(null));
    }

    private Answer await(CompletableFuture<Answer> answer, long waitMs) {
        Answer a;
        try {
            a = answer.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            a = new Answer("timeout", null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            a = new Answer("interrupted", null, null);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
        asking = null;
        // let the person see the green box, then take the question away
        Edt.run(() -> {
            if (pending != answer) return;
            Timer t = new Timer(700, e -> { if (pending == answer) endQuestion(null); });
            t.setRepeats(false);
            t.start();
        });
        return a;
    }

    /** EDT. Ends the waiting question, if any: its boxes, bar and catcher go; the person's palette stays. */
    private void endQuestion(CompletableFuture<Answer> next) {
        asking = null;
        if (pending != null && !pending.isDone()) pending.complete(new Answer("cleared", null, null));
        pending = next;
        boolean checking = recording != null && recording.state == Recording.State.CHECKING;
        for (Overlay o : overlays.values()) {
            o.clearMarks();
            o.ask(null);
            if (!checking) o.removeCatcher();
        }
        sweep();
    }

    private void sweep() {
        List<JRootPane> gone = new ArrayList<JRootPane>();
        for (Map.Entry<JRootPane, Overlay> e : overlays.entrySet()) {
            if (e.getValue().empty() && hoverListener == null) {
                e.getValue().dispose();
                gone.add(e.getKey());
            }
        }
        for (JRootPane r : gone) overlays.remove(r);
    }

    Collection<Overlay> overlays() {
        return new ArrayList<Overlay>(overlays.values());
    }

    private void onHoverEvent(AWTEvent event) {
        if (!(event instanceof MouseEvent)) return;
        MouseEvent e = (MouseEvent) event;
        if (e.getID() != MouseEvent.MOUSE_MOVED && e.getID() != MouseEvent.MOUSE_EXITED) return;
        Component src = e.getComponent();
        if (src == null || Overlay.ours(src)) return;
        JRootPane root = src instanceof RootPaneContainer ? ((RootPaneContainer) src).getRootPane() : SwingUtilities.getRootPane(src);
        if (root == null || isPopup(SwingUtilities.getWindowAncestor(root))) return;
        Overlay o = overlay(root);
        Point p = SwingUtilities.convertPoint(src, e.getPoint(), o.layers());
        Component under = o.appComponentAt(p);
        if (under == null) {
            o.hover(null);
            return;
        }
        Target t = snap(under, SwingUtilities.convertPoint(o.layers(), p, under));
        o.hover(t == null ? null : Overlay.Mark.of(t, t.shown, Overlay.HOVER, true));
    }

    /** The catcher's mouse event, snapped: what lies under it in the app. */
    Target snapIn(Overlay o, MouseEvent e) {
        Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), o.layers());
        Component under = o.appComponentAt(p);
        return under == null ? null : snap(under, SwingUtilities.convertPoint(o.layers(), p, under));
    }

    void hoverIn(Overlay o, MouseEvent e) {
        Target t = snapIn(o, e);
        o.hover(t == null ? null : Overlay.Mark.of(t, t.shown, Overlay.HOVER, false));
    }

    /** What a recorded step's target is now, or null when it does not resolve yet. EDT. */
    Target resolveTarget(Locator target) {
        try {
            return target(resolver.resolve(target.copy()));
        } catch (RuntimeException notThereYet) {
            return null;
        }
    }

    /**
     * Walks up from {@code deepest} to the first thing a locator can address alone: a table cell or column, a tab, or a
     * component. Remembers the last answer: a walk resolves the whole window several times. EDT.
     */
    /**
     * {@link #snap} without its cache: for a click, whose element may have changed since the pointer last passed
     * over it (a button that now reads "Place order" instead of "Next"). EDT.
     */
    Target snapNow(Component deepest, Point inDeepest) {
        snappedAt = null;
        snapped = null;
        return snap(deepest, inDeepest);
    }

    Target snap(Component deepest, Point inDeepest) {
        if (deepest == null) return null;
        Integer tab = null;
        int row = -1;
        int column = -1;
        Component at = deepest;
        JTable edited = editingTable(deepest);
        if (deepest instanceof JTableHeader && ((JTableHeader) deepest).getTable() != null) {
            column = ((JTableHeader) deepest).columnAtPoint(inDeepest);
            at = ((JTableHeader) deepest).getTable();
        } else if (deepest instanceof JTable) {
            row = ((JTable) deepest).rowAtPoint(inDeepest);
            column = ((JTable) deepest).columnAtPoint(inDeepest);
            if (row < 0 || column < 0) row = column = -1;
        } else if (edited != null) {
            at = edited;
            row = edited.getEditingRow();
            column = edited.getEditingColumn();
        } else if (deepest instanceof JTabbedPane) {
            int i = ((JTabbedPane) deepest).indexAtLocation(inDeepest.x, inDeepest.y);
            if (i >= 0) tab = i;
        }
        if (at == snappedAt && row == snappedRow && column == snappedColumn
                && (tab == null ? snappedTab == null : tab.equals(snappedTab))) {
            return snapped;
        }
        Target found = null;
        if (at instanceof JTable && column >= 0) {
            try {
                found = row >= 0 ? cell((JTable) at, row, column) : column((JTable) at, column);
            } catch (DriverException notAddressable) {
                found = null;
            }
        }
        for (Component c = at; found == null && c != null && !(c instanceof Window); c = c.getParent()) {
            try {
                found = target(c, c == at ? tab : null);
            } catch (DriverException notAddressable) {
                // keep walking: a plain panel or a renderer has no locator of its own
            }
        }
        snappedAt = at;
        snappedTab = tab;
        snappedRow = row;
        snappedColumn = column;
        snapped = found;
        return found;
    }

    /**
     * The table whose open cell editor {@code c} is or sits in, else null: a composite editor (a spinner) puts its own
     * text field and arrows under the pointer, and those mean the cell too. EDT.
     */
    static JTable editingTable(Component c) {
        for (Component k = c; k != null && !(k instanceof Window); k = k.getParent()) {
            if (k.getParent() instanceof JTable) {
                JTable table = (JTable) k.getParent();
                return table.isEditing() && table.getEditorComponent() == k ? table : null;
            }
        }
        return null;
    }

    /** A table cell as the recorder addresses it: the row by key, the column by header. EDT. */
    Target cell(JTable table, int row, int column) {
        Locator l = Locator.ofRef(refs.add(table, null));
        l.row = TableWidget.rowKey(table, row);
        l.column = TableWidget.headers(table).get(column);
        Resolved r = resolver.resolve(l);
        Recorder.Recorded rec = recorder.record(r);
        return new Target(rec.locator, rec.fragile, "cell " + rowText(l.row) + " · " + l.column + suffix(rec),
                Target.shown(r.role, resolver.seenName(r), rec.locator), "cell", table, null, row, column, rec.seen);
    }

    /** A whole table column, addressed by its header. EDT. */
    Target column(JTable table, int column) {
        Locator l = Locator.ofRef(refs.add(table, null));
        l.column = TableWidget.headers(table).get(column);
        Resolved r = resolver.resolve(l);
        Recorder.Recorded rec = recorder.record(r);
        return new Target(rec.locator, rec.fragile, "column '" + l.column + "'" + suffix(rec),
                Target.shown(r.role, resolver.seenName(r), rec.locator), "column", table, null, -1, column, rec.seen);
    }

    /**
     * The entry of {@code list} at {@code p} (list coordinates), addressed as an {@code item} by its text as {@link Cells}
     * renders it; null when {@code p} is on no entry, or another entry reads the same (the item would find either). EDT.
     */
    Target listItem(JList<?> list, Point p) {
        int index = list.locationToIndex(p);
        Rectangle cell = index < 0 ? null : list.getCellBounds(index, index);
        if (cell == null || !cell.contains(p)) return null;
        String text = Cells.list(list, index).text;
        try {
            if (ListWidget.index(list, text) != index) return null;
        } catch (DriverException notOne) {
            return null;
        }
        return item(list, Collections.singletonList(text), index);
    }

    /**
     * The row of {@code tree} at {@code p} (tree coordinates), addressed as an {@code item} by its path; null when {@code p}
     * is on no row (left of it lie the expand handle and the indent) or the path is not unique. EDT.
     */
    Target treeRow(JTree tree, Point p) {
        int row = tree.getClosestRowForLocation(p.x, p.y);
        Rectangle bounds = row < 0 ? null : tree.getRowBounds(row);
        if (bounds == null || p.y < bounds.y || p.y >= bounds.y + bounds.height || p.x < bounds.x) return null;
        List<String> path = TreeWidget.path(tree, row);
        try {
            if (TreeWidget.row(tree, path) != row) return null;
        } catch (DriverException notOne) {
            return null;
        }
        return item(tree, path, row);
    }

    /** A list entry or tree row ({@code row}, its view index) as a locator with {@code item} addresses it; null when none does. */
    private Target item(Component c, List<String> item, int row) {
        try {
            Locator l = Locator.ofRef(refs.add(c, null));
            l.item = new ArrayList<String>(item);
            Resolved r = resolver.resolve(l);
            Recorder.Recorded rec = recorder.record(r);
            StringBuilder s = new StringBuilder(r.role);
            if (r.name != null) s.append(" '").append(r.name).append('\'');
            s.append(" · ").append(String.join(" > ", item)).append(suffix(rec));
            return new Target(rec.locator, rec.fragile, s.toString(), Target.shown(r.role, resolver.seenName(r), rec.locator),
                    r.role, c, null, row, -1, rec.seen);
        } catch (DriverException notAddressable) {
            return null;
        }
    }

    /** The target for a component on its own (no cell), as a locator addresses it; null when nothing does. EDT. */
    Target component(Component c) {
        for (Component k = c; k != null && !(k instanceof Window); k = k.getParent()) {
            try {
                return target(k, null);
            } catch (DriverException notAddressable) {
                // walk up
            }
        }
        return null;
    }

    private Target target(Component c, Integer tab) {
        String ref = refs.add(c, tab);
        return target(resolver.resolve(Locator.ofRef(ref)));
    }

    private Target target(Resolved r) {
        Recorder.Recorded rec = recorder.record(r);
        int row = -1;
        int column = -1;
        String role = r.role;
        if (r.component instanceof JTable && r.column() != null) {
            JTable table = (JTable) r.component;
            try {
                column = TableWidget.column(table, r.column());
                if (r.row() != null) row = TableWidget.row(table, r.row());
                role = row >= 0 ? "cell" : "column";
            } catch (DriverException e) {
                column = -1;
                row = -1;
            }
        }
        StringBuilder s = new StringBuilder(r.role);
        if (r.name != null) s.append(" '").append(r.name).append('\'');
        if (rec.locator.row != null) s.append(" · ").append(rowText(rec.locator.row));
        if (rec.locator.column != null) s.append(" · ").append(rec.locator.column);
        s.append(suffix(rec));
        // the agent reads the locator; the person reads the name they see, also when the locator goes by id
        return new Target(rec.locator, rec.fragile, s.toString(), Target.shown(r.role, resolver.seenName(r), rec.locator), role,
                r.component, r.tab, row, column, rec.seen);
    }

    private static String suffix(Recorder.Recorded rec) {
        Locator l = rec.locator;
        StringBuilder s = new StringBuilder();
        if (l.id != null) s.append("  #").append(l.id);
        else if (l.scope != null && !l.scope.isEmpty()) s.append("  in ").append(String.join(" > ", l.scope));
        if (l.nth != null) s.append("  nth ").append(l.nth).append(" (fragile)");
        return s.toString();
    }

    static String rowText(Map<String, Object> row) {
        StringBuilder s = new StringBuilder();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (s.length() > 0) s.append(", ");
            s.append("index".equals(e.getKey()) ? "row " + e.getValue() : e.getKey() + "=" + e.getValue());
        }
        return s.toString();
    }

    Overlay overlayFor(Component c) {
        JRootPane root = c instanceof RootPaneContainer ? ((RootPaneContainer) c).getRootPane() : SwingUtilities.getRootPane(c);
        return root == null ? null : overlay(root);
    }

    Overlay overlay(JRootPane root) {
        Overlay o = overlays.get(root);
        if (o == null) {
            Overlay made = new Overlay(root);
            made.spotFor = b -> spotIn(made);
            made.spot = homes.get(root);
            made.picked = spot -> homes.put(made.root, spot);
            overlays.put(root, made);
            o = made;
        }
        return o;
    }

    /** The main window's overlay: the first showing frame. */
    private Overlay mainOverlay() {
        JRootPane root = mainRoot();
        return root == null ? null : overlay(root);
    }

    /** The main window: the first showing frame. */
    private static JRootPane mainRoot() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w instanceof JFrame && !isPopup(w) && !Overlay.ours(w)) {
                return ((JFrame) w).getRootPane();
            }
        }
        return null;
    }

    /** The open modal dialog nothing blocks, else the main window. */
    private static JRootPane liveRoot() {
        Dialog top = Modality.top();
        if (top instanceof RootPaneContainer && !isPopup(top) && !Overlay.ours(top)) {
            return ((RootPaneContainer) top).getRootPane();
        }
        return mainRoot();
    }

    /** The focused app window's overlay; the main window's when none is. The palette's folder dialog is no app window. */
    private Overlay focusedOverlay() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w.isFocused() && !isPopup(w) && !Overlay.ours(w) && w instanceof RootPaneContainer) {
                return overlay(((RootPaneContainer) w).getRootPane());
            }
        }
        return mainOverlay();
    }

    /** The app's showing windows; never a popup or the palette's own (its folder dialog). */
    static List<JRootPane> appRoots() {
        List<JRootPane> out = new ArrayList<JRootPane>();
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && !isPopup(w) && !Overlay.ours(w) && w instanceof RootPaneContainer) {
                out.add(((RootPaneContainer) w).getRootPane());
            }
        }
        return out;
    }

    static boolean isPopup(Window w) {
        return w == null || w.getType() == Window.Type.POPUP || w.getClass().getName().startsWith("javax.swing.Popup$");
    }

    /**
     * How many checks follow step {@code i} before the next action (or {@code n}): an action and its checks are one step
     * for the person, one Next runs them all.
     */
    static int checksAfter(List<StepLine> steps, int i, int n) {
        int count = 0;
        for (int j = i + 1; j < Math.min(n, steps.size()) && steps.get(j).check(); j++) count++;
        return count;
    }

    static void pause(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void onEdt(Runnable r) {
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        }
    }
}
