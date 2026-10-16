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
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        Edit(String label, String past, RecordedStep.Author author, List<RecordedStep> before, int maxIdAfter,
                boolean cut, Set<Integer> keepRawOnUndo) {
            this.label = label;
            this.past = past;
            this.author = author;
            this.before = before;
            this.maxIdAfter = maxIdAfter;
            this.cut = cut;
            this.keepRawOnUndo = keepRawOnUndo;
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
        history.addFirst(new Edit(label + " by " + by(author), past, author, before, nextId - 1, cut, keepRawOnUndo));
        while (history.size() > HISTORY) history.removeLast();
        changed(by(author) + " " + past);
        return past;
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
        boolean isCheck = StepNumbers.isCheck(action);
        RecordedStep.Group group = isCheck && !steps.isEmpty() ? steps.get(steps.size() - 1).group : null;
        RecordedStep step = new RecordedStep(nextId++, author, action, false, group, row, seen, null, false);
        steps.add(step);
        changed("the " + author.json + " recorded " + (isCheck ? "check " : "step ") + addressOf(step.id));
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

    /** A new model holding the same steps and groups (no history): an edit is tried on it before it is made for real. */
    RecordingModel copy() {
        RecordingModel copy = new RecordingModel();
        copy.load(flow(), RecordedStep.Author.LOADED);
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
        for (int i = st.get(to - 1).last(); i >= st.get(from - 1).first(); i--) steps.remove(i);
        String what = stepsText(from, to);
        String past = "deleted " + what;
        if (checks > 0) {
            past += " and " + (from == to ? "its " : "their ") + checks + (checks == 1 ? " check" : " checks");
        }
        return finish("delete of " + what, past, author, before, false);
    }

    /** Removes check {@code check} of step {@code step}. */
    String deleteCheck(int step, int check, RecordedStep.Author author) {
        int flat = flatOf(step, check, true);
        List<RecordedStep> before = copyOfRecords();
        steps.remove(flat);
        return finish("delete of check " + step + "." + check, "deleted check " + step + "." + check, author, before,
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
     * ({@link RecordedStep#raw}, an earlier fold's own); checks after the last stay under it. {@code editor} made the
     * fold; undo brings the records back kept raw.
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
            if (r.folded()) raw.addAll(r.raw);
            else raw.add(r.keptRaw(false));
        }
        int a = addressOf(ids.get(0)).step;
        int b = addressOf(ids.get(ids.size() - 1)).step;
        List<RecordedStep> before = copyOfRecords();
        RecordedStep made = RecordedStep.fold(nextId++, by, action, raw).inGroup(group);
        for (int k = 0; k < ids.size(); k++) steps.remove(first);
        steps.add(first, made);
        String what = stepsText(a, b);
        String into = action.say == null ? action.describe() : action.say;
        String did = edited("fold of " + what + " into \"" + into + "\"", "folded " + what + " into \"" + into + "\"",
                editor, before, false, new HashSet<Integer>(ids));
        return new Folded(made, did);
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

    /** The flat index of the record with {@code id}, or -1. */
    private int indexOf(int id) {
        for (int i = 0; i < steps.size(); i++) if (steps.get(i).id == id) return i;
        return -1;
    }

    // ------------------------------------------------------------------ undo

    /** The records after the last edit is taken back, or null when there is nothing to undo. */
    private List<RecordedStep> undone() {
        Edit e = history.peekFirst();
        if (e == null) return null;
        List<RecordedStep> out = new ArrayList<RecordedStep>(e.before.size());
        for (RecordedStep s : e.before) out.add(e.keepRawOnUndo.contains(s.id) ? s.keptRaw(true) : s);
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
        steps.clear();
        steps.addAll(after);
        String did = "undid the " + e.label;
        if (dropped > 0) did += " (" + dropped + " step" + (dropped == 1 ? "" : "s") + " recorded after it dropped)";
        changed(by(by) + " " + did);
        return did;
    }

    private DriverException outOfRange(int n) {
        int count = steps();
        String range = count == 0 ? "is empty"
                : "has " + count + " step" + (count == 1 ? "" : "s") + " (1-" + count + ")";
        return bad("no step " + n + ": the recording " + range);
    }
}
