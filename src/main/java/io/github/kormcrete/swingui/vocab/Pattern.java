package io.github.kormcrete.swingui.vocab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A run of recorded steps that a domain step stands for, as {@link PatternCompiler} made it: one pattern step per
 * recorded step, in order, each one optional or not. {@link #match} says whether a run of steps (step JSON as do_action
 * takes it) is this pattern, and with which param values. Nothing here is AWT; a pattern never changes.
 *
 * <p>A pattern step matches a step as a subset: every field the pattern names must be in the step and match, fields
 * it leaves out match anything (a recorder may write a window, a scope, another column); {@code timeout}, {@code via},
 * {@code fragile}, {@code nth} and {@code window} are always left out (but a table column of that name in a
 * {@code row} is not). Nested objects match the same way, arrays item by item with the same length, except a
 * {@code scope}: its items are found in the step's scope in order, with others between them (a subsequence). Text
 * matches as {@link PatternCompiler} describes ({@code {param}} captures, {@code *}); a lone INT capture also matches
 * a whole number that is not negative (a row index). The same param captured twice must hold the same text.
 *
 * <p>A run can fit a pattern more than one way. The fits are tried in a fixed order and the first one that fits the
 * whole run wins: an optional entry is taken before it is left out, a scope item is found as early as it can be, a
 * {@code *} takes as little text as it can, and a capture takes as little as it can, except that a capture right
 * before a {@code *} takes as much as it can. That last rule is for a glob that means "starts with the value": from
 * {@code ui.tab(order + "*")}, the title {@code ORD-1640 · Ada Shop Ltd} gives {@code order} the whole title, which
 * reaches the same tab again (the shortest fit, {@code O}, would also reach it but says nothing). A capture's fit is
 * never final while the rest is matched: when a later field or step uses the same param, the fits are tried until
 * one holds for both (the tab above and a row {@code Order = ORD-1640} give {@code ORD-1640}).
 *
 * <p>Trying fits costs. Inside a text only ends that can work are tried (a last {@code *} or capture takes the rest; one
 * before a literal or a bound capture ends only where that text occurs), and a text that lacks its literals or bound
 * captures fails in one pass. A match makes at most {@link #FIT_BUDGET} choices and does at most {@link #WORK_LIMIT}
 * work, and then throws {@link TooAmbiguous} instead of answering. Before each pattern step it asks whether the rest of
 * the pattern can still match the rest of the run at all, each pattern step on its own with its captures free
 * (remembered per match; each such look on a tenth of the budgets, all of them together on one more
 * {@link #WORK_LIMIT}, and a look that runs out, or any look once that is spent, answers yes): a run that fails on a
 * later step's literal fails at once, without trying every fit of the steps before. Beyond that there is no memo:
 * when only the same param in two places disagrees, fits are tried until the budget ends. Each fit that holds
 * nests the rest of the match in the call stack, so the depth grows with steps × fields × text parts: match a window
 * of recent steps, not a whole recording.
 *
 * <p>Not plugin API; may change.
 */
public final class Pattern {

    /** A run of steps that is the pattern: the values its params took. */
    public static final class Match {
        private final Map<String, String> params;

        Match(Map<String, String> params) {
            this.params = Collections.unmodifiableMap(params);
        }

        /**
         * The step's declared params the run gave a value, in declared order, as text (an INT in digits). An optional
         * param the run left out is not here: the step gives it its default.
         */
        public Map<String, String> params() { return params; }

        @Override public String toString() { return "Match" + params; }
    }

    private final String stepId;
    private final boolean derived;
    private final List<ObjectNode> source;
    private final List<Element> elements;
    /** Every pattern step in order, inside uses too; each optional when it or a use around it is. */
    private final List<One> flat = new ArrayList<One>();
    private final List<Boolean> flatOptional = new ArrayList<Boolean>();
    private final Map<One, Integer> flatIndex = new IdentityHashMap<One, Integer>();
    private final List<String> params;

    Pattern(String stepId, boolean derived, List<ObjectNode> source, List<Element> elements, List<String> params) {
        this.stepId = stepId;
        this.derived = derived;
        List<ObjectNode> copies = new ArrayList<ObjectNode>();
        for (ObjectNode s : source) copies.add(s.deepCopy());
        this.source = Collections.unmodifiableList(copies);
        this.elements = Collections.unmodifiableList(copy(elements));
        flatten(this.elements, false);
        this.params = Collections.unmodifiableList(new ArrayList<String>(params));
    }

    /** The id of the domain step this pattern stands for. */
    public String stepId() { return stepId; }

    /** True when the pattern is the step's body (traced or declarative); false when the plugin wrote it. */
    public boolean derived() { return derived; }

    /** The pattern steps as written ({@code match}/{@code matchAlso} entries) or derived (the body's steps); copies. */
    public List<ObjectNode> source() {
        List<ObjectNode> copies = new ArrayList<ObjectNode>();
        for (ObjectNode s : source) copies.add(s.deepCopy());
        return copies;
    }

    List<Element> elements() { return elements; }

    /** The fewest steps a run of this pattern has: its pattern steps that are not optional. */
    int shortest() {
        int n = 0;
        for (Boolean optional : flatOptional) if (!optional) n++;
        return n;
    }

    /** The most steps a run of this pattern has: all its pattern steps. */
    int longest() {
        return flat.size();
    }

    /**
     * How specific the pattern is: the literal characters of its texts plus one per number, true, false or null it
     * names, in the pattern steps that are not optional (what every run of it holds).
     */
    int literals() {
        int n = 0;
        for (int i = 0; i < flat.size(); i++) if (!flatOptional.get(i)) n += flat.get(i).node.literals();
        return n;
    }

    /** Fresh elements, so each pattern step of this pattern is its own object (a step used twice is two). */
    private static List<Element> copy(List<Element> elements) {
        List<Element> out = new ArrayList<Element>();
        for (Element e : elements) {
            out.add(e instanceof One ? new One(((One) e).node, e.optional)
                    : new Group(copy(((Group) e).elements), e.optional));
        }
        return out;
    }

    private void flatten(List<Element> elements, boolean optional) {
        for (Element e : elements) {
            if (e instanceof One) {
                flatIndex.put((One) e, flat.size());
                flat.add((One) e);
                flatOptional.add(optional || e.optional);
            } else {
                flatten(((Group) e).elements, optional || e.optional);
            }
        }
    }

    /**
     * Whether {@code steps}, all of them, are this pattern: every pattern step that is not optional matches one step,
     * in order, and every step is matched. Null when not. When a run fits more than one way, the first fit in the order
     * the class describes wins.
     *
     * @throws TooAmbiguous when the run is still undecided after {@link #FIT_BUDGET} choices or {@link #WORK_LIMIT}
     *                      work
     */
    public Match match(List<? extends JsonNode> steps) {
        Search search = new Search(this, steps);
        Map<String, CharSequence> bound;
        try {
            if (!search.canFinish(0, 0)) return null;
            bound = seq(elements, 0, steps, 0, Collections.<String, CharSequence>emptyMap(),
                    (at, b) -> at == steps.size() ? b : null, search);
        } finally {
            LAST_WORK.set(search.used());
        }
        if (bound == null) return null;
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String p : params) if (bound.containsKey(p)) out.put(p, bound.get(p).toString());
        return new Match(out);
    }

    /** A run of steps the pattern begins with: the values its params took so far, and how many steps the step takes. */
    public static final class Prefix {
        private final Map<String, String> params;
        private final int steps;
        private final int of;

        Prefix(Map<String, String> params, int steps, int of) {
            this.params = Collections.unmodifiableMap(params);
            this.steps = steps;
            this.of = of;
        }

        /** The declared params the run gave a value so far, in declared order (as {@link Match#params}). */
        public Map<String, String> params() { return params; }

        /** The steps of the run. */
        public int steps() { return steps; }

        /** The steps the whole step would take: the run's, and the fewest that must still come. */
        public int of() { return of; }

        @Override public String toString() { return "Prefix" + params + " " + steps + " of " + of; }
    }

    /**
     * Whether {@code steps}, all of them, begin this pattern and at least one step that is not optional must still come:
     * the steps so far could become the step. Null when not: a run that goes another way, and one that is the whole
     * step already ({@link #match}). The fits are tried as {@link #match} tries them, the first that holds wins.
     *
     * @throws TooAmbiguous as {@link #match} does
     */
    public Prefix prefix(List<? extends JsonNode> steps) {
        if (steps.isEmpty()) return null;
        Search search = new Search(this, steps, true);
        if (!search.canFinish(0, 0)) return null;
        // a fit that ends with every pattern step matched is the whole step, not a beginning of it
        Map<String, CharSequence> bound = seq(elements, 0, steps, 0, Collections.<String, CharSequence>emptyMap(),
                (at, b) -> null, search);
        if (bound == null) return null;
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String p : params) if (bound.containsKey(p)) out.put(p, bound.get(p).toString());
        return new Prefix(out, steps.size(), steps.size() + search.toCome);
    }

    /** The pattern steps from {@code i} on that are not optional: what must still come after a run that ends before {@code i}. */
    private int requiredFrom(int i) {
        int n = 0;
        for (int k = i; k < flat.size(); k++) if (!flatOptional.get(k)) n++;
        return n;
    }

    @Override public String toString() { return stepId + (derived ? " (derived) " : " ") + source; }

    /**
     * The most choices one {@link #match} hands on with the rest of the match still to go: a text fitted one of the
     * ways it can fit (one with a {@code *} or a capture not bound yet before its last part), a scope item placed, or
     * an optional entry left out. A text that fits one way only (literals and bound captures, with at most a
     * {@code *} or new capture at the end, like {@code action:"type"} or {@code {p}}) is no choice and costs nothing
     * here. A run still undecided then is {@link TooAmbiguous}, not "no match". Real runs need far fewer: {@code *{p}*}
     * in a 400-character note, narrowed by a later {@code {p}}, takes about 80000 at worst. The budget is for runs
     * where every step fits alone but the captures cannot agree (several {@code *{p}*} whose values must also make up a
     * later text), where trying every combination could take minutes.
     */
    public static final int FIT_BUDGET = 100000;

    /**
     * The most work one {@link #match} does, counted in small steps: a text part placed, a character compared while
     * looking for a literal or a bound capture, a digit read for an INT capture, a capture bound. It bounds the time a
     * match takes when long texts and long bound values make each choice costly (a fraction of a second). The looks at
     * whether a pattern step can match a step alone share one more such amount, at most a tenth of it each, so a match
     * does at most twice this work however many pattern steps and steps it has.
     */
    public static final int WORK_LIMIT = 20000000;

    /** The work the last {@link #match} on this thread did, its probes' included: what the tests bound, not a clock. */
    private static final ThreadLocal<Long> LAST_WORK = new ThreadLocal<Long>();

    /** {@link #LAST_WORK}; 0 before any match on this thread. */
    static long lastWork() {
        Long w = LAST_WORK.get();
        return w == null ? 0 : w;
    }

    /**
     * A run whose match this pattern could not decide within {@link #FIT_BUDGET} or {@link #WORK_LIMIT}: neither a
     * match nor a mismatch. Folding should leave the run as it is and can say why ({@link #getMessage}).
     */
    public static final class TooAmbiguous extends RuntimeException {
        private final String stepId;

        TooAmbiguous(String stepId) {
            super("the pattern of '" + stepId + "' is too ambiguous: its captures fit the steps in more ways than a match "
                    + "tries", null, false, false);
            this.stepId = stepId;
        }

        /** The id of the step whose pattern this is. */
        public String stepId() { return stepId; }
    }

    /**
     * One match's state: the fits it may still try, and whether the rest of the pattern can still match the rest of
     * the run at all, judged step by step with every capture free (a pattern step matching its step alone, optional
     * steps taken or left out freely, a use's steps each on their own). That is looser than the match, so when it says
     * no, no fit of the steps before can help, and the search gives up at once instead of trying them all.
     */
    static final class Search {
        private final Pattern pattern;
        private final List<? extends JsonNode> steps;
        private int left;
        private int work;
        /** [pattern step][step]: null not asked yet. */
        private final Boolean[][] alone;
        /** [pattern step][step]: whether pattern steps from the first on can match steps from the second on. */
        private final Boolean[][] finish;
        /** The work all {@link #alone} probes of one match have left together; the match and its probes share it. */
        private final int[] probeWork;
        /** This search is one {@link #alone} probe: its work also comes out of {@link #probeWork}. */
        private final boolean probe;
        /**
         * A {@link Pattern#prefix} search: the run may end before the pattern does, while a pattern step that is not
         * optional is still to come.
         */
        final boolean prefix;
        /** For a prefix that fits: the pattern steps that are not optional still to come after the run. */
        int toCome;

        Search(Pattern pattern, List<? extends JsonNode> steps) {
            this(pattern, steps, false);
        }

        Search(Pattern pattern, List<? extends JsonNode> steps, boolean prefix) {
            this(pattern, steps, FIT_BUDGET, WORK_LIMIT, new int[] {WORK_LIMIT}, false, prefix);
        }

        private Search(Pattern pattern, List<? extends JsonNode> steps, int fits, int work, int[] probeWork,
                       boolean probe, boolean prefix) {
            this.pattern = pattern;
            this.steps = steps;
            this.left = fits;
            this.work = work;
            this.probeWork = probeWork;
            this.probe = probe;
            this.prefix = prefix;
            this.alone = new Boolean[pattern.flat.size()][steps.size()];
            this.finish = new Boolean[pattern.flat.size() + 1][steps.size() + 1];
        }

        void spend() {
            if (--left < 0) throw new TooAmbiguous(pattern.stepId);
        }

        /** The work done so far: this search's and its probes' together. */
        long used() {
            return (long) WORK_LIMIT - work + WORK_LIMIT - probeWork[0];
        }

        void work() {
            work(1);
        }

        void work(int amount) {
            work -= amount;
            if (probe) probeWork[0] -= amount;
            if (work < 0 || probe && probeWork[0] < 0) throw new TooAmbiguous(pattern.stepId);
        }

        /** Whether {@code one} can match the step at {@code at} and the pattern steps after it the steps after that. */
        boolean canTake(One one, int at) {
            int i = pattern.flatIndex.get(one);
            return at < steps.size() && alone(i, at) && canFinish(i + 1, at + 1);
        }

        boolean canFinish(int i, int at) {
            Boolean known = finish[i][at];
            if (known != null) return known;
            boolean can;
            if (prefix && at == steps.size()) {
                can = true;   // the rest may come later (looser: a run that is the whole step is ruled out after)
            } else if (i == pattern.flat.size()) {
                can = at == steps.size();
            } else {
                can = pattern.flatOptional.get(i) && canFinish(i + 1, at)
                        || at < steps.size() && alone(i, at) && canFinish(i + 1, at + 1);
            }
            finish[i][at] = can;
            return can;
        }

        /** A search for one {@link #alone} question: a tenth of the budgets, its work shared with the other probes. */
        private Search probe() {
            return new Search(pattern, steps, FIT_BUDGET / 10, WORK_LIMIT / 10, probeWork, true, false);
        }

        /**
         * Whether pattern step {@code i} alone, captures free, can match the step at {@code at}. On a budget of its
         * own, and out of the work all probes of the match share; when either runs out the answer is yes (looser, so
         * still never a wrong no), at once once the shared work is spent.
         */
        private boolean alone(int i, int at) {
            Boolean known = alone[i][at];
            if (known == null) {
                Map<String, CharSequence> free = Collections.emptyMap();
                if (probeWork[0] <= 0) {
                    known = true;
                } else {
                    try {
                        known = pattern.flat.get(i).node.match(steps.get(at), free, b -> b, probe()) != null;
                    } catch (TooAmbiguous e) {
                        known = true;
                    }
                }
                alone[i][at] = known;
            }
            return known;
        }
    }

    /** What to match after an element took its steps. */
    interface Then {
        Map<String, CharSequence> go(int at, Map<String, CharSequence> bound);
    }

    static Map<String, CharSequence> seq(List<Element> els, int k, List<? extends JsonNode> steps, int at,
                                         Map<String, CharSequence> bound, Then then, Search search) {
        if (k == els.size()) return then.go(at, bound);
        Element e = els.get(k);
        Then next = (j, b) -> seq(els, k + 1, steps, j, b, then, search);
        Map<String, CharSequence> found = e.take(steps, at, bound, next, search);
        if (found == null && e.optional) {
            search.spend();
            found = next.go(at, bound);
        }
        return found;
    }

    /** One entry of a pattern: a pattern step, or another step's pattern ({@code use}). */
    abstract static class Element {
        final boolean optional;

        Element(boolean optional) {
            this.optional = optional;
        }

        abstract Map<String, CharSequence> take(List<? extends JsonNode> steps, int at, Map<String, CharSequence> bound,
                                                Then next, Search search);

        /** The captures this element always binds when it matches (none for an optional one). */
        abstract void alwaysCaptured(List<String> into);
    }

    /** A pattern step: matches one step. */
    static final class One extends Element {
        final Node node;

        One(Node node, boolean optional) {
            super(optional);
            this.node = node;
        }

        @Override Map<String, CharSequence> take(List<? extends JsonNode> steps, int at, Map<String, CharSequence> bound,
                                                 Then next, Search search) {
            if (search.prefix && at == steps.size()) {
                // the run ended here: a beginning of the step while one that must come is still to come
                int toCome = search.pattern.requiredFrom(search.pattern.flatIndex.get(this));
                if (toCome == 0) return null;
                search.toCome = toCome;
                return bound;
            }
            if (!search.canTake(this, at)) return null;
            return node.match(steps.get(at), bound, b -> next.go(at + 1, b), search);
        }

        @Override void alwaysCaptured(List<String> into) {
            if (!optional) node.captures(into);
        }
    }

    /** Another step's pattern, all of it or (optional) none of it. */
    static final class Group extends Element {
        final List<Element> elements;

        Group(List<Element> elements, boolean optional) {
            super(optional);
            this.elements = elements;
        }

        @Override Map<String, CharSequence> take(List<? extends JsonNode> steps, int at, Map<String, CharSequence> bound,
                                                 Then next, Search search) {
            return seq(elements, 0, steps, at, bound, next, search);
        }

        @Override void alwaysCaptured(List<String> into) {
            if (optional) return;
            for (Element e : elements) e.alwaysCaptured(into);
        }
    }

    /** What to match after a value matched with these bindings: the final bindings, or null to try the next fit. */
    interface Rest {
        Map<String, CharSequence> go(Map<String, CharSequence> bound);
    }

    /** A pattern value: matches a value of a step (null when the step has no such field), binding captures. */
    abstract static class Node {
        /**
         * Tries each fit of {@code actual} in turn, handing its bindings to {@code rest}; the first final bindings
         * {@code rest} gives back, or null when no fit holds.
         */
        abstract Map<String, CharSequence> match(JsonNode actual, Map<String, CharSequence> bound, Rest rest,
                                                 Search search);

        abstract void captures(List<String> into);

        /** Its literal characters, plus one per number, true, false or null (see {@link Pattern#literals}). */
        abstract int literals();
    }

    /** An object: every field it names matches, the others are free. */
    static final class Fields extends Node {
        final List<Map.Entry<String, Node>> fields;

        Fields(Map<String, Node> fields) {
            this.fields = new ArrayList<Map.Entry<String, Node>>(fields.entrySet());
        }

        @Override Map<String, CharSequence> match(JsonNode actual, Map<String, CharSequence> bound, Rest rest,
                                                  Search search) {
            if (actual == null || !actual.isObject()) return null;
            return from(0, actual, bound, rest, search);
        }

        private Map<String, CharSequence> from(int i, JsonNode actual, Map<String, CharSequence> bound, Rest rest,
                                               Search search) {
            if (i == fields.size()) return rest.go(bound);
            Map.Entry<String, Node> f = fields.get(i);
            return f.getValue().match(actual.get(f.getKey()), bound, b -> from(i + 1, actual, b, rest, search), search);
        }

        @Override void captures(List<String> into) {
            for (Map.Entry<String, Node> f : fields) f.getValue().captures(into);
        }

        @Override int literals() {
            int n = 0;
            for (Map.Entry<String, Node> f : fields) n += f.getValue().literals();
            return n;
        }
    }

    /** An array: as many items, each matching in turn. */
    static final class Items extends Node {
        final List<Node> items;

        Items(List<Node> items) {
            this.items = items;
        }

        @Override Map<String, CharSequence> match(JsonNode actual, Map<String, CharSequence> bound, Rest rest,
                                                  Search search) {
            if (actual == null || !actual.isArray() || actual.size() != items.size()) return null;
            return from(0, actual, bound, rest, search);
        }

        private Map<String, CharSequence> from(int i, JsonNode actual, Map<String, CharSequence> bound, Rest rest,
                                               Search search) {
            if (i == items.size()) return rest.go(bound);
            return items.get(i).match(actual.get(i), bound, b -> from(i + 1, actual, b, rest, search), search);
        }

        @Override void captures(List<String> into) {
            for (Node n : items) n.captures(into);
        }

        @Override int literals() {
            int n = 0;
            for (Node i : items) n += i.literals();
            return n;
        }
    }

    /** A scope: its steps are found in the step's scope in order, with others before, between and after them. */
    static final class Scope extends Node {
        final List<Text> wanted;

        Scope(List<Text> wanted) {
            this.wanted = wanted;
        }

        @Override Map<String, CharSequence> match(JsonNode actual, Map<String, CharSequence> bound, Rest rest,
                                                  Search search) {
            if (actual != null && !actual.isArray()) return null;
            return from(0, actual, 0, bound, rest, search);
        }

        private Map<String, CharSequence> from(int k, JsonNode actual, int start, Map<String, CharSequence> bound,
                                               Rest rest, Search search) {
            if (k == wanted.size()) return rest.go(bound);
            if (actual == null) return null;
            for (int i = start; i < actual.size(); i++) {
                search.work();
                int at = i;
                Map<String, CharSequence> found = wanted.get(k).match(actual.get(i), bound, b -> {
                    search.spend();
                    return from(k + 1, actual, at + 1, b, rest, search);
                }, search);
                if (found != null) return found;
            }
            return null;
        }

        @Override void captures(List<String> into) {
            for (Text t : wanted) t.captures(into);
        }

        @Override int literals() {
            int n = 0;
            for (Text t : wanted) n += t.literals();
            return n;
        }
    }

    /** A number, true, false or null: the same value (a number by its value: 3 is 3.0). */
    static final class Same extends Node {
        final JsonNode value;

        Same(JsonNode value) {
            this.value = value;
        }

        @Override Map<String, CharSequence> match(JsonNode actual, Map<String, CharSequence> bound, Rest rest,
                                                  Search search) {
            return same(actual) ? rest.go(bound) : null;
        }

        private boolean same(JsonNode actual) {
            if (value.isNull()) return actual == null || actual.isNull();
            if (actual == null) return false;
            if (value.isNumber() && actual.isNumber()) return value.decimalValue().compareTo(actual.decimalValue()) == 0;
            return value.equals(actual);
        }

        @Override void captures(List<String> into) { }

        @Override int literals() { return 1; }
    }

    /** Text of literal parts, {@code *} (any text) and captures; its fits in the order the class describes. */
    static final class Text extends Node {
        /** A literal part. */
        static final class Literal {
            final String text;

            Literal(String text) {
                this.text = text;
            }
        }

        /** A {@code {param}}: some text (at least one character), or digits in int range for an INT param. */
        static final class Capture {
            final String name;
            final boolean digits;

            Capture(String name, boolean digits) {
                this.name = name;
                this.digits = digits;
            }
        }

        static final Object STAR = new Object();

        /** Literal, Capture or STAR, in order. */
        final List<Object> parts;

        Text(List<Object> parts) {
            this.parts = parts;
        }

        @Override Map<String, CharSequence> match(JsonNode actual, Map<String, CharSequence> bound, Rest rest,
                                                  Search search) {
            if (actual == null) return null;
            String s;
            if (actual.isTextual()) {
                s = actual.asText();
            } else if (loneDigits() && actual.isIntegralNumber() && actual.canConvertToInt() && actual.intValue() >= 0) {
                s = String.valueOf(actual.intValue());
            } else {
                return null;
            }
            if (!fixedFit(s, bound, search)) return null;
            return fit(0, s, 0, choice(bound), bound, rest, search);
        }

        private boolean loneDigits() {
            return parts.size() == 1 && parts.get(0) instanceof Capture && ((Capture) parts.get(0)).digits;
        }

        /**
         * Whether the fixed parts (literals, and captures {@code bound} already holds) can be in {@code s} at all: in
         * order, the first at the start when the text starts with one, the last at the end when it ends with one. A
         * text that fails this has no fit, found in one pass.
         */
        private boolean fixedFit(String s, Map<String, CharSequence> bound, Search search) {
            int at = 0;
            int lastPart = parts.size() - 1;
            for (int k = 0; k <= lastPart; k++) {
                CharSequence part = fixed(parts.get(k), bound);
                if (part == null) continue;
                if (k == 0) {
                    if (!holds(s, 0, part, search)) return false;
                } else if (k == lastPart) {
                    int end = s.length() - part.length();
                    if (end < at || !holds(s, end, part, search)) return false;
                } else {
                    int found = find(s, part, at, s.length(), search);
                    if (found < 0) return false;
                    at = found;
                }
                at += part.length();
            }
            return true;
        }

        /**
         * Whether this text can fit more than one way with these bindings: some part before the last is a {@code *}
         * or a capture not bound yet. Only then is a fit handed on a choice ({@link Search#spend}).
         */
        private boolean choice(Map<String, CharSequence> bound) {
            for (int k = 0; k < parts.size() - 1; k++) {
                Object p = parts.get(k);
                if (p == STAR || p instanceof Capture && !bound.containsKey(((Capture) p).name)) return true;
            }
            return false;
        }

        /** Whether {@code s} holds {@code part} at {@code at}; each character compared is {@link Search#work}. */
        private static boolean holds(String s, int at, CharSequence part, Search search) {
            if (at + part.length() > s.length()) return false;
            int i = 0;
            while (i < part.length() && s.charAt(at + i) == part.charAt(i)) i++;
            search.work(i + 1);
            return i == part.length();
        }

        /** The first place from {@code from} up to {@code to} where {@code part} is in {@code s}, or -1. */
        private static int find(String s, CharSequence part, int from, int to, Search search) {
            int last = Math.min(to, s.length() - part.length());
            for (int i = from; i <= last; i++) if (holds(s, i, part, search)) return i;
            return -1;
        }

        /** The text part {@code k} must be: a literal, or a capture already bound; null when it is free. */
        private static CharSequence fixed(Object part, Map<String, CharSequence> bound) {
            if (part instanceof Literal) return ((Literal) part).text;
            if (part instanceof Capture) return bound.get(((Capture) part).name);
            return null;
        }

        /**
         * Parts from {@code k} on fit {@code s} from {@code at} on, each fit tried in turn. Only ends that can work are
         * tried: a {@code *} or capture that is the last part takes the rest, and one followed by a literal (or a bound
         * capture) ends only where that text occurs. Each part placed, character compared and capture bound is
         * {@link Search#work}; a whole fit handed on is {@link Search#spend} when the text could fit more than one way
         * ({@code choice}).
         */
        private Map<String, CharSequence> fit(int k, String s, int at, boolean choice, Map<String, CharSequence> bound,
                                              Rest rest, Search search) {
            if (k == parts.size()) {
                if (at != s.length()) return null;
                if (choice) search.spend();
                return rest.go(bound);
            }
            search.work();
            Object p = parts.get(k);
            CharSequence here = fixed(p, bound);
            if (here != null) {
                return holds(s, at, here, search) ? fit(k + 1, s, at + here.length(), choice, bound, rest, search) : null;
            }
            boolean last = k + 1 == parts.size();
            CharSequence next = last ? null : fixed(parts.get(k + 1), bound);
            if (p == STAR) {
                if (last) return fit(k + 1, s, s.length(), choice, bound, rest, search);
                if (next != null) {
                    for (int end = find(s, next, at, s.length(), search); end >= 0;
                         end = find(s, next, end + 1, s.length(), search)) {
                        Map<String, CharSequence> found = fit(k + 1, s, end, choice, bound, rest, search);
                        if (found != null) return found;
                    }
                    return null;
                }
                for (int end = at; end <= s.length(); end++) {
                    Map<String, CharSequence> found = fit(k + 1, s, end, choice, bound, rest, search);
                    if (found != null) return found;
                }
                return null;
            }
            Capture c = (Capture) p;
            int longest = s.length();
            if (c.digits) {
                longest = at;
                while (longest < s.length() && isDigit(s.charAt(longest))) longest++;
                search.work(longest - at);
            }
            if (longest == at) return null;
            if (last) return longest == s.length() ? bind(c, s, at, longest, k, choice, bound, rest, search) : null;
            if (next != null) {
                for (int end = find(s, next, at + 1, longest, search); end >= 0;
                     end = find(s, next, end + 1, longest, search)) {
                    Map<String, CharSequence> found = bind(c, s, at, end, k, choice, bound, rest, search);
                    if (found != null) return found;
                }
                return null;
            }
            boolean greedy = parts.get(k + 1) == STAR;
            for (int n = 1; n <= longest - at; n++) {
                int end = greedy ? longest - n + 1 : at + n;
                Map<String, CharSequence> found = bind(c, s, at, end, k, choice, bound, rest, search);
                if (found != null) return found;
            }
            return null;
        }

        /** Capture {@code c} as {@code s[at, end)} and fit the parts after it from {@code end}. */
        private Map<String, CharSequence> bind(Capture c, String s, int at, int end, int k, boolean choice,
                                               Map<String, CharSequence> bound, Rest rest, Search search) {
            search.work(1 + bound.size());
            if (c.digits && !fitsAnInt(s, at, end, search)) return null;
            CharSequence value = new Slice(s, at, end);
            Map<String, CharSequence> b = new LinkedHashMap<String, CharSequence>(bound);
            b.put(c.name, value);
            return fit(k + 1, s, end, choice, b, rest, search);
        }

        private static boolean isDigit(char ch) {
            return ch >= '0' && ch <= '9';
        }

        /** Whether the digits {@code s[at, end)} make a number in int range (leading zeros allowed). */
        private static boolean fitsAnInt(String s, int at, int end, Search search) {
            search.work(end - at);
            long value = 0;
            for (int i = at; i < end; i++) {
                value = value * 10 + (s.charAt(i) - '0');
                if (value > Integer.MAX_VALUE) return false;
            }
            return true;
        }

        @Override void captures(List<String> into) {
            for (Object p : parts) if (p instanceof Capture) into.add(((Capture) p).name);
        }

        @Override int literals() {
            int n = 0;
            for (Object p : parts) if (p instanceof Literal) n += ((Literal) p).text.length();
            return n;
        }
    }

    /** A capture's value: part of a step's text, not copied while the match goes on (a {@link Match} copies it). */
    static final class Slice implements CharSequence {
        private final String s;
        private final int start;
        private final int end;

        Slice(String s, int start, int end) {
            this.s = s;
            this.start = start;
            this.end = end;
        }

        @Override public int length() { return end - start; }

        @Override public char charAt(int i) { return s.charAt(start + i); }

        @Override public CharSequence subSequence(int from, int to) { return new Slice(s, start + from, start + to); }

        @Override public String toString() { return s.substring(start, end); }
    }
}
