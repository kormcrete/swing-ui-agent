package io.github.kormcrete.swingui.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.outcome.Outcomes;
import io.github.kormcrete.swingui.flow.Action;
import io.github.kormcrete.swingui.flow.DoAction;
import io.github.kormcrete.swingui.flow.FlowJson;
import io.github.kormcrete.swingui.flow.refs.Refs;
import io.github.kormcrete.swingui.flow.verb.MenuVerb;
import io.github.kormcrete.swingui.flow.verb.RememberVerb;
import io.github.kormcrete.swingui.flow.verb.VerbAction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The recorder's automatic links: a value in a new step that an earlier step made is written as a reference to it
 * ({@code ${copy.rows.Orders.added[0].Order}}), so a replay uses what that step makes then. Strict on purpose: only a
 * value that was new in that step's outcome (its value index), distinctive (4 characters or more, no yes/no/true/false,
 * no integer below 1000), not the earlier step's own input, matched whole, or whole inside typed text. A remembered
 * value and a text made unique per run are linked alike. Several sources: a field, row, item or remembered value beats
 * a text or a title; then the most recent step wins.
 */
final class Linker {

    private Linker() {
    }

    /** The step as linked, the value each reference stood for, and the records it links to. */
    static final class Linked {
        final Action action;
        /** Each reference written, with the value it stands for now (the recorded value). */
        final Map<String, String> seen;
        /** The record ids of the earlier steps the action now refers to. */
        final Set<Integer> sources;
        /** The id each source without one gets, by record id; a remember step is named by its name already. */
        final Map<Integer, String> ids;

        Linked(Action action, Map<String, String> seen, Set<Integer> sources, Map<Integer, String> ids) {
            this.action = action;
            this.seen = Collections.unmodifiableMap(seen);
            this.sources = Collections.unmodifiableSet(sources);
            this.ids = Collections.unmodifiableMap(ids);
        }

        /** Whether anything was linked. */
        boolean linked() {
            return !seen.isEmpty();
        }
    }

    /** Roles whose name is a caption (a label beside a field, a table's name), never a value the app made. */
    private static final Set<String> CAPTIONED = new HashSet<String>(Arrays.asList(
            "text", "password", "combo", "list", "table", "tree", "tabs", "menubar", "menu", "section"));
    private static final Set<String> WORDS = new HashSet<String>(Arrays.asList("yes", "no", "true", "false"));
    private static final Pattern INTEGER = Pattern.compile("[-+]?\\d+");

    /** Links the values of {@code a} (strings escaped as the recording keeps them) to what the steps {@code earlier} made. */
    static Linked link(Action a, List<RecordedStep> earlier) {
        Work w = new Work(earlier);
        if (w.best.isEmpty()) return w.none(a);
        ObjectNode tree = FlowJson.mapper().valueToTree(a);
        String kind = tree.path("action").asText();
        if (a instanceof VerbAction) {
            for (String field : ((VerbAction) a).targets().keySet()) {
                if (tree.get(field) instanceof ObjectNode) w.locator((ObjectNode) tree.get(field));
            }
        }
        switch (kind) {
            case "type":
                w.typed(tree, "text");
                break;
            case "select":
                w.whole(tree, "item");
                w.wholeEach(tree.get("items"));
                break;
            case "pick":
                w.wholeEach(tree.get("path"));
                break;
            case "expect":
                w.whole(tree, "text");
                w.whole(tree, "value");
                JsonNode cell = tree.get("cell");
                if (cell instanceof ObjectNode) {
                    w.whole((ObjectNode) cell, "expected");
                    JsonNode target = tree.get("target");
                    w.row(target == null ? null : target.get("name"), cell.get("row"));
                }
                break;
            case "do":
                w.wholeEach(tree.get("params"));
                break;
            default:
                break;
        }
        if (w.matches.isEmpty()) return w.none(a);
        return w.finish(tree);
    }

    // ------------------------------------------------------------------ candidates

