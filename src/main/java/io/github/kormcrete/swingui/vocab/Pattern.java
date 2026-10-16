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
 * <p>Trying fits costs: a match tries at most {@link #FIT_BUDGET} of them and then throws {@link TooAmbiguous}
 * instead of answering. Before each pattern step it asks whether the rest of the pattern can still match the rest of
 * the run at all, each pattern step on its own with its captures free (remembered per match): a run that fails on a
 * later step's literal fails at once, without trying every fit of the steps before. Beyond that there is no memo:
 * when only the same param in two places disagrees, fits are tried until the budget ends. Each fit that holds
 * nests the rest of the match in the call stack, so the depth grows with steps × fields × text parts: match a window
 * of recent steps, not a whole recording.
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
     * @throws TooAmbiguous when the run is still undecided after {@link #FIT_BUDGET} fits were tried
     */
    public Match match(List<? extends JsonNode> steps) {
        Search search = new Search(this, steps);
        if (!search.canFinish(0, 0)) return null;
        Map<String, String> bound = seq(elements, 0, steps, 0, Collections.<String, String>emptyMap(),
                (at, b) -> at == steps.size() ? b : null, search);
        if (bound == null) return null;
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String p : params) if (bound.containsKey(p)) out.put(p, bound.get(p));
        return new Match(out);
    }

    @Override public String toString() { return stepId + (derived ? " (derived) " : " ") + source; }

    /**
     * The most fits one {@link #match} tries (an optional entry taken or left out, a scope item at one place, a
     * {@code *} or a capture of one length): a run that is still undecided then is {@link TooAmbiguous}, not "no
     * match". Patterns of plain text and a few captures need far fewer. It is for a run where every step fits alone
     * but the captures cannot agree (several {@code *{p}*} whose values must also make up a later text): trying every
     * combination could take minutes.
     */
    public static final int FIT_BUDGET = 10000;

    /**
     * A run whose match this pattern could not decide within {@link #FIT_BUDGET} fits: neither a match nor a mismatch.
     * Folding should leave the run as it is and can say why ({@link #getMessage}).
     */
    public static final class TooAmbiguous extends RuntimeException {
        private final String stepId;

        TooAmbiguous(String stepId) {
            super("the pattern of '" + stepId + "' is too ambiguous: no answer within " + FIT_BUDGET + " fits", null,
                    false, false);
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
        private int left = FIT_BUDGET;
        /** [pattern step][step]: null not asked yet. */
        private final Boolean[][] alone;
        /** [pattern step][step]: whether pattern steps from the first on can match steps from the second on. */
        private final Boolean[][] finish;

        Search(Pattern pattern, List<? extends JsonNode> steps) {
            this.pattern = pattern;
            this.steps = steps;
            this.alone = new Boolean[pattern.flat.size()][steps.size()];
            this.finish = new Boolean[pattern.flat.size() + 1][steps.size() + 1];
        }

        void spend() {
            if (--left < 0) throw new TooAmbiguous(pattern.stepId);
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
            if (i == pattern.flat.size()) {
                can = at == steps.size();
            } else {
                can = pattern.flatOptional.get(i) && canFinish(i + 1, at)
                        || at < steps.size() && alone(i, at) && canFinish(i + 1, at + 1);
            }
            finish[i][at] = can;
            return can;
        }

        private boolean alone(int i, int at) {
            Boolean known = alone[i][at];
            if (known == null) {
                Map<String, String> free = Collections.emptyMap();
                known = pattern.flat.get(i).node.match(steps.get(at), free, b -> b, this) != null;
                alone[i][at] = known;
            }
            return known;
        }
    }

    /** What to match after an element took its steps. */
    interface Then {
        Map<String, String> go(int at, Map<String, String> bound);
    }

    static Map<String, String> seq(List<Element> els, int k, List<? extends JsonNode> steps, int at,
                                   Map<String, String> bound, Then then, Search search) {
        if (k == els.size()) return then.go(at, bound);
        Element e = els.get(k);
        Then next = (j, b) -> seq(els, k + 1, steps, j, b, then, search);
        Map<String, String> found = e.take(steps, at, bound, next, search);
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

        abstract Map<String, String> take(List<? extends JsonNode> steps, int at, Map<String, String> bound, Then next,
                                          Search search);

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

        @Override Map<String, String> take(List<? extends JsonNode> steps, int at, Map<String, String> bound, Then next,
                                           Search search) {
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

        @Override Map<String, String> take(List<? extends JsonNode> steps, int at, Map<String, String> bound, Then next,
                                           Search search) {
            return seq(elements, 0, steps, at, bound, next, search);
        }

        @Override void alwaysCaptured(List<String> into) {
            if (optional) return;
            for (Element e : elements) e.alwaysCaptured(into);
        }
    }

    /** What to match after a value matched with these bindings: the final bindings, or null to try the next fit. */
    interface Rest {
        Map<String, String> go(Map<String, String> bound);
    }

    /** A pattern value: matches a value of a step (null when the step has no such field), binding captures. */
    abstract static class Node {
        /**
         * Tries each fit of {@code actual} in turn, handing its bindings to {@code rest}; the first final bindings
         * {@code rest} gives back, or null when no fit holds.
         */
        abstract Map<String, String> match(JsonNode actual, Map<String, String> bound, Rest rest, Search search);

        abstract void captures(List<String> into);
    }

    /** An object: every field it names matches, the others are free. */
    static final class Fields extends Node {
        final List<Map.Entry<String, Node>> fields;

        Fields(Map<String, Node> fields) {
            this.fields = new ArrayList<Map.Entry<String, Node>>(fields.entrySet());
        }

        @Override Map<String, String> match(JsonNode actual, Map<String, String> bound, Rest rest, Search search) {
            if (actual == null || !actual.isObject()) return null;
            return from(0, actual, bound, rest, search);
        }

        private Map<String, String> from(int i, JsonNode actual, Map<String, String> bound, Rest rest, Search search) {
            if (i == fields.size()) return rest.go(bound);
            Map.Entry<String, Node> f = fields.get(i);
            return f.getValue().match(actual.get(f.getKey()), bound, b -> from(i + 1, actual, b, rest, search), search);
        }

        @Override void captures(List<String> into) {
            for (Map.Entry<String, Node> f : fields) f.getValue().captures(into);
        }
    }

    /** An array: as many items, each matching in turn. */
    static final class Items extends Node {
        final List<Node> items;

        Items(List<Node> items) {
            this.items = items;
        }

        @Override Map<String, String> match(JsonNode actual, Map<String, String> bound, Rest rest, Search search) {
            if (actual == null || !actual.isArray() || actual.size() != items.size()) return null;
            return from(0, actual, bound, rest, search);
        }

        private Map<String, String> from(int i, JsonNode actual, Map<String, String> bound, Rest rest, Search search) {
            if (i == items.size()) return rest.go(bound);
            return items.get(i).match(actual.get(i), bound, b -> from(i + 1, actual, b, rest, search), search);
        }

        @Override void captures(List<String> into) {
            for (Node n : items) n.captures(into);
        }
    }

    /** A scope: its steps are found in the step's scope in order, with others before, between and after them. */
    static final class Scope extends Node {
        final List<Text> wanted;

        Scope(List<Text> wanted) {
            this.wanted = wanted;
        }

        @Override Map<String, String> match(JsonNode actual, Map<String, String> bound, Rest rest, Search search) {
            if (actual != null && !actual.isArray()) return null;
            return from(0, actual, 0, bound, rest, search);
        }

        private Map<String, String> from(int k, JsonNode actual, int start, Map<String, String> bound, Rest rest,
                                         Search search) {
            if (k == wanted.size()) return rest.go(bound);
            if (actual == null) return null;
            for (int i = start; i < actual.size(); i++) {
                search.spend();
                int at = i;
                Map<String, String> found = wanted.get(k).match(actual.get(i), bound,
                        b -> from(k + 1, actual, at + 1, b, rest, search), search);
                if (found != null) return found;
            }
            return null;
        }

        @Override void captures(List<String> into) {
            for (Text t : wanted) t.captures(into);
        }
    }

    /** A number, true, false or null: the same value (a number by its value: 3 is 3.0). */
    static final class Same extends Node {
        final JsonNode value;

        Same(JsonNode value) {
            this.value = value;
        }

        @Override Map<String, String> match(JsonNode actual, Map<String, String> bound, Rest rest, Search search) {
            return same(actual) ? rest.go(bound) : null;
        }

        private boolean same(JsonNode actual) {
            if (value.isNull()) return actual == null || actual.isNull();
            if (actual == null) return false;
            if (value.isNumber() && actual.isNumber()) return value.decimalValue().compareTo(actual.decimalValue()) == 0;
            return value.equals(actual);
        }

        @Override void captures(List<String> into) { }
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

        @Override Map<String, String> match(JsonNode actual, Map<String, String> bound, Rest rest, Search search) {
            if (actual == null) return null;
            String s;
            if (actual.isTextual()) {
                s = actual.asText();
            } else if (loneDigits() && actual.isIntegralNumber() && actual.canConvertToInt() && actual.intValue() >= 0) {
                s = String.valueOf(actual.intValue());
            } else {
                return null;
            }
            return fit(0, s, 0, bound, rest, search);
        }

        private boolean loneDigits() {
            return parts.size() == 1 && parts.get(0) instanceof Capture && ((Capture) parts.get(0)).digits;
        }

        /** Parts from {@code k} on fit {@code s} from {@code at} on, each fit tried in turn. */
        private Map<String, String> fit(int k, String s, int at, Map<String, String> bound, Rest rest, Search search) {
            if (k == parts.size()) return at == s.length() ? rest.go(bound) : null;
            Object p = parts.get(k);
            if (p instanceof Literal) {
                String lit = ((Literal) p).text;
                return s.startsWith(lit, at) ? fit(k + 1, s, at + lit.length(), bound, rest, search) : null;
            }
            if (p == STAR) {
                for (int end = at; end <= s.length(); end++) {
                    search.spend();
                    Map<String, String> found = fit(k + 1, s, end, bound, rest, search);
                    if (found != null) return found;
                }
                return null;
            }
            Capture c = (Capture) p;
            String known = bound.get(c.name);
            if (known != null) {
                return s.startsWith(known, at) ? fit(k + 1, s, at + known.length(), bound, rest, search) : null;
            }
            int longest = at;
            while (longest < s.length() && (!c.digits || isDigit(s.charAt(longest)))) longest++;
            boolean greedy = k + 1 < parts.size() && parts.get(k + 1) == STAR;
            for (int n = 1; n <= longest - at; n++) {
                int end = greedy ? longest - n + 1 : at + n;
                String value = s.substring(at, end);
                if (c.digits && !fitsAnInt(value)) continue;
                search.spend();
                Map<String, String> b = new LinkedHashMap<String, String>(bound);
                b.put(c.name, value);
                Map<String, String> found = fit(k + 1, s, end, b, rest, search);
                if (found != null) return found;
            }
            return null;
        }

        private static boolean isDigit(char ch) {
            return ch >= '0' && ch <= '9';
        }

        private static boolean fitsAnInt(String digits) {
            try {
                Integer.parseInt(digits);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        @Override void captures(List<String> into) {
            for (Object p : parts) if (p instanceof Capture) into.add(((Capture) p).name);
        }
    }
}
