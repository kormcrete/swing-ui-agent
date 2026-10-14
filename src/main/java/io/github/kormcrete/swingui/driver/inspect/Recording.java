package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.model.Locator;

import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

/**
 * One recording the person made, from their ● Record to their Done, and after it: the palette (Record, Check, Steps,
 * Done; after Done ● Record, Steps, Export…), the person's own steps (Record mode), the check catcher (Check mode), the
 * steps panel with replay, redo and delete, and the export panel that writes the recording as a JUnit class. Only the
 * person starts and ends it. EDT, except where a method says otherwise.
 */
final class Recording {

    enum State { RECORDING, CHECKING, STOPPED }

    private static final Color STEPS = new Color(0x6B, 0x4F, 0xBB);
    private static final Color RED = new Color(0xE5, 0x39, 0x35);

    private final Inspector inspector;
    private final Inspector.Sink sink;
    private final ExecutorService sinkThread;
    private final Runnable recordAgain;
    Overlay home;
    final List<String> lines = Collections.synchronizedList(new ArrayList<String>());
    private final HumanSteps steps;
    private final Bar activeBar;
    private final Bar stoppedBar;
    Bar bar;
    /** Per step: ● recorded, ▶ running, ✓ replayed, ✗ failed. */
    private final List<String> marks = new ArrayList<String>();
    StepsPanel panel;
    ExportPanel exportPanel;
    State state = State.RECORDING;
    boolean replaying;
    /** Completes at the person's Done; a redo that records on starts a new one. */
    volatile CompletableFuture<List<String>> done = new CompletableFuture<List<String>>();
    /** Re-reads the recording while the panel is open, so it never lags (typing shows as it happens). */
    private final Timer live = new Timer(500, e -> refresh(-1));

    /** Replay controls, read by the replay thread. */
    volatile boolean paused;
    volatile boolean stopped;
    final Semaphore next = new Semaphore(0);
    private Bar replayBar;

    Recording(Inspector inspector, Inspector.Sink sink, ExecutorService sinkThread, Overlay home, Runnable recordAgain) {
        this.inspector = inspector;
        this.sink = sink;
        this.sinkThread = sinkThread;
        this.home = home;
        this.recordAgain = recordAgain;
        this.steps = new HumanSteps(inspector, this::emit);
        this.activeBar = new Bar("Recording", "Use the app: every step is recorded.").dot(RED, true)
                .button("Record", Overlay.NO, () -> mode(false))
                .button("Check", Overlay.YES, () -> mode(true))
                .button("Steps", STEPS, this::toggleSteps)
                .button("Done", Overlay.HOVER, this::done);
        this.stoppedBar = new Bar("Recorded", "").dot(new Color(0x8A, 0x96, 0xAD), false)
                .button("● Record", Overlay.NO, recordAgain)
                .button("Steps", STEPS, this::toggleSteps)
                .button("Export…", Overlay.YES, this::toggleExport);
        this.bar = activeBar;
    }

    void start() {
        home.bar(bar);
        inspector.recordingBusy("a person is recording in the app (they end it with Done in its palette)");
        mode(false);
        refresh(-1);
    }

    boolean active() {
        return state != State.STOPPED;
    }

    void mode(boolean check) {
        if (replaying || state == State.STOPPED) return;
        if (check) steps.flush();
        state = check ? State.CHECKING : State.RECORDING;
        activeBar.active(check ? "Check" : "Record");
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
        activeBar.text(check ? "Click what to check, then choose the check." : "Use the app: every step is recorded.");
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
        JComponent open = panel != null ? panel : exportPanel;
        from.bar(null);
        if (open != null) from.panel(null);
        if (replaying) {
            from.controlsAway(false);
            to.controlsAway(true);
        }
        home = to;
        to.bar(shown);
        if (open != null) to.panel(open);
        if (state == State.CHECKING && !replaying) mode(true);
    }

    /** The person's Done: stop listening, keep the recording for the agent, offer Steps and a new ● Record. */
    void done() {
        if (replaying || state == State.STOPPED) return;
        steps.flush();
        steps.active(false);
        for (Overlay o : inspector.overlays()) o.removeCatcher();
        state = State.STOPPED;
        inspector.recordingBusy(null);
        bar = stoppedBar;
        home.bar(bar);
        // the steps already handed to the sink thread reach the session before anyone reads the recording
        CompletableFuture<List<String>> finished = done;
        sinkThread.submit(() -> {
            int n = sink.steps().size();
            SwingUtilities.invokeLater(() -> stoppedBar.text(n + (n == 1 ? " step" : " steps")
                    + " - Export… writes them as a test, or the agent does"));
            finished.complete(new ArrayList<String>(lines));
        });
    }

    /** Stops for good: a new recording replaces this one. */
    void dispose() {
        stopped = true;
        next.release();
        live.stop();
        steps.active(false);
        if (panel != null || exportPanel != null) home.panel(null);
        panel = null;
        exportPanel = null;
        for (Overlay o : inspector.overlays()) o.removeCatcher();
        if (!done.isDone()) done.complete(new ArrayList<String>(lines));
    }