    /** A value an earlier step made, and where it sits. */
    private static final class Candidate {
        final String value;
        /** The step that made it (for a text made unique per run: the step that typed it, which needs no id). */
        final RecordedStep source;
        /** The path after the step's id ({@code .fields.Number}), empty for a remembered value. */
        final String path;
        /** For a text made unique per run: its form with the references, as written. */
        final String template;
        /** 0: a field, row, item, remembered value or a text unique per run; 1: a text, a title, anything else. */
        final int kind;
        /** 0 for the most recent step, counting up. */
        final int age;

        Candidate(String value, RecordedStep source, String path, String template, int kind, int age) {
            this.value = value;
            this.source = source;
            this.path = path;
            this.template = template;
            this.kind = kind;
            this.age = age;
        }
    }

    /** One link made: the text node to write (an object field or an array slot) and its pieces. */
    private static final class Match {
        final JsonNode parent;
        final String field;
        final int index;
        /** Fixed text (already escaped) and candidates, in order. */
        final List<Object> pieces;

        Match(JsonNode parent, String field, int index, List<Object> pieces) {
            this.parent = parent;
            this.field = field;
            this.index = index;
            this.pieces = pieces;
        }
    }

    private static final class Work {
        final List<RecordedStep> earlier;
        /** The best candidate for each value, best first. */
        final Map<String, Candidate> best = new LinkedHashMap<String, Candidate>();
        final List<Match> matches = new ArrayList<Match>();

        Work(List<RecordedStep> earlier) {
            this.earlier = earlier;
            List<Candidate> all = new ArrayList<Candidate>();
            for (int i = earlier.size() - 1, age = 0; i >= 0; i--, age++) candidates(earlier.get(i), age, all);
            // stable: within a kind, the most recent step first, and a step's own order (its value index's) after that
            Collections.sort(all, new Comparator<Candidate>() {
                @Override public int compare(Candidate x, Candidate y) {
                    return x.kind != y.kind ? x.kind - y.kind : x.age - y.age;
                }
            });
            for (Candidate c : all) if (!best.containsKey(c.value)) best.put(c.value, c);
        }

        private static void candidates(RecordedStep s, int age, List<Candidate> out) {
            if (s.action instanceof RememberVerb) {
                String as = ((RememberVerb) s.action).as;
                if (as != null) {
                    for (String v : s.newValues.keySet()) if (distinctive(v)) out.add(new Candidate(v, s, "", null, 0, age));
                }
                return;
            }
            Set<String> own = ownValues(s);
            for (Map.Entry<String, String> e : s.newValues.entrySet()) {
                String v = e.getKey();
                if (!distinctive(v) || own.contains(v)) continue;
                String p = e.getValue();
                int kind = p.startsWith(".fields") || p.startsWith(".rows") || p.startsWith(".items") ? 0 : 1;
                out.add(new Candidate(v, s, p, null, kind, age));
            }
            composed(s, age, out);
        }

        /** The texts of the step made unique per run (references to {@code run} only), as they were when it ran. */
        private static void composed(final RecordedStep s, final int age, final List<Candidate> out) {
            if (s.linkSeen.isEmpty()) return;
            Refs.mapValues(FlowJson.mapper().valueToTree(s.action), text -> {
                List<Refs.Ref> refs;
                try {
                    refs = Refs.scan(text);
                } catch (IllegalArgumentException malformed) {
                    return text;
                }
                if (refs.isEmpty()) return text;
                for (Refs.Ref r : refs) {
                    if (!"run".equals(r.id) || !s.linkSeen.containsKey(r.source)) return text;
                }
                String value = fill(text, refs, s.linkSeen);
                if (distinctive(value)) out.add(new Candidate(value, s, null, text, 0, age));
                return text;
            });
        }

        /**
         * The value strings of a step's own action (what it typed, picked, named), with its references as they stood
         * when it ran: never what the app made.
         */
        private static Set<String> ownValues(final RecordedStep s) {
            final Set<String> own = new HashSet<String>(s.linkSeen.values());
            Refs.mapValues(FlowJson.mapper().valueToTree(s.action), text -> {
                List<Refs.Ref> refs;
                try {
                    refs = Refs.scan(text);
                } catch (IllegalArgumentException malformed) {
                    return text;
                }
                boolean known = true;
                for (Refs.Ref r : refs) known &= s.linkSeen.containsKey(r.source);
                own.add(known ? fill(text, refs, s.linkSeen) : Refs.unescape(text));
                return text;
            });
            return own;
        }

