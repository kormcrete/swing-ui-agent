package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.flow.Action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The steps recorded so far, each with an id that is never reused and an author. Not thread-safe: every call happens
 * under the session's monitor. Sentences, groups and folds hang off step ids, so they survive other steps coming and
 * going; {@link #rev()} moves on every change so a reader can tell its copy is stale.
 */
final class RecordingModel {

    /** Where the recording stands: nothing started yet, taking steps, or ended (Done, stop, a loaded flow). */
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
        state = State.RECORDING;
        this.startedBy = by;
        this.explicit = explicit;
        this.shared = shared;
        this.startedAt = now;
        rev++;
    }

    /** Takes steps again after a stop, keeping the steps (a redo that records on). */
    void resume(RecordedStep.Author by, boolean explicit, boolean shared, long now) {
        state = State.RECORDING;
        this.startedBy = by;
        this.explicit = explicit;
        this.shared = shared;
        this.startedAt = now;
        rev++;
    }

    /** Ends the recording: no further step joins it. */
    void stop() {
        state = State.STOPPED;
        rev++;
    }

    /** The steps are a loaded flow's: stopped, started by the flow. */
    void loaded() {
        state = State.STOPPED;
        startedBy = RecordedStep.Author.FLOW;
        explicit = false;
        shared = false;
        notRecorded.clear();
        rev++;
    }

    /** A step that ran but could not be recorded, with why. */
    void refused(String line) {
        notRecorded.add(line);
        rev++;
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
        rev++;
        return step;
    }

    /** Empties the recording and ends it (idle). Ids keep counting: a step gone is never confused with a later one. */
    void clear() {
        steps.clear();
        notRecorded.clear();
        state = State.IDLE;
        explicit = false;
        shared = false;
        rev++;
    }

    /** Replaces the recording with {@code actions}, all by {@code author}. */
    void load(List<Action> actions, RecordedStep.Author author) {
        steps.clear();
        for (Action a : actions) steps.add(new RecordedStep(nextId++, author, a, false));
        rev++;
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

    /** Removes the step at position {@code n} (1-based); the palette's positional edit. */
    void remove(int n) {
        if (n < 1 || n > steps.size()) throw outOfRange(n);
        steps.remove(n - 1);
        rev++;
    }

    /** Keeps the first {@code keep} steps (the palette's positional redo). */
    void truncate(int keep) {
        int k = Math.max(0, keep);
        if (k >= steps.size()) return;
        while (steps.size() > k) steps.remove(steps.size() - 1);
        rev++;
    }

    private DriverException outOfRange(int n) {
        String range = steps.isEmpty() ? "is empty"
                : "has " + steps.size() + " step" + (steps.size() == 1 ? "" : "s") + " (1-" + steps.size() + ")";
        return new DriverException(Reason.BAD_INPUT, "no step " + n + ": the recording " + range);
    }
}
