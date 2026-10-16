package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.model.RowChoice;
import io.github.kormcrete.swingui.flow.Action;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.List;

/**
 * One step of the session's recording: the {@link Action} plus the id it keeps for the whole recording and who made it.
 * The id is never reused; a position is only its place in the list right now. What a vocabulary's folding needs lives
 * here too and never reaches a file: how the step's elements were seen, the raw steps a fold stands for, and whether the
 * step is to be kept raw.
 */
final class RecordedStep {

    /** Who made the step: the person at the palette, the agent, or a loaded recorded test. */
    enum Author {
        PERSON("person"), AGENT("agent"), LOADED("loaded");

        final String json;

        Author(String json) {
            this.json = json;
        }
    }

    /** A group of whole steps with one sentence; every record of its steps (actions and checks) shares the one object. */
    static final class Group {
        final int id;
        final String say;

        Group(int id, String say) {
            this.id = id;
            this.say = say;
        }
    }

    final int id;
    final Author author;
    final Action action;
    /** The step was changed after it was recorded (by either side). */
    final boolean edited;

    /** The group the step belongs to, or null. */
    final Group group;
    /** The row alternatives of a table step, once known; null otherwise. */
    final RowChoice row;
    /**
     * Each target field of the step ({@code target}, {@code from}, {@code to}) as its element was seen when the step was
     * recorded (full scope, role, name, every cell of its row); null when not known. Only folding reads it.
     */
    final JsonNode seen;
    /** A fold: the raw steps this {@code do} step was made of, in order (never folds themselves); null otherwise. */
    final List<RecordedStep> raw;
    /** Kept raw: folding never takes the step into a fold, nor folds across it (keepRaw, an unfold, an undone fold). */
    final boolean keepRaw;

    RecordedStep(int id, Author author, Action action, boolean edited) {
        this(id, author, action, edited, null, null);
    }

    RecordedStep(int id, Author author, Action action, boolean edited, Group group, RowChoice row) {
        this(id, author, action, edited, group, row, null, null, false);
    }

    RecordedStep(int id, Author author, Action action, boolean edited, Group group, RowChoice row, JsonNode seen,
                 List<RecordedStep> raw, boolean keepRaw) {
        this.id = id;
        this.author = author;
        this.action = action;
        this.edited = edited;
        this.group = group;
        this.row = row;
        this.seen = seen;
        this.raw = raw == null ? null : Collections.unmodifiableList(raw);
        this.keepRaw = keepRaw;
    }

    /** A fold of {@code raw} into {@code action}, a {@code do} step: new, by {@code author}, in no group. */
    static RecordedStep fold(int id, Author author, Action action, List<RecordedStep> raw) {
        return new RecordedStep(id, author, action, false, null, null, null, raw, false);
    }

    /** Whether the step is a fold of raw steps. */
    boolean folded() {
        return raw != null;
    }

    /**
     * The same step (id, author, group, row) with another action that does the same: its sentence changed, its edit
     * mark. How it was seen and what it folds stay.
     */
    RecordedStep with(Action action, boolean edited) {
        return new RecordedStep(id, author, action, edited, group, row, seen, raw, keepRaw);
    }

    /**
     * The same step (id, author, group, row) with an action that may do something else: an edit of its target, text or
     * whole step. It is no longer what was seen, nor a fold of the raw steps it was made of.
     */
    RecordedStep changed(Action action) {
        return new RecordedStep(id, author, action, true, group, row, null, null, keepRaw);
    }

    /** The same step in {@code group} (null = none). */
    RecordedStep inGroup(Group group) {
        return new RecordedStep(id, author, action, edited, group, row, seen, raw, keepRaw);
    }

    /** The same step with its row alternatives. */
    RecordedStep withRow(RowChoice row) {
        return new RecordedStep(id, author, action, edited, group, row, seen, raw, keepRaw);
    }

    /** The same step kept raw (or not). */
    RecordedStep keptRaw(boolean keep) {
        return new RecordedStep(id, author, action, edited, group, row, seen, raw, keep);
    }
}
