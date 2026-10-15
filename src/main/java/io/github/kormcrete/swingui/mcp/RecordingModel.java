package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.flow.Action;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * The steps recorded so far, each with an id that is never reused and an author. Not thread-safe: every call happens
 * under the session's monitor. Sentences, groups and folds hang off step ids, so they survive other steps coming and
 * going; {@link #rev()} moves on every change so a reader can tell its copy is stale.
 */
final class RecordingModel {

    /** Where the recording stands: nothing started yet, taking steps, or ended (Done, stop, a loaded recorded test). */
    enum State {
        IDLE("idle"), RECORDING("recording"), STOPPED("stopped");

        final String json;

        State(String json) {
            this.json = json;
        }
    }

    /** The recording as of one revision: immutable, so a reader needs no lock. */
    static final class Snapshot {
        final int rev;
        final State state;
        final boolean explicit;
        final RecordedStep.Author startedBy;
        final long startedAt;
        final boolean shared;
        final List<RecordedStep> steps;
        final List<String> notRecorded;

        private Snapshot(RecordingModel m) {
            this.rev = m.rev;
            this.state = m.state;
            this.explicit = m.explicit;
            this.startedBy = m.startedBy;
            this.startedAt = m.startedAt;
            this.shared = m.shared;
            this.steps = Collections.unmodifiableList(new ArrayList<RecordedStep>(m.steps));
            this.notRecorded = Collections.unmodifiableList(new ArrayList<String>(m.notRecorded));
        }
    }

    private final List<RecordedStep> steps = new ArrayList<RecordedStep>();
    private final List<String> notRecorded = new ArrayList<String>();
    private final Deque<Edit> history = new ArrayDeque<Edit>();
    private String lastChange = "nothing yet";
    private int nextId = 1;
    private int rev;
    private State state = State.IDLE;
    /** Started by {@code recording start}, the person's Record or a redo, not implicitly by a session reset. */
    private boolean explicit;
    private RecordedStep.Author startedBy = RecordedStep.Author.AGENT;
    private long startedAt;
    /** The palette shows it and the person's steps join it. */
    private boolean shared;

    State state() {
        return state;
    }

    boolean explicit() {
        return explicit;
    }

    RecordedStep.Author startedBy() {
        return startedBy;
    }

    long startedAt() {
        return startedAt;
    }

    boolean shared() {
        return shared;
    }

    /** Begins a fresh recording: no steps, none left unrecorded, taking steps. */
    void start(RecordedStep.Author by, boolean explicit, boolean shared, long now) {
        steps.clear();
        notRecorded.clear();
        history.clear();
        state = State.RECORDING;
        this.startedBy = by;
        this.explicit = explicit;
        this.shared = shared;
        this.startedAt = now;
        changed("the " + by.json + " started a recording");
    }

    /** Takes steps again after a stop, keeping the steps (a redo that records on). */
    void resume(RecordedStep.Author by, boolean explicit, boolean shared, long now) {
        state = State.RECORDING;
        this.startedBy = by;
        this.explicit = explicit;
        this.shared = shared;
        this.startedAt = now;
        changed("the " + by.json + " resumed the recording");
    }

    /** Ends the recording: no further step joins it. */
    void stop() {
        state = State.STOPPED;
        changed("the recording stopped");
    }

    /** The steps are a loaded recorded test's: stopped, started by the recorded test. */
    void loaded() {
        state = State.STOPPED;
        startedBy = RecordedStep.Author.LOADED;
        explicit = false;
        shared = false;
        notRecorded.clear();
        changed("a recorded test was loaded");
    }

    /** A step that ran but could not be recorded, with why. */
    void refused(String line) {
        notRecorded.add(line);
        changed("a step could not be recorded");
    }

    List<String> notRecorded() {
        return Collections.unmodifiableList(new ArrayList<String>(notRecorded));
    }

    Snapshot snapshot() {
        return new Snapshot(this);
    }

    /** Adds {@code action} at the end with the next id. */
    RecordedStep append(Action action, RecordedStep.Author author) {
        RecordedStep step = new RecordedStep(nextId++, author, action, false);
        steps.add(step);
        changed("the " + author.json + " recorded step " + steps.size());
        return step;
    }

    /** Empties the recording and ends it (idle). Ids keep counting: a step gone is never confused with a later one. */
    void clear() {
        steps.clear();
        notRecorded.clear();
        history.clear();
        state = State.IDLE;
        explicit = false;
        shared = false;
        changed("the recording was cleared");
    }

    /** Replaces the recording with {@code actions}, all by {@code author}. */
    void load(List<Action> actions, RecordedStep.Author author) {
        steps.clear();
        history.clear();
        for (Action a : actions) steps.add(new RecordedStep(nextId++, author, a, false));
        changed("a recorded test was loaded");
    }

    List<Action> actions() {
        List<Action> out = new ArrayList<Action>(steps.size());
        for (RecordedStep s : steps) out.add(s.action);
        return out;
    }

    List<RecordedStep> steps() {
        return Collections.unmodifiableList(new ArrayList<RecordedStep>(steps));
    }

    int size() {
        return steps.size();
    }

    int rev() {
        return rev;
    }

    /** The step at position {@code n} (1-based). */
    RecordedStep at(int n) {
        if (n < 1 || n > steps.size()) throw outOfRange(n);
        return steps.get(n - 1);
    }

    /** The position (1-based) of the step with {@code id}, 0 when it is gone. */
    int positionOf(int id) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id == id) return i + 1;
        }
        return 0;
    }

    /** Longest history of edits kept. */
    static final int HISTORY = 50;

    /** One edit that undo can take back: the steps from before it, and the highest id right after. */
    private static final class Edit {
        final String label;
        final String past;
        final RecordedStep.Author author;
        final List<RecordedStep> before;
        final int maxIdAfter;
        final boolean cut;

        Edit(String label, String past, RecordedStep.Author author, List<RecordedStep> before, int maxIdAfter,
                boolean cut) {
            this.label = label;
            this.past = past;
            this.author = author;
            this.before = before;
            this.maxIdAfter = maxIdAfter;
            this.cut = cut;
        }
    }

    /** What the last change was, e.g. "the person deleted step 3", for the refusal of an edit made on a stale rev. */
    String lastChange() {
        return lastChange;
    }

    private void changed(String what) {
        lastChange = what;
        rev++;
    }

    private static String by(RecordedStep.Author a) {
        return a.json.equals("loaded") ? "the loaded test" : "the " + a.json;
    }

    /** Records the edit just made (steps before it in {@code before}) and returns its past-tense text. */
    private String edited(String label, String past, RecordedStep.Author author, List<RecordedStep> before,
            boolean cut) {
        history.addFirst(new Edit(label + " by " + by(author), past, author, before, nextId - 1, cut));
        while (history.size() > HISTORY) history.removeLast();
        changed(by(author) + " " + past);
        return past;
    }

    /** Inserts {@code action} so that it is step {@code at} (1 to size+1); it counts as edited. */
    String insert(int at, Action action, RecordedStep.Author author) {
        if (at < 1 || at > steps.size() + 1) {
            throw new DriverException(Reason.BAD_INPUT, "cannot insert at step " + at + ": the recording "
                    + (steps.isEmpty() ? "is empty (insert at 1)" : "has " + steps.size() + " steps (insert at 1-"
                    + (steps.size() + 1) + ")"));
        }
        List<RecordedStep> before = new ArrayList<RecordedStep>(steps);
        steps.add(at - 1, new RecordedStep(nextId++, author, action, true));
        return edited("insert at step " + at, "inserted at step " + at, author, before, false);
    }

    /** Removes steps {@code from} to {@code to} (1-based, inclusive). */
    String delete(int from, int to, RecordedStep.Author author) {
        if (from < 1 || from > steps.size()) throw outOfRange(from);
        if (to < from || to > steps.size()) throw outOfRange(to);
        List<RecordedStep> before = new ArrayList<RecordedStep>(steps);
        for (int i = to; i >= from; i--) steps.remove(i - 1);
        String what = from == to ? "step " + from : "steps " + from + "-" + to;
        return edited("delete of " + what, "deleted " + what, author, before, false);
    }

    /** Moves step {@code n} so that it is step {@code to} afterwards. */
    String move(int n, int to, RecordedStep.Author author) {
        if (n < 1 || n > steps.size()) throw outOfRange(n);
        if (to < 1 || to > steps.size()) throw outOfRange(to);
        List<RecordedStep> before = new ArrayList<RecordedStep>(steps);
        steps.add(to - 1, steps.remove(n - 1));
        return edited("move of step " + n + " to " + to, "moved step " + n + " to " + to, author, before, false);
    }

    /** Replaces the action of step {@code n}; the step keeps its id and author and counts as edited. */
    String replace(int n, Action action, RecordedStep.Author author) {
        RecordedStep old = at(n);
        List<RecordedStep> before = new ArrayList<RecordedStep>(steps);
        steps.set(n - 1, old.with(action, true));
        return edited("edit of step " + n, "edited step " + n, author, before, false);
    }

    /** Drops step {@code n} and everything after it (a redo's cut); null when there is nothing to drop. */
    String cutFrom(int n, RecordedStep.Author author) {
        if (n < 1 || n > steps.size()) return null;
        List<RecordedStep> before = new ArrayList<RecordedStep>(steps);
        while (steps.size() >= n) steps.remove(steps.size() - 1);
        return edited("cut from step " + n + " (redo)", "cut from step " + n, author, before, true);
    }

    /** A replay passed the step with {@code id}: it no longer counts as edited. Whether that changed anything. */
    boolean markReplayed(int id) {
        for (int i = 0; i < steps.size(); i++) {
            RecordedStep s = steps.get(i);
            if (s.id == id && s.edited) {
                steps.set(i, s.with(s.action, false));
                changed("step " + (i + 1) + " passed a replay");
                return true;
            }
        }
        return false;
    }

    /** The steps after the last edit is taken back, or null when there is nothing to undo. */
    private List<RecordedStep> undone() {
        Edit e = history.peekFirst();
        if (e == null) return null;
        List<RecordedStep> out = new ArrayList<RecordedStep>(e.before);
        if (!e.cut) {
            for (RecordedStep s : steps) if (s.id > e.maxIdAfter) out.add(s);
        }
        return out;
    }

    /** The actions undo would leave, to check them before committing; null when there is nothing to undo. */
    List<Action> undoPreview() {
        List<RecordedStep> after = undone();
        if (after == null) return null;
        List<Action> out = new ArrayList<Action>(after.size());
        for (RecordedStep s : after) out.add(s.action);
        return out;
    }

    /** What undo would take back, e.g. "delete of step 7 by the person", or null. */
    String undoLabel() {
        Edit e = history.peekFirst();
        return e == null ? null : e.label;
    }

    /**
     * Takes back the last edit, keeping steps recorded since (a redo's cut instead drops the steps recorded after
     * it, which followed the cut); {@code by} undid it, as {@link #lastChange} then says. Returns what it did ("undid
     * the delete of step 7 by the person"), or null when there is nothing to undo.
     */
    String undo(RecordedStep.Author by) {
        Edit e = history.peekFirst();
        List<RecordedStep> after = undone();
        if (e == null) return null;
        int dropped = 0;
        if (e.cut) {
            for (RecordedStep s : steps) if (s.id > e.maxIdAfter) dropped++;
        }
        history.removeFirst();
        steps.clear();
        steps.addAll(after);
        String did = "undid the " + e.label;
        if (dropped > 0) did += " (" + dropped + " step" + (dropped == 1 ? "" : "s") + " recorded after it dropped)";
        changed(by(by) + " " + did);
        return did;
    }

    private DriverException outOfRange(int n) {
        String range = steps.isEmpty() ? "is empty"
                : "has " + steps.size() + " step" + (steps.size() == 1 ? "" : "s") + " (1-" + steps.size() + ")";
        return new DriverException(Reason.BAD_INPUT, "no step " + n + ": the recording " + range);
    }
}