        Linked none(Action a) {
            return new Linked(a, new LinkedHashMap<String, String>(), new HashSet<Integer>(),
                    new LinkedHashMap<Integer, String>());
        }

        // -------------------------------------------------------------- where values are

        void locator(ObjectNode l) {
            JsonNode role = l.get("role");
            if (role == null || !CAPTIONED.contains(role.asText())) whole(l, "name");
            wholeEach(l.get("item"));
            row(l.get("name"), l.get("row"));
        }

        /**
         * The keys of a row locator. A row an earlier step added (in its outcome, under the table {@code name}) is linked
         * by that row: each key column that holds a new value, the others exact; when it holds none (a twin of another
         * row), the row stays exact. Any other row has its key values linked one by one.
         */
        void row(JsonNode name, JsonNode row) {
            if (!(row instanceof ObjectNode)) return;
            ObjectNode keys = (ObjectNode) row;
            String table = name != null && name.isTextual() && !hasRefs(name.textValue())
                    ? Refs.unescape(name.textValue()) : null;
            if (table != null && addedRow(table, keys)) return;
            for (Iterator<String> it = keys.fieldNames(); it.hasNext(); ) whole(keys, it.next());
        }

        private boolean addedRow(String table, ObjectNode keys) {
            for (int i = earlier.size() - 1; i >= 0; i--) {
                RecordedStep s = earlier.get(i);
                if (s.outcome == null) continue;
                JsonNode added = s.outcome.path("rows").path(table).path("added");
                for (int k = 0; k < added.size(); k++) {
                    if (!sameRow(keys, added.get(k))) continue;
                    Set<String> own = ownValues(s);
                    for (Iterator<Map.Entry<String, JsonNode>> it = keys.fields(); it.hasNext(); ) {
                        Map.Entry<String, JsonNode> e = it.next();
                        String v = Refs.unescape(e.getValue().textValue());
                        if (!s.newValues.containsKey(v) || !distinctive(v) || own.contains(v)) continue;
                        String path = ".rows" + Outcomes.key(table) + ".added[" + k + "]" + Outcomes.key(e.getKey());
                        matches.add(new Match(keys, e.getKey(), -1, Collections.<Object>singletonList(
                                new Candidate(v, s, path, null, 0, 0))));
                    }
                    return true;
                }
            }
            return false;
        }

        /** Every key of the row locator names the same text as the added row's column. */
        private static boolean sameRow(ObjectNode keys, JsonNode added) {
            if (keys.size() == 0) return false;
            for (Iterator<Map.Entry<String, JsonNode>> it = keys.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode cell = added.get(e.getKey());
                if (!e.getValue().isTextual() || hasRefs(e.getValue().textValue()) || cell == null || !cell.isTextual()
                        || !cell.textValue().equals(Refs.unescape(e.getValue().textValue()))) {
                    return false;
                }
            }
            return true;
        }

        /** Each text of an array or object (a pick's path, a row's values, a do step's params). */
        void wholeEach(JsonNode values) {
            if (values instanceof ArrayNode) {
                for (int i = 0; i < values.size(); i++) {
                    Candidate c = wholeMatch(values.get(i));
                    if (c != null) matches.add(new Match(values, null, i, Collections.<Object>singletonList(c)));
                }
            } else if (values instanceof ObjectNode) {
                for (Iterator<String> it = values.fieldNames(); it.hasNext(); ) whole((ObjectNode) values, it.next());
            }
        }

        void whole(ObjectNode parent, String field) {
            Candidate c = wholeMatch(parent.get(field));
            if (c != null) matches.add(new Match(parent, field, -1, Collections.<Object>singletonList(c)));
        }

        private Candidate wholeMatch(JsonNode node) {
            if (node == null || !node.isTextual() || hasRefs(node.textValue())) return null;
            return best.get(Refs.unescape(node.textValue()));
        }

