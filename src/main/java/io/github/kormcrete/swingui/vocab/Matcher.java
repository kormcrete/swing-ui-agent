package io.github.kormcrete.swingui.vocab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Folds a recording's raw steps into domain steps while it is made: a run of steps that is a domain step's
 * {@link Pattern} becomes one {@code do} step. Nothing here is AWT or session: the recording comes in as {@link Item}s
 * (step JSON as do_action takes it, with who made it), the vocabulary as {@link Candidate}s, and the folds go out.
 *
 * <p>Each new raw step ({@link Item#fresh}) is looked at in turn, as if it had just been recorded: the runs that end at
 * it are tried, longest first, back to the last barrier, never longer than the longest pattern. A barrier is a check,
 * a {@code do} step that is no fold (the agent's own), a step by someone else (a person's and the agent's steps never
 * fold together), and any item the caller marks ({@link Item#barrier}: kept raw, before a replay, in a group, named).
 * A run holds whole items: raw steps and earlier folds (their raw steps), so a fold can grow into a longer step ("Add
 * Gel pen, black to the cart" and a Qty 10 become "Add 10 × Gel pen, black to the cart"), but never takes part of one.
 *
 * <p>The longest run that matches wins, then the most specific pattern ({@link Pattern#literals}). Two domain steps (or
 * one with other values) that tie fold nothing: the run is noted with its {@code candidates}. A winner whose step does
 * not fold on its own ({@link Candidate#autoFold} false) is only suggested. A pattern that cannot decide a run
 * ({@link Pattern.TooAmbiguous}) as long as the winner's, or longer, stops the fold too: noted, with why. All matches
 * of one {@link #fold} share one deadline; past it the steps not looked at yet stay as they are, noted.
 */
public final class Matcher {

    private Matcher() {}

    /** A domain step a recording may fold into. */
    public static final class Candidate {
        /** The name a {@code do} step calls it by, e.g. {@code order-desk:addToCart}. */
        public final String name;
        final List<Pattern> patterns;
        final boolean autoFold;

        public Candidate(String name, List<Pattern> patterns, boolean autoFold) {
            this.name = name;
            this.patterns = Collections.unmodifiableList(new ArrayList<Pattern>(patterns));
            this.autoFold = autoFold;
        }
    }

    /** One record of the recording, as the matcher sees it. */
    public static final class Item {
        /** What the record is. */
        public enum Kind {
            /** An action step as recorded: the only kind a pattern matches. */
            RAW,
            /** A fold made earlier: one {@code do} step standing for its raw steps. */
            FOLDED,
            /** A check (an expect, a domain check): a barrier. */
            CHECK,
            /** A {@code do} step that is no fold, such as the agent's own: a barrier. */
            DO
        }

        /** What the caller knows the record by (the matcher never looks at it); for a fold made here, its {@link Fold}. */
        public final Object key;
        public final Kind kind;
        /** Who made it ({@code person}, {@code agent}, ...): a run is one author's. */
        public final String author;
        /** A raw step's JSON as a pattern matches it; null for the other kinds. */
        public final JsonNode step;
        /** A fold's raw steps, in order (raw items); empty for the other kinds. */
        public final List<Item> raw;
        /** Nothing folds across it, and it folds into nothing (kept raw, the start of a replay, in a group, named). */
        public final boolean barrier;
        /** A raw step recorded since the last match: {@link #fold} looks at the runs that end at it. */
        public final boolean fresh;

        private Item(Object key, Kind kind, String author, JsonNode step, List<Item> raw, boolean barrier, boolean fresh) {
            this.key = key;
            this.kind = kind;
            this.author = author;
            this.step = step;
            this.raw = Collections.unmodifiableList(new ArrayList<Item>(raw));
            this.barrier = barrier;
            this.fresh = fresh;
        }

        public static Item raw(Object key, String author, JsonNode step, boolean barrier, boolean fresh) {
            return new Item(key, Kind.RAW, author, step, Collections.<Item>emptyList(), barrier, fresh);
        }

        /** A fold made earlier, standing for {@code raw} (raw items, in order). */
        public static Item folded(Object key, String author, List<Item> raw, boolean barrier) {
            return new Item(key, Kind.FOLDED, author, null, raw, barrier, false);
        }

        public static Item check(Object key, String author) {
            return new Item(key, Kind.CHECK, author, null, Collections.<Item>emptyList(), true, false);
        }

        public static Item doStep(Object key, String author) {
            return new Item(key, Kind.DO, author, null, Collections.<Item>emptyList(), true, false);
        }

        /** The raw steps this item stands for: itself, or a fold's. */
        List<Item> raws() {
            return kind == Kind.FOLDED ? raw : Collections.singletonList(this);
        }

        /** It can be part of a run: a raw step or a fold, not marked a barrier. */
        boolean foldable() {
            return (kind == Kind.RAW || kind == Kind.FOLDED) && !barrier;
        }

        @Override public String toString() {
            return kind + (key == null ? "" : " " + key) + (kind == Kind.RAW ? " " + step : kind == Kind.FOLDED ? " " + raw : "");
        }
    }

    /** One domain step with its values that matches a run. */
    public static final class Choice {
        /** The {@link Candidate#name}. */
        public final String step;
        /** The values the run gave the step's params, in declared order (an optional one left out is not here). */
        public final Map<String, String> params;
        final int literals;
        final boolean autoFold;

        Choice(String step, Map<String, String> params, int literals, boolean autoFold) {
            this.step = step;
            this.params = Collections.unmodifiableMap(new LinkedHashMap<String, String>(params));
            this.literals = literals;
            this.autoFold = autoFold;
        }

        boolean same(Choice o) {
            return step.equals(o.step) && params.equals(o.params);
        }

        @Override public String toString() { return step + params; }
    }

    /** A run folded into one {@code do} step. */
    public static final class Fold {
        /** The items the fold took the place of, in order: raw steps and earlier folds. */
        public final List<Item> span;
        public final Choice choice;
        /** The fold as an item ({@link Item.Kind#FOLDED}, its key this fold), in the place of {@link #span}. */
        public final Item item;

        Fold(List<Item> span, Choice choice) {
            this.span = Collections.unmodifiableList(new ArrayList<Item>(span));
            this.choice = choice;
            List<Item> raw = new ArrayList<Item>();
            for (Item i : span) raw.addAll(i.raws());
            this.item = Item.folded(this, span.get(0).author, raw, false);
        }

        /** The raw steps the fold stands for, in order. */
        public List<Item> raw() {
            return item.raw;
        }

        @Override public String toString() { return "Fold " + choice + " of " + span; }
    }

    /** A run that did not fold, and why: a tie, a step that only suggests, a match that could not be decided. */
    public static final class Note {
        public enum Kind {
            /** Two or more steps (or values) fit the run equally well: {@link #choices}. */
            TIE,
            /** The one step that fits does not fold on its own: {@link #choices} holds it. */
            SUGGESTED,
            /** It could not be decided, {@link #why}: the steps stay as they are. */
            UNDECIDED
        }

        public final Kind kind;
        /** The run, in order; for an undecided one, the step looked at when it happened. */
        public final List<Item> span;
        public final List<Choice> choices;
        /** Why it could not be decided; null for the other kinds. */
        public final String why;

        Note(Kind kind, List<Item> span, List<Choice> choices, String why) {
            this.kind = kind;
            this.span = Collections.unmodifiableList(new ArrayList<Item>(span));
            this.choices = Collections.unmodifiableList(new ArrayList<Choice>(choices));
            this.why = why;
        }

        @Override public String toString() { return kind + " " + choices + (why == null ? "" : " " + why) + " at " + span; }
    }

    /** What {@link #fold} did. */
    public static final class Result {
        /** The recording afterwards: the items given, with each fold in the place of its run. */
        public final List<Item> items;
        /** The folds in the order they were made; a later one may take an earlier one ({@link Fold#span}). */
        public final List<Fold> folds;
        public final List<Note> notes;
        /** The deadline passed: the fresh steps after the last one looked at were not looked at. */
        public final boolean outOfTime;

        Result(List<Item> items, List<Fold> folds, List<Note> notes, boolean outOfTime) {
            this.items = Collections.unmodifiableList(items);
            this.folds = Collections.unmodifiableList(folds);
            this.notes = Collections.unmodifiableList(notes);
            this.outOfTime = outOfTime;
        }
    }

    /** What an undecided match says. */
    static final String COULD_NOT_DECIDE = "could not decide whether the steps are '%s': its pattern fits them in more "
            + "ways than a match tries";
    static final String OUT_OF_TIME = "folding ran out of time before it looked at this step";

    /**
     * Folds the fresh raw steps of {@code items} into the steps of {@code vocabulary}, each fresh step in order as if it
     * had just been recorded (see the class). {@code deadline} is a {@link System#nanoTime} value all matches share.
     */
    public static Result fold(List<Item> items, List<Candidate> vocabulary, long deadline) {
        List<Item> list = new ArrayList<Item>(items);
        List<Fold> folds = new ArrayList<Fold>();
        List<Note> notes = new ArrayList<Note>();
        int longest = longest(vocabulary);
        for (int k = 0; k < list.size(); k++) {
            Item at = list.get(k);
            if (at.kind != Item.Kind.RAW || !at.fresh || at.barrier || longest == 0) continue;
            if (System.nanoTime() - deadline > 0) {
                notes.add(new Note(Note.Kind.UNDECIDED, Collections.singletonList(at), Collections.<Choice>emptyList(),
                        OUT_OF_TIME));
                return new Result(list, folds, notes, true);
            }
            Outcome o = best(list, k, longest, vocabulary, deadline);
            if (o.outOfTime) {
                notes.add(new Note(Note.Kind.UNDECIDED, Collections.singletonList(at), Collections.<Choice>emptyList(),
                        OUT_OF_TIME));
                return new Result(list, folds, notes, true);
            }
            if (o.note != null) notes.add(o.note);
            if (o.fold == null) continue;
            folds.add(o.fold);
            int from = k - o.fold.span.size() + 1;
            for (int i = k; i >= from; i--) list.remove(i);
            list.add(from, o.fold.item);
            k = from;
        }
        return new Result(list, folds, notes, false);
    }

    /** A fold by name that could not be told within its deadline. */
    public static final class OutOfTime extends RuntimeException {
        OutOfTime() {
            super("folding ran out of time before it could tell what the steps are", null, false, false);
        }
    }

    /**
     * Every domain step with its values that matches all of {@code span} (whole items, in order), the most specific
     * first; empty when none does. Barriers and authors are the caller's to check: this is a fold asked for by name.
     * {@code deadline} is a {@link System#nanoTime} value, looked at before each pattern.
     *
     * @throws Pattern.TooAmbiguous when a pattern could not decide the run (its message says which)
     * @throws OutOfTime when the deadline passed before every pattern was tried
     */
    public static List<Choice> matches(List<Item> span, List<Candidate> vocabulary, long deadline) {
        List<JsonNode> steps = steps(span);
        List<Choice> out = new ArrayList<Choice>();
        for (Candidate c : vocabulary) {
            for (Pattern p : c.patterns) {
                if (steps.size() < p.shortest() || steps.size() > p.longest()) continue;
                if (System.nanoTime() - deadline > 0) throw new OutOfTime();
                Pattern.Match m = match(p, steps);
                if (m != null) add(out, new Choice(c.name, m.params(), p.literals(), c.autoFold));
            }
        }
        Collections.sort(out, (a, b) -> b.literals - a.literals);
        return out;
    }

    /** {@code p.match(steps)}; a match nested deeper than the thread's stack could not be decided either. */
    private static Pattern.Match match(Pattern p, List<JsonNode> steps) {
        try {
            return p.match(steps);
        } catch (StackOverflowError e) {
            throw new Pattern.TooAmbiguous(p.stepId());
        }
    }

    /**
     * A recorded step as a pattern matches it: {@code action} (the step's JSON) with each target field {@code seen} names
     * ({@code target}, {@code from}, {@code to}: the element as it was when the step was recorded) given that element's
     * full scope (every tab and section around it), role, name and row (every cell of it), in the place of the shorter
     * locator the recorder writes; the rest stays the step's own (column, id, window, ...). Null {@code seen}: the step
     * as it is. With {@code rowByPosition} (the step names its table row by its index, as the person chose) the row stays
     * that index, as a decimal number: a pattern's literal index still matches it, an INT param (which takes whole
     * numbers only) never does, and a row of values is not offered. {@code action} itself is not changed.
     */
    public static ObjectNode matchForm(JsonNode action, JsonNode seen, boolean rowByPosition) {
        ObjectNode form = ((ObjectNode) action).deepCopy();
        for (Iterator<Map.Entry<String, JsonNode>> it = form.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> f = it.next();
            if (!f.getValue().isObject()) continue;
            ObjectNode target = (ObjectNode) f.getValue();
            JsonNode was = seen == null ? null : seen.get(f.getKey());
            if (was != null && was.isObject()) {
                for (String key : new String[] {"scope", "role", "name"}) {
                    if (was.has(key)) target.set(key, was.get(key).deepCopy());
                }
                if (!rowByPosition && was.path("row").isObject() && target.has("row")) {
                    target.set("row", was.get("row").deepCopy());
                }
            }
            JsonNode index = target.path("row").path("index");
            if (rowByPosition && target.path("row").size() == 1 && index.isIntegralNumber()) {
                ((ObjectNode) target.get("row")).set("index", DoubleNode.valueOf(index.doubleValue()));
            }
        }
        return form;
    }

    /** Why a pattern of step {@code stepId} left a run undecided, in a person's words. */
    public static String couldNotDecide(String stepId) {
        return String.format(COULD_NOT_DECIDE, stepId);
    }

    // ---- one fresh step

    /** What the runs ending at one fresh step give: a fold, a note, both or neither, or no time to tell. */
    private static final class Outcome {
        Fold fold;
        Note note;
        boolean outOfTime;
    }

    /** The best run ending at {@code list[k]}, as the class describes. */
    private static Outcome best(List<Item> list, int k, int longest, List<Candidate> vocabulary, long deadline) {
        Outcome o = new Outcome();
        String author = list.get(k).author;
        int bestLength = 0;
        int bestStart = -1;
        List<Choice> best = new ArrayList<Choice>();
        int undecidedLength = 0;
        int undecidedStart = -1;
        String undecidedWhy = null;
        int length = 0;
        for (int s = k; s >= 0; s--) {
            Item item = list.get(s);
            if (!item.foldable() || !same(item.author, author)) break;
            length += item.raws().size();
            if (length > longest) break;
            List<Item> span = list.subList(s, k + 1);
            List<JsonNode> steps = steps(span);
            for (Candidate c : vocabulary) {
                for (Pattern p : c.patterns) {
                    if (length < p.shortest() || length > p.longest()) continue;
                    if (System.nanoTime() - deadline > 0) {
                        o.outOfTime = true;
                        return o;
                    }
                    Pattern.Match m;
                    try {
                        m = match(p, steps);
                    } catch (Pattern.TooAmbiguous e) {
                        if (length > undecidedLength) {
                            undecidedLength = length;
                            undecidedStart = s;
                            undecidedWhy = couldNotDecide(e.stepId());
                        }
                        continue;
                    }
                    if (m == null) continue;
                    Choice choice = new Choice(c.name, m.params(), p.literals(), c.autoFold);
                    if (length > bestLength) {
                        bestLength = length;
                        bestStart = s;
                        best.clear();
                    }
                    if (length == bestLength) add(best, choice);
                }
            }
        }
        if (undecidedWhy != null && undecidedLength >= bestLength) {
            o.note = new Note(Note.Kind.UNDECIDED, new ArrayList<Item>(list.subList(undecidedStart, k + 1)), best,
                    undecidedWhy);
            return o;
        }
        if (best.isEmpty()) return o;
        Collections.sort(best, (a, b) -> b.literals - a.literals);
        List<Item> span = new ArrayList<Item>(list.subList(bestStart, k + 1));
        List<Choice> top = new ArrayList<Choice>();
        for (Choice c : best) if (c.literals == best.get(0).literals) top.add(c);
        if (top.size() > 1) {
            o.note = new Note(Note.Kind.TIE, span, top, null);
        } else if (!top.get(0).autoFold) {
            o.note = new Note(Note.Kind.SUGGESTED, span, top, null);
        } else {
            o.fold = new Fold(span, top.get(0));
        }
        return o;
    }

    /** Adds {@code c} unless the same step with the same values is there; the more specific of the two stays. */
    private static void add(List<Choice> into, Choice c) {
        for (int i = 0; i < into.size(); i++) {
            if (into.get(i).same(c)) {
                if (c.literals > into.get(i).literals) into.set(i, c);
                return;
            }
        }
        into.add(c);
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static List<JsonNode> steps(List<Item> span) {
        List<JsonNode> steps = new ArrayList<JsonNode>();
        for (Item i : span) for (Item r : i.raws()) steps.add(r.step);
        return steps;
    }

    private static int longest(List<Candidate> vocabulary) {
        int n = 0;
        for (Candidate c : vocabulary) for (Pattern p : c.patterns) n = Math.max(n, p.longest());
        return n;
    }
}
