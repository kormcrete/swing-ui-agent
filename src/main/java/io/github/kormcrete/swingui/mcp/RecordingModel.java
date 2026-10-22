package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.RowChoice;
import io.github.kormcrete.swingui.flow.Action;
import io.github.kormcrete.swingui.flow.Flow;
import io.github.kormcrete.swingui.flow.FlowGroup;
import io.github.kormcrete.swingui.flow.FlowJson;
import io.github.kormcrete.swingui.flow.RecordedTestFiles;
import io.github.kormcrete.swingui.flow.StepNumbers;
import io.github.kormcrete.swingui.flow.refs.JsonPaths;
import io.github.kormcrete.swingui.flow.refs.Refs;
import io.github.kormcrete.swingui.flow.refs.RunValues;
import io.github.kormcrete.swingui.flow.verb.RememberVerb;
import io.github.kormcrete.swingui.flow.verb.TypeVerb;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.time.LocalDateTime;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;

/**
 * The records made so far, each with an id that is never reused and an author. In memory the list is flat, one record
 * per action or check; the public edits work on STEPS (see {@link StepNumbers}): an action with the checks under it, so
 * a step is deleted, moved or cut with its checks and no check is left without its step. Groups are runs of whole
 * steps; every record of a member step shares the group. Not thread-safe: every call happens under the session's
 * monitor; {@link #rev()} moves on every change so a reader can tell its copy is stale.
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
    private int nextGroupId = 1;
    private int rev;
    private State state = State.IDLE;
    /** Started by {@code recording start}, the person's Record or a redo, not implicitly by a session reset. */
    private boolean explicit;
    private RecordedStep.Author startedBy = RecordedStep.Author.AGENT;
    private long startedAt;
    /** The palette shows it and the person's steps join it. */
    private boolean shared;
    /** Records with this id or a lower one were there when a replay last started: none folds with a later one. */
    private int replayFloor;
    /** What the steps changed and remembered, by step id (or {@link #key}), and this run's {@code run} values. */
    private RunValues runValues = freshValues();
    /** A {@link #copy}: edits are tried on it, and it changes no values it shares with the recording. */
    private boolean trial;
    /** By record id: the paths links made to that step use (see {@link #link}). */
    private final Map<Integer, Set<String>> linkedPaths = new HashMap<Integer, Set<String>>();
    /** A step's sentence after a link or an unlink ({@link #sentences}); by default the one it has. */
    private BiFunction<Action, Action, Action> sentences = (before, after) -> after;

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
        linkedPaths.clear();
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

    /**
     * Ends the recording: no further step joins it. What its steps changed shrinks to their value index, which links
     * made afterwards still use.
     */
    void stop() {
        shrinkOlderThan(0);
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

    /** Longest history of edits kept. */
    static final int HISTORY = 50;

    /**
     * One edit that undo can take back: the steps from before it, and the highest id right after; for a fold, the ids of
     * the records it took, kept raw once undo brings them back (folding would only make the fold again).
     */
    private static final class Edit {
        final String label;
        final String past;
        final RecordedStep.Author author;
        final List<RecordedStep> before;
        final int maxIdAfter;
        final boolean cut;
        final Set<Integer> keepRawOnUndo;
        /** For a fold, the id of the record it made; 0 for any other edit. */
        final int folded;

        Edit(String label, String past, RecordedStep.Author author, List<RecordedStep> before, int maxIdAfter,
                boolean cut, Set<Integer> keepRawOnUndo, int folded) {
            this.label = label;
            this.past = past;
            this.author = author;
            this.before = before;
            this.maxIdAfter = maxIdAfter;
            this.cut = cut;
            this.keepRawOnUndo = keepRawOnUndo;
            this.folded = folded;
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
            boolean cut, Set<Integer> keepRawOnUndo) {
        keep(new Edit(label + " by " + by(author), past, author, before, nextId - 1, cut, keepRawOnUndo, 0));
        changed(by(author) + " " + past);
        return past;
    }

    private void keep(Edit e) {
        history.addFirst(e);
        while (history.size() > HISTORY) history.removeLast();
    }

    /** Where the recording stood at {@link #mark()}: its records, and the edits in the history then. */
    static final class Mark {
        final List<RecordedStep> before;
        final Set<Edit> edits;

        private Mark(List<RecordedStep> before, Set<Edit> edits) {
            this.before = before;
            this.edits = edits;
        }
    }

    /** The recording as it stands, for {@link #asOneEdit} to make what follows one edit. */
    Mark mark() {
        Set<Edit> edits = Collections.newSetFromMap(new IdentityHashMap<Edit, Boolean>());
        edits.addAll(history);
        return new Mark(copyOfRecords(), edits);
    }

    /**
     * Makes everything since {@code m} (records appended, the edits made on them) one edit that undo takes back at once:
     * the edits since {@code m} leave the history and one edit, {@code label} by {@code author}, takes their place.
     * Returns {@code past}.
     */
    String asOneEdit(Mark m, String label, String past, RecordedStep.Author author) {
        // only edits made since the mark: one undone since must not let it reach the edits from before
        while (!history.isEmpty() && !m.edits.contains(history.peekFirst())) history.removeFirst();
        return edited(label, past, author, m.before, false, Collections.<Integer>emptySet());
    }

    // ------------------------------------------------------------------ recording and reading

    /** Adds an action as a new step at the end, or a check under the last step; never joins a group by placement. */
    RecordedStep append(Action action, RecordedStep.Author author) {
        return append(action, author, null);
    }

    /** As {@link #append(Action, RecordedStep.Author)}, with the alternatives of the table row the action names (null: none known). */
    RecordedStep append(Action action, RecordedStep.Author author, RowChoice row) {
        return append(action, author, row, null);
    }

    /** As {@link #append(Action, RecordedStep.Author, RowChoice)}, with how its elements were seen (see {@link RecordedStep#seen}). */
    RecordedStep append(Action action, RecordedStep.Author author, RowChoice row, JsonNode seen) {
        return append(action, author, row, seen, false);
    }

    /** As {@link #append(Action, RecordedStep.Author, RowChoice, JsonNode)}; {@code keepRaw}: folding never takes it. */
    RecordedStep append(Action action, RecordedStep.Author author, RowChoice row, JsonNode seen, boolean keepRaw) {
        boolean isCheck = StepNumbers.isCheck(action);
        RecordedStep.Group group = isCheck && !steps.isEmpty() ? steps.get(steps.size() - 1).group : null;
        RecordedStep step = new RecordedStep(nextId++, author, action, false, group, row, seen, null, keepRaw);
        steps.add(step);
        changed("the " + author.json + " recorded " + (isCheck ? "check " : "step ") + addressOf(step.id));
        return step;
    }

    /** Empties the recording and ends it (idle). Ids keep counting: a step gone is never confused with a later one. */
    void clear() {
        steps.clear();
        notRecorded.clear();
        history.clear();
        runValues = freshValues();
        linkedPaths.clear();
        state = State.IDLE;
        explicit = false;
        shared = false;
        changed("the recording was cleared");
    }

    /** Replaces the recording with the steps and groups of a {@link Flow}, all by {@code author}. */
    void load(Flow flow, RecordedStep.Author author) {
        List<RecordedStep> loaded = new ArrayList<RecordedStep>(flow.steps.size());
        for (Action a : flow.steps) loaded.add(new RecordedStep(nextId++, author, a, false));
        for (FlowGroup g : flow.groups) {
            if (g.from < 0 || g.to < g.from || g.to >= loaded.size()) {
                throw bad("the group '" + g.say + "' lies outside the " + loaded.size() + " records");
            }
            RecordedStep.Group group = new RecordedStep.Group(nextGroupId++, g.say);
            for (int i = g.from; i <= g.to; i++) loaded.set(i, loaded.get(i).inGroup(group));
        }
        steps.clear();
        history.clear();
        steps.addAll(loaded);
        runValues = freshValues();
        linkedPaths.clear();
        changed("a recorded test was loaded");
    }

    /** Replaces the recording with {@code actions} (no groups), all by {@code author}. */
    void load(List<Action> actions, RecordedStep.Author author) {
        Flow flow = new Flow();
        flow.steps = new ArrayList<Action>(actions);
        load(flow, author);
    }

    /** The flat actions and checks, one per record. */
    List<Action> actions() {
        List<Action> out = new ArrayList<Action>(steps.size());
        for (RecordedStep s : steps) out.add(s.action);
        return out;
    }

    /** The records, flat, with ids, authors and groups. */
    List<RecordedStep> records() {
        return Collections.unmodifiableList(new ArrayList<RecordedStep>(steps));
    }

    /** The recording as a flat list of actions plus its groups as flat ranges. */
    Flow flow() {
        return viewOf(steps);
    }

    /**
     * A new model holding the same records (ids, groups, the values their links stood for) and reading the same values
     * (no history): an edit is tried on it before it is made for real. The edits tried there only read the values.
     */
    RecordingModel copy() {
        RecordingModel copy = new RecordingModel();
        copy.trial = true;
        copy.steps.addAll(steps);
        copy.nextId = nextId;
        copy.nextGroupId = nextGroupId;
        copy.runValues = runValues;
        copy.sentences = sentences;
        for (Map.Entry<Integer, Set<String>> e : linkedPaths.entrySet()) {
            copy.linkedPaths.put(e.getKey(), new HashSet<String>(e.getValue()));
        }
        return copy;
    }

    /** The flat indexes {first, last} of the records of steps {@code from} to {@code to}; BAD_INPUT when one is no step. */
    int[] flatRange(int from, int to) {
        List<StepNumbers.Step> st = StepNumbers.of(actions());
        if (from < 1 || from > st.size()) throw outOfRange(from);
        if (to < 1 || to > st.size()) throw outOfRange(to);
        if (to < from) throw bad("'to' (" + to + ") is before 'from' (" + from + ")");
        return new int[] {st.get(from - 1).first(), st.get(to - 1).last()};
    }

    /** The actions of {@code records} with their groups as flat ranges. */
    static Flow viewOf(List<RecordedStep> records) {
        Flow flow = new Flow();
        Map<Integer, int[]> ranges = new LinkedHashMap<Integer, int[]>();
        Map<Integer, String> says = new LinkedHashMap<Integer, String>();
        for (int i = 0; i < records.size(); i++) {
            RecordedStep r = records.get(i);
            flow.steps.add(r.action);
            if (r.group == null) continue;
            int[] range = ranges.get(r.group.id);
            if (range == null) {
                ranges.put(r.group.id, new int[] {i, i});
                says.put(r.group.id, r.group.say);
            } else {
                range[1] = i;
            }
        }
        for (Map.Entry<Integer, int[]> e : ranges.entrySet()) {
            flow.groups.add(new FlowGroup(e.getValue()[0], e.getValue()[1], says.get(e.getKey())));
        }
        return flow;
    }

    /** The number of records (actions and checks); a replay loop walks them. */
    int size() {
        return steps.size();
    }

    /** The record at flat position {@code n} (1-based), for the replay loop and the session's flat adapters. */
    RecordedStep at(int n) {
        if (n < 1 || n > steps.size()) {
            throw bad("no step " + n + ": the recording " + (steps.isEmpty() ? "is empty"
                    : "has " + steps.size() + (steps.size() == 1 ? " step" : " steps") + " (1-" + steps.size() + ")"));
        }
        return steps.get(n - 1);
    }

    /** The number of steps (an action with its checks; a leading run of checks is step 1). */
    int steps() {
        return StepNumbers.of(actions()).size();
    }

    int rev() {
        return rev;
    }

    /** The record at {@code address}: a step's action, or one of its checks. */
    RecordedStep at(StepAddress address) {
        if (address.step < 1 || address.step > steps()) throw outOfRange(address.step);
        return steps.get(StepNumbers.flatIndex(actions(), address.step, address.check));
    }

    /** Where the record with {@code id} sits now, or null when it is gone. */
    StepAddress addressOf(int id) {
        for (StepNumbers.Step s : StepNumbers.of(actions())) {
            if (s.action >= 0 && steps.get(s.action).id == id) return new StepAddress(s.number, 0);
            for (int k = 0; k < s.checks.size(); k++) {
                if (steps.get(s.checks.get(k)).id == id) return new StepAddress(s.number, k + 1);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    /** "step 2" or "check 2.1": the record at flat index {@code i}, as the numbering names it. */
    private String describe(int i) {
        return (StepNumbers.isCheck(steps.get(i).action) ? "check " : "step ") + StepNumbers.label(actions(), i);
    }

    private static DriverException bad(String message) {
        return new DriverException(Reason.BAD_INPUT, message);
    }

    private static String stepsText(int from, int to) {
        return from == to ? "step " + from : "steps " + from + "-" + to;
    }

    /** The group of a step: that of its first record. */
    private RecordedStep.Group groupOf(StepNumbers.Step s) {
        return steps.get(s.first()).group;
    }

    private static boolean same(RecordedStep.Group a, RecordedStep.Group b) {
        return a != null && b != null && a.id == b.id;
    }

    /** Puts every record of the flat range {@code from..to} (inclusive) in {@code group} (null = none). */
    private void setGroup(int from, int to, RecordedStep.Group group) {
        for (int i = from; i <= to; i++) steps.set(i, steps.get(i).inGroup(group));
    }

    /** The first and last step number of group {@code g} (0, 0 when it has none). */
    private int[] extent(RecordedStep.Group g) {
        int a = 0;
        int b = 0;
        for (StepNumbers.Step s : StepNumbers.of(actions())) {
            if (same(groupOf(s), g)) {
                if (a == 0) a = s.number;
                b = s.number;
            }
        }
        return new int[] {a, b};
    }

    /**
     * Rules (f) and (g) after an edit: a group left with one step ends (its sentence moves to that step's action when
     * it has none, and is dropped for a check-only step or a step with its own); a group with no step is simply gone.
     * Returns the text added to the edit's {@code did}, empty when nothing ended.
     */
    private String repairGroups() {
        Map<Integer, List<StepNumbers.Step>> byGroup = new LinkedHashMap<Integer, List<StepNumbers.Step>>();
        Map<Integer, RecordedStep.Group> groups = new LinkedHashMap<Integer, RecordedStep.Group>();
        for (StepNumbers.Step s : StepNumbers.of(actions())) {
            RecordedStep.Group g = groupOf(s);
            if (g == null) continue;
            if (!byGroup.containsKey(g.id)) {
                byGroup.put(g.id, new ArrayList<StepNumbers.Step>());
                groups.put(g.id, g);
            }
            byGroup.get(g.id).add(s);
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<Integer, List<StepNumbers.Step>> e : byGroup.entrySet()) {
            if (e.getValue().size() != 1) continue;
            StepNumbers.Step s = e.getValue().get(0);
            RecordedStep.Group g = groups.get(e.getKey());
            setGroup(s.first(), s.last(), null);
            String what;
            if (s.checkOnly()) {
                what = "its sentence was dropped";
            } else if (steps.get(s.action).action.say == null) {
                RecordedStep r = steps.get(s.action);
                steps.set(s.action, r.with(withSay(r.action, g.say), r.edited));
                what = "its sentence is now step " + s.number + "'s";
            } else {
                what = "step " + s.number + " keeps its own sentence and the group's was dropped";
            }
            out.append("; the group \"").append(g.say).append("\" ended with one step left: ").append(what);
        }
        return out.toString();
    }

    /** A copy of {@code action} with another sentence; an action that history shares is never changed. */
    private static Action withSay(Action action, String say) {
        Action copy = FlowJson.mapper().convertValue(action, Action.class);
        copy.say = say;
        return copy;
    }

    private List<RecordedStep> copyOfRecords() {
        return new ArrayList<RecordedStep>(steps);
    }

    /** The end of every edit: the groups are repaired, the edit is kept for undo, and its past-tense text returned. */
    private String finish(String label, String past, RecordedStep.Author author, List<RecordedStep> before, boolean cut) {
        return edited(label, past + repairGroups(), author, before, cut, Collections.<Integer>emptySet());
    }

    // ------------------------------------------------------------------ edits (steps and checks)

    /**
     * Puts {@code action} at {@code at}. An action goes at a step position (1 to steps+1), before that step's action;
     * a check goes at {@code n.k} under step n (k up to its checks + 1), or at plain position 1 to make the check-only
     * step 1. It counts as edited.
     */
    String insert(StepAddress at, Action action, RecordedStep.Author author) {
        List<StepNumbers.Step> st = StepNumbers.of(actions());
        List<RecordedStep> before = copyOfRecords();
        RecordedStep.Group group = null;
        int flatAt;
        String label;
        String past;
        if (StepNumbers.isCheck(action)) {
            if (at.check == 0) {
                if (at.step != 1 || (!st.isEmpty() && st.get(0).checkOnly())) {
                    if (at.step < 1 || at.step > st.size()) throw outOfRange(at.step);
                    throw bad("a check goes under a step: at \"" + at.step + "." + (st.get(at.step - 1).checks.size() + 1)
                            + "\"");
                }
                flatAt = 0;
                label = "insert of check 1.1";
                past = "inserted check 1.1";
            } else {
                if (at.step < 1 || at.step > st.size()) throw outOfRange(at.step);
                StepNumbers.Step s = st.get(at.step - 1);
                if (at.check > s.checks.size() + 1) {
                    throw bad("no place for check " + at + ": step " + at.step + " has " + s.checks.size()
                            + (s.checks.size() == 1 ? " check" : " checks") + " (the next is \"" + at.step + "."
                            + (s.checks.size() + 1) + "\")");
                }
                flatAt = at.check <= s.checks.size() ? s.checks.get(at.check - 1) : s.last() + 1;
                group = groupOf(s);
                label = "insert of check " + at;
                past = "inserted check " + at;
            }
        } else {
            if (at.check != 0) {
                throw bad("an action goes at a step position (" + at.step + "), not at a check label (" + at + ")");
            }
            if (at.step < 1 || at.step > st.size() + 1) {
                throw bad("cannot insert at step " + at.step + ": the recording "
                        + (st.isEmpty() ? "is empty (insert at 1)" : "has " + st.size() + " steps (insert at 1-"
                        + (st.size() + 1) + ")"));
            }
            if (at.step == 1 && !st.isEmpty() && st.get(0).checkOnly()) {
                throw bad("step 1 holds only checks, which run before any action: insert at 2, or delete them first");
            }
            flatAt = at.step <= st.size() ? st.get(at.step - 1).first() : steps.size();
            RecordedStep.Group left = at.step >= 2 ? groupOf(st.get(at.step - 2)) : null;
            RecordedStep.Group right = at.step <= st.size() ? groupOf(st.get(at.step - 1)) : null;
            if (same(left, right)) group = left;
            label = "insert at step " + at.step;
            past = "inserted at step " + at.step;
        }
        steps.add(flatAt, new RecordedStep(nextId++, author, action, true, group, null));
        return finish(label, past, author, before, false);
    }

    /** Deletes what {@code at} names: a check alone, or a step with its checks. */
    String delete(StepAddress at, RecordedStep.Author author) {
        return at.isCheck() ? deleteCheck(at.step, at.check, author) : deleteSteps(at.step, at.step, author);
    }

    /** Removes steps {@code from} to {@code to} (1-based, inclusive) with their checks. */
    String deleteSteps(int from, int to, RecordedStep.Author author) {
        List<StepNumbers.Step> st = StepNumbers.of(actions());
        if (from < 1 || from > st.size()) throw outOfRange(from);
        if (to < from || to > st.size()) throw outOfRange(to);
        List<RecordedStep> before = copyOfRecords();
        int checks = 0;
        for (int n = from; n <= to; n++) checks += st.get(n - 1).checks.size();
        String kept = keepValues(st.get(from - 1).first(), st.get(to - 1).last());
        for (int i = st.get(to - 1).last(); i >= st.get(from - 1).first(); i--) steps.remove(i);
        String what = stepsText(from, to);
        String past = "deleted " + what;
        if (checks > 0) {
            past += " and " + (from == to ? "its " : "their ") + checks + (checks == 1 ? " check" : " checks");
        }
        return finish("delete of " + what, past + kept, author, before, false);
    }

    /** Removes check {@code check} of step {@code step}. */
    String deleteCheck(int step, int check, RecordedStep.Author author) {
        int flat = flatOf(step, check, true);
        List<RecordedStep> before = copyOfRecords();
        String kept = keepValues(flat, flat);
        steps.remove(flat);
        return finish("delete of check " + step + "." + check, "deleted check " + step + "." + check + kept, author, before,
                false);
    }

    /** Moves step {@code n} with its checks so that it is step {@code to} afterwards. */
    String moveStep(int n, int to, RecordedStep.Author author) {
        List<StepNumbers.Step> st = StepNumbers.of(actions());
        if (n < 1 || n > st.size()) throw outOfRange(n);
        if (to < 1 || to > st.size()) throw outOfRange(to);
        if (st.get(0).checkOnly()) {
            if (n == 1) throw bad("step 1 holds only checks (a check-only step): it cannot be moved, delete it instead");
            if (to == 1) {
                throw bad("step 1 holds only checks, which run before any action: move to 2 or later, or delete them first");
            }
        }
        List<RecordedStep> before = copyOfRecords();
        List<List<RecordedStep>> blocks = new ArrayList<List<RecordedStep>>();
        for (StepNumbers.Step s : st) blocks.add(new ArrayList<RecordedStep>(steps.subList(s.first(), s.last() + 1)));
        List<RecordedStep> moving = blocks.remove(n - 1);
        RecordedStep.Group own = moving.get(0).group;
        RecordedStep.Group left = to >= 2 ? blocks.get(to - 2).get(0).group : null;
        RecordedStep.Group right = to - 1 < blocks.size() ? blocks.get(to - 1).get(0).group : null;
        RecordedStep.Group group = null;
        if (own != null && (same(own, left) || same(own, right))) group = own;
        else if (same(left, right)) group = left;
        for (int i = 0; i < moving.size(); i++) moving.set(i, moving.get(i).inGroup(group));
        blocks.add(to - 1, moving);
        steps.clear();
        for (List<RecordedStep> b : blocks) steps.addAll(b);
        return finish("move of step " + n + " to " + to, "moved step " + n + " to " + to, author, before, false);
    }

    /** Replaces the action at {@code at} (a step's or a check's, the same kind); the record keeps its id and counts as edited. */
    String replace(StepAddress at, Action action, RecordedStep.Author author) {
        return change(at, action, "edit of ", "edited ", author, "", false);
    }

    /**
     * As {@link #replace} for a table step whose row is named another way ({@code byPosition}: its index, else its value).
     * The row alternatives stay: both ways still name the same row.
     */
    String row(StepAddress at, Action rewritten, RecordedStep.Author author, boolean byPosition) {
        return change(at, rewritten, "row of ", "changed the row of ", author, byPosition ? " by position" : " by value", true);
    }

    private String change(StepAddress at, Action action, String label, String past, RecordedStep.Author author,
            String labelSuffix, boolean keepRow) {
        RecordedStep old = at(at);
        boolean check = StepNumbers.isCheck(old.action);
        if (check != StepNumbers.isCheck(action)) {
            throw bad((check ? "check " : "step ") + at + " is " + (check ? "a check" : "an action") + ": it is replaced by "
                    + (check ? "a check" : "an action") + " (delete it and insert the other kind)");
        }
        List<RecordedStep> before = copyOfRecords();
        // an edit that is not about the row may have moved it: what was known about the row is learned again by a replay;
        // and it may be about another element: no longer what was seen, nor the fold of its raw steps
        steps.set(flatOf(at.step, at.check, false), keepRow ? old.with(action, true) : old.changed(action).withRow(null));
        String what = (check ? "check " : "step ") + at;
        return finish(label + what + labelSuffix, past + what, author, before, false);
    }

    /**
     * Sets, changes or (with {@code ""} or null) removes the sentence of the step or check at {@code at}. Not a behaviour
     * change. Removing a sentence that is not there changes nothing (no revision, no undo entry): null.
     */
    String say(StepAddress at, String say, RecordedStep.Author author) {
        RecordedStep old = at(at);
        String text = say == null ? "" : say.trim();
        if (text.isEmpty() && old.action.say == null) return null;
        if (!text.isEmpty()) RecordedTestFiles.checkSay(text, (at.isCheck() ? "check " : "step ") + at);
        List<RecordedStep> before = copyOfRecords();
        steps.set(flatOf(at.step, at.check, false), old.with(withSay(old.action, text.isEmpty() ? null : text), old.edited));
        String what = (at.isCheck() ? "check " : "step ") + at;
        return finish("sentence of " + what, (text.isEmpty() ? "removed" : "set") + " the sentence of " + what, author,
                before, false);
    }

    /** Drops step {@code n} and everything after it (a redo's cut); null when there is nothing to drop. */
    String cutFromStep(int n, RecordedStep.Author author) {
        List<StepNumbers.Step> st = StepNumbers.of(actions());
        if (n < 1 || n > st.size()) return null;
        List<RecordedStep> before = copyOfRecords();
        while (steps.size() > st.get(n - 1).first()) steps.remove(steps.size() - 1);
        return finish("cut from step " + n + " (redo)", "cut from step " + n, author, before, true);
    }

    /** Makes steps {@code from} to {@code to} one group with sentence {@code say}; the same range again renames it. */
    String group(int from, int to, String say, RecordedStep.Author author) {
        List<StepNumbers.Step> st = StepNumbers.of(actions());
        if (from < 1 || from > st.size()) throw outOfRange(from);
        if (to < 1 || to > st.size()) throw outOfRange(to);
        if (to < from) throw bad("group: 'to' (" + to + ") is before 'from' (" + from + ")");
        if (to == from) throw bad("a group takes at least two steps");
        String text = say == null ? "" : say.trim();
        if (text.isEmpty()) throw bad("a group needs a sentence (to end a group, ungroup it)");
        RecordedTestFiles.checkSay(text, "the group's sentence");
        Map<Integer, Integer> inRange = new LinkedHashMap<Integer, Integer>();
        Map<Integer, RecordedStep.Group> found = new LinkedHashMap<Integer, RecordedStep.Group>();
        int ungrouped = 0;
        for (int n = from; n <= to; n++) {
            RecordedStep.Group g = groupOf(st.get(n - 1));
            if (g == null) {
                ungrouped++;
                continue;
            }
            Integer c = inRange.get(g.id);
            inRange.put(g.id, c == null ? 1 : c + 1);
            found.put(g.id, g);
        }
        boolean rename = false;
        RecordedStep.Group renamed = null;
        for (Map.Entry<Integer, RecordedStep.Group> e : found.entrySet()) {
            RecordedStep.Group g = e.getValue();
            int[] ext = extent(g);
            int total = ext[1] - ext[0] + 1;
            int count = inRange.get(g.id);
            String where = "the group of steps " + ext[0] + "-" + ext[1];
            if (count == total && ungrouped == 0 && found.size() == 1) {
                rename = true;
                renamed = g;
            } else if (found.size() == 1 && ungrouped == 0 && count == to - from + 1) {
                throw bad("groups do not nest: steps " + from + "-" + to + " are inside " + where);
            } else if (count == total) {
                throw bad("groups do not nest: steps " + from + "-" + to + " contain " + where);
            } else {
                throw bad("steps " + from + "-" + to + " overlap " + where + ": a group takes whole steps and groups do not overlap");
            }
        }
        List<RecordedStep> before = copyOfRecords();
        RecordedStep.Group group = new RecordedStep.Group(rename ? renamed.id : nextGroupId++, text);
        setGroup(st.get(from - 1).first(), st.get(to - 1).last(), group);
        String what = stepsText(from, to);
        return finish("group of " + what, (rename ? "renamed the group of " : "grouped ") + what, author, before, false);
    }

    /** Ends the group that holds step {@code n}; its steps stay. */
    String ungroup(int n, RecordedStep.Author author) {
        List<StepNumbers.Step> st = StepNumbers.of(actions());
        if (n < 1 || n > st.size()) throw outOfRange(n);
        RecordedStep.Group g = groupOf(st.get(n - 1));
        if (g == null) throw bad("step " + n + " is in no group");
        int[] ext = extent(g);
        List<RecordedStep> before = copyOfRecords();
        setGroup(st.get(ext[0] - 1).first(), st.get(ext[1] - 1).last(), null);
        String what = stepsText(ext[0], ext[1]);
        return finish("ungroup of " + what, "ungrouped " + what, author, before, false);
    }

    /** A replay passed the step with {@code id}: it no longer counts as edited. Whether that changed anything. */
    boolean markReplayed(int id) {
        for (int i = 0; i < steps.size(); i++) {
            RecordedStep s = steps.get(i);
            if (s.id == id && s.edited) {
                steps.set(i, s.with(s.action, false));
                changed(describe(i) + " passed a replay");
                return true;
            }
        }
        return false;
    }

    /**
     * The alternatives of the table row the record with {@code id} names are known; no change of revision (and false)
     * when they were, or when there are none.
     */
    boolean rowKnown(int id, RowChoice choice) {
        if (choice == null) return false;
        for (int i = 0; i < steps.size(); i++) {
            RecordedStep s = steps.get(i);
            if (s.id != id) continue;
            if (choice.equals(s.row)) return false;
            steps.set(i, s.withRow(choice));
            changed("the row alternatives of " + describe(i) + " are known");
            return true;
        }
        return false;
    }

    /** The flat index of step {@code step}'s action ({@code check} 0) or check; {@code mustBeCheck} refuses a plain step. */
    private int flatOf(int step, int check, boolean mustBeCheck) {
        if (step < 1 || step > steps()) throw outOfRange(step);
        if (mustBeCheck && check < 1) throw bad("a check label is needed: \"" + step + ".1\"");
        return StepNumbers.flatIndex(actions(), step, check);
    }

    // ------------------------------------------------------------------ folds (a vocabulary's do steps)

    /** A replay starts: no record there now folds with one recorded after it. */
    void replayStarted() {
        replayFloor = nextId - 1;
    }

    /** Records with this id or a lower one were there when a replay last started (see {@link #replayStarted}). */
    int replayFloor() {
        return replayFloor;
    }

    /** What {@link #fold} made: the new record, and the edit's past-tense text. */
    static final class Folded {
        final RecordedStep step;
        final String did;

        Folded(RecordedStep step, String did) {
            this.step = step;
            this.did = did;
        }
    }

    /**
     * Puts {@code action}, a {@code do} step by {@code by}, in the place of the records {@code ids}: action records (raw
     * steps or earlier folds) next to each other, in order, in one group or none. It keeps their raw steps
     * ({@link RecordedStep#raw}, an earlier fold's own); checks after the last stay under it. {@code editor} asked for the
     * fold; null when folding recognised {@code by}'s steps by itself, which the edit's label says ("… (recognised in the
     * person's steps)") so that whoever undoes it is never named as the one who folded. Undo brings the records back kept
     * raw.
     */
    Folded fold(List<Integer> ids, Action action, RecordedStep.Author by, RecordedStep.Author editor) {
        if (ids.isEmpty()) throw bad("a fold takes at least one step");
        int first = indexOf(ids.get(0));
        if (first < 0) throw bad("that step is gone: the recording changed since (nothing folded)");
        List<RecordedStep> raw = new ArrayList<RecordedStep>();
        RecordedStep.Group group = steps.get(first).group;
        for (int k = 0; k < ids.size(); k++) {
            int i = first + k;
            if (i >= steps.size() || steps.get(i).id != ids.get(k)) {
                throw bad("only steps next to each other fold, with no check between them (nothing folded)");
            }
            RecordedStep r = steps.get(i);
            if (StepNumbers.isCheck(r.action)) throw bad("a check does not fold (nothing folded)");
            if (r.group == null ? group != null : !same(r.group, group)) {
                throw bad("the steps are not in one group (nothing folded)");
            }
            // each raw step keeps what it changed while the fold does: a value of it that comes late still finds it
            if (r.folded()) raw.addAll(r.raw);
            else raw.add(r.keptRaw(false));
        }
        int a = addressOf(ids.get(0)).step;
        int b = addressOf(ids.get(ids.size() - 1)).step;
        List<RecordedStep> before = copyOfRecords();
        RecordedStep made = merged(RecordedStep.fold(nextId++, by, action, raw).inGroup(group));
        for (int k = 0; k < ids.size(); k++) steps.remove(first);
        steps.add(first, made);
        // a copy only tries the edit: the values it shares are the recording's
        if (made.outcome != null && !trial) {
            runValues.put(key(made), made.outcome);
            shrinkOlderThan(FULL_OUTCOMES);
        }
        String what = stepsText(a, b);
        String into = action.say == null ? action.describe() : action.say;
        String fold = "fold of " + what + " into \"" + into + "\"";
        String past = "folded " + what + " into \"" + into + "\"";
        Set<Integer> took = new HashSet<Integer>(ids);
        if (editor == null) {
            String whose = by.json.equals("loaded") ? "the loaded test's" : "the " + by.json + "'s";
            keep(new Edit(fold + " (recognised in " + whose + " steps)", past, by, before, nextId - 1, false, took, made.id));
            changed("folding recognised " + whose + " " + what + " as \"" + into + "\"");
            return new Folded(made, past);
        }
        keep(new Edit(fold + " by " + by(editor), past, editor, before, nextId - 1, false, took, made.id));
        changed(by(editor) + " " + past);
        return new Folded(made, past);
    }

    /**
     * Takes back the fold that made the record {@code id}, as {@link #undo} does, while that fold is the last edit; null
     * (nothing changes) when it is not, or no fold made it.
     */
    String undoFold(int id, RecordedStep.Author by) {
        return lastEditFolded(id) ? undo(by) : null;
    }

    /** Whether the last edit is the fold that made the record {@code id}, which is still there. */
    boolean lastEditFolded(int id) {
        Edit e = history.peekFirst();
        return e != null && e.folded == id && indexOf(id) >= 0;
    }

    /** Puts the raw steps of fold {@code n} back in its place, each kept raw; its checks stay under the last one. */
    String unfold(int n, RecordedStep.Author author) {
        List<StepNumbers.Step> st = StepNumbers.of(actions());
        if (n < 1 || n > st.size()) throw outOfRange(n);
        StepNumbers.Step s = st.get(n - 1);
        RecordedStep fold = s.checkOnly() ? null : steps.get(s.action);
        if (fold == null || !fold.folded()) {
            throw bad("step " + n + " is no fold: only a do step a recording folded from raw steps unfolds");
        }
        List<String> users = usersOf(fold.action.id);
        if (!users.isEmpty()) {
            boolean one = users.size() == 1;
            throw bad((one ? "step " : "steps ") + and(users) + (one ? " uses" : " use") + " what step " + n + " changed, "
                    + "and a link breaks a fold: use the exact value there first (the recording is unchanged)");
        }
        List<RecordedStep> before = copyOfRecords();
        steps.remove(s.action);
        for (int k = fold.raw.size() - 1; k >= 0; k--) {
            steps.add(s.action, fold.raw.get(k).keptRaw(true).inGroup(fold.group));
        }
        int last = n + fold.raw.size() - 1;
        return finish("unfold of step " + n, "unfolded step " + n + " into " + stepsText(n, last), author, before, false);
    }

    /**
     * Keeps steps {@code from} to {@code to} raw: folding never takes them into a fold nor folds across them. A fold
     * among them is refused (unfold it); null when they all are kept raw already (nothing changes).
     */
    String keepRaw(int from, int to, RecordedStep.Author author) {
        List<StepNumbers.Step> st = StepNumbers.of(actions());
        if (from < 1 || from > st.size()) throw outOfRange(from);
        if (to < from || to > st.size()) throw outOfRange(to);
        List<Integer> marked = new ArrayList<Integer>();
        for (int n = from; n <= to; n++) {
            StepNumbers.Step s = st.get(n - 1);
            if (s.checkOnly()) continue;
            RecordedStep r = steps.get(s.action);
            if (r.folded()) throw bad("step " + n + " is a fold: unfold it to keep its raw steps raw");
            if (!r.keepRaw) marked.add(s.action);
        }
        if (marked.isEmpty()) return null;
        List<RecordedStep> before = copyOfRecords();
        for (int i : marked) steps.set(i, steps.get(i).keptRaw(true));
        String what = stepsText(from, to);
        return finish("keep raw of " + what, "kept " + what + " raw", author, before, false);
    }

    /** {@code fold} with what its raw steps changed, merged ({@link FoldOutcomes}); as it is when they changed nothing known. */
    private static RecordedStep merged(RecordedStep fold) {
        FoldOutcomes.Merged m = FoldOutcomes.of(fold.raw);
        return m == null ? fold : fold.withOutcome(m.outcome, m.newValues);
    }

    /** The flat index of the fold that holds the raw step {@code id}, or -1. */
    private int foldOf(int id) {
        for (int i = 0; i < steps.size(); i++) {
            RecordedStep s = steps.get(i);
            if (!s.folded()) continue;
            for (RecordedStep r : s.raw) if (r.id == id) return i;
        }
        return -1;
    }

    /**
     * The record {@code id} as the recording holds it: a step of its own, or a raw step of a fold (whose last raw step it
     * then is when {@code last}); null when it is gone, or not the fold's last raw step.
     */
    RecordedStep rawOrRecord(int id, boolean last) {
        int i = indexOf(id);
        if (i >= 0) return steps.get(i);
        int f = foldOf(id);
        if (f < 0) return null;
        List<RecordedStep> raw = steps.get(f).raw;
        for (int k = 0; k < raw.size(); k++) {
            if (raw.get(k).id == id) return !last || k == raw.size() - 1 ? raw.get(k) : null;
        }
        return null;
    }

    /** The numbers of the steps whose references use {@code name} (a step id or remember name); none for null. */
    List<String> usersOf(String name) {
        Set<Integer> out = new TreeSet<Integer>();
        if (name == null) return new ArrayList<String>();
        List<StepNumbers.Step> st = StepNumbers.of(actions());
        for (StepNumbers.Step n : st) {
            for (int i = n.first(); i <= n.last(); i++) {
                try {
                    for (Refs.Ref r : Refs.of(steps.get(i).action)) if (name.equals(r.id)) out.add(n.number);
                } catch (IllegalArgumentException malformed) {
                    // a reference it cannot read names no one
                }
            }
        }
        List<String> numbers = new ArrayList<String>();
        for (int k : out) numbers.add(String.valueOf(k));
        return numbers;
    }

    /** The flat index of the record with {@code id}, or -1. */
    private int indexOf(int id) {
        for (int i = 0; i < steps.size(); i++) if (steps.get(i).id == id) return i;
        return -1;
    }

    // ------------------------------------------------------------------ what the steps changed (outcomes)

    /** How many of the latest steps keep their whole outcome; older ones keep only their value index. */
    static final int FULL_OUTCOMES = 20;

    private static RunValues freshValues() {
        return new RunValues(RunValues.newRunId(), LocalDateTime.now());
    }

    /**
     * The values references use in this session: what each step changed when it last ran, under its {@code id} or, for
     * a step without one, {@code #<record id>}; what was remembered; the {@code run} values.
     */
    RunValues runValues() {
        return runValues;
    }

    /** The name a step's outcome is kept under in {@link #runValues}: its {@code id}, else {@code #<record id>}. */
    static String key(RecordedStep s) {
        return s.action.id != null ? s.action.id : "#" + s.id;
    }

    /**
     * What the record {@code id} changed when it ran just now: kept on it (whole, the older ones shrinking to their value
     * index) and in {@link #runValues}, newer than what was there. Nothing when the record is gone. Not an edit: no new
     * revision.
     */
    void outcome(int id, ObjectNode outcome, Map<String, String> newValues) {
        int i = indexOf(id);
        if (i < 0) {
            lateRaw(id, outcome, newValues);
            return;
        }
        RecordedStep s = steps.get(i).withOutcome(outcome, newValues);
        steps.set(i, s);
        runValues.put(key(s), outcome);
        shrinkOlderThan(FULL_OUTCOMES);
    }

    /**
     * The record {@code id} ran again and what it changed is not known (the app could not be read, or it failed): it
     * keeps nothing, and {@link #runValues} nothing under its key, so a reference to it fails rather than give what an
     * earlier run made. Not an edit.
     */
    void forgetOutcome(int id) {
        int i = indexOf(id);
        if (i < 0) return;
        RecordedStep s = steps.get(i);
        steps.set(i, s.withOutcome(null, null));
        runValues.remove(key(s));
    }

    /**
     * What the raw step {@code id} of a fold changed, come after folding took it (a person's outcome is read at their
     * next input): kept on the raw step, and the fold's merged outcome made again. Nothing when no fold holds it.
     */
    private void lateRaw(int id, ObjectNode outcome, Map<String, String> newValues) {
        int f = foldOf(id);
        if (f < 0) return;
        RecordedStep fold = steps.get(f);
        List<RecordedStep> raw = new ArrayList<RecordedStep>(fold.raw);
        for (int k = 0; k < raw.size(); k++) if (raw.get(k).id == id) raw.set(k, raw.get(k).withOutcome(outcome, newValues));
        RecordedStep made = merged(fold.withRaw(raw));
        steps.set(f, made);
        if (made.outcome != null) runValues.put(key(made), made.outcome);
        shrinkOlderThan(FULL_OUTCOMES);
    }

    /**
     * The value the remember record {@code id} took: its value index, for links made later ({@code ${name}} alone). Not
     * an edit.
     */
    void remembered(int id, String value) {
        int i = indexOf(id);
        if (i >= 0 && value != null) steps.set(i, steps.get(i).withOutcome(null, Collections.singletonMap(value, "")));
    }

    // ------------------------------------------------------------------ links (references to what earlier steps made)

    /** {@link #link(int, Action, Map, Map)} for sources that all have their id already. */
    String link(int stepId, Action linked, Map<String, String> seen) {
        return link(stepId, linked, seen, Collections.<Integer, String>emptyMap());
    }

    /**
     * Rewrites the record {@code stepId} as {@code linked}, the same step with references to what earlier steps made
     * ({@code seen}: the value each stood for), and gives each source in {@code sourceIds} (record id → step id) its id;
     * what a source changed is then found under that id too. One edit, made for the step's author: undo takes back the
     * link and the ids, and the step stays with its exact values.
     */
    String link(int stepId, Action linked, Map<String, String> seen, Map<Integer, String> sourceIds) {
        return link(stepId, linked, seen, sourceIds, null);
    }

    /** {@link #link(int, Action, Map, Map)} made by {@code by} (the person linking by hand); null: for the step's author. */
    String link(int stepId, Action linked, Map<String, String> seen, Map<Integer, String> sourceIds, RecordedStep.Author by) {
        int i = indexOf(stepId);
        if (i < 0) throw bad("that step is gone: the recording changed since (nothing linked)");
        List<RecordedStep> before = copyOfRecords();
        RecordedStep s = steps.get(i);
        Map<String, String> allSeen = new LinkedHashMap<String, String>(s.linkSeen);
        allSeen.putAll(seen);
        steps.set(i, s.with(sentences.apply(s.action, linked), s.edited).withLinkSeen(allSeen));
        for (Map.Entry<Integer, String> e : sourceIds.entrySet()) {
            int k = indexOf(e.getKey());
            if (k < 0) continue;
            RecordedStep source = steps.get(k);
            Action named = FlowJson.mapper().convertValue(source.action, Action.class);
            named.id = e.getValue();
            steps.set(k, source.with(named, source.edited));
            runValues.put(e.getValue(), source.outcome != null ? source.outcome : indexTree(source.newValues));
        }
        // the paths the link uses stay with their source when it shrinks to its value index
        for (String ref : seen.keySet()) {
            List<Refs.Ref> refs = Refs.scan(ref);
            if (refs.size() != 1 || refs.get(0).path.isEmpty()) continue;
            for (RecordedStep r : steps) {
                if (r.action instanceof RememberVerb || !refs.get(0).id.equals(r.action.id)) continue;
                if (!linkedPaths.containsKey(r.id)) linkedPaths.put(r.id, new HashSet<String>());
                linkedPaths.get(r.id).add(refs.get(0).path);
            }
        }
        String what = describe(i);
        return finish("link of " + what, "linked " + what + " to " + sourcesText(linked), by != null ? by : s.author, before,
                false);
    }

    /**
     * How {@link #link} and {@link #unlink} say a step they change: {@code follow.apply(before, after)} returns
     * {@code after}, or a copy of it with another sentence (a generated sentence that follows its params).
     */
    void sentences(BiFunction<Action, Action, Action> follow) {
        this.sentences = follow;
    }

    /** "what step 1 changed", "what steps 1 and 3 changed", "a remembered value", "the run's values". */
    private String sourcesText(Action linked) {
        Set<String> ids = new HashSet<String>();
        for (Refs.Ref r : Refs.of(linked)) ids.add(r.id);
        TreeSet<Integer> changed = new TreeSet<Integer>();
        boolean remembered = false;
        for (RecordedStep r : steps) {
            if (r.action instanceof RememberVerb && ids.contains(((RememberVerb) r.action).as)) {
                remembered = true;
            } else if (r.action.id != null && ids.contains(r.action.id)) {
                changed.add(addressOf(r.id).step);
            }
        }
        if (changed.isEmpty()) return remembered ? "a remembered value" : "the run's values";
        List<String> numbers = new ArrayList<String>();
        for (int n : changed) numbers.add(String.valueOf(n));
        String list = numbers.size() == 1 ? numbers.get(0)
                : String.join(", ", numbers.subList(0, numbers.size() - 1)) + " and " + numbers.get(numbers.size() - 1);
        return "what " + (numbers.size() == 1 ? "step " : "steps ") + list + " changed";
    }

    /**
     * Turns every reference of the record {@code stepId} back into the value it stood for when the step last ran (else
     * what this session knows now), kept as text. One edit by {@code by}; null when the step has no reference.
     */
    String unlink(int stepId, RecordedStep.Author by) {
        int i = indexOf(stepId);
        if (i < 0) throw bad("that step is gone: the recording changed since (nothing changed)");
        final RecordedStep s = steps.get(i);
        // a text made unique per run goes back to the text as typed, and so do the later uses of it
        if (uniqueSource(i)) return uniqueChange(i, "off", true, by);
        String unknown = unknownValue(steps, StepNumbers.labels(actions()), i, null, runValues);
        if (unknown != null) throw bad(unknown);
        JsonNode tree = FlowJson.mapper().valueToTree(s.action);
        JsonNode exact = Refs.mapValues(tree, text -> exactText(text, s.linkSeen));
        if (exact.equals(tree)) return null;
        Action a;
        try {
            a = FlowJson.mapper().treeToValue(exact, Action.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a step written to JSON does not read back: " + e.getOriginalMessage(), e);
        }
        List<RecordedStep> before = copyOfRecords();
        a = sentences.apply(s.action, a);
        steps.set(i, s.with(a, s.edited).withLinkSeen(Collections.<String, String>emptyMap()));
        String what = describe(i);
        return finish("exact value of " + what, "used the exact value in " + what, by, before, false);
    }

    /** {@code text} with each reference replaced by the value it stood for, escaped like any typed text. */
    private String exactText(String text, Map<String, String> seen) {
        return filled(text, seen, runValues, null, Unknown.REFUSE, false);
    }

    /** What to do with a reference no value is known for. */
    enum Unknown {
        /** BAD_INPUT: replay the steps before it first. */
        REFUSE,
        /** "…" in its place (for the person's eyes only). */
        DOTS
    }

    /**
     * {@code text} (as the recording keeps it) with each reference to an id in {@code only} (null: every reference)
     * replaced by the value it stood for when the step last ran ({@code seen}), else by what {@code values} has now.
     * The run's id unique per run added and no run has used yet goes, with the space that came with it: the text as it
     * was typed. {@code plain}: escapes undone, for the person's eyes; else the values escaped like any typed text.
     */
    static String filled(String text, Map<String, String> seen, RunValues values, Set<String> only, Unknown unknown,
            boolean plain) {
        List<Refs.Ref> refs;
        try {
            refs = Refs.scan(text);
        } catch (IllegalArgumentException malformed) {
            return plain ? Refs.unescape(text) : text;
        }
        if (refs.isEmpty()) return plain ? Refs.unescape(text) : text;
        StringBuilder sb = new StringBuilder();
        int last = 0;
        for (Refs.Ref r : refs) {
            String fixed = text.substring(last, r.start);
            last = r.end;
            if (only != null && !only.contains(r.id)) {
                sb.append(plain ? Refs.unescape(fixed) : fixed).append(r.source);
                continue;
            }
            String value = seen.get(r.source);
            if (value == null && Linker.RUN_ID.equals(r.source)) {
                // the space before it, or else the one after it, came with it
                if (fixed.endsWith(" ")) fixed = fixed.substring(0, fixed.length() - 1);
                else if (last < text.length() && text.charAt(last) == ' ') last++;
                sb.append(plain ? Refs.unescape(fixed) : fixed);
                continue;
            }
            if (value == null) value = values.resolve(r).orElse(null);
            sb.append(plain ? Refs.unescape(fixed) : fixed);
            if (value != null) {
                sb.append(plain ? value : Refs.escape(value));
            } else if (unknown == Unknown.REFUSE) {
                throw bad("no value is known for " + r.source + ": replay the steps before it first");
            } else {
                sb.append("…");
            }
        }
        String rest = text.substring(last);
        return sb.append(plain ? Refs.unescape(rest) : rest).toString();
    }

    /** The entries of {@code seen} whose reference {@code a} still has. */
    private static Map<String, String> stillUsed(Action a, Map<String, String> seen) {
        if (seen.isEmpty()) return seen;
        Set<String> used = new HashSet<String>();
        for (Refs.Ref r : Refs.of(a)) used.add(r.source);
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : seen.entrySet()) if (used.contains(e.getKey())) out.put(e.getKey(), e.getValue());
        return out;
    }

    /** {@code a} with {@code fn} applied to each of its value strings; {@code a} itself when nothing changes. */
    private static Action mapped(Action a, java.util.function.Function<String, String> fn) {
        JsonNode tree = FlowJson.mapper().valueToTree(a);
        JsonNode out = Refs.mapValues(tree, fn);
        if (out.equals(tree)) return a;
        try {
            return FlowJson.mapper().treeToValue(out, Action.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a step written to JSON does not read back: " + e.getOriginalMessage(), e);
        }
    }

    /** The name a reference to the record {@code s} uses: a remember entry's name, else the step's id; null when none. */
    static String nameOf(RecordedStep s) {
        return s.action instanceof RememberVerb ? ((RememberVerb) s.action).as : s.action.id;
    }

    /**
     * Before the records at flat {@code from}-{@code to} go: every other record that uses what they changed or
     * remembered gets the values it used, as text. Returns "; steps 5 and 7 keep the values they used" (their numbers
     * before the delete), or "" when none uses them. Refused, nothing changed, when one of those values is not known
     * yet (a loaded recorded test not replayed): it says which, in words.
     */
    private String keepValues(int from, int to) {
        final Set<String> names = new HashSet<String>();
        for (int i = from; i <= to; i++) {
            String name = nameOf(steps.get(i));
            if (name != null) names.add(name);
        }
        if (names.isEmpty()) return "";
        List<StepNumbers.Step> st = StepNumbers.of(actions());
        List<String> labels = StepNumbers.labels(actions());
        // nothing changes when a step that uses it has a value no one knows yet
        for (int i = 0; i < steps.size(); i++) {
            if (i >= from && i <= to) continue;
            String unknown = unknownValue(steps, labels, i, names, runValues);
            if (unknown != null) throw bad(unknown + " (nothing deleted)");
        }
        TreeSet<Integer> users = new TreeSet<Integer>();
        for (StepNumbers.Step n : st) {
            for (int i = n.first(); i <= n.last(); i++) {
                if (i >= from && i <= to) continue;
                final RecordedStep s = steps.get(i);
                Action exact = mapped(s.action, text -> filled(text, s.linkSeen, runValues, names, Unknown.REFUSE, false));
                if (exact == s.action) continue;
                steps.set(i, s.with(exact, s.edited).withLinkSeen(stillUsed(exact, s.linkSeen)));
                users.add(n.number);
            }
        }
        if (users.isEmpty()) return "";
        List<String> numbers = new ArrayList<String>();
        for (int n : users) numbers.add(String.valueOf(n));
        return "; " + (numbers.size() == 1 ? "step " + numbers.get(0) + " keeps the values it used"
                : "steps " + and(numbers) + " keep the values they used");
    }

    /**
     * Why the record at flat {@code i} of {@code records} ({@code labels}: their labels) cannot have its references to
     * {@code only} (null: all) turned into values, in words: the first one no value is known for ("Step 5 uses what
     * step 3 changed, and that value is not known yet: replay up to step 3 first"); null when every value is known.
     * A run's id unique per run added and no run used yet is known: it goes.
     */
    static String unknownValue(List<RecordedStep> records, List<String> labels, int i, Set<String> only, RunValues values) {
        RecordedStep s = records.get(i);
        List<Refs.Ref> refs;
        try {
            refs = Refs.of(s.action);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        for (Refs.Ref r : refs) {
            if (only != null && !only.contains(r.id)) continue;
            if (s.linkSeen.containsKey(r.source) || Linker.RUN_ID.equals(r.source) || values.resolve(r).isPresent()) continue;
            int source = -1;
            for (int k = 0; k < records.size() && source < 0; k++) if (r.id.equals(nameOf(records.get(k)))) source = k;
            return notKnown(labels.get(i), source < 0 ? null : records.get(source),
                    source < 0 ? null : labels.get(source));
        }
        return null;
    }

    /**
     * {@link #unknownValue} in words: {@code source} (null: gone) at {@code sourceLabel}, used by the step or check
     * {@code userLabel} ("7", "7.2").
     */
    static String notKnown(String userLabel, RecordedStep source, String sourceLabel) {
        String user = stepOrCheck(userLabel);
        if (source == null) return user + " uses a value of a step that is gone";
        String step = stepOf(sourceLabel);
        String what = source.action instanceof RememberVerb ? "the remembered " + ((RememberVerb) source.action).as
                : "what step " + step + " changed";
        return user + " uses " + what + ", and that value is not known yet: replay up to step " + step + " first";
    }

    /** A label as the start of a sentence: "Step 7" for "7", "Check 7.2" for "7.2". */
    static String stepOrCheck(String label) {
        return (label.indexOf('.') >= 0 ? "Check " : "Step ") + label;
    }

    /** The step of a label: "7" for "7" and "7.1". */
    private static String stepOf(String label) {
        int dot = label.indexOf('.');
        return dot < 0 ? label : label.substring(0, dot);
    }

    /**
     * Whether the record at flat {@code i} is a text made unique per run that is its own: a typed text in the form unique
     * per run gives ({@link Linker#uniqueForm}) that is no use of an earlier one's ({@link #uniqueUse}).
     */
    boolean uniqueSource(int i) {
        return uniqueSource(steps, i);
    }

    static boolean uniqueSource(List<RecordedStep> records, int i) {
        Action a = records.get(i).action;
        return a instanceof TypeVerb && ((TypeVerb) a).text != null && Linker.uniqueForm(((TypeVerb) a).text) != null
                && uniqueUse(records, i) < 0;
    }

    /**
     * The flat index of the earlier record whose text made unique per run the record at {@code i} uses (that whole text,
     * on its own or as whole words in a value of its); -1 when it uses none.
     */
    static int uniqueUse(List<RecordedStep> records, int i) {
        final List<String> texts = new ArrayList<String>();
        Refs.mapValues(FlowJson.mapper().valueToTree(records.get(i).action), t -> {
            texts.add(t);
            return t;
        });
        for (int k = 0; k < i; k++) {
            Action a = records.get(k).action;
            if (!(a instanceof TypeVerb) || ((TypeVerb) a).text == null) continue;
            String made = ((TypeVerb) a).text;
            if (Linker.uniqueForm(made) == null) continue;
            for (String t : texts) if (Linker.wholeIn(t, made)) return k;
        }
        return -1;
    }

    /** "5", "5 and 7", "4, 5 and 7". */
    private static String and(List<String> items) {
        if (items.size() == 1) return items.get(0);
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }

    /**
     * Unique per run for the typed text of the record {@code stepId}: {@code where} "end" or "start" adds the run's id
     * there ({@code ${run.id}}, a space between), "off" takes it away. Nothing is typed again: the later records that
     * have the text as it was typed - whole, or as a whole word in theirs, short or not - use the unique text too (and
     * follow it to the start, the end, or back to the plain text). One edit by {@code by}; null when it is that way already.
     */
    String unique(int stepId, String where, RecordedStep.Author by) {
        if (!"end".equals(where) && !"start".equals(where) && !"off".equals(where)) {
            throw bad("unique per run is \"end\", \"start\" or \"off\", not \"" + where + "\"");
        }
        int i = indexOf(stepId);
        if (i < 0) throw bad("that step is gone: the recording changed since (nothing changed)");
        final RecordedStep s = steps.get(i);
        if (!(s.action instanceof TypeVerb) || ((TypeVerb) s.action).text == null) {
            throw bad("only a typed text can be unique per run");
        }
        int source = uniqueUse(steps, i);
        if (source >= 0) {
            throw bad(describe(i) + " uses the unique text of " + describe(source) + ": change it there");
        }
        String now = Linker.uniqueForm(((TypeVerb) s.action).text);
        if (where.equals(now == null ? "off" : now)) return null;
        return uniqueChange(i, where, false, by);
    }

    /**
     * {@link #unique} of the record at flat {@code i}, a text of its own; {@code exact}: "Use the exact value" on it -
     * off, and what else it uses as the values it used too.
     */
    private String uniqueChange(int i, String where, boolean exact, RecordedStep.Author by) {
        final RecordedStep s = steps.get(i);
        String text = ((TypeVerb) s.action).text;
        String now = Linker.uniqueForm(text);
        String base = Linker.uniqueBase(text);
        String what = describe(i);
        if (Refs.unescape(base).trim().isEmpty()) throw bad(what + " types no text to make unique");
        if (exact) {
            String unknown = unknownValue(steps, StepNumbers.labels(actions()), i, null, runValues);
            if (unknown != null) throw bad(unknown);
            base = filled(base, s.linkSeen, runValues, null, Unknown.REFUSE, false);
        }
        final String made = "off".equals(where) ? base : "end".equals(where) ? base + " " + Linker.RUN_ID
                : Linker.RUN_ID + " " + base;
        List<RecordedStep> before = copyOfRecords();
        TypeVerb typed = (TypeVerb) FlowJson.mapper().convertValue(s.action, Action.class);
        typed.text = made;
        RecordedStep unique = s.with(typed, s.edited).withLinkSeen(stillUsed(typed, s.linkSeen));
        steps.set(i, unique);
        // the text as typed, for its later uses; not known when a value it uses is not
        String plain;
        try {
            plain = filled(base, s.linkSeen, runValues, null, Unknown.REFUSE, true);
        } catch (DriverException unknown) {
            plain = null;
        }
        final String old = now == null ? null : text;
        List<StepNumbers.Step> st = StepNumbers.of(actions());
        int own = addressOf(s.id).step;
        TreeSet<Integer> users = new TreeSet<Integer>();
        for (StepNumbers.Step n : st) {
            for (int k = n.first(); k <= n.last(); k++) {
                if (k <= i) continue;
                RecordedStep r = steps.get(k);
                // a use of the unique text as it was follows it
                Action a = old == null ? r.action : mapped(r.action, t -> Linker.replaceWhole(t, old, made));
                Map<String, String> seen = new LinkedHashMap<String, String>(r.linkSeen);
                if (!"off".equals(where) && plain != null) {
                    Linker.Linked l = Linker.uniqueBack(a, unique, plain, made);
                    if (l.linked()) {
                        a = l.action;
                        seen.putAll(l.seen);
                    }
                }
                if (a == r.action) continue;
                steps.set(k, r.with(a, r.edited).withLinkSeen(stillUsed(a, seen)));
                if (n.number != own) users.add(n.number);
            }
        }
        List<String> numbers = new ArrayList<String>();
        for (int n : users) numbers.add(String.valueOf(n));
        String past = exact ? "used the exact value in " + what
                : "off".equals(where) ? "turned unique per run off in " + what : "made the text of " + what + " unique per run";
        if (!numbers.isEmpty()) {
            boolean one = numbers.size() == 1;
            past += "; " + (one ? "step " : "steps ") + and(numbers) + ("off".equals(where)
                    ? (one ? " uses" : " use") + " the plain text again" : (one ? " uses it" : " use it"));
        }
        return finish((exact ? "exact value of " : "unique per run of ") + what, past, by, before, false);
    }

    /** The values the references of record {@code id} stood for when it ran just now. Not an edit. */
    void linkSeen(int id, Map<String, String> seen) {
        int i = indexOf(id);
        if (i >= 0) steps.set(i, steps.get(i).withLinkSeen(seen));
    }

    /**
     * Keeps the whole outcome of the last {@code keep} records that have one; older ones keep only their value index
     * ({@link RecordedStep#newValues}), here, in the undo history and in {@link #runValues}, where a reference to one
     * of those values still finds it.
     */
    void shrinkOlderThan(int keep) {
        Set<Integer> whole = new HashSet<Integer>();
        for (int i = steps.size() - 1; i >= 0 && whole.size() < keep; i--) {
            if (steps.get(i).outcome != null) whole.add(steps.get(i).id);
        }
        for (int i = 0; i < steps.size(); i++) {
            RecordedStep s = steps.get(i);
            if (s.outcome == null || whole.contains(s.id)) continue;
            RecordedStep shrunk = s.shrunk();
            steps.set(i, shrunk);
            runValues.put(key(s), keptTree(s.outcome, shrunk.newValues, linkedPaths.get(s.id)));
        }
        for (Edit e : history) {
            for (int i = 0; i < e.before.size(); i++) {
                RecordedStep s = e.before.get(i);
                if (s.outcome != null && !whole.contains(s.id)) e.before.set(i, s.shrunk());
            }
        }
    }

    /**
     * What a step that shrinks keeps for references: its value index, and the value at each path a link made to it uses,
     * read from the {@code outcome} it is losing (a link may name a row by a path the index does not hold, the value
     * being indexed under a field). A path that holds no single value in that outcome is not kept: a reference to it
     * then fails, never giving a value an earlier run made.
     */
    private static ObjectNode keptTree(ObjectNode outcome, Map<String, String> newValues, Set<String> paths) {
        ObjectNode tree = indexTree(newValues);
        if (paths == null) return tree;
        for (String path : paths) {
            List<JsonNode> hits = JsonPaths.select(outcome, "$" + path);
            if (hits.size() != 1 || !hits.get(0).isValueNode() || hits.get(0).isNull()) continue;
            try {
                JsonPaths.put(tree, "$" + path, hits.get(0).asText());
            } catch (IllegalArgumentException clash) {
                // the index holds the place already
            }
        }
        return tree;
    }

    /** An outcome holding only the values of a value index, each at its path. */
    private static ObjectNode indexTree(Map<String, String> newValues) {
        return FoldOutcomes.indexTree(newValues);
    }

    // ------------------------------------------------------------------ undo

    /** The records after the last edit is taken back, or null when there is nothing to undo. */
    private List<RecordedStep> undone() {
        Edit e = history.peekFirst();
        if (e == null) return null;
        List<RecordedStep> out = new ArrayList<RecordedStep>(e.before.size());
        Map<Integer, RecordedStep> now = new HashMap<Integer, RecordedStep>();
        for (RecordedStep s : steps) {
            now.put(s.id, s);
            // an undone fold's raw steps come back with what they changed since
            if (s.folded()) for (RecordedStep r : s.raw) now.put(r.id, r);
        }
        for (RecordedStep s : e.before) {
            // what a step changed in the app is no edit: undo keeps what is known now (an edit forgot it: the old one's)
            RecordedStep current = now.get(s.id);
            if (current != null && (current.outcome != null || !current.newValues.isEmpty())) {
                s = s.withOutcome(current.outcome, current.newValues);
            }
            out.add(e.keepRawOnUndo.contains(s.id) ? s.keptRaw(true) : s);
        }
        if (!e.cut) {
            // records made since join as they would have: an action never in a group, a check in its step's
            for (RecordedStep s : steps) {
                if (s.id <= e.maxIdAfter) continue;
                boolean check = StepNumbers.isCheck(s.action);
                out.add(s.inGroup(check && !out.isEmpty() ? out.get(out.size() - 1).group : null));
            }
        }
        return out;
    }

    /** The recording undo would leave, to check it before committing; null when there is nothing to undo. */
    Flow undoPreview() {
        List<RecordedStep> after = undone();
        return after == null ? null : viewOf(after);
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
        Set<String> gone = new HashSet<String>();
        for (RecordedStep s : steps) gone.addAll(names(s));
        for (RecordedStep s : after) gone.removeAll(names(s));
        // an id or remember name the undo takes away (a link's new id, a remember entry) names nothing any more
        for (String id : gone) runValues.remove(id);
        steps.clear();
        steps.addAll(after);
        String did = "undid the " + e.label;
        if (dropped > 0) did += " (" + dropped + " step" + (dropped == 1 ? "" : "s") + " recorded after it dropped)";
        changed(by(by) + " " + did);
        return did;
    }

    /** The names {@code s} keeps values under: its id, and the name it remembers as. */
    private static Set<String> names(RecordedStep s) {
        Set<String> names = new HashSet<String>();
        if (s.action.id != null) names.add(s.action.id);
        if (s.action instanceof RememberVerb && ((RememberVerb) s.action).as != null) names.add(((RememberVerb) s.action).as);
        return names;
    }

    private DriverException outOfRange(int n) {
        int count = steps();
        String range = count == 0 ? "is empty"
                : "has " + count + " step" + (count == 1 ? "" : "s") + " (1-" + count + ")";
        return bad("no step " + n + ": the recording " + range);
    }
}