    private void emit(Map<String, Object> step) {
        sinkThread.submit(() -> {
            Inspector.Result r = sink.done(step);
            SwingUtilities.invokeLater(() -> show(r));
        });
    }

    private void check(Map<String, Object> step, Target t, Overlay o) {
        activeBar.text("checking …");
        sinkThread.submit(() -> {
            Inspector.Result r = sink.check(step);
            SwingUtilities.invokeLater(() -> {
                show(r);
                Overlay.Mark m = Overlay.Mark.of(t, r.ok ? "check recorded" : "check failed", r.ok ? Overlay.YES : Overlay.NO, false);
                m.result = r.ok;
                o.mark(m, null);
                Timer clear = new Timer(1500, e -> { if (!replaying) o.clearMarks(); });
                clear.setRepeats(false);
                clear.start();
            });
        });
    }

    private void show(Inspector.Result r) {
        lines.add((r.ok ? "" : "NOT RECORDED: ") + r.line);
        activeBar.text((r.ok ? "recorded: " : "✗ not recorded: ") + r.line);
        refresh(-1);
    }

    void toggleSteps() {
        if (panel != null) {
            home.panel(null);
            panel = null;
            live.stop();
            return;
        }
        exportPanel = null;
        panel = new StepsPanel(() -> replay(0, -1, null), i -> replay(i, -1, null), this::redoFrom, this::delete,
                this::toggleSteps);
        home.panel(panel);
        refresh(-1);
        live.start();
    }