        /** Typed text: linked whole, else each candidate found whole inside it (fixed text around it). */
        void typed(ObjectNode parent, String field) {
            JsonNode node = parent.get(field);
            if (node == null || !node.isTextual() || hasRefs(node.textValue())) return;
            String raw = Refs.unescape(node.textValue());
            Candidate all = best.get(raw);
            if (all != null) {
                matches.add(new Match(parent, field, -1, Collections.<Object>singletonList(all)));
                return;
            }
            TreeMap<Integer, Candidate> at = new TreeMap<Integer, Candidate>();
            for (Candidate c : best.values()) {
                for (int i = raw.indexOf(c.value); i >= 0; i = raw.indexOf(c.value, i + 1)) {
                    if (fits(raw, i, c.value, at)) at.put(i, c);
                }
            }
            if (at.isEmpty()) return;
            List<Object> pieces = new ArrayList<Object>();
            int last = 0;
            for (Map.Entry<Integer, Candidate> e : at.entrySet()) {
                if (e.getKey() > last) pieces.add(Refs.escape(raw.substring(last, e.getKey())));
                pieces.add(e.getValue());
                last = e.getKey() + e.getValue().value.length();
            }
            if (last < raw.length()) pieces.add(Refs.escape(raw.substring(last)));
            matches.add(new Match(parent, field, -1, pieces));
        }

        /**
         * Whether {@code value} at {@code i} in {@code raw} is whole (no letter or digit glued to it), clear of the places
         * taken already, and not right after a {@code $} (which a reference cannot follow).
         */
        private static boolean fits(String raw, int i, String value, TreeMap<Integer, Candidate> at) {
            int end = i + value.length();
            if (i > 0 && glued(raw.charAt(i - 1), value.charAt(0))) return false;
            if (end < raw.length() && glued(raw.charAt(end), value.charAt(value.length() - 1))) return false;
            if (i > 0 && raw.charAt(i - 1) == '$') return false;
            Map.Entry<Integer, Candidate> before = at.floorEntry(i);
            if (before != null && before.getKey() + before.getValue().value.length() > i) return false;
            Map.Entry<Integer, Candidate> after = at.ceilingEntry(i);
            return after == null || after.getKey() >= end;
        }

        private static boolean glued(char outside, char edge) {
            return Character.isLetterOrDigit(outside) && Character.isLetterOrDigit(edge);
        }

        // -------------------------------------------------------------- writing

        Linked finish(ObjectNode tree) {
            Map<Integer, String> ids = ids();
            Map<String, String> seen = new LinkedHashMap<String, String>();
            Set<Integer> sources = new java.util.TreeSet<Integer>();
            for (Match m : matches) {
                StringBuilder text = new StringBuilder();
                for (Object p : m.pieces) {
                    if (p instanceof String) {
                        text.append((String) p);
                        continue;
                    }
                    Candidate c = (Candidate) p;
                    if (c.template != null) {
                        text.append(c.template);
                        for (Refs.Ref r : Refs.scan(c.template)) seen.put(r.source, c.source.linkSeen.get(r.source));
                        continue;
                    }
                    String ref = "${" + idOf(c.source, ids) + c.path + "}";
                    text.append(ref);
                    seen.put(ref, c.value);
                    sources.add(c.source.id);
                }
                if (m.field != null) ((ObjectNode) m.parent).set(m.field, TextNode.valueOf(text.toString()));
                else ((ArrayNode) m.parent).set(m.index, TextNode.valueOf(text.toString()));
            }
            try {
                return new Linked(FlowJson.mapper().treeToValue(tree, Action.class), seen, sources, ids);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("a linked step does not read back: " + e.getOriginalMessage(), e);
            }
        }

