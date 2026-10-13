package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.Driver;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Edt;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Recorder;
import io.github.kormcrete.swingui.driver.model.Refs;
import io.github.kormcrete.swingui.driver.model.Resolved;
import io.github.kormcrete.swingui.driver.model.Resolver;
import io.github.kormcrete.swingui.driver.model.RowSpec;
import io.github.kormcrete.swingui.driver.widgets.Cells;
import io.github.kormcrete.swingui.driver.widgets.TableWidget;

import javax.swing.JFrame;
import javax.swing.JRootPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.JTableHeader;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
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

/**
 * Work between the agent and the person at the app, in the app window (an app started with {@code inspector=on}):
 * <ul>
 *   <li>The person records: a palette in the main window starts a {@link Recording} with ● Record and ends it with Done.
 *       Only the person starts and ends one; the agent reads it ({@link #recording}) and exports it.</li>
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
    }

    public static final class Result {
        public final boolean ok;
        public final String line;

        public Result(boolean ok, String line) {
            this.ok = ok;
            this.line = line;
        }
    }

    private static Inspector instance;

    private final Refs refs;
    private final Resolver resolver;
    private final Recorder recorder;
    /** Why a test run must wait: the agent's question or pick is open. Read off the EDT by the agent's lease. */
    private volatile String asking;
    /** Why a test run must wait: the person records or replays. */
    private volatile String recordingBusy;

    /** EDT only. */
    private final Map<JRootPane, Overlay> overlays = new WeakHashMap<JRootPane, Overlay>();
    private CompletableFuture<Answer> pending;
    private AWTEventListener hoverListener;
    private Sink sink;
    private Bar idle;
    private Overlay paletteHome;
    private Recording recording;
    private boolean testRun;
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

    /** How many of the agent's own steps are running now: the person's recorder ignores input meanwhile. */
    private static final java.util.concurrent.atomic.AtomicInteger agentActing = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * The agent starts ({@code true}) or ends one of its own steps. Its input reaches the app as events the person's
     * recorder would also see; the agent's side records the step already, so the recorder must not record it twice.
     */
    public static void agentActing(boolean on) {
        if (on) agentActing.incrementAndGet(); else agentActing.decrementAndGet();
    }

    /** Whether one of the agent's own steps is running. */
    static boolean agentIsActing() {
        return agentActing.get() > 0;
    }

    /** A test run took ({@code true}) or gave back the agent: the palette leaves the window meanwhile, then returns. */
    public static void testRun(boolean on) {
        Inspector i = instance;
        if (i == null) return;
        SwingUtilities.invokeLater(() -> {
            i.testRun = on;
            for (Overlay o : i.overlays.values()) o.hidePalette(on);
        });
    }

    void recordingBusy(String reason) {
        recordingBusy = reason;
    }

    // ---- the person records ---------------------------------------------------------------------------------------

    /**
     * Puts the palette (● Record) into the app's main window, now and whenever that window changes. The person's
     * recordings go to {@code sink}. Call once, for an app started with {@code inspector=on}.
     */
    public void enable(Sink sink) {
        SwingUtilities.invokeLater(() -> {
            if (this.sink != null) return;
            this.sink = sink;
            idle = new Bar("swing-ui-agent", "").button("● Record", Overlay.NO, this::startRecording);
            new Timer(1000, e -> attachPalette()).start();
            attachPalette();
        });
    }

    /** The palette lives in the main window: the first showing frame. EDT. */
    private void attachPalette() {
        if (sink == null) return;
        Overlay o = mainOverlay();
        if (o == null) return;
        if (recording != null && recording.replaying) return;
        Bar want = recording != null ? recording.bar : idle;
        if (paletteHome != o) {
            if (paletteHome != null) paletteHome.bar(null);
            paletteHome = o;
            if (recording != null) recording.home = o;
        }
        o.hidePalette(testRun);
        o.bar(want);
    }

    /** ● Record: a fresh recording, from the screen the person is on. EDT. */
    private void startRecording() {
        if (sink == null || paletteHome == null) return;
        if (recording != null && recording.replaying) return;
        if (recording != null) recording.dispose();
        recording = null;
        Overlay home = paletteHome;
        sinkThread.submit(() -> {
            sink.begin();
            SwingUtilities.invokeLater(() -> {
                recording = new Recording(this, sink, sinkThread, home, this::startRecording);
                recording.start();
            });
        });
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
            o = new Overlay(root);
            overlays.put(root, o);
        }
        return o;
    }

    /** The main window's overlay: the first showing frame. */
    private Overlay mainOverlay() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w instanceof JFrame && !isPopup(w)) return overlay(((JFrame) w).getRootPane());
        }
        return null;
    }

    /** The focused app window's overlay; the main window's when none is focused. */
    private Overlay focusedOverlay() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w.isFocused() && !isPopup(w) && w instanceof RootPaneContainer) {
                return overlay(((RootPaneContainer) w).getRootPane());
            }
        }
        return mainOverlay();
    }

    static List<JRootPane> appRoots() {
        List<JRootPane> out = new ArrayList<JRootPane>();
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && !isPopup(w) && w instanceof RootPaneContainer) out.add(((RootPaneContainer) w).getRootPane());
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
