package io.github.kormcrete.swingui.vocab;

import io.github.kormcrete.swingui.spi.DomainStep;
import io.github.kormcrete.swingui.spi.ParamType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;

/**
 * Makes the {@link Pattern}s of a plugin's domain steps: what run of recorded steps each one stands for.
 *
 * <p>A step's own pattern is its {@link DomainStep#match} when given; else its body: a declarative body's steps, or a
 * Java body's steps as traced (a {@link Function} from step to {@link Traced}, which the caller runs with a time
 * limit). Each {@link DomainStep#matchAlso} adds another pattern, for another way to do the same thing.
 *
 * <p>Text in a written pattern ({@code match}, {@code matchAlso}): {@code {name}} of a declared param captures text
 * (at least one character, as little as fits) or, for an INT param, digits in int range; {@code *} matches any text;
 * a backslash makes the star, opening brace or backslash after it plain text. A {@code {name}} of an undeclared param
 * is a problem, as are a backslash before anything else and a {@code {param}} in a field name (field names are plain
 * text). An entry {@code "optional":true} may be left out; an entry {@code {"use":"<id>"}} (and at most
 * {@code "optional"}) is the own pattern of another step of the same plugin, all of it, with that step's param names
 * (so {@code addQtyToCart} = use {@code addToCart}, use {@code setQuantity} means the same product in both).
 *
 * <p>Text in a derived pattern (the body's steps): only a {@code {name}} of a declared param is a capture; everything
 * else is plain text, {@code *}, backslashes and other {@code {name}}s too, except that a {@code tab:} or
 * {@code section:} scope step ending in {@code *} matches a title prefix, as the driver resolves it
 * ({@code ui.tab("New order*")}). A Java body that writes a declared {@code {name}} itself cannot be told from the
 * param, but its trace then differs between values and has no steps. A derived pattern a step cannot have (the body
 * cannot be traced, makes no steps, misses a required param, or puts a param in a field name) is no problem: the
 * step stays, without that pattern, and {@link Compiled#why} says why.
 *
 * <p>Every pattern must capture each required param; one whose entries are all optional, or that is empty, is a
 * problem. A step with a problem is to be dropped.
 */
public final class PatternCompiler {

