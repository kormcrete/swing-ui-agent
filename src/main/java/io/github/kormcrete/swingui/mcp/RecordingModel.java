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

    private final List<RecordedStep> steps = new ArrayList<RecordedStep>();
    private int nextId = 1;
    private int rev;

    /** Adds {@code action} at the end with the next id. */
    RecordedStep append(Action action, RecordedStep.Author author) {
        RecordedStep step = new RecordedStep(nextId++, author, action, false);
        steps.add(step);
        rev++;
        return step;
    }

    /** Empties the recording. Ids keep counting: a step gone is never confused with a later one. */
    void clear() {
        steps.clear();
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