    /** Export…: opens (or closes) the panel where the person names the class; it replaces the steps panel. */
    void toggleExport() {
        if (exportPanel != null) {
            home.panel(null);
            exportPanel = null;
            return;
        }
        if (state != State.STOPPED) return;
        sinkThread.submit(() -> {
            String pkg = sink.exportPackage();
            SwingUtilities.invokeLater(() -> {
                if (state != State.STOPPED || exportPanel != null) return;
                if (panel != null) {
                    panel = null;
                    live.stop();
                }
                exportPanel = new ExportPanel("RecordedUiIT", pkg, (c, p) -> export(c, p).thenAccept(r ->
                        SwingUtilities.invokeLater(() -> {
                            if (exportPanel == null) return;
                            if (r.ok) toggleExport(); else exportPanel.refused(r.line);
                        })), this::toggleExport);
                home.panel(exportPanel);
            });
        });
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

    private void delete(int i) {
        sinkThread.submit(() -> {
            sink.remove(i);
            SwingUtilities.invokeLater(() -> {
                if (i < marks.size()) marks.remove(i);
                bar.text("deleted step " + (i + 1));
                refresh(Math.min(i, marks.size() - 1));
            });
        });
    }

    /** Re-reads the recording into the panel; {@code select} picks a row (-1 keeps the selection). */
    private void refresh(int select) {
        sinkThread.submit(() -> {
            List<String> all = sink.steps();
            SwingUtilities.invokeLater(() -> {
                while (marks.size() < all.size()) marks.add("●");
                while (marks.size() > all.size()) marks.remove(marks.size() - 1);
                if (panel != null) panel.show(all, marks, select, replaying || !active() ? null : steps.pending());
            });
        });
    }

    /**
     * Cuts the recording before step {@code i}, replays what is left to get the app back there, and records on - also
     * after Done: redo always means recording the rest.
     */
    private void redoFrom(int i) {
        steps.flush();
        sinkThread.submit(() -> {
            sink.truncate(i);
            SwingUtilities.invokeLater(() -> {
                while (marks.size() > i) marks.remove(marks.size() - 1);
                Runnable recordOn = () -> {
                    if (exportPanel != null) {
                        home.panel(null);
                        exportPanel = null;
                    }
                    if (state == State.STOPPED) {
                        state = State.RECORDING;
                        done = new CompletableFuture<List<String>>();
                        bar = activeBar;
                        home.bar(bar);
                        inspector.recordingBusy("a person is recording in the app (they end it with Done in its palette)");
                    }
                    mode(false);
                    activeBar.text(i == 0 ? "Recording cleared: record from here." : "Back at step " + i + ": record the rest.");
                };
                if (i == 0) {
                    refresh(-1);
                    recordOn.run();
                } else {
                    replay(0, i, recordOn);
                }
            });
        });
    }

    /**
     * Replays steps {@code from} up to {@code to} (exclusive; -1 all) the way the exported test will: each runs as the
     * agent's step would, nothing is recorded, the person's own input is not listened to, the catcher and the steps panel
     * are out of the way. At the panel's speed each target is boxed for a moment BEFORE its step runs; an action and the
     * checks after it are one step, and step by step waits for Next with the last results still in view. Pause, Next and
     * Stop are on the bar, which moves off the box. The first failure stops the replay.
     */
    private void replay(int from, int to, Runnable after) {
        if (replaying) return;
        replaying = true;
        boolean wasChecking = state == State.CHECKING;
        int speed = panel == null ? 1 : panel.speed();
        final long delay = speed == 0 ? 0 : speed == 1 ? 700 : 1500;
        paused = speed == 3;
        stopped = false;
        next.drainPermits();
        steps.flush();
        steps.active(false);
        inspector.recordingBusy("a person is replaying their recording in the app");
        for (Overlay o : inspector.overlays()) o.removeCatcher();
        replayBar = new Bar("Replaying", "").dot(Overlay.HOVER, false)
                .button("Pause", Overlay.NAVY.brighter(), () -> { paused = true; replayBar.active("Pause"); })
                .button("Resume", Overlay.YES, () -> { paused = false; replayBar.active(""); next.release(); })
                .button("Next", Overlay.HOVER, next::release)
                .button("Stop", Overlay.NO, () -> { stopped = true; next.release(); });
        if (paused) replayBar.active("Pause");
        home.bar(replayBar);
        // the flushed steps reach the recording before it is read
        sinkThread.submit(() -> runReplay(from, to, delay, wasChecking, after));
    }

    /** The replay loop, on the sink thread. */
    private void runReplay(int from, int to, long delay, boolean wasChecking, Runnable after) {
        List<String> all = sink.steps();
        int n = to < 0 ? all.size() : Math.min(to, all.size());
        String outcome = n <= from ? "nothing to replay" : "replayed steps " + (from + 1) + "-" + n + ": all passed";
        int failed = -1;
        Inspector.onEdt(() -> {
            while (marks.size() < all.size()) marks.add("●");
            // marks from an earlier run must not mix with this one
            for (int i = from; i < n; i++) marks.set(i, "●");
            home.controlsAway(true);
        });
        for (int i = from; i < n; i++) {
            final int k = i;
            final Locator target = sink.target(i);
            final boolean check = all.get(i).startsWith("expect(");
            final String what = check ? "check: " + all.get(i).substring(7, all.get(i).length() - 1) : all.get(i);
            final String label = (i + 1) + "/" + n + "  " + what;
            // an action and the checks right after it are one step for the person: one Next runs them all
            final boolean starts = i == from || !check;
            final int group = starts ? Inspector.checksAfter(all, i, n) : 0;
            final Overlay.Mark[] mine = new Overlay.Mark[1];
            Inspector.onEdt(() -> {
                marks.set(k, "▶");
                // while paused the last step's results stay in view beside this one, until Next
                mine[0] = boxStep(target, label, check, starts && !paused);
                if (mine[0] != null && starts && paused) mine[0].label = label + "   (up next)";
                replayBar.text(label + (group > 0 ? " + " + group + (group == 1 ? " check" : " checks") : "")
                        + (paused && starts ? "   - paused: Next runs it" : ""));
            });
            if (starts) {
                if (!waitTurn(delay)) {
                    Inspector.onEdt(() -> marks.set(k, "●"));
                    outcome = "■ stopped before step " + (i + 1);
                    failed = i;
                    break;
                }
                Inspector.onEdt(() -> {
                    for (Overlay o : inspector.overlays()) {
                        if (mine[0] != null) o.keepOnly(mine[0]); else o.clearMarks();
                    }
                    if (mine[0] != null) mine[0].label = label;
                });
            } else {
                Inspector.pause(Math.min(delay, 300));
            }
            Inspector.Result r = sink.replay(i);
            Inspector.onEdt(() -> {
                marks.set(k, r.ok ? "✓" : "✗");
                Overlay.Mark m = mine[0];
                if (m != null) {
                    m.result = r.ok;
                    if (check || !r.ok) m.color = r.ok ? Overlay.YES : Overlay.NO;
                    m.label = (k + 1) + "/" + n + "  " + (check ? (r.ok ? "passed: " : "FAILED: ") + what.substring(7)
                            : (r.ok ? "" : "FAILED: ") + what);
                    for (Overlay o : inspector.overlays()) o.repaint();
                }
            });
            if (!r.ok) {
                failed = i;
                outcome = "✗ step " + (i + 1) + " failed: " + r.line;
                break;
            }
            // a check's outcome stays in view long enough to be seen, at any speed; step by step keeps it until Next
            if (!paused) Inspector.pause(check ? Math.max(delay, 600) : delay / 2);
        }
        final String text = outcome;
        final int stop = failed;
        SwingUtilities.invokeLater(() -> {
            for (Overlay o : inspector.overlays()) o.clearMarks();
            home.controlsAway(false);
            replaying = false;
            home.bar(bar);
            if (active()) {
                inspector.recordingBusy("a person is recording in the app (they end it with Done in its palette)");
                mode(wasChecking);
            } else {
                inspector.recordingBusy(null);
            }
            bar.text(text);
            refresh(stop);
            if (stop < 0 && after != null) after.run();
        });
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