    /** The fields a pattern never compares: how a step ran, not what it did. */
    static final Set<String> IGNORED = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList("timeout", "via", "fragile", "nth", "window")));

    private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\{([A-Za-z]\\w*)\\}");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A Java body's traced steps (with {@code {param}} placeholders), or why it has none. */
    public static final class Traced {
        final List<ObjectNode> steps;
        final String why;

        private Traced(List<ObjectNode> steps, String why) {
            this.steps = steps;
            this.why = why;
        }

        public static Traced steps(List<ObjectNode> steps) {
            return new Traced(new ArrayList<ObjectNode>(steps), null);
        }

        public static Traced none(String why) {
            return new Traced(null, why);
        }
    }

    /** One step's patterns, or its problems. */
    public static final class Compiled {
        private final DomainStep step;
        private final List<Pattern> patterns;
        private final String why;
        private final List<String> problems;

        Compiled(DomainStep step, List<Pattern> patterns, String why, List<String> problems) {
            this.step = step;
            this.patterns = Collections.unmodifiableList(new ArrayList<Pattern>(patterns));
            this.why = why;
            this.problems = Collections.unmodifiableList(new ArrayList<String>(problems));
        }

        public DomainStep step() { return step; }

        /** Its own pattern first (when it has one), then one per {@code matchAlso}; empty when it has problems. */
        public List<Pattern> patterns() { return patterns; }

        /** Why the step has no own pattern from its body ({@code match} not given, body not derivable); else null. */
        public String why() { return why; }

        /** What is wrong with its written patterns, e.g. "match entry 1 has no action"; the step is to be dropped. */
        public List<String> problems() { return problems; }
    }

    private final Map<String, DomainStep> byId = new LinkedHashMap<String, DomainStep>();
    private final Function<DomainStep, Traced> tracer;
    private final Map<String, Own> owns = new HashMap<String, Own>();
    private final Map<String, List<Entry>> written = new HashMap<String, List<Entry>>();

    private PatternCompiler(List<DomainStep> steps, Function<DomainStep, Traced> tracer) {
        for (DomainStep s : steps) byId.put(s.id(), s);
        this.tracer = tracer;
    }

    /**
     * The patterns of {@code steps}, one plugin's vocabulary with unique ids whose {@code match}, {@code matchAlso} and
     * {@code steps} entries are JSON objects: one {@link Compiled} per step, in order. {@code tracer} is asked once per
     * Java body that has no {@code match}.
     */
    public static List<Compiled> compile(List<DomainStep> steps, Function<DomainStep, Traced> tracer) {
        PatternCompiler c = new PatternCompiler(steps, tracer);
        List<Compiled> out = new ArrayList<Compiled>();
        for (DomainStep s : steps) out.add(c.compiled(s));
        return out;
    }

    // ---- a step's patterns

    private Compiled compiled(DomainStep step) {
        Set<String> problems = new LinkedHashSet<String>();
        Own main = own(step);
        problems.addAll(main.problems);
        List<Pattern> patterns = new ArrayList<Pattern>();
        if (main.pattern != null) patterns.add(main.pattern);
        for (int k = 0; k < step.matchAlso().size(); k++) {
            String label = "matchAlso " + (k + 1);
            List<String> also = new ArrayList<String>();
            Pattern p = writtenPattern(step, label, step.matchAlso().get(k), false, also);
            problems.addAll(also);
            if (p != null) patterns.add(p);
        }
        if (!problems.isEmpty()) {
            return new Compiled(step, Collections.<Pattern>emptyList(), main.why, new ArrayList<String>(problems));
        }
        return new Compiled(step, patterns, main.why, Collections.<String>emptyList());
    }

    /** A step's own pattern (written or derived), or why it has none, or its problems. */
    private static final class Own {
        final Pattern pattern;
        final String why;
        final List<String> problems;

        Own(Pattern pattern, String why, List<String> problems) {
            this.pattern = pattern;
            this.why = why;
            this.problems = problems;
        }
    }

    private Own own(DomainStep step) {
        Own known = owns.get(step.id());
        if (known != null) return known;
        Own made;
        if (!step.match().isEmpty()) {
            List<String> problems = new ArrayList<String>();
            Pattern p = writtenPattern(step, "match", step.match(), true, problems);
            made = new Own(problems.isEmpty() ? p : null, null, problems);
        } else {
            made = derived(step);
        }
        owns.put(step.id(), made);
        return made;
    }

    private Own derived(DomainStep step) {
        List<ObjectNode> steps;
        if (step.body() == null) {
            steps = new ArrayList<ObjectNode>();
            for (String json : step.steps()) steps.add(object(json));
        } else {
            Traced t = tracer.apply(step);
            if (t.why != null) return new Own(null, t.why, Collections.<String>emptyList());
            steps = t.steps;
        }
        if (steps.isEmpty()) return new Own(null, "the body makes no steps", Collections.<String>emptyList());
        Map<String, ParamType> declared = declared(step);
        List<Pattern.Element> elements = new ArrayList<Pattern.Element>();
        try {
            for (ObjectNode s : steps) elements.add(new Pattern.One(derivedNode(s, declared, false, false), false));
        } catch (NotDerivable e) {
            return new Own(null, e.getMessage(), Collections.<String>emptyList());
        }
        String missing = missing(step, elements);
        if (missing != null) return new Own(null, "the pattern captures no {" + missing + "}, a required param",
                Collections.<String>emptyList());
        return new Own(new Pattern(step.id(), true, steps, elements, new ArrayList<String>(declared.keySet())), null,
                Collections.<String>emptyList());
    }

    /** A {@code match} ({@code own}) or {@code matchAlso} pattern; null with {@code problems} when it has any. */
    private Pattern writtenPattern(DomainStep step, String label, List<String> json, boolean own, List<String> problems) {
        if (json.isEmpty()) {
            problems.add(label + " is empty");
            return null;
        }
        Map<String, ParamType> declared = declared(step);
        List<ObjectNode> source = new ArrayList<ObjectNode>();
        List<Pattern.Element> elements = new ArrayList<Pattern.Element>();
        boolean allOptional = true;
        for (int i = 0; i < json.size(); i++) {
            String where = label + " entry " + (i + 1);
            ObjectNode entry = object(json.get(i));
            source.add(entry);
            Pattern.Element e = writtenEntry(step, where, entry, declared, own, problems);
            if (e != null) {
                elements.add(e);
                allOptional &= e.optional;
            }
        }
        if (!problems.isEmpty()) return null;
        if (allOptional) {
            problems.add(label + ": every entry is optional, so it would match no steps");
            return null;
        }
        String missing = missing(step, elements);
        if (missing != null) {
            problems.add(label + " captures no {" + missing + "}, a required param");
            return null;
        }
        return new Pattern(step.id(), false, source, elements, new ArrayList<String>(declared.keySet()));
    }

    private Pattern.Element writtenEntry(DomainStep step, String where, ObjectNode entry, Map<String, ParamType> declared,
                                         boolean own, List<String> problems) {
        JsonNode optional = entry.get("optional");
        boolean isOptional = false;
        if (optional != null) {
            if (!optional.isBoolean()) {
                problems.add(where + ": optional is true or false");
                return null;
            }
            isOptional = optional.booleanValue();
        }
        if (entry.has("use")) return use(step, where, entry, isOptional, own, problems);
        if (!entry.has("action")) {
            problems.add(where + " has no action");
            return null;
        }
        ObjectNode rest = entry.deepCopy();
        rest.remove("optional");
        int before = problems.size();
        Pattern.Node node = writtenNode(rest, declared, false, where, problems);
        return problems.size() == before ? new Pattern.One(node, isOptional) : null;
    }

    /**
     * A {@code {"use":"<id>"}} entry: the other step's own pattern as a group. Only an entry of {@code step}'s own pattern
     * can lead back to it: a matchAlso entry is made after the own pattern, which nothing uses in turn.
     */
    private Pattern.Element use(DomainStep step, String where, ObjectNode entry, boolean optional, boolean own,
                                List<String> problems) {
        for (Iterator<String> it = entry.fieldNames(); it.hasNext(); ) {
            String f = it.next();
            if (!f.equals("use") && !f.equals("optional")) {
                problems.add(where + ": a use entry holds only use and optional");
                return null;
            }
        }
        JsonNode id = entry.get("use");
        if (!id.isTextual()) {
            problems.add(where + ": use names a step id");
            return null;
        }
        DomainStep used = byId.get(id.asText());
        if (used == null) {
            problems.add(where + " uses '" + id.asText() + "', which is no step of this plugin");
            return null;
        }
        List<String> circle = own ? circle(step, used) : null;
        if (circle != null) {
            problems.add(where + " uses '" + used.id() + "' in a circle: " + String.join(" → ", circle));
            return null;
        }
        Own theirs = own(used);
        if (!theirs.problems.isEmpty()) {
            problems.add(where + " uses '" + used.id() + "', which has problems of its own");
            return null;
        }
        if (theirs.pattern == null) {
            problems.add(where + " uses '" + used.id() + "', which has no pattern (" + theirs.why + ")");
            return null;
        }
        return new Pattern.Group(theirs.pattern.elements(), optional);
    }

    // ---- uses that come back

    /** {@code step}, {@code used}, ... back to {@code step} when {@code used}'s uses lead back to it; else null. */
    private List<String> circle(DomainStep step, DomainStep used) {
        Map<String, String> cameFrom = new HashMap<String, String>();
        Deque<String> queue = new ArrayDeque<String>();
        queue.add(used.id());
        cameFrom.put(used.id(), null);
        while (!queue.isEmpty()) {
            String at = queue.poll();
            if (at.equals(step.id())) {
                List<String> path = new ArrayList<String>();
                for (String s = at; s != null; s = cameFrom.get(s)) path.add(0, s);
                path.add(0, step.id());
                return path;
            }
            for (String next : uses(byId.get(at))) {
                if (!cameFrom.containsKey(next)) {
                    cameFrom.put(next, at);
                    queue.add(next);
                }
            }
        }
        return null;
    }

    /** The ids a step's own written pattern uses (a use names its own pattern only, so matchAlso entries do not count). */
    private List<String> uses(DomainStep step) {
        List<String> ids = new ArrayList<String>();
        if (step == null) return ids;
        for (Entry e : entries(step)) if (e.use != null && byId.containsKey(e.use)) ids.add(e.use);
        return ids;
    }

    private static final class Entry {
        final String use;

        Entry(String use) {
            this.use = use;
        }
    }

    private List<Entry> entries(DomainStep step) {
        List<Entry> known = written.get(step.id());
        if (known != null) return known;
        List<Entry> out = new ArrayList<Entry>();
        for (String json : step.match()) {
            JsonNode use = object(json).get("use");
            out.add(new Entry(use != null && use.isTextual() ? use.asText() : null));
        }
        written.put(step.id(), out);
        return out;
    }

    // ---- nodes

    /** Thrown inside a derived pattern that cannot be one; its message is the why. */
    private static final class NotDerivable extends RuntimeException {
        NotDerivable(String why) {
            super(why, null, false, false);
        }
    }

    private static Pattern.Node derivedNode(JsonNode value, Map<String, ParamType> declared, boolean inRow, boolean scope) {
        if (value.isObject()) {
            Map<String, Pattern.Node> fields = new LinkedHashMap<String, Pattern.Node>();
            for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> f = it.next();
                String key = f.getKey();
                if (!inRow && IGNORED.contains(key)) continue;
                if (namesAParam(key, declared)) {
                    throw new NotDerivable("a param stands in a field name ('" + key + "'), which a pattern does not match");
                }
                JsonNode v = f.getValue();
                if (!inRow && key.equals("scope") && v.isArray()) {
                    List<Pattern.Text> wanted = new ArrayList<Pattern.Text>();
                    for (JsonNode s : v) {
                        if (!s.isTextual()) throw new NotDerivable("a scope holds something other than text: " + v);
                        wanted.add(derivedText(s.asText(), declared, true));
                    }
                    fields.put(key, new Pattern.Scope(wanted));
                } else {
                    fields.put(key, derivedNode(v, declared, !inRow && key.equals("row"), false));
                }
            }
            return new Pattern.Fields(fields);
        }
        if (value.isArray()) {
            List<Pattern.Node> items = new ArrayList<Pattern.Node>();
            for (JsonNode v : value) items.add(derivedNode(v, declared, false, false));
            return new Pattern.Items(items);
        }
        if (value.isTextual()) return derivedText(value.asText(), declared, scope);
        return new Pattern.Same(value);
    }

    /** Derived text: a declared {@code {name}} captures, the rest is plain; a tab/section scope step may end in a glob. */
    private static Pattern.Text derivedText(String s, Map<String, ParamType> declared, boolean scopeStep) {
        boolean glob = scopeStep && s.endsWith("*") && (s.startsWith("tab:") || s.startsWith("section:"));
        String text = glob ? s.substring(0, s.length() - 1) : s;
        List<Object> parts = new ArrayList<Object>();
        StringBuilder lit = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(text);
        int at = 0;
        while (m.find()) {
            ParamType type = declared.get(m.group(1));
            if (type == null) continue;
            lit.append(text, at, m.start());
            flush(lit, parts);
            parts.add(new Pattern.Text.Capture(m.group(1), type == ParamType.INT));
            at = m.end();
        }
        lit.append(text.substring(at));
        flush(lit, parts);
        if (glob) parts.add(Pattern.Text.STAR);
        return new Pattern.Text(parts);
    }

    private static Pattern.Node writtenNode(JsonNode value, Map<String, ParamType> declared, boolean inRow, String where,
                                            List<String> problems) {
        if (value.isObject()) {
            Map<String, Pattern.Node> fields = new LinkedHashMap<String, Pattern.Node>();
            for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> f = it.next();
                String key = f.getKey();
                if (!inRow && IGNORED.contains(key)) continue;
                if (namesAParam(key, declared)) {
                    problems.add(where + ": a field name holds a {param}: '" + key + "'");
                    continue;
                }
                JsonNode v = f.getValue();
                if (!inRow && key.equals("scope") && v.isArray()) {
                    List<Pattern.Text> wanted = new ArrayList<Pattern.Text>();
                    for (JsonNode s : v) {
                        if (!s.isTextual()) {
                            problems.add(where + ": a scope holds only text");
                            break;
                        }
                        wanted.add(writtenText(s.asText(), declared, where, problems));
                    }
                    fields.put(key, new Pattern.Scope(wanted));
                } else {
                    fields.put(key, writtenNode(v, declared, !inRow && key.equals("row"), where, problems));
                }
            }
            return new Pattern.Fields(fields);
        }
        if (value.isArray()) {
            List<Pattern.Node> items = new ArrayList<Pattern.Node>();
            for (JsonNode v : value) items.add(writtenNode(v, declared, false, where, problems));
            return new Pattern.Items(items);
        }
        if (value.isTextual()) return writtenText(value.asText(), declared, where, problems);
        return new Pattern.Same(value);
    }

    /**
     * Written text: {@code {param}} captures, {@code *} globs; a backslash makes the next star, opening brace or
     * backslash text.
     */
    private static Pattern.Text writtenText(String s, Map<String, ParamType> declared, String where, List<String> problems) {
        List<Object> parts = new ArrayList<Object>();
        StringBuilder lit = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(s);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                char next = i + 1 < s.length() ? s.charAt(i + 1) : 0;
                if (next != '*' && next != '{' && next != '\\') {
                    problems.add(where + ": a \\ makes only *, { or \\ text: '" + s + "'");
                    return new Pattern.Text(parts);
                }
                lit.append(next);
                i++;
            } else if (c == '*') {
                flush(lit, parts);
                parts.add(Pattern.Text.STAR);
            } else if (c == '{' && m.find(i) && m.start() == i) {
                String name = m.group(1);
                ParamType type = declared.get(name);
                if (type == null) {
                    problems.add(where + " names {" + name + "}, which is not a declared param");
                    lit.append(m.group());
                } else {
                    flush(lit, parts);
                    parts.add(new Pattern.Text.Capture(name, type == ParamType.INT));
                }
                i = m.end() - 1;
            } else {
                lit.append(c);
            }
        }
        flush(lit, parts);
        return new Pattern.Text(parts);
    }

    private static void flush(StringBuilder lit, List<Object> parts) {
        if (lit.length() == 0) return;
        parts.add(new Pattern.Text.Literal(lit.toString()));
        lit.setLength(0);
    }

    private static boolean namesAParam(String key, Map<String, ParamType> declared) {
        Matcher m = PLACEHOLDER.matcher(key);
        while (m.find()) if (declared.containsKey(m.group(1))) return true;
        return false;
    }

    // ---- helpers

    /** The first required param the pattern does not always capture; null when it captures them all. */
    private static String missing(DomainStep step, List<Pattern.Element> elements) {
        List<String> captured = new ArrayList<String>();
        for (Pattern.Element e : elements) e.alwaysCaptured(captured);
        for (DomainStep.Param p : step.params()) {
            if (!p.optional() && !captured.contains(p.name())) return p.name();
        }
        return null;
    }

    private static Map<String, ParamType> declared(DomainStep step) {
        Map<String, ParamType> out = new LinkedHashMap<String, ParamType>();
        for (DomainStep.Param p : step.params()) out.put(p.name(), p.type());
        return out;
    }

    /** {@code json}, checked to be an object before (by the vocabulary check). */
    private static ObjectNode object(String json) {
        try {
            return (ObjectNode) JSON.readTree(json);
        } catch (JsonProcessingException | ClassCastException e) {
            throw new IllegalArgumentException("not a JSON object: " + json, e);
        }
    }
}