        /** The id each source without one gets, in the order of the steps. */
        private Map<Integer, String> ids() {
            final Set<Integer> needed = new HashSet<Integer>();
            for (Match m : matches) {
                for (Object p : m.pieces) {
                    if (!(p instanceof Candidate) || ((Candidate) p).template != null) continue;
                    RecordedStep s = ((Candidate) p).source;
                    if (!(s.action instanceof RememberVerb) && s.action.id == null) needed.add(s.id);
                }
            }
            Set<String> taken = new HashSet<String>();
            taken.add("run");
            for (RecordedStep s : earlier) {
                if (s.action.id != null) taken.add(s.action.id);
                if (s.action instanceof RememberVerb && ((RememberVerb) s.action).as != null) {
                    taken.add(((RememberVerb) s.action).as);
                }
            }
            Map<Integer, String> ids = new LinkedHashMap<Integer, String>();
            for (RecordedStep s : earlier) {
                if (!needed.contains(s.id) || ids.containsKey(s.id)) continue;
                String base = idBase(s.action);
                String id = base;
                for (int n = 2; taken.contains(id); n++) id = base + n;
                taken.add(id);
                ids.put(s.id, id);
            }
            return ids;
        }

        private static String idOf(RecordedStep s, Map<Integer, String> ids) {
            if (s.action instanceof RememberVerb) return ((RememberVerb) s.action).as;
            return s.action.id != null ? s.action.id : ids.get(s.id);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Lower camel of what the step names (its target, its last menu item, its domain step), else of its kind. */
    static String idBase(Action a) {
        String name = null;
        if (a instanceof VerbAction) {
            for (Locator l : ((VerbAction) a).targets().values()) {
                if (l == null) continue;
                name = l.name != null ? l.name : l.id;
                if (name != null) break;
            }
        }
        if (name == null && a instanceof MenuVerb && ((MenuVerb) a).path != null && !((MenuVerb) a).path.isEmpty()) {
            List<String> path = ((MenuVerb) a).path;
            name = path.get(path.size() - 1);
        }
        if (name == null && a instanceof DoAction) name = ((DoAction) a).step;
        String id = name == null ? "" : camel(Refs.unescape(name));
        if (id.isEmpty() || !Character.isLetter(id.charAt(0))) {
            String kind = camel(FlowJson.mapper().valueToTree(a).path("action").asText("step"));
            id = kind.isEmpty() ? "step" : kind + (id.isEmpty() ? "" : capital(id));
        }
        return id.length() > 36 ? id.substring(0, 36) : id;
    }

    /** "Place order…" → {@code placeOrder}: ASCII letters and digits, words joined, the first lower case. */
    private static String camel(String text) {
        StringBuilder out = new StringBuilder();
        for (String word : text.split("[^A-Za-z0-9]+")) {
            if (word.isEmpty()) continue;
            out.append(out.length() == 0 ? lowerFirst(word) : capital(word));
        }
        return out.toString();
    }

    private static String lowerFirst(String w) {
        return w.substring(0, 1).toLowerCase(Locale.ROOT) + w.substring(1);
    }

    private static String capital(String w) {
        return w.substring(0, 1).toUpperCase(Locale.ROOT) + w.substring(1);
    }

    /** At least 4 characters, no yes/no/true/false, no integer below 1000. */
    static boolean distinctive(String v) {
        String t = v.trim();
        if (t.length() < 4 || WORDS.contains(t.toLowerCase(Locale.ROOT))) return false;
        if (INTEGER.matcher(t).matches()) {
            try {
                return Long.parseLong(t.startsWith("+") ? t.substring(1) : t) >= 1000;
            } catch (NumberFormatException huge) {
                return true;
            }
        }
        return true;
    }

    private static boolean hasRefs(String text) {
        try {
            return Refs.has(text);
        } catch (IllegalArgumentException malformed) {
            return true;   // not ours to touch
        }
    }

    /** {@code text} with each reference replaced by its value in {@code seen} and escapes undone. */
    private static String fill(String text, List<Refs.Ref> refs, Map<String, String> seen) {
        StringBuilder sb = new StringBuilder();
        int last = 0;
        for (Refs.Ref r : refs) {
            sb.append(Refs.unescape(text.substring(last, r.start))).append(seen.get(r.source));
            last = r.end;
        }
        return sb.append(Refs.unescape(text.substring(last))).toString();
    }
}
