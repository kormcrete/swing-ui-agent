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
     * in order, and every step is matched. Null when not. When a run fits more than one way, the first one wins:
     * an optional step is taken before it is left out, and a capture takes as little text as it can.
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
            Map<String, String> b = node.match(steps.get(at), bound);
            return b == null ? null : next.go(at + 1, b);
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

    /** A pattern value: matches a value of a step (null when the step has no such field), binding captures. */
    abstract static class Node {
        /** The bindings with this node's captures added, or null when {@code actual} does not match. */
        abstract Map<String, String> match(JsonNode actual, Map<String, String> bound);

        abstract void captures(List<String> into);
    }

    /** An object: every field it names matches, the others are free. */
    static final class Fields extends Node {
        final Map<String, Node> fields;

        Fields(Map<String, Node> fields) {
            this.fields = fields;
        }

        @Override Map<String, String> match(JsonNode actual, Map<String, String> bound) {
            if (actual == null || !actual.isObject()) return null;
            Map<String, String> b = bound;
            for (Map.Entry<String, Node> f : fields.entrySet()) {
                b = f.getValue().match(actual.get(f.getKey()), b);
                if (b == null) return null;
            }
            return b;
        }

        @Override void captures(List<String> into) {
            for (Node n : fields.values()) n.captures(into);
        }
    }

    /** An array: as many items, each matching in turn. */
    static final class Items extends Node {
        final List<Node> items;

        Items(List<Node> items) {
            this.items = items;
        }

        @Override Map<String, String> match(JsonNode actual, Map<String, String> bound) {
            if (actual == null || !actual.isArray() || actual.size() != items.size()) return null;
            Map<String, String> b = bound;
            for (int i = 0; i < items.size(); i++) {
                b = items.get(i).match(actual.get(i), b);
                if (b == null) return null;
            }
            return b;
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

        @Override Map<String, String> match(JsonNode actual, Map<String, String> bound) {
            if (actual != null && !actual.isArray()) return null;
            return from(0, actual, 0, bound);
        }

        private Map<String, String> from(int k, JsonNode actual, int start, Map<String, String> bound) {
            if (k == wanted.size()) return bound;
            if (actual == null) return null;
            for (int i = start; i < actual.size(); i++) {
                Map<String, String> b = wanted.get(k).match(actual.get(i), bound);
                if (b == null) continue;
                Map<String, String> rest = from(k + 1, actual, i + 1, b);
                if (rest != null) return rest;
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

        @Override Map<String, String> match(JsonNode actual, Map<String, String> bound) {
            if (value.isNull()) return actual == null || actual.isNull() ? bound : null;
            if (actual == null) return null;
            if (value.isNumber() && actual.isNumber()) {
                return value.decimalValue().compareTo(actual.decimalValue()) == 0 ? bound : null;
            }
            return value.equals(actual) ? bound : null;
        }

        @Override void captures(List<String> into) { }
    }

    /** Text of literal parts, {@code *} (any text) and captures. */
    static final class Text extends Node {
        /** A literal part. */
        static final class Literal {
            final String text;

            Literal(String text) {
                this.text = text;
            }
        }

        /** A {@code {param}}: some text (at least one character), or digits for an INT param. */
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
        private final java.util.regex.Pattern fixed;
        private final String literal;

        Text(List<Object> parts) {
            this.parts = parts;
            boolean captures = false;
            boolean stars = false;
            StringBuilder lit = new StringBuilder();
            for (Object p : parts) {
                if (p instanceof Capture) captures = true;
                else if (p == STAR) stars = true;
                else lit.append(((Literal) p).text);
            }
            this.literal = captures || stars ? null : lit.toString();
            this.fixed = captures || !stars ? null : regex(Collections.<String, String>emptyMap(), new ArrayList<String>());
        }

        @Override Map<String, String> match(JsonNode actual, Map<String, String> bound) {
            String s;
            if (actual == null) return null;
            if (actual.isTextual()) {
                s = actual.asText();
            } else if (loneDigits() && actual.isIntegralNumber() && actual.canConvertToInt() && actual.intValue() >= 0) {
                s = String.valueOf(actual.intValue());
            } else {
                return null;
            }
            if (literal != null) return literal.equals(s) ? bound : null;
            if (fixed != null) return fixed.matcher(s).matches() ? bound : null;
            List<String> groups = new ArrayList<String>();
            java.util.regex.Matcher m = regex(bound, groups).matcher(s);
            if (!m.matches()) return null;
            Map<String, String> b = new LinkedHashMap<String, String>(bound);
            for (int g = 0; g < groups.size(); g++) {
                String value = m.group(g + 1);
                if (digits(groups.get(g)) && !fitsAnInt(value)) return null;
                b.put(groups.get(g), value);
            }
            return b;
        }

        private boolean loneDigits() {
            return parts.size() == 1 && parts.get(0) instanceof Capture && ((Capture) parts.get(0)).digits;
        }

        private boolean digits(String name) {
            for (Object p : parts) if (p instanceof Capture && ((Capture) p).name.equals(name)) return ((Capture) p).digits;
            return false;
        }

        private static boolean fitsAnInt(String digits) {
            try {
                Integer.parseInt(digits);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        /** The regex for this text; a capture already bound is its value, a name seen before in it a backreference. */
        private java.util.regex.Pattern regex(Map<String, String> bound, List<String> groups) {
            StringBuilder re = new StringBuilder();
            for (Object p : parts) {
                if (p == STAR) {
                    re.append(".*?");
                } else if (p instanceof Literal) {
                    re.append(java.util.regex.Pattern.quote(((Literal) p).text));
                } else {
                    Capture c = (Capture) p;
                    int seen = groups.indexOf(c.name);
                    if (bound.containsKey(c.name)) {
                        re.append(java.util.regex.Pattern.quote(bound.get(c.name)));
                    } else if (seen >= 0) {
                        re.append('\\').append(seen + 1);
                    } else {
                        groups.add(c.name);
                        re.append(c.digits ? "([0-9]+)" : "(.+?)");
                    }
                }
            }
            return java.util.regex.Pattern.compile(re.toString(), java.util.regex.Pattern.DOTALL);
        }

        @Override void captures(List<String> into) {
            for (Object p : parts) if (p instanceof Capture) into.add(((Capture) p).name);
        }
    }
}
