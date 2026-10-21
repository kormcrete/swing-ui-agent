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
import io.github.kormcrete.swingui.flow.refs.JsonPaths;
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
 * no integer below 1000), not the earlier step's own input, matched whole, or whole inside typed text (a domain step's
 * own values, under {@code step} of its outcome, too). A row or item
 * is linked by its position only when its step added (or changed) that one alone: a search that filled a table never
 * is. A remembered value and a text made unique per run are linked alike (that text also in its plain form, as typed
 * before it was made unique, short or not: the person asked for it). Several sources: the most recent step wins; within
 * one step a field, a row, an item or a domain step's own value beats a text or a title (then the order of its value
 * index).
 *
 * <p>The person's links go through here too: what earlier steps changed that a step has ({@link Pool}, offered in
 * words), the one they pick ({@link #linkTo}), and the later uses of a text made unique per run ({@link #uniqueBack}).
 * Those take short values as well; only the automatic links keep to distinctive ones.
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
        private final boolean any;

        Linked(Action action, Map<String, String> seen, Set<Integer> sources, Map<Integer, String> ids, boolean any) {
            this.action = action;
            this.seen = Collections.unmodifiableMap(seen);
            this.sources = Collections.unmodifiableSet(sources);
            this.ids = Collections.unmodifiableMap(ids);
            this.any = any;
        }

        /**
         * Whether anything was linked (a text unique per run that has not run yet links with no value seen for its
         * {@code run} reference).
         */
        boolean linked() {
            return any;
        }
    }

    /** Roles whose name is a caption (a label beside a field, a table's name), never a value the app made. */
    private static final Set<String> CAPTIONED = new HashSet<String>(Arrays.asList(
            "text", "password", "combo", "list", "table", "tree", "tabs", "menubar", "menu", "section"));
    private static final Set<String> WORDS = new HashSet<String>(Arrays.asList("yes", "no", "true", "false"));
    private static final Pattern INTEGER = Pattern.compile("[-+]?\\d+");

    /** Links the values of {@code a} (strings escaped as the recording keeps them) to what the steps {@code earlier} made. */
    static Linked link(Action a, List<RecordedStep> earlier) {
        return link(a, earlier, earlier);
    }

    /**
     * {@link #link(Action, List)}; {@code records}: the whole recording, whose names (step ids, remember names) an id
     * given to a source must not take - a later step's too.
     */
    static Linked link(Action a, List<RecordedStep> earlier, List<RecordedStep> records) {
        List<Candidate> all = new ArrayList<Candidate>();
        for (int i = earlier.size() - 1, age = 0; i >= 0; i--, age++) candidates(earlier.get(i), age, all, true);
        return new Work(earlier, all, true).names(records).run(a);
    }

    /**
     * The person's link: the values of {@code a} that are what the step with record id {@code sourceId} (one of
     * {@code earlier}) changed in {@code group} ({@link Offer#group}) - whole, or whole inside typed text - become
     * references to it, short ones too. Nothing linked when that step is gone or none of them is in {@code a}.
     */
    static Linked linkTo(Action a, List<RecordedStep> earlier, int sourceId, String group) {
        return linkTo(a, earlier, sourceId, group, earlier);
    }

    /** {@link #linkTo(Action, List, int, String)}; {@code records}: as for {@link #link(Action, List, List)}. */
    static Linked linkTo(Action a, List<RecordedStep> earlier, int sourceId, String group, List<RecordedStep> records) {
        List<Candidate> all = new ArrayList<Candidate>();
        for (RecordedStep s : earlier) {
            if (s.id != sourceId) continue;
            List<Candidate> of = new ArrayList<Candidate>();
            candidates(s, 0, of, false);
            for (Candidate c : of) if (groupOf(c).equals(group)) all.add(c);
        }
        return new Work(earlier, all, false).names(records).run(a);
    }

    /**
     * The agent's link: where {@code a} has {@code value} - whole, or whole inside typed text - it becomes a reference
     * to {@code path} ({@code .rows.Orders.added[0].Number}; "" for a remembered value) of what {@code source} (one of
     * {@code earlier}) changed or remembered, short values too. {@code field} (null: any place a step has values) names
     * the one place, as a path into the step's JSON ({@code text}, {@code target.row.Number}, {@code params.qty}).
     * Nothing linked when the value is not there. {@code records}: as for {@link #link(Action, List, List)}.
     */
    static Linked linkToValue(Action a, List<RecordedStep> earlier, RecordedStep source, String path, String value,
            String field, List<RecordedStep> records) {
        Work work = new Work(earlier, Collections.singletonList(new Candidate(value, source, path, null, 0, 0)), false)
                .names(records);
        work.only = field;
        return work.run(a);
    }

    /** Whether {@code value} is one {@code s} itself typed, picked or named (never what the app made). */
    static boolean ownValue(RecordedStep s, String value) {
        return ownValues(s, FlowJson.mapper().valueToTree(s.action)).contains(value);
    }

    /**
     * A later step's use of a text made unique per run: where {@code a} has {@code plain} (the text as typed, references
     * filled), whole or as a whole word in typed text, it gets {@code template} (that text with its {@code run}
     * reference) instead; short texts too. {@code made} is the step made unique: the values its references stood for.
     */
    static Linked uniqueBack(Action a, RecordedStep made, String plain, String template) {
        List<Candidate> one = Collections.singletonList(new Candidate(plain, made, null, template, 0, 0));
        return new Work(Collections.<RecordedStep>emptyList(), one, false).run(a);
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
        /**
         * 0: a field, row, item, remembered value or a text unique per run; 1: a text, a title, anything else; 2: a text
         * unique per run in its plain form.
         */
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

    /** The candidates of step {@code s}, {@code age} steps back; {@code strict}: only distinctive values (automatic links). */
    private static void candidates(RecordedStep s, int age, List<Candidate> out, boolean strict) {
        if (s.action instanceof RememberVerb) {
            String as = ((RememberVerb) s.action).as;
            if (as != null) {
                for (String v : s.newValues.keySet()) {
                    if (!strict || distinctive(v)) out.add(new Candidate(v, s, "", null, 0, age));
                }
            }
            return;
        }
        JsonNode tree = FlowJson.mapper().valueToTree(s.action);
        Set<String> own = s.newValues.isEmpty() ? Collections.<String>emptySet() : ownValues(s, tree);
        for (Map.Entry<String, String> e : s.newValues.entrySet()) {
            String v = e.getKey();
            String p = e.getValue();
            if (strict && !distinctive(v) || own.contains(v) || several(s.outcome, p)) continue;
            // what a domain step says it made counts as a field does
            int kind = p.startsWith(".fields") || p.startsWith(".rows") || p.startsWith(".items") || p.startsWith(".step")
                    ? 0 : 1;
            out.add(new Candidate(v, s, p, null, kind, age));
        }
        composed(s, tree, age, out, strict);
    }

    /** What {@link #uniqueForm} adds: the run's id. */
    static final String RUN_ID = "${run.id}";

    /**
     * The texts of the step made unique per run (references to {@code run} only), as they were when it ran; and a text
     * in the form unique per run gives it ({@link #uniqueForm}) in its plain form, as it was typed before.
     */
    private static void composed(final RecordedStep s, JsonNode tree, final int age, final List<Candidate> out,
            final boolean strict) {
        Refs.mapValues(tree, text -> {
            List<Refs.Ref> refs;
            try {
                refs = Refs.scan(text);
            } catch (IllegalArgumentException malformed) {
                return text;
            }
            if (refs.isEmpty()) return text;
            boolean seen = true;
            for (Refs.Ref r : refs) {
                if (!"run".equals(r.id)) return text;
                seen &= s.linkSeen.containsKey(r.source);
            }
            if (seen) {
                String value = fill(text, refs, s.linkSeen);
                if (!strict || distinctive(value)) out.add(new Candidate(value, s, null, text, 0, age));
            }
            String plain = uniqueForm(text) == null ? null : Refs.unescape(uniqueBase(text));
            if (plain != null && !plain.trim().isEmpty()) out.add(new Candidate(plain, s, null, text, 2, age));
            return text;
        });
    }

    /**
     * Where unique per run put the run's id in {@code text} (as the recording keeps it): "end" for {@code <text>
     * ${run.id}}, "start" for {@code ${run.id} <text>}; null for any other text.
     */
    static String uniqueForm(String text) {
        if (text.endsWith(" " + RUN_ID) && text.length() > RUN_ID.length() + 1) return "end";
        if (text.startsWith(RUN_ID + " ") && text.length() > RUN_ID.length() + 1) return "start";
        return null;
    }

    /** {@code text} without what unique per run added ({@link #uniqueForm}); the text itself when it has nothing added. */
    static String uniqueBase(String text) {
        String form = uniqueForm(text);
        if ("end".equals(form)) return text.substring(0, text.length() - RUN_ID.length() - 1);
        if ("start".equals(form)) return text.substring(RUN_ID.length() + 1);
        return text;
    }

    /**
     * The value strings of a step's own action (what it typed, picked, named), with its references as they stood
     * when it ran: never what the app made.
     */
    private static Set<String> ownValues(final RecordedStep s, JsonNode tree) {
        final Set<String> own = new HashSet<String>(s.linkSeen.values());
        Refs.mapValues(tree, text -> {
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

    // ------------------------------------------------------------------ where a step has values

    /**
     * Walks the places of a step where a value can be linked: a target's name (not a caption's), item and row keys;
     * typed text; a selected item, a picked path; what a check expects; a domain step's params. Not menu paths, sentences,
     * windows, ids or file paths.
     */
    private abstract static class Walk {

        void walk(ObjectNode tree, Action a) {
            if (a instanceof VerbAction) {
                for (String field : ((VerbAction) a).targets().keySet()) {
                    if (tree.get(field) instanceof ObjectNode) locator((ObjectNode) tree.get(field));
                }
            }
            switch (tree.path("action").asText()) {
                case "type":
                    typed(tree, "text");
                    break;
                case "select":
                    whole(tree, "item");
                    wholeEach(tree.get("items"));
                    break;
                case "pick":
                    wholeEach(tree.get("path"));
                    break;
                case "expect":
                    whole(tree, "text");
                    whole(tree, "value");
                    JsonNode cell = tree.get("cell");
                    if (cell instanceof ObjectNode) {
                        whole((ObjectNode) cell, "expected");
                        JsonNode target = tree.get("target");
                        row(target == null ? null : target.get("name"), cell.get("row"));
                    }
                    break;
                case "do":
                    wholeEach(tree.get("params"));
                    break;
                default:
                    break;
            }
        }

        void locator(ObjectNode l) {
            JsonNode role = l.get("role");
            if (role == null || !CAPTIONED.contains(role.asText())) whole(l, "name");
            wholeEach(l.get("item"));
            row(l.get("name"), l.get("row"));
        }

        /** The keys of a row locator ({@code name}: its table's), each a value of its own. */
        void row(JsonNode name, JsonNode row) {
            if (!(row instanceof ObjectNode)) return;
            for (Iterator<String> it = row.fieldNames(); it.hasNext(); ) whole((ObjectNode) row, it.next());
        }

        /** Each text of an array or object (a pick's path, a row's values, a do step's params). */
        void wholeEach(JsonNode values) {
            if (values instanceof ArrayNode) {
                for (int i = 0; i < values.size(); i++) wholeAt((ArrayNode) values, i);
            } else if (values instanceof ObjectNode) {
                for (Iterator<String> it = values.fieldNames(); it.hasNext(); ) whole((ObjectNode) values, it.next());
            }
        }

        /** A value that is linked whole: {@code parent}'s {@code field}, when there is one. */
        abstract void whole(ObjectNode parent, String field);

        /** A value that is linked whole: slot {@code i} of {@code parent}. */
        abstract void wholeAt(ArrayNode parent, int i);

        /** Typed text: linked whole, or each value found whole inside it. */
        abstract void typed(ObjectNode parent, String field);
    }

    /** The plain texts (escapes undone) at the places a step has values, without those that hold a reference. */
    private static final class Spots extends Walk {
        final List<String> whole = new ArrayList<String>();
        final List<String> typed = new ArrayList<String>();

        @Override void whole(ObjectNode parent, String field) {
            add(parent.get(field), whole);
        }

        @Override void wholeAt(ArrayNode parent, int i) {
            add(parent.get(i), whole);
        }

        @Override void typed(ObjectNode parent, String field) {
            add(parent.get(field), typed);
        }

        private static void add(JsonNode node, List<String> to) {
            if (node != null && node.isTextual() && !hasRefs(node.textValue())) to.add(Refs.unescape(node.textValue()));
        }

        /** Whether {@code value} is one of them, or whole inside typed text. */
        boolean has(String value) {
            if (whole.contains(value) || typed.contains(value)) return true;
            for (String t : typed) {
                for (int i = t.indexOf(value); i >= 0; i = t.indexOf(value, i + 1)) {
                    if (fits(t, i, value, new TreeMap<Integer, Candidate>())) return true;
                }
            }
            return false;
        }
    }

    private static final class Work extends Walk {
        final List<RecordedStep> earlier;
        /** Whether a row an earlier step added is linked by that row (automatic links). */
        final boolean rows;
        /** The best candidate for each value, best first. */
        final Map<String, Candidate> best = new LinkedHashMap<String, Candidate>();
        final List<Match> matches = new ArrayList<Match>();
        /** The one place to link, as a path into the step's JSON; null: every place. */
        String only;
        /** The records whose names an id given to a source must not take; by default the earlier ones. */
        List<RecordedStep> named;

        Work names(List<RecordedStep> records) {
            this.named = records;
            return this;
        }

        Work(List<RecordedStep> earlier, List<Candidate> all, boolean rows) {
            this.earlier = earlier;
            this.rows = rows;
            List<Candidate> sorted = new ArrayList<Candidate>(all);
            // stable: the most recent step first, then by kind, then in the order of the step's value index
            Collections.sort(sorted, new Comparator<Candidate>() {
                @Override public int compare(Candidate x, Candidate y) {
                    return x.age != y.age ? x.age - y.age : x.kind - y.kind;
                }
            });
            for (Candidate c : sorted) if (!best.containsKey(c.value)) best.put(c.value, c);
        }

        Linked run(Action a) {
            if (best.isEmpty()) return none(a);
            ObjectNode tree = FlowJson.mapper().valueToTree(a);
            walk(tree, a);
            if (only != null) {
                List<JsonNode> at = JsonPaths.select(tree, (only.startsWith("[") ? "$" : "$.") + only);
                JsonNode place = at.size() == 1 ? at.get(0) : null;
                for (Iterator<Match> it = matches.iterator(); it.hasNext(); ) {
                    Match m = it.next();
                    JsonNode node = m.field != null ? m.parent.get(m.field) : m.parent.get(m.index);
                    if (node != place) it.remove();
                }
            }
            return matches.isEmpty() ? none(a) : finish(tree);
        }

        Linked none(Action a) {
            return new Linked(a, new LinkedHashMap<String, String>(), new HashSet<Integer>(),
                    new LinkedHashMap<Integer, String>(), false);
        }

        // -------------------------------------------------------------- where values are

        /**
         * The keys of a row locator. A row an earlier step added (in its outcome, under the table {@code name}) is linked
         * by that row: each key column that holds a new value, the others exact; when it holds none (a twin of another
         * row), the row stays exact. Any other row has its key values linked one by one.
         */
        @Override void row(JsonNode name, JsonNode row) {
            if (!(row instanceof ObjectNode)) return;
            ObjectNode keys = (ObjectNode) row;
            String table = name != null && name.isTextual() && !hasRefs(name.textValue())
                    ? Refs.unescape(name.textValue()) : null;
            if (rows && table != null && addedRow(table, keys)) return;
            super.row(name, row);
        }

        private boolean addedRow(String table, ObjectNode keys) {
            for (int i = earlier.size() - 1; i >= 0; i--) {
                RecordedStep s = earlier.get(i);
                if (s.outcome == null) continue;
                JsonNode added = s.outcome.path("rows").path(table).path("added");
                // a row among several the step added is never linked by its position (an older step may have made it)
                if (added.size() > 1) continue;
                for (int k = 0; k < added.size(); k++) {
                    if (!sameRow(keys, added.get(k))) continue;
                    Set<String> own = ownValues(s, FlowJson.mapper().valueToTree(s.action));
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

        @Override void wholeAt(ArrayNode values, int i) {
            Candidate c = wholeMatch(values.get(i));
            if (c != null) matches.add(new Match(values, null, i, Collections.<Object>singletonList(c)));
        }

        @Override void whole(ObjectNode parent, String field) {
            Candidate c = wholeMatch(parent.get(field));
            if (c != null) matches.add(new Match(parent, field, -1, Collections.<Object>singletonList(c)));
        }

        private Candidate wholeMatch(JsonNode node) {
            if (node == null || !node.isTextual() || hasRefs(node.textValue())) return null;
            return best.get(Refs.unescape(node.textValue()));
        }

        /** Typed text: linked whole, else each candidate found whole inside it (fixed text around it). */
        @Override void typed(ObjectNode parent, String field) {
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
                        // a text unique per run that has not run yet: its run's id was never seen
                        for (Refs.Ref r : Refs.scan(c.template)) {
                            if (c.source.linkSeen.containsKey(r.source)) seen.put(r.source, c.source.linkSeen.get(r.source));
                        }
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
                return new Linked(FlowJson.mapper().treeToValue(tree, Action.class), seen, sources, ids, true);
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
            // every name of the recording, a later step's too: a file with one name twice does not load
            for (RecordedStep s : named != null ? named : earlier) {
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

    /** Whether {@code part} is in {@code text} whole: the whole text, or with no letter or digit glued to either end. */
    static boolean wholeIn(String text, String part) {
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) if (wholeAt(text, i, part)) return true;
        return false;
    }

    /** {@code text} with each whole {@code part} in it ({@link #wholeIn}) replaced by {@code by}. */
    static String replaceWhole(String text, String part, String by) {
        StringBuilder out = new StringBuilder();
        int last = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            if (i < last || !wholeAt(text, i, part)) continue;
            out.append(text, last, i).append(by);
            last = i + part.length();
        }
        return last == 0 ? text : out.append(text.substring(last)).toString();
    }

    private static boolean wholeAt(String text, int i, String part) {
        int end = i + part.length();
        return !(i > 0 && glued(text.charAt(i - 1), part.charAt(0)))
                && !(end < text.length() && glued(text.charAt(end), part.charAt(part.length() - 1)));
    }

    // ------------------------------------------------------------------ what the person may link a step to

    /**
     * Something one earlier step changed that the person may link a later step to: a row or item it added or changed
     * (its new values), one other value, a remembered value, or a text it made unique per run. Values plain, in the
     * order of the step's value index.
     */
    static final class Offer {
        final RecordedStep source;
        /**
         * Which of the step's values: the path of the row or item ({@code .rows.Orders.added[0]}) or of the value
         * ({@code .fields.Number}), "" for a remembered value, {@code =} and the text for a text unique per run.
         */
        final String group;
        /** The values, plain, each with its path ("" for a remembered value, null for a text unique per run). */
        final Map<String, String> values = new LinkedHashMap<String, String>();

        Offer(RecordedStep source, String group) {
            this.source = source;
            this.group = group;
        }
    }

    /** The {@link Offer#group} of a candidate. */
    private static String groupOf(Candidate c) {
        if (c.template != null) return "=" + c.template;
        java.util.regex.Matcher m = ROW.matcher(c.path);
        return m.find() ? m.group() : c.path;
    }

    /**
     * What the steps of a recording changed, gathered one step after another: what each step may be linked to is what
     * the steps before it changed ({@link #fitting}). The words are the caller's.
     */
    static final class Pool {
        /** Each step's offers, oldest step first. */
        private final List<List<Offer>> offers = new ArrayList<List<Offer>>();

        /** What {@code s} changed joins the pool: the steps after it may use it. */
        void add(RecordedStep s) {
            List<Candidate> of = new ArrayList<Candidate>();
            candidates(s, 0, of, false);
            Map<String, Offer> groups = new LinkedHashMap<String, Offer>();
            for (Candidate c : of) {
                String g = groupOf(c);
                Offer o = groups.get(g);
                if (o == null) groups.put(g, o = new Offer(s, g));
                if (!o.values.containsKey(c.value)) o.values.put(c.value, c.path);
            }
            if (!groups.isEmpty()) offers.add(new ArrayList<Offer>(groups.values()));
        }

        /**
         * What a step doing {@code a} may use, newest step first (then in the order of its value index), at most
         * {@code max}: the offers one of whose values it has (whole, or whole inside typed text, where it holds no
         * reference already).
         */
        List<Offer> fitting(Action a, int max) {
            List<Offer> out = new ArrayList<Offer>();
            if (offers.isEmpty()) return out;
            Spots spots = new Spots();
            spots.walk(FlowJson.mapper().<ObjectNode>valueToTree(a), a);
            if (spots.whole.isEmpty() && spots.typed.isEmpty()) return out;
            for (int i = offers.size() - 1; i >= 0; i--) {
                for (Offer o : offers.get(i)) {
                    if (out.size() == max) return out;
                    for (String v : o.values.keySet()) {
                        if (spots.has(v)) {
                            out.add(o);
                            break;
                        }
                    }
                }
            }
            return out;
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
        if (name == null && a instanceof DoAction && ((DoAction) a).step != null) {
            String step = ((DoAction) a).step;
            name = step.substring(step.lastIndexOf(':') + 1);   // the step's own id, not its plugin's name
        }
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

    private static final String KEY = "(?:\\.[A-Za-z_][A-Za-z0-9_-]*|\\['(?:[^'\\\\]|\\\\.)*'\\])";
    /** A path into a list of rows or items a step added or changed, e.g. {@code .rows.Orders.added[2].Order}. */
    private static final Pattern LISTED = Pattern.compile("^(\\.(?:rows|items)" + KEY + "\\.(?:added|changed))\\[\\d+\\]");

    /** A row's or an item's path in an added or changed list: {@code .rows.Orders.added[0]}, {@code .rows.T.changed[1].after}. */
    private static final Pattern ROW = Pattern.compile("^\\.(?:rows|items)" + KEY + "\\.(?:added|changed)\\[\\d+\\](?:\\.after)?");

    /**
     * Whether {@code path} (in {@code outcome}) is in a list of added or changed rows or items with more than one entry:
     * its position there says nothing a replay can count on. False when the outcome is not known.
     */
    static boolean several(ObjectNode outcome, String path) {
        if (outcome == null) return false;
        java.util.regex.Matcher m = LISTED.matcher(path);
        if (!m.find()) return false;
        List<JsonNode> list = JsonPaths.select(outcome, "$" + m.group(1));
        return list.size() == 1 && list.get(0).size() > 1;
    }

    /** {@code newValues} without the values {@link #several} leaves out, for a step about to lose its outcome. */
    static Map<String, String> singles(ObjectNode outcome, Map<String, String> newValues) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : newValues.entrySet()) {
            if (!several(outcome, e.getValue())) out.put(e.getKey(), e.getValue());
        }
        return out;
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
