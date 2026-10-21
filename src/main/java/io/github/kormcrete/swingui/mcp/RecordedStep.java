package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.model.RowChoice;
import io.github.kormcrete.swingui.flow.Action;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One step of the session's recording: the {@link Action} plus the id it keeps for the whole recording and who made it.
 * The id is never reused; a position is only its place in the list right now. What a vocabulary's folding needs lives
 * here too and never reaches a file: how the step's elements were seen, the raw steps a fold stands for, and whether the
 * step is to be kept raw. So does what the step changed in the app when it last ran in this session.
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
    /**
     * What the step changed in the app when it last ran in this session (recorded or replayed), capped; null when not
     * known, or once it shrank to {@link #newValues} (only the latest steps keep it whole).
     */
    final ObjectNode outcome;
    /** The values that step made that the app showed nowhere before it, each with its path in {@link #outcome}: the value index. */
    final Map<String, String> newValues;
    /** Each reference of the step ({@code ${copy.rows.Orders.added[0].Number}}) with the value it stood for when it last ran. */
    final Map<String, String> linkSeen;

    RecordedStep(int id, Author author, Action action, boolean edited) {
        this(id, author, action, edited, null, null);
    }

    RecordedStep(int id, Author author, Action action, boolean edited, Group group, RowChoice row) {
        this(id, author, action, edited, group, row, null, null, false);
    }

    RecordedStep(int id, Author author, Action action, boolean edited, Group group, RowChoice row, JsonNode seen,
                 List<RecordedStep> raw, boolean keepRaw) {
        this(id, author, action, edited, group, row, seen, raw, keepRaw, null, null, null);
    }

    private RecordedStep(int id, Author author, Action action, boolean edited, Group group, RowChoice row, JsonNode seen,
                         List<RecordedStep> raw, boolean keepRaw, ObjectNode outcome, Map<String, String> newValues,
                         Map<String, String> linkSeen) {
        this.outcome = outcome;
        this.newValues = newValues == null ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(newValues));
        this.linkSeen = linkSeen == null ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(linkSeen));
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
        return new RecordedStep(id, author, action, edited, group, row, seen, raw, keepRaw, outcome, newValues, linkSeen);
    }

    /**
     * The same step (id, author, group, row) with an action that may do something else: an edit of its target, text or
     * whole step. It is no longer what was seen, nor a fold of the raw steps it was made of, and what it changed when
     * it last ran is not what it changes now (a replay learns that again). The values its references stood for stay:
     * they are what the earlier steps made.
     */
    RecordedStep changed(Action action) {
        return new RecordedStep(id, author, action, true, group, row, null, null, keepRaw, null, null, linkSeen);
    }

    /** The same step in {@code group} (null = none). */
    RecordedStep inGroup(Group group) {
        return new RecordedStep(id, author, action, edited, group, row, seen, raw, keepRaw, outcome, newValues, linkSeen);
    }

    /** The same step with its row alternatives. */
    RecordedStep withRow(RowChoice row) {
        return new RecordedStep(id, author, action, edited, group, row, seen, raw, keepRaw, outcome, newValues, linkSeen);
    }

    /** The same step kept raw (or not). */
    RecordedStep keptRaw(boolean keep) {
        return new RecordedStep(id, author, action, edited, group, row, seen, raw, keep, outcome, newValues, linkSeen);
    }

    /** The same step with what it changed when it last ran: {@code outcome} and its value index. */
    RecordedStep withOutcome(ObjectNode outcome, Map<String, String> newValues) {
        return new RecordedStep(id, author, action, edited, group, row, seen, raw, keepRaw, outcome, newValues, linkSeen);
    }

    /**
     * The same step with only its value index of what it changed (no whole outcome). A value whose position among
     * several added or changed rows or items is all that names it leaves the index: without the outcome no one can tell.
     */
    RecordedStep shrunk() {
        if (outcome == null) return this;
        List<RecordedStep> smaller = null;
        if (raw != null) {
            smaller = new ArrayList<RecordedStep>(raw.size());
            for (RecordedStep r : raw) smaller.add(r.shrunk());
        }
        return new RecordedStep(id, author, action, edited, group, row, seen, smaller, keepRaw, null,
                Linker.singles(outcome, newValues), linkSeen);
    }

    /** The same fold with {@code raw} as its raw steps (what one of them changed came late). */
    RecordedStep withRaw(List<RecordedStep> raw) {
        return new RecordedStep(id, author, action, edited, group, row, seen, raw, keepRaw, outcome, newValues, linkSeen);
    }

    /** The same step with the values its references stood for when it last ran. */
    RecordedStep withLinkSeen(Map<String, String> seenValues) {
        return new RecordedStep(id, author, action, edited, group, row, seen, raw, keepRaw, outcome, newValues, seenValues);
    }
}
