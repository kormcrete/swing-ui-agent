package io.github.kormcrete.swingui.vocab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
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
    private final List<String> params;

    Pattern(String stepId, boolean derived, List<ObjectNode> source, List<Element> elements, List<String> params) {
        this.stepId = stepId;
        this.derived = derived;
        List<ObjectNode> copies = new ArrayList<ObjectNode>();
        for (ObjectNode s : source) copies.add(s.deepCopy());
        this.source = Collections.unmodifiableList(copies);
        this.elements = Collections.unmodifiableList(new ArrayList<Element>(elements));
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

    /**
     * Whether {@code steps}, all of them, are this pattern: every pattern step that is not optional matches one step,
     * in order, and every step is matched. Null when not. When a run fits more than one way, the first fit in the order
     * the class describes wins.
     */
    public Match match(List<? extends JsonNode> steps) {
        Map<String, String> bound = seq(elements, 0, steps, 0, Collections.<String, String>emptyMap(),
                (at, b) -> at == steps.size() ? b : null);
        if (bound == null) return null;
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String p : params) if (bound.containsKey(p)) out.put(p, bound.get(p));
        return new Match(out);
    }

    @Override public String toString() { return stepId + (derived ? " (derived) " : " ") + source; }

    /** What to match after an element took its steps. */
    interface Then {
        Map<String, String> go(int at, Map<String, String> bound);
    }

    static Map<String, String> seq(List<Element> els, int k, List<? extends JsonNode> steps, int at,
                                   Map<String, String> bound, Then then) {
        if (k == els.size()) return then.go(at, bound);
        Element e = els.get(k);
        Then next = (j, b) -> seq(els, k + 1, steps, j, b, then);
        Map<String, String> found = e.take(steps, at, bound, next);
        if (found == null && e.optional) found = next.go(at, bound);
        return found;
    }

    /** One entry of a pattern: a pattern step, or another step's pattern ({@code use}). */
    abstract static class Element {
        final boolean optional;

        Element(boolean optional) {
            this.optional = optional;
        }

        abstract Map<String, String> take(List<? extends JsonNode> steps, int at, Map<String, String> bound, Then next);

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

        @Override Map<String, String> take(List<? extends JsonNode> steps, int at, Map<String, String> bound, Then next) {
            if (at >= steps.size()) return null;
            return node.match(steps.get(at), bound, b -> next.go(at + 1, b));
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

        @Override Map<String, String> take(List<? extends JsonNode> steps, int at, Map<String, String> bound, Then next) {
            return seq(elements, 0, steps, at, bound, next);
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
        abstract Map<String, String> match(JsonNode actual, Map<String, String> bound, Rest rest);

        abstract void captures(List<String> into);
    }

    /** An object: every field it names matches, the others are free. */
    static final class Fields extends Node {
        final List<Map.Entry<String, Node>> fields;

        Fields(Map<String, Node> fields) {
            this.fields = new ArrayList<Map.Entry<String, Node>>(fields.entrySet());
        }

        @Override Map<String, String> match(JsonNode actual, Map<String, String> bound, Rest rest) {
            if (actual == null || !actual.isObject()) return null;
            return from(0, actual, bound, rest);
        }

        private Map<String, String> from(int i, JsonNode actual, Map<String, String> bound, Rest rest) {
            if (i == fields.size()) return rest.go(bound);
            Map.Entry<String, Node> f = fields.get(i);
            return f.getValue().match(actual.get(f.getKey()), bound, b -> from(i + 1, actual, b, rest));
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

        @Override Map<String, String> match(JsonNode actual, Map<String, String> bound, Rest rest) {
            if (actual == null || !actual.isArray() || actual.size() != items.size()) return null;
            return from(0, actual, bound, rest);
        }

        private Map<String, String> from(int i, JsonNode actual, Map<String, String> bound, Rest rest) {
            if (i == items.size()) return rest.go(bound);
            return items.get(i).match(actual.get(i), bound, b -> from(i + 1, actual, b, rest));
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

        @Override Map<String, String> match(JsonNode actual, Map<String, String> bound, Rest rest) {
            if (actual != null && !actual.isArray()) return null;
            return from(0, actual, 0, bound, rest);
        }

        private Map<String, String> from(int k, JsonNode actual, int start, Map<String, String> bound, Rest rest) {
            if (k == wanted.size()) return rest.go(bound);
            if (actual == null) return null;
            for (int i = start; i < actual.size(); i++) {
                int at = i;
                Map<String, String> found = wanted.get(k).match(actual.get(i), bound, b -> from(k + 1, actual, at + 1, b, rest));
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

        @Override Map<String, String> match(JsonNode actual, Map<String, String> bound, Rest rest) {
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

        @Override Map<String, String> match(JsonNode actual, Map<String, String> bound, Rest rest) {
            if (actual == null) return null;
            String s;
            if (actual.isTextual()) {
                s = actual.asText();
            } else if (loneDigits() && actual.isIntegralNumber() && actual.canConvertToInt() && actual.intValue() >= 0) {
                s = String.valueOf(actual.intValue());
            } else {
                return null;
            }
            return fit(0, s, 0, bound, rest);
        }

        private boolean loneDigits() {
            return parts.size() == 1 && parts.get(0) instanceof Capture && ((Capture) parts.get(0)).digits;
        }

        /** Parts from {@code k} on fit {@code s} from {@code at} on, each fit tried in turn. */
        private Map<String, String> fit(int k, String s, int at, Map<String, String> bound, Rest rest) {
            if (k == parts.size()) return at == s.length() ? rest.go(bound) : null;
            Object p = parts.get(k);
            if (p instanceof Literal) {
                String lit = ((Literal) p).text;
                return s.startsWith(lit, at) ? fit(k + 1, s, at + lit.length(), bound, rest) : null;
            }
            if (p == STAR) {
                for (int end = at; end <= s.length(); end++) {
                    Map<String, String> found = fit(k + 1, s, end, bound, rest);
                    if (found != null) return found;
                }
                return null;
            }
            Capture c = (Capture) p;
            String known = bound.get(c.name);
            if (known != null) return s.startsWith(known, at) ? fit(k + 1, s, at + known.length(), bound, rest) : null;
            int longest = at;
            while (longest < s.length() && (!c.digits || isDigit(s.charAt(longest)))) longest++;
            boolean greedy = k + 1 < parts.size() && parts.get(k + 1) == STAR;
            for (int n = 1; n <= longest - at; n++) {
                int end = greedy ? longest - n + 1 : at + n;
                String value = s.substring(at, end);
                if (c.digits && !fitsAnInt(value)) continue;
                Map<String, String> b = new LinkedHashMap<String, String>(bound);
                b.put(c.name, value);
                Map<String, String> found = fit(k + 1, s, end, b, rest);
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
