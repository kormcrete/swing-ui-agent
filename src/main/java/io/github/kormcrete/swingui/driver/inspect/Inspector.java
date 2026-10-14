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
import io.github.kormcrete.swingui.driver.widgets.TableWidget;
import io.github.kormcrete.swingui.driver.window.Modality;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.JTableHeader;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Dimension;
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
import java.util.function.Supplier;

/**
 * Work between the agent and the person at the app, in the app window (while the palette is on, the agent's default):
 * <ul>
 *   <li>The person records: a palette in the main window (in the open modal dialog while one is open) starts a
 *       {@link Recording} with ● Record and ends it with Done. Only the person starts and ends one; the agent reads it
 *       ({@link #recording}) and exports it.</li>
 *   <li>The person loads a flow file (Load flow…) into the recording and runs it: the app reset, then every step
 *       replayed. While a run or replay goes on no other call drives the app ({@link #drivingReason}).</li>
 *   <li>{@link #highlight}: the agent shows what a locator matches - numbered boxes when several - with a question; the
 *       person answers with the check mark on a box, or "None of these" on the question bar.</li>
 *   <li>{@link #pick}: the agent asks the person to click what they mean; the click goes to the inspector, not the app,
 *       and comes back as the locator the recorder writes.</li>
 *   <li>{@link #hover}: passive; a box follows the pointer and names the locator under it. The app keeps every event.</li>
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
        public final List<String> steps;

        Answer(String outcome, Integer chosen, Target target) {
            this(outcome, chosen, target, null);
        }

        Answer(String outcome, Integer chosen, Target target, List<String> steps) {
            this.outcome = outcome;
            this.chosen = chosen;
            this.target = target;
            this.steps = steps;
        }
    }

    /** Where the person's steps and checks go: the session that records the agent's steps too. Never called on the EDT. */
    public interface Sink {
        /** The person pressed ● Record: a fresh recording starts here, on the screen they are on. */
        void begin();

        /** A step the person already did: record it. */
        Result done(Map<String, Object> step);

        /** A check the person chose: run it, record it when it passes. */
        Result check(Map<String, Object> step);

        /** Every step of the recording so far, agent's and person's, as one line each. */
        List<String> steps();

        /** Step {@code index}'s target, or null for a step without one (menu, press). */
        Locator target(int index);

        /** Runs step {@code index} again, without recording it. */
        Result replay(int index);

        /** Keeps the first {@code keep} steps and drops the rest. */
        void truncate(int keep);

        /** Drops step {@code index}. */
        void remove(int index);

        /** The package an export offers first: the app plugin's export package, else that of the app's main class. */
        String exportPackage();

        /**
         * Writes the recording as a JUnit {@code @UiTest} class, as {@code export_test} does, and keeps it (the person
         * may replay, redo or export again). The result's line says where it went, or why nothing was written.
         */
        Result export(String className, String pkg);

        /**
         * The folder Export… offers for a flow: one set explicitly (agent option, system property), else the folder
         * the person last used, else the default flows folder. Absolute.
         */
        Path flowsFolder();

        /** The flow file the recording came from (loaded, or exported before), or null: the one a save goes over. */
        Path loadedFlow();

        /**
         * Writes the recording as the flow {@code <folder>/<name>.json}, as {@code export_flow} does, and keeps it. A
         * folder that cannot be written comes back with {@link Result#folder} set, so the palette asks for another.
         */
        Result exportFlow(String name, Path folder);

        /** The flow files in {@code folder}, sorted; one that cannot be read comes with its problem. */
        List<FlowEntry> flows(Path folder);

        /**
         * Makes the flow file the recording, as if its steps had just been run after a reset, and remembers its folder.
         * The result's line is the flow's name, or why it was not loaded (the recording is then unchanged).
         */
        Result loadFlow(Path file);

        /** Resets the app as a flow starts ({@code session reset} without touching the recording). */
        Result reset();
    }

    /** A flow file as Load flow… lists it. */
    public static final class FlowEntry {
        public final String name;
        /** Null when it has none. */
        public final String description;
        public final int steps;
        /** Why the file cannot be read, or null; a file with a problem cannot be loaded. */
        public final String problem;
        public final Path file;

        public FlowEntry(String name, String description, int steps, String problem, Path file) {
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

    private static volatile Inspector instance;

    private final Refs refs;
    private final Resolver resolver;
    private final Recorder recorder;
    /** Why a test run must wait: the agent's question or pick is open. Read off the EDT by the agent's lease. */
    private volatile String asking;
    /** Why a test run must wait: the person records or replays. */
    private volatile String recordingBusy;
    /** Why no other call may drive the app: the person runs a flow or replays steps from the palette. */
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
    /** The app's windows, which Check mode covers. */
    Supplier<List<JRootPane>> roots = Inspector::appRoots;
    /** Where the bar sat in each window it left (null: where it goes by itself); outlives the window's overlay. */
    private final Map<JRootPane, Point> spots = new WeakHashMap<JRootPane, Point>();
    private Recording recording;
    /** Load flow…'s panel while it is open. EDT. */
    FlowsPanel flowsPanel;
    private boolean testRun;
    /** The kind Export… opens on when no flow is loaded: the one the person used last in this JVM. EDT. */
    String exportKind = ExportPanel.JAVA;
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
     * Why every mutating call from elsewhere must wait - a person runs a flow or replays steps from the palette, whose
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

    /** The agent's last step ended: a palette that left a window for it comes back. EDT. */
    void agentDone() {
        for (Overlay o : overlays.values()) o.endYield();
        attachPalette();
    }

    void recordingBusy(String reason) {
        recordingBusy = reason;
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
                .button("Load flow…", Overlay.HOVER, this::toggleFlows);
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
        Bar want = recording != null ? recording.bar : idle;
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
    }

    /**
     * Moves the palette from its window to {@code to}, with the person's recording - its panel and a running replay's
     * bar too. The bar takes the spot it had in {@code to} before ({@link #spotIn}): in a dialog, never over its
     * buttons. A test run's palette moves in hidden. EDT.
     */
    private void movePalette(Overlay to, Bar want) {
        Overlay from = paletteHome;
        Bar shown = from != null && from.bar() != null ? from.bar() : want;
        if (from != null) spots.put(from.root, shown.moved);
        shown.moved = spotIn(to, shown);
        to.hidePalette(testRun);
        paletteHome = to;
        if (recording != null) recording.rehome(to); else to.bar(idle);
        if (flowsPanel != null) {
            // out of the old window first: while it still holds the panel, any change there would take it back
            if (from != null) from.panel(null);
            to.panel(flowsPanel);
        }
        if (from == null) return;
        from.bar(null);
        from.panel(null);
        from.endYield();
        if (from.empty()) {
            from.dispose();
            if (overlays.get(from.root) == from) overlays.remove(from.root);
        }
    }

    /**
     * Where {@code bar} goes in {@code o}'s window: where the palette sat when it last left that window (null: where it
     * goes by itself). In a dialog only where it covers none of the dialog's buttons (Next, OK) at its own size; else
     * the first corner clear of them.
     */
    private Point spotIn(Overlay o, Bar bar) {
        Point left = spots.get(o.root);
        if (!(SwingUtilities.getWindowAncestor(o.root) instanceof Dialog)) return left;
        JLayeredPane layers = o.layers();
        List<Rectangle> buttons = new ArrayList<Rectangle>();
        buttonsIn(layers, layers, buttons);
        Dimension size = bar.getPreferredSize();
        Rectangle at = Overlay.placeBar(size.width, size.height, layers.getWidth(), layers.getHeight(), left, buttons);
        return at.getLocation();
    }

    /** The app's visible buttons under {@code c}, in layered-pane coordinates. */
    private static void buttonsIn(Container c, JLayeredPane layers, List<Rectangle> out) {
        for (Component k : c.getComponents()) {
            if (!k.isVisible() || Overlay.ours(k)) continue;
            if (k instanceof AbstractButton) out.add(SwingUtilities.convertRectangle(c, k.getBounds(), layers));
            if (k instanceof Container) buttonsIn((Container) k, layers, out);
        }
    }

    /** Whether {@code root} has room for the palette: its buttons in a row, and three bars' height. */
    private static boolean fits(JRootPane root, Bar bar) {
        JLayeredPane layers = root.getLayeredPane();
        return layers.getWidth() >= bar.minimumWidth() + 16 && layers.getHeight() >= 3 * bar.getPreferredSize().height;
    }

    /** ● Record: a fresh recording, from the screen the person is on. EDT. */
    private void startRecording() {
        if (sink == null || paletteHome == null) return;
        if (recording != null && recording.replaying) return;
        closeFlows();
        if (recording != null) recording.dispose();
        recording = null;
        sinkThread.submit(() -> {
            sink.begin();
            SwingUtilities.invokeLater(() -> {
                // where the palette is now: an agent step may have opened or closed a dialog while begin() waited
                Overlay home = paletteHome;
                if (home == null) return;
                // a flow loaded while begin() waited goes: this ● Record came after it
                if (recording != null) recording.dispose();
                recording = new Recording(this, sink, sinkThread, home, this::startRecording);
                recording.start();
            });
        });
    }

    /**
     * Load flow…: opens (or closes) the panel with the flows in the flows folder; it replaces the steps or export panel.
     * Not while the person records or replays. EDT.
     */
    void toggleFlows() {
        if (flowsPanel != null) {
            closeFlows();
            return;
        }
        if (sink == null || paletteHome == null || busyRecording()) return;
        if (recording != null) recording.closePanels();
        sinkThread.submit(() -> {
            Path folder = sink.flowsFolder();
            List<FlowEntry> flows = listed(folder);
            int current = sink.steps().size();
            SwingUtilities.invokeLater(() -> {
                if (flowsPanel != null || paletteHome == null || busyRecording()) return;
                flowsPanel = new FlowsPanel(folder, flows, current, this::listFlows, this::loadFlow, this::closeFlows);
                paletteHome.panel(flowsPanel);
            });
        });
    }

    /** The person records or replays: no flow is loaded meanwhile. EDT. */
    private boolean busyRecording() {
        return recording != null && (recording.active() || recording.replaying);
    }

    /** Load flow…'s panel goes, with its folder dialog. EDT. */
    void closeFlows() {
        if (flowsPanel == null) return;
        flowsPanel.closeChooser();
        if (paletteHome != null && paletteHome.holds(flowsPanel)) paletteHome.panel(null);
        flowsPanel = null;
    }

    /** The flows in {@code folder}; none when it cannot be listed. Sink thread. */
    private List<FlowEntry> listed(Path folder) {
        try {
            return sink.flows(folder);
        } catch (RuntimeException e) {
            return new ArrayList<FlowEntry>();
        }
    }

    /** The person picked another folder in the panel: its flows. EDT. */
    private void listFlows(Path folder) {
        FlowsPanel panel = flowsPanel;
        if (panel == null) return;
        sinkThread.submit(() -> {
            List<FlowEntry> flows = listed(folder);
            SwingUtilities.invokeLater(() -> { if (flowsPanel == panel) panel.show(folder, flows); });
        });
    }

    /**
     * Load: the flow becomes the recording, shown as a loaded {@link Recording} with its steps ready to run; the
     * previous recording goes. A refused load says why in the panel and changes nothing. EDT.
     */
    private void loadFlow(Path file) {
        FlowsPanel panel = flowsPanel;
        if (panel == null || busyRecording()) return;
        sinkThread.submit(() -> {
            Result r = sink.loadFlow(file);
            int n = r.ok ? sink.steps().size() : 0;
            SwingUtilities.invokeLater(() -> {
                if (!r.ok) {
                    if (flowsPanel == panel) panel.refused(r.line);
                    return;
                }
                closeFlows();
                Overlay home = paletteHome;
                if (home == null) return;
                if (recording != null) recording.dispose();
                recording = Recording.loaded(this, sink, sinkThread, home, this::startRecording, r.line, n);
            });
        });
    }

    /** The person's recording - or the flow they loaded - or null before their first ● Record or load. EDT. */
    Recording recording() {
        return recording;
    }

    /**
     * The person's recording, for the agent: {@code done} with its lines once they pressed Done, {@code recording}
     * while they are still at it (after waiting up to {@code waitMs} for Done), {@code idle} when they never pressed
     * ● Record. The agent never starts or ends one.
     */
    public Answer recording(long waitMs) {
        Recording r = Edt.call(() -> recording);
        if (r == null) return new Answer("idle", null, null);
        if (!Edt.call(r::active)) return new Answer("done", null, null, new ArrayList<String>(r.lines));
        try {
            return new Answer("done", null, null, r.done.get(waitMs, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            return new Answer("recording", null, null, new ArrayList<String>(r.lines));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Answer("interrupted", null, null);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
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
                Overlay.Mark m = Overlay.Mark.of(t, targets.size() > 1 ? String.valueOf(n) : t.element, Overlay.ASK, false);
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
                o.hover(Overlay.Mark.of(t, t.element, Overlay.YES, false));
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
        o.hover(t == null ? null : Overlay.Mark.of(t, t.element, Overlay.HOVER, true));
    }

    /** The catcher's mouse event, snapped: what lies under it in the app. */
    Target snapIn(Overlay o, MouseEvent e) {
        Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), o.layers());
        Component under = o.appComponentAt(p);
        return under == null ? null : snap(under, SwingUtilities.convertPoint(o.layers(), p, under));
    }

    void hoverIn(Overlay o, MouseEvent e) {
        Target t = snapIn(o, e);
        o.hover(t == null ? null : Overlay.Mark.of(t, t.element, Overlay.HOVER, false));
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
        if (deepest instanceof JTableHeader && ((JTableHeader) deepest).getTable() != null) {
            column = ((JTableHeader) deepest).columnAtPoint(inDeepest);
            at = ((JTableHeader) deepest).getTable();
        } else if (deepest instanceof JTable) {
            row = ((JTable) deepest).rowAtPoint(inDeepest);
            column = ((JTable) deepest).columnAtPoint(inDeepest);
            if (row < 0 || column < 0) row = column = -1;
        } else if (deepest.getParent() instanceof JTable && ((JTable) deepest.getParent()).getEditorComponent() == deepest) {
            JTable table = (JTable) deepest.getParent();
            at = table;
            row = table.getEditingRow();
            column = table.getEditingColumn();
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

    /** A table cell as the recorder addresses it: the row by key, the column by header. EDT. */
    Target cell(JTable table, int row, int column) {
        Locator l = Locator.ofRef(refs.add(table, null));
        l.row = rowKey(table, row);
        l.column = TableWidget.headers(table).get(column);
        Recorder.Recorded rec = recorder.record(resolver.resolve(l));
        return new Target(rec.locator, rec.fragile, "cell " + rowText(l.row) + " · " + l.column + suffix(rec), "cell",
                table, null, row, column);
    }

    /** A whole table column, addressed by its header. EDT. */
    Target column(JTable table, int column) {
        Locator l = Locator.ofRef(refs.add(table, null));
        l.column = TableWidget.headers(table).get(column);
        Recorder.Recorded rec = recorder.record(resolver.resolve(l));
        return new Target(rec.locator, rec.fragile, "column '" + l.column + "'" + suffix(rec), "column", table, null, -1,
                column);
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
        return new Target(rec.locator, rec.fragile, s.toString(), role, r.component, r.tab, row, column);
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

    /**
     * The row by its first column whose text is non-empty and unique in the table - an order number, not an index -
     * so the step still finds the row after sorting or a new row; by index only when no column is unique.
     */
    static Map<String, Object> rowKey(JTable table, int row) {
        List<String> headers = TableWidget.headers(table);
        for (int c = 0; c < table.getColumnCount(); c++) {
            String v = Cells.table(table, row, c).text;
            if (v == null || v.trim().isEmpty()) continue;
            boolean unique = true;
            for (int r = 0; r < table.getRowCount() && unique; r++) {
                if (r != row && v.equals(Cells.table(table, r, c).text)) unique = false;
            }
            if (!unique || headers.indexOf(headers.get(c)) != c) continue;
            Map<String, Object> key = new LinkedHashMap<String, Object>();
            key.put(headers.get(c), v);
            // the spec must resolve back to this row
            try {
                if (TableWidget.row(table, RowSpec.of(key)) == row) return key;
            } catch (DriverException ignored) {
                // try the next column
            }
        }
        Map<String, Object> key = new LinkedHashMap<String, Object>();
        key.put("index", row);
        return key;
    }

    Overlay overlayFor(Component c) {
        JRootPane root = c instanceof RootPaneContainer ? ((RootPaneContainer) c).getRootPane() : SwingUtilities.getRootPane(c);
        return root == null ? null : overlay(root);
    }

    Overlay overlay(JRootPane root) {
        Overlay o = overlays.get(root);
        if (o == null) {
            Overlay made = new Overlay(root);
            made.spotFor = b -> spotIn(made, b);
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
    static int checksAfter(List<String> steps, int i, int n) {
        int count = 0;
        for (int j = i + 1; j < Math.min(n, steps.size()) && steps.get(j).startsWith("expect("); j++) count++;
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
