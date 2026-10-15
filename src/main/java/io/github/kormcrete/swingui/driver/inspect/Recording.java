package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.model.Locator;

import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Component;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * One recording the person made, from their ● Record to their Done, and after it: the palette (Record, Check, Steps,
 * Done; after Done ● Record, Load…, Steps, Export…), the person's own steps (Record mode), the check catcher (Check
 * mode), the steps panel with replay, redo and delete (a replay of everything and every redo begin at the app reset,
 * as the exported test does), sentences typed in place of its rows, its row menu (Describe…, Ungroup, how a table row
 * is named), and the export panel that writes the recording as a JUnit class or a recorded test file. The
 * person starts and ends it with ● Record and Done, or the agent does ({@link #startedByAgent},
 * {@link #stoppedByAgent}); while the agent replays, its bar stands in for the palette's ({@link #agentReplay}). A
 * recorded test file the person or the agent loads is one too, already stopped ({@link #loaded}): its steps panel runs it - the
 * app reset, then every step. While a replay or run goes on, no other call drives the app
 * ({@link Inspector#drivingReason}). The agent's steps and edits join the same recording: the panel shows each change
 * at once, and the agent's edits as a note on the bar ({@link #changed}). EDT, except where a method says otherwise.
 */
final class Recording {

    enum State { RECORDING, CHECKING, STOPPED }

    /** Why a test run waits while the person's recording runs, and while the agent's does. */
    static final String PERSON_BUSY = "a person is recording in the app (they end it with Done in its palette)";
    static final String AGENT_BUSY =
            "a recording is running in the app (the agent started it): Done in its palette or the agent ends it";

    /** What the bar says in each mode; in Check mode, also how to get out of it. */
    static final String RECORD_HINT = "Use the app: every step is recorded.";
    static final String CHECK_HINT = "Click what to check · Esc or ◀ to go back";
    /** The Record button while checking: it leads back to recording. */
    static final String BACK = "◀ Back to recording";

    private static final Color STEPS = new Color(0x6B, 0x4F, 0xBB);
    private static final Color RED = new Color(0xE5, 0x39, 0x35);

    private final Inspector inspector;
    private final Inspector.Sink sink;
    private final ExecutorService sinkThread;
    private final Runnable recordAgain;
    /** The name of the recorded test file this recording was loaded from; null for one the person recorded. */
    final String loadedName;
    Overlay home;
    final List<String> lines = Collections.synchronizedList(new ArrayList<String>());
    private final HumanSteps steps;
    private final Bar activeBar;
    private final Bar stoppedBar;
    Bar bar;
    /** By step id, what its last replay did: ▶ running, ✓ replayed, ✗ failed. None: ● recorded, ✎ edited. */
    private final Map<Integer, String> marks = new HashMap<Integer, String>();
    StepsPanel panel;
    ExportPanel exportPanel;
    State state = State.RECORDING;
    boolean replaying;
    /** The agent's bar while the agent replays, else null. */
    private Bar agentReplay;
    /** Why a test run waits while this recording runs: whoever started it. */
    private String busy = PERSON_BUSY;
    /** Completes at the person's Done; a redo that records on starts a new one. */
    volatile CompletableFuture<List<String>> done = new CompletableFuture<List<String>>();
    /**
     * Re-reads the recording while the panel is open, so it never lags (typing shows as it happens); a backstop, as the
     * session tells of every change ({@link #changed}).
     */
    final Timer live = new Timer(500, e -> refresh(-1));

    /** How long the agent's note stays on the bar before the text it covered comes back. */
    static final int NOTE_MS = 4000;
    /** How long a check's result stays on the bar in Check mode before the way out is shown again. */
    int hintBackMs = NOTE_MS;
    /** Puts back what the bar said before the agent's note; running while a note shows. */
    Timer noteTimer;
    private Bar noted;
    private String beforeNote;

    /** Esc while checking: back to recording, the key kept from the app; installed only while Check mode needs it. */
    private final KeyEventDispatcher escape = this::escape;
    private KeyboardFocusManager escapeOn;
    /** Esc left Check mode: the rest of that keystroke (typed, released) is kept from the app too. */
    private boolean swallowing;

    /** Replay controls, read by the replay thread. */
    volatile boolean paused;
    volatile boolean stopped;
    final Semaphore next = new Semaphore(0);
    private Bar replayBar;

    Recording(Inspector inspector, Inspector.Sink sink, ExecutorService sinkThread, Overlay home, Runnable recordAgain) {
        this(inspector, sink, sinkThread, home, recordAgain, null);
    }

    private Recording(Inspector inspector, Inspector.Sink sink, ExecutorService sinkThread, Overlay home,
                      Runnable recordAgain, String loadedName) {
        this.inspector = inspector;
        this.loadedName = loadedName;
        this.sink = sink;
        this.sinkThread = sinkThread;
        this.home = home;
        this.recordAgain = recordAgain;
        this.steps = new HumanSteps(inspector, this::emit);
        this.activeBar = new Bar("Recording", RECORD_HINT).dot(RED, true)
                .button("Record", Overlay.NO, () -> mode(false))
                .button("Check", Overlay.YES, () -> mode(true))
                .button("Steps", STEPS, this::toggleSteps)
                .button("Done", Overlay.HOVER, this::done);
        this.stoppedBar = new Bar(loadedName != null ? "Loaded" : "Recorded", "").dot(new Color(0x8A, 0x96, 0xAD), false)
                .button("● Record", Overlay.NO, recordAgain)
                .button("Load…", Overlay.HOVER, inspector::toggleLoad)
                .button("Steps", STEPS, this::toggleSteps)
                .button("Export…", Overlay.YES, this::toggleExport);
        this.bar = activeBar;
    }

    /**
     * The recorded test {@code name} (its {@code steps} steps already in the session's recording) shown loaded: stopped, the
     * bar titled "Loaded", the steps panel open with ▶ Run. EDT.
     */
    static Recording loaded(Inspector inspector, Inspector.Sink sink, ExecutorService sinkThread, Overlay home,
                            Runnable recordAgain, String name, int steps) {
        Recording r = new Recording(inspector, sink, sinkThread, home, recordAgain, name);
        r.state = State.STOPPED;
        r.bar = r.stoppedBar;
        r.done.complete(new ArrayList<String>());
        r.stoppedBar.text(name + " - " + steps + (steps == 1 ? " step" : " steps"));
        home.bar(r.bar);
        r.toggleSteps();
        return r;
    }

    void start() {
        home.bar(bar);
        inspector.recordingBusy(busy);
        mode(false);
        refresh(-1);
    }

    /**
     * Starts as the agent's recording: the session runs it already (no begin), the bar says the agent started it, and
     * the person's steps join it.
     */
    void startedByAgent() {
        busy = AGENT_BUSY;
        start();
        activeBar.text("The agent started this recording: use the app, your steps join it.");
    }

    /** Whether the person's input is recorded now. */
    boolean listening() {
        return steps.listening();
    }

    boolean active() {
        return state != State.STOPPED;
    }

    void mode(boolean check) {
        if (replaying || agentReplay != null || state == State.STOPPED) return;
        if (check) steps.flush();
        state = check ? State.CHECKING : State.RECORDING;
        if (check) activeBar.relabel("Record", BACK); else activeBar.relabel(BACK, "Record");
        activeBar.active(check ? "Check" : "Record");
        listenForEscape(check || swallowing);
        steps.active(!check);
        for (JRootPane root : inspector.roots.get()) {
            Overlay o = inspector.overlay(root);
            if (check) {
                o.catcher(e -> inspector.hoverIn(o, e), e -> {
                    Target t = inspector.snapIn(o, e);
                    if (t != null) CheckMenu.show(t, e, (step, target) -> check(step, target, o));
                });
            } else {
                o.removeCatcher();
            }
        }
        activeBar.text(check ? CHECK_HINT : RECORD_HINT);
    }

    /**
     * The keyboard focus manager's look at a key before anyone else: Esc in Check mode goes back to recording, and that
     * keystroke never reaches the app or the recorder. Not while a popup menu shows (Esc closes it, as always) or a
     * replay runs. EDT.
     */
    private boolean escape(KeyEvent e) {
        boolean esc = e.getID() == KeyEvent.KEY_TYPED ? e.getKeyChar() == KeyEvent.VK_ESCAPE : e.getKeyCode() == KeyEvent.VK_ESCAPE;
        if (!esc) return false;
        if (e.getID() == KeyEvent.KEY_PRESSED) {
            swallowing = false;
            // Esc in a sentence typed in the Steps list cancels that, nothing more; Esc elsewhere leaves Check mode
            if (state != State.CHECKING || replaying || agentReplay != null
                    || panel != null && panel.naming() && e.getComponent() == panel.nameField()
                    || MenuSelectionManager.defaultManager().getSelectedPath().length > 0) {
                listenForEscape(state == State.CHECKING);
                return false;
            }
            swallowing = true;
            mode(false);
            return true;
        }
        if (!swallowing) return false;
        if (e.getID() == KeyEvent.KEY_RELEASED) {
            swallowing = false;
            listenForEscape(state == State.CHECKING);
        }
        return true;
    }

    /** Installs or removes the {@link #escape} dispatcher. EDT. */
    private void listenForEscape(boolean on) {
        if (on && escapeOn == null) {
            escapeOn = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            escapeOn.addKeyEventDispatcher(escape);
        } else if (!on && escapeOn != null) {
            escapeOn.removeKeyEventDispatcher(escape);
            escapeOn = null;
        }
    }

    /** No more Esc for Check mode: the recording stopped or goes. EDT. */
    private void stopListeningForEscape() {
        swallowing = false;
        listenForEscape(false);
    }

    /**
     * The palette moved to {@code to} (a modal dialog opened or closed): the bar its old window shows - a replay's
     * too - and the steps or export panel go with it; during a replay the panel stays out of the way there as well. In
     * Check mode the new window gets a catcher too. EDT.
     */
    void rehome(Overlay to) {
        Overlay from = home;
        if (to == from) return;
        Bar shown = from.bar() != null ? from.bar() : bar;
        // a sentence being typed is saved first: the field would leave the window with the keyboard
        if (panel != null) panel.commitNaming();
        JComponent open = panel != null ? panel : exportPanel;
        from.bar(null);
        if (open != null) from.panel(null);
        if (replaying || agentReplay != null) {
            from.controlsAway(false);
            to.controlsAway(true);
        }
        home = to;
        to.bar(shown);
        if (open != null) to.panel(open);
        if (state == State.CHECKING && !replaying && agentReplay == null) mode(true);
    }

    /** The person's Done: stop listening, keep the recording for the agent, offer Steps and a new ● Record. */
    void done() {
        if (replaying || agentReplay != null || state == State.STOPPED) return;
        stop(true);
    }

    /** The agent stopped the recording (the session did already): shown as after Done, saying who ended it. */
    void stoppedByAgent() {
        if (state == State.STOPPED) return;
        stop(false);
    }

    /** Stops listening and shows the stopped bar; {@code person}: their Done, which the session hears of. */
    private void stop(boolean person) {
        stop(person, null);
    }

    /** {@link #stop(boolean)}; {@code why}: what the stopped bar says instead of the step count, null for the count. */
    private void stop(boolean person, String why) {
        steps.flush();
        steps.active(false);
        stopListeningForEscape();
        for (Overlay o : inspector.overlays()) o.removeCatcher();
        state = State.STOPPED;
        inspector.recordingBusy(null);
        bar = stoppedBar;
        if (agentReplay == null && !replaying) home.bar(bar);
        // the steps already handed to the sink thread reach the session before anyone reads the recording
        CompletableFuture<List<String>> finished = done;
        sinkThread.submit(() -> {
            if (person) sink.end();
            int n = StepsPanel.stepCount(sink.steps());
            String count = n + (n == 1 ? " step" : " steps");
            SwingUtilities.invokeLater(() -> stoppedBar.text(why != null ? why : person
                    ? count + " - Export… writes them as a test, or the agent does"
                    : "The agent ended the recording - " + count));
            finished.complete(new ArrayList<String>(lines));
        });
    }

    /**
     * The agent replays: {@code agentBar} (no buttons) stands in for the palette's bar, the steps panel's controls go,
     * the person's input is not recorded and Check mode's catcher goes; the marks of an earlier replay go too.
     */
    void agentReplay(Bar agentBar) {
        if (agentReplay != null) return;
        agentReplay = agentBar;
        // the agent's replay is not the person's doing: what they typed goes, the keyboard goes back to the app
        if (panel != null) panel.cancelNaming();
        steps.flush();
        steps.active(false);
        for (Overlay o : inspector.overlays()) o.removeCatcher();
        marks.clear();
        home.controlsAway(true);
        home.bar(agentBar);
        refresh(-1);
    }

    /** The agent's replay ended: the palette's bar and controls come back, and the person records again if they did. */
    void agentReplayEnded() {
        if (agentReplay == null) return;
        agentReplay = null;
        home.controlsAway(false);
        home.bar(bar);
        if (active()) {
            // the bar keeps what it said (who started the recording), as before the replay
            String text = activeBar.text();
            mode(state == State.CHECKING);
            activeBar.text(text);
        }
        refresh(-1);
    }

    /** The agent's replay ran step {@code id}: ✓ or ✗ on its row. */
    void replayed(int id, boolean ok) {
        marks.put(id, ok ? "✓" : "✗");
        refresh(-1);
    }

    /** Stops for good: a new recording replaces this one. */
    void dispose() {
        stopped = true;
        next.release();
        live.stop();
        if (noteTimer != null) noteTimer.stop();
        steps.active(false);
        stopListeningForEscape();
        // the records a sentence was typed for are gone with this recording: the keyboard goes back, nothing is saved
        if (panel != null) panel.cancelNaming();
        if (panel != null || exportPanel != null) home.panel(null);
        panel = null;
        closeExportPanel();
        for (Overlay o : inspector.overlays()) o.removeCatcher();
        if (!done.isDone()) done.complete(new ArrayList<String>(lines));
    }

    private void emit(Map<String, Object> step) {
        sinkThread.submit(() -> {
            Inspector.Result r = sink.done(step);
            SwingUtilities.invokeLater(() -> show(r));
        });
    }

    /** The person chose a check in the check menu: {@code step} for {@code t}, in the window of {@code o}. */
    void check(Map<String, Object> step, Target t, Overlay o) {
        activeBar.text("checking …");
        sinkThread.submit(() -> {
            Inspector.Result r = sink.check(step);
            SwingUtilities.invokeLater(() -> {
                show(r);
                hintBack(activeBar.text());
                Overlay.Mark m = Overlay.Mark.of(t, r.ok ? "check recorded" : "check failed", r.ok ? Overlay.YES : Overlay.NO, false);
                m.result = r.ok;
                o.mark(m, null);
                Timer clear = new Timer(1500, e -> { if (!replaying) o.clearMarks(); });
                clear.setRepeats(false);
                clear.start();
            });
        });
    }

    /** A check's result {@code shown} on the bar: after a while, if the bar still says it, Check mode's way out again. */
    private void hintBack(String shown) {
        Timer back = new Timer(hintBackMs, e -> {
            if (state == State.CHECKING && !replaying && agentReplay == null && shown.equals(activeBar.text())) {
                activeBar.text(CHECK_HINT);
            }
        });
        back.setRepeats(false);
        back.start();
    }

    private void show(Inspector.Result r) {
        lines.add((r.ok ? "" : "NOT RECORDED: ") + r.line);
        activeBar.text((r.ok ? "recorded: " : "✗ not recorded: ") + r.line);
        refresh(-1);
    }

    void toggleSteps() {
        if (panel != null) {
            // Close or Steps while a sentence is typed: saved, as a click elsewhere would
            panel.commitNaming();
            home.panel(null);
            panel = null;
            live.stop();
            return;
        }
        closeExportPanel();
        inspector.closeLoad();
        // a replay of everything - a loaded recorded test's Run too - begins at the reset, as the test will
        // the panel hands on record indexes: a group's header row is no record
        panel = loadedName != null
                ? new StepsPanel("▶ Run", this::run, i -> replay(i, -1, null), this::redoFrom, this::delete, this::undo,
                        this::toggleSteps)
                : new StepsPanel("▶ Replay all", () -> replay(0, -1, null), i -> replay(i, -1, null), this::redoFrom,
                        this::delete, this::undo, this::toggleSteps);
        StepsPanel shown = panel;
        panel.edits(new StepsPanel.Edits() {
            @Override public BooleanSupplier naming() {
                return startNaming(shown);
            }
            @Override public CompletableFuture<Inspector.Result> describe(int fromId, int toId, String say) {
                return edit(() -> sink.describe(fromId, toId, say), "✗ not described: ");
            }
            @Override public void ungroup(int id) {
                edit(() -> sink.ungroup(id), "✗ not ungrouped: ");
            }
            @Override public void rowBy(int id, String by) {
                edit(() -> sink.rowBy(id, by), "✗ row not changed: ");
            }
        });
        home.panel(panel);
        refresh(-1);
        live.start();
    }

    /**
     * Export…: opens (or closes) the panel where the person names the JUnit class or the recorded test file; it replaces the
     * steps panel. It opens on Recorded test when the recording came from a recorded test file, else on the kind they used last.
     */
    void toggleExport() {
        if (exportPanel != null) {
            home.panel(null);
            closeExportPanel();
            return;
        }
        if (state != State.STOPPED) return;
        sinkThread.submit(() -> {
            String pkg = sink.exportPackage();
            Path folder = sink.recordedFolder();
            Path loaded = sink.loadedTest();
            SwingUtilities.invokeLater(() -> {
                if (state != State.STOPPED || exportPanel != null) return;
                if (panel != null) {
                    panel.commitNaming();
                    panel = null;
                    live.stop();
                }
                inspector.closeLoad();
                exportPanel = new ExportPanel("RecordedUiIT", pkg, folder, loaded, inspector.exportKind, (c, p) -> {
                    inspector.exportKind = ExportPanel.JAVA;
                    exported(export(c, p));
                }, (name, to) -> {
                    inspector.exportKind = ExportPanel.RECORDED;
                    exported(exportRecordedTest(name, to));
                }, this::toggleExport);
                home.panel(exportPanel);
            });
        });
    }

    /** An export from the panel ended: written closes the panel, refused keeps it open with the reason. */
    private void exported(CompletableFuture<Inspector.Result> result) {
        result.thenAccept(r -> SwingUtilities.invokeLater(() -> {
            if (exportPanel == null) return;
            if (r.ok) toggleExport(); else exportPanel.refused(r.line, r.folder);
        }));
    }

    /** The steps or export panel goes: Load recorded test… opens its own in their place. */
    void closePanels() {
        if (panel == null && exportPanel == null) return;
        if (panel != null) panel.commitNaming();
        home.panel(null);
        panel = null;
        live.stop();
        closeExportPanel();
    }

    /** The export panel is gone (closed, or replaced): its folder dialog goes with it. */
    private void closeExportPanel() {
        if (exportPanel != null) exportPanel.closeChooser();
        exportPanel = null;
    }

    /**
     * Writes the recording as {@code pkg.className} and says on the bar where it went, or why it did not; completes
     * with the result after the bar is updated. Any thread.
     */
    CompletableFuture<Inspector.Result> export(String className, String pkg) {
        CompletableFuture<Inspector.Result> result = new CompletableFuture<Inspector.Result>();
        sinkThread.submit(() -> {
            Inspector.Result r = sink.export(className, pkg);
            SwingUtilities.invokeLater(() -> stoppedBar.text((r.ok ? "exported: " : "✗ not exported: ") + r.line));
            result.complete(r);
        });
        return result;
    }

    /**
     * Writes the recording as the recorded test {@code <folder>/<name>.json} and says on the bar where it went, or why it did
     * not; completes with the result after the bar is updated. Any thread.
     */
    CompletableFuture<Inspector.Result> exportRecordedTest(String name, Path folder) {
        CompletableFuture<Inspector.Result> result = new CompletableFuture<Inspector.Result>();
        sinkThread.submit(() -> {
            Inspector.Result r = sink.exportRecordedTest(name, folder);
            SwingUtilities.invokeLater(() -> stoppedBar.text((r.ok ? "exported: " : "✗ not exported: ") + r.line));
            result.complete(r);
        });
        return result;
    }

    /**
     * The session's recording changed: the panel re-reads it now, and {@code notice} - the agent's edit - shows on the
     * bar (null: nothing to say).
     */
    void changed(String notice) {
        if (notice != null) note(notice);
        refresh(-1);
    }

    /**
     * Shows {@code text} on the bar for {@link #NOTE_MS}, then what the bar said before; a note that comes while
     * another shows replaces it, and the text before both comes back.
     */
    private void note(String text) {
        boolean showing = noteTimer != null && noteTimer.isRunning() && noted == bar;
        if (noteTimer != null) noteTimer.stop();
        if (!showing) beforeNote = bar.text();
        Bar on = bar;
        noted = on;
        on.text(text);
        noteTimer = new Timer(NOTE_MS, e -> {
            // a newer line (a step recorded, a replay's outcome) stays
            if (text.equals(on.text())) on.text(beforeNote);
        });
        noteTimer.setRepeats(false);
        noteTimer.start();
    }

    /**
     * ✕ Delete on the row of record {@code record}: deletes what the person saw there, by its id - an action's step
     * with its checks, or one check.
     */
    private void delete(int record) {
        int id = panel == null ? -1 : panel.idOfRecord(record);
        if (id < 0) return;
        sinkThread.submit(() -> {
            Inspector.Result r = sink.delete(id);
            SwingUtilities.invokeLater(() -> {
                if (r.ok) marks.remove(id);
                bar.text(r.ok ? r.line : "✗ not deleted: " + r.line);
                refresh(record);
            });
        });
    }

    /**
     * The person starts typing a sentence in place of a row of {@code shown} (a double click, F2, Enter, or Describe…):
     * not while a replay runs. Returns what gives the keyboard back when the field ends (Enter, Esc, a click on the
     * palette, the panel going, its records gone) to the app's component that had it - the one that has it now, else
     * the one the list took it from when the person clicked a row (never the palette's own) - and says whether it
     * took it (not one gone with a closed dialog); null when no naming may start.
     */
    private BooleanSupplier startNaming(StepsPanel shown) {
        if (replaying || agentReplay != null) return null;
        Component typing = inspector.focusOwner.get();
        if (typing == null || Overlay.ours(typing)) typing = shown.appFocus();
        Component back = typing;
        return () -> back != null && inspector.giveFocus.test(back);
    }

    /**
     * An edit from the row menu or a sentence typed in place, on the sink thread: its result goes on the bar
     * ({@code refused} before a refusal's reason) and the panel shows the recording as the edit left it, in the same
     * turn of the EDT; completes with the result after both, so a field closing on it never shows the rows before it.
     */
    private CompletableFuture<Inspector.Result> edit(Supplier<Inspector.Result> call, String refused) {
        CompletableFuture<Inspector.Result> result = new CompletableFuture<Inspector.Result>();
        sinkThread.submit(() -> {
            Inspector.Result r;
            try {
                r = call.get();
            } catch (RuntimeException e) {
                r = new Inspector.Result(false, why(e));
            }
            Inspector.Result done = r;
            List<Inspector.StepLine> read;
            String label;
            try {
                read = sink.steps();
                label = sink.undoLabel();
            } catch (RuntimeException e) {
                // the edit's answer still comes: a field waiting for it never stays uneditable
                read = null;
                label = null;
            }
            List<Inspector.StepLine> all = read;
            String undoLabel = label;
            SwingUtilities.invokeLater(() -> {
                bar.text(done.ok ? done.line : refused + done.line);
                if (all != null) showSteps(all, undoLabel, -1); else refresh(-1);
                result.complete(done);
            });
        });
        return result;
    }

    /** ↶ Undo: takes back the last edit, whoever made it, and says what. */
    private void undo() {
        sinkThread.submit(() -> {
            Inspector.Result r = sink.undo();
            SwingUtilities.invokeLater(() -> {
                bar.text(r.ok ? "undid: " + r.line : "✗ not undone: " + r.line);
                refresh(-1);
            });
        });
    }

    /** Re-reads the recording into the panel; {@code select} picks a record's row (-1 keeps the selection). */
    private void refresh(int select) {
        sinkThread.submit(() -> {
            List<Inspector.StepLine> all = sink.steps();
            String undoLabel = sink.undoLabel();
            SwingUtilities.invokeLater(() -> showSteps(all, undoLabel, select));
        });
    }

    /** Shows the recording {@code all} as read on the sink thread; {@code select} as for {@link #refresh}. EDT. */
    private void showSteps(List<Inspector.StepLine> all, String undoLabel, int select) {
        Set<Integer> ids = new HashSet<Integer>();
        for (Inspector.StepLine s : all) ids.add(s.id);
        marks.keySet().retainAll(ids);
        int row = Math.min(select, all.size() - 1);
        if (panel != null) panel.show(all, marks, row, replaying || !active() ? null : steps.pending(), undoLabel);
    }

    /**
     * Cuts the recording at the step of record {@code record}, as the person saw it (by its id), resets the app and replays what is
     * left to get it back there, as the test will, and records on - also after Done: redo always means recording the
     * rest. A reset or step that fails, or the person's Stop, leaves the cut (Undo brings it back) and the recording
     * stopped - a live one too, as at Done: the screen is not where the steps before the cut leave it.
     */
    private void redoFrom(int record) {
        int id = panel == null ? -1 : panel.idOfRecord(record);
        if (id < 0) return;
        // a sentence being typed is saved before the cut, which may take its records
        panel.commitNaming();
        steps.flush();
        sinkThread.submit(() -> {
            Inspector.Result cut = sink.cutFrom(id);
            // the records before the cut, as the recording is now: the agent may have changed it since the person looked
            List<Inspector.StepLine> left = cut.ok ? sink.steps() : new ArrayList<Inspector.StepLine>();
            int i = left.size();
            int stepsLeft = StepsPanel.stepCount(left);
            SwingUtilities.invokeLater(() -> {
                if (!cut.ok) {
                    bar.text("✗ no redo: " + cut.line);
                    refresh(-1);
                    return;
                }
                Runnable recordOn = () -> {
                    if (exportPanel != null) {
                        home.panel(null);
                        closeExportPanel();
                    }
                    sinkThread.submit(sink::resume);
                    if (state == State.STOPPED) {
                        state = State.RECORDING;
                        done = new CompletableFuture<List<String>>();
                        bar = activeBar;
                        home.bar(bar);
                        busy = PERSON_BUSY;
                        inspector.recordingBusy(busy);
                    }
                    mode(false);
                    activeBar.text(i == 0 ? "Recording cleared: record from here." : "Back at step " + stepsLeft + ": record the rest.");
                };
                // from the first step only the reset runs before the person records
                replay(0, i, recordOn);
            });
        });
    }

    /**
     * Replays steps {@code from} up to {@code to} (exclusive; -1 all) the way the exported test will: from the first
     * step the app is reset first (no step of its own: the bar says so), each step runs as the agent's would, nothing is
     * recorded, the person's own input is not listened to, the catcher and the steps panel are out of the way. At the
     * panel's speed each target is boxed for a moment BEFORE its step runs; an action and the checks after it are one
     * step, and step by step waits for Next with the last results still in view. Pause, Next and Stop are on the bar,
     * which moves off the box. The first failure, or the person's Stop, ends the replay.
     */
    private void replay(int from, int to, Runnable after) {
        replay(from, to, after, false);
    }

    /**
     * ▶ Run, for a loaded recorded test: the app reset as the recorded test starts, then every step replayed; a reset that fails runs
     * nothing and says why.
     */
    void run() {
        replay(0, -1, null, true);
    }

    /**
     * {@link #replay(int, int, Runnable)}; {@code run}: a loaded recorded test's ▶ Run, which says so. A replay from the first
     * step resets the app first; a reset that fails runs nothing and says why.
     */
    private void replay(int from, int to, Runnable after, boolean run) {
        if (replaying || agentReplay != null) return;
        boolean reset = from == 0;
        // no sentence is typed while the steps run: one being typed is saved, on the sink thread ahead of the replay
        if (panel != null) panel.commitNaming();
        replaying = true;
        boolean wasChecking = state == State.CHECKING;
        int speed = panel == null ? 1 : panel.speed();
        final long delay = speed == 0 ? 0 : speed == 1 ? 700 : 1500;
        paused = speed == 3;
        stopped = false;
        next.drainPermits();
        steps.flush();
        steps.active(false);
        String why = run ? "a person is running " + loadedName + " in the app" : "a person is replaying their recording in the app";
        inspector.recordingBusy(why);
        // the palette's steps go to the session directly: the agent's lease keeps every other call out meanwhile
        inspector.driving(why);
        for (Overlay o : inspector.overlays()) o.removeCatcher();
        replayBar = new Bar(run ? "Running" : "Replaying", reset ? "resetting the app …" : "").dot(Overlay.HOVER, false)
                .button("Pause", Overlay.NAVY.brighter(), () -> { paused = true; replayBar.active("Pause"); })
                .button("Resume", Overlay.YES, () -> { paused = false; replayBar.active(""); next.release(); })
                .button("Next", Overlay.HOVER, next::release)
                .button("Stop", Overlay.NO, () -> { stopped = true; next.release(); });
        if (paused) replayBar.active("Pause");
        home.bar(replayBar);
        // the flushed steps reach the recording before it is read
        sinkThread.submit(() -> {
            try {
                // an empty recording has nothing to get back to; a redo from the first step records from the reset
                if (reset && (after != null || !sink.steps().isEmpty())) {
                    Inspector.Result r = resetApp();
                    if (!r.ok) {
                        ended("✗ reset failed: " + r.line, -1, false, wasChecking, after);
                        return;
                    }
                }
                runReplay(from, to, delay, wasChecking, after);
            } catch (RuntimeException | Error e) {
                // nobody reads this task's future: whatever broke, the replay ends and the app is the person's again
                System.err.println("swing-ui-agent inspector: replay failed");
                e.printStackTrace();
                ended("✗ replay failed: " + why(e), -1, false, wasChecking, after);
            }
        });
    }

    /** The replay's reset; one that throws is a failed one. Sink thread. */
    private Inspector.Result resetApp() {
        try {
            return sink.reset();
        } catch (RuntimeException e) {
            return new Inspector.Result(false, why(e));
        }
    }

    /** What broke, never "null": the message, else the kind of failure. */
    private static String why(Throwable e) {
        String m = e.getMessage();
        return m != null && !m.trim().isEmpty() ? m : e.getClass().getSimpleName();
    }

    /** The replay loop, on the sink thread. */
    private void runReplay(int from, int to, long delay, boolean wasChecking, Runnable after) {
        List<Inspector.StepLine> all = sink.steps();
        // the bar names steps and checks as the list numbers them
        List<String> labels = StepsPanel.labels(all);
        int n = to < 0 ? all.size() : Math.min(to, all.size());
        String outcome = n <= from ? "nothing to replay" : "replayed " + steps(labels, from, n) + ": all passed";
        // the counter counts steps only: a check shows its step's number
        final String total = n <= from ? "" : StepsPanel.stepOf(labels.get(n - 1));
        int failed = -1;
        Inspector.onEdt(() -> {
            // marks from an earlier run must not mix with this one
            for (int i = from; i < n; i++) marks.remove(all.get(i).id);
            home.controlsAway(true);
        });
        for (int i = from; i < n; i++) {
            final int id = all.get(i).id;
            final Locator target = sink.target(i);
            final boolean check = all.get(i).check();
            final String counter = StepsPanel.stepOf(labels.get(i)) + "/" + total + "  ";
            // the bar says what it does in a few words, the box beside the element only its number and sentence
            final String label = counter + said(all.get(i), labels.get(i), target);
            final String said = all.get(i).say != null ? "  " + all.get(i).say : "";
            final String number = labels.get(i);
            final String box = number + said;
            // an action and the checks right after it are one step for the person: one Next runs them all
            final boolean starts = i == from || !check;
            final int group = starts ? Inspector.checksAfter(all, i, n) : 0;
            final Overlay.Mark[] mine = new Overlay.Mark[1];
            Inspector.onEdt(() -> {
                marks.put(id, "▶");
                // while paused the last step's results stay in view beside this one, until Next
                mine[0] = boxStep(target, box, check, starts && !paused);
                if (mine[0] != null && starts && paused) mine[0].label = box + "   (up next)";
                replayBar.text(label + (group > 0 ? " + " + group + (group == 1 ? " check" : " checks") : "")
                        + (paused && starts ? "   - paused: Next runs it" : ""));
            });
            if (starts) {
                if (!waitTurn(delay)) {
                    Inspector.onEdt(() -> marks.remove(id));
                    outcome = "■ stopped before step " + StepsPanel.stepOf(labels.get(i));
                    failed = i;
                    break;
                }
                Inspector.onEdt(() -> {
                    for (Overlay o : inspector.overlays()) {
                        if (mine[0] != null) o.keepOnly(mine[0]); else o.clearMarks();
                    }
                    if (mine[0] != null) mine[0].label = box;
                });
            } else {
                Inspector.pause(Math.min(delay, 300));
            }
            Inspector.Result r = sink.replay(i);
            Inspector.onEdt(() -> {
                marks.put(id, r.ok ? "✓" : "✗");
                Overlay.Mark m = mine[0];
                if (m != null) {
                    m.result = r.ok;
                    if (check || !r.ok) m.color = r.ok ? Overlay.YES : Overlay.NO;
                    m.label = check || !r.ok ? number + (r.ok ? " ✓" : " ✗") + said : box;
                    for (Overlay o : inspector.overlays()) o.repaint();
                }
            });
            if (!r.ok) {
                failed = i;
                outcome = "✗ " + (check ? "check " : "step ") + labels.get(i) + " failed: " + r.line;
                break;
            }
            // a check's outcome stays in view long enough to be seen, at any speed; step by step keeps it until Next
            if (!paused) Inspector.pause(check ? Math.max(delay, 600) : delay / 2);
        }
        // a Stop the loop never saw (during the reset of a redo from the first step, or after the last step) counts too
        if (failed < 0 && stopped) {
            outcome = n <= from ? "■ stopped after the reset" : "■ stopped after step " + StepsPanel.stepOf(labels.get(n - 1));
        }
        ended(outcome, failed, failed < 0 && !stopped, wasChecking, after);
    }

    /**
     * What the replay bar says record {@code s} (labelled {@code label}, aimed at {@code target}) does: its sentence,
     * else its verb and the element's visible name - never the locator, which the steps list shows on hover.
     */
    static String said(Inspector.StepLine s, String label, Locator target) {
        if (s.say != null) return s.say;
        String what = shownName(target);
        if (s.check()) return "check " + label + (what != null ? " " + what : "");
        String line = s.line;
        int open = line.indexOf('(');
        String verb = open < 0 ? line : line.substring(0, open);
        if (target == null) {
            // a step without an element (a menu path, keys): its arguments are what the person sees
            return open < 0 || !line.endsWith(")") ? line : verb + " " + line.substring(open + 1, line.length() - 1);
        }
        if (verb.equals("type")) verb = "type into";
        return what != null ? verb + " " + what : verb;
    }

    /** The element's name as the person reads it, quoted: a cell's column in its table, else its name, else its id. */
    private static String shownName(Locator l) {
        if (l == null) return null;
        String name = l.name != null ? "“" + l.name + "”" : l.id != null ? "“" + l.id + "”" : null;
        String part = l.column != null ? l.column : l.item != null && !l.item.isEmpty() ? l.item.get(l.item.size() - 1) : null;
        if (part == null) return name;
        return "“" + part + "”" + (name != null ? " in " + name : "");
    }

    /**
     * The replay or run ended with {@code text} on the bar ({@code stop}: the step it stopped at, -1 none; {@code passed}:
     * the reset and every step passed): the palette comes back and other calls may drive the app again. {@code after}
     * (a redo's record-on) runs when it passed; when it did not, a live recording stops as at Done, so the person's
     * steps on a screen the replay did not reach never follow the cut. Sink thread.
     */
    private void ended(String text, int stop, boolean passed, boolean wasChecking, Runnable after) {
        SwingUtilities.invokeLater(() -> {
            for (Overlay o : inspector.overlays()) o.clearMarks();
            home.controlsAway(false);
            replaying = false;
            inspector.driving(null);
            if (!passed && after != null && active()) {
                stop(true, text + " - recording stopped");
                bar.text(text + " - recording stopped");
                refresh(stop);
                return;
            }
            home.bar(bar);
            if (active()) {
                inspector.recordingBusy(busy);
                mode(wasChecking);
            } else {
                inspector.recordingBusy(null);
            }
            bar.text(text);
            refresh(stop);
            if (passed && after != null) after.run();
        });
    }

    /** "steps 2-4" (or "step 2") for the records {@code from} to {@code to} (exclusive), by their labels. */
    private static String steps(List<String> labels, int from, int to) {
        String first = StepsPanel.stepOf(labels.get(from));
        String last = StepsPanel.stepOf(labels.get(to - 1));
        return first.equals(last) ? "step " + first : "steps " + first + "-" + last;
    }

    /** Waits out the step's moment, or for Next while paused; false when the person pressed Stop. */
    private boolean waitTurn(long delay) {
        long until = System.currentTimeMillis() + delay;
        while (!stopped) {
            if (paused) {
                try {
                    next.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                return !stopped;
            }
            if (System.currentTimeMillis() >= until) {
                next.drainPermits();
                return true;
            }
            Inspector.pause(40);
        }
        return false;
    }

    /**
     * Boxes a step's target, labelled; {@code clear} removes the boxes before it first. Returns the box, or null for a
     * step without a target or one not on screen yet (the step waits for it itself).
     */
    private Overlay.Mark boxStep(Locator target, String label, boolean check, boolean clear) {
        if (clear) for (Overlay o : inspector.overlays()) o.clearMarks();
        if (target == null) return null;
        Target t = inspector.resolveTarget(target);
        if (t == null) return null;
        Overlay o = inspector.overlayFor(t.component);
        if (o == null) return null;
        Overlay.Mark m = Overlay.Mark.of(t, label, check ? Overlay.CHECK : Overlay.HOVER, false);
        o.mark(m, null);
        return m;
    }
}
