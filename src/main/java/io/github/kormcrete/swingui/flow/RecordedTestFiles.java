package io.github.kormcrete.swingui.flow;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Recorded test files on disk, format versions 2 and 3: strict where {@link FlowJson} is lenient, so a typo never silently
 * drops a step's meaning and a file from a newer agent says why it cannot run. Each step carries the checks that follow it
 * ({@link StepNumbers}); a step or check may have a one-line {@code say}; whole steps may be grouped:
 * <pre>
 * {
 *   "version" : 2,
 *   "name" : "place-order",
 *   "description" : "Ada Shop Ltd orders paper",
 *   "start" : "reset",
 *   "steps" : [ {
 *     "checks" : [ { "say" : "The cart is empty", "target" : { "role" : "label", "name" : "Total" }, "text" : "0" } ]
 *   }, {
 *     "say" : "Open a new order for Ada Shop Ltd",
 *     "steps" : [ { "action" : "menu", "path" : [ "Orders", "New order" ] }, { "action" : "type", ... } ]
 *   }, {
 *     "action" : "click", "say" : "Add the paper", "target" : { ... },
 *     "checks" : [ { "say" : "The cart total is 4.99", "target" : { ... }, "text" : "4.99" } ]
 *   } ]
 * }
 * </pre>
 * Step 1 may hold only checks (they run before any action); a group is {@code say} and {@code steps} (two or more, no group
 * inside). Version 3 adds {@code do} steps, a plugin's domain steps with concrete values ({@link DoAction}), and
 * {@code "requires"}, the plugins they come from:
 * <pre>
 *   "requires" : [ "order-desk" ],
 *   "steps" : [ {
 *     "action" : "do", "say" : "Add Gel pen, black to the cart", "step" : "order-desk:addToCart",
 *     "params" : { "product" : "Gel pen, black" },
 *     "checks" : [ { "action" : "do", "step" : "order-desk:cartTotalIs", "params" : { "total" : "4.99" } } ]
 *   } ]
 * </pre>
 * A domain check sits in {@code checks} with its {@code "action" : "do"}. A recorded test without do steps is written as
 * version 2, which every earlier agent of this format reads. Names reserved for later formats are refused with a hint to
 * update the agent. Files always use {@code \n}.
 */
public final class RecordedTestFiles {

    /** The newest format this agent reads and writes: a recorded test with {@code do} steps is written in it. */
    public static final int VERSION = 3;
    /** The format a recorded test without {@code do} steps is written in; the oldest this agent reads. */
    public static final int VERSION_WITHOUT_DO = 2;
    /** The only start there is: right after a session reset. */
    public static final String START_RESET = "reset";
    /** System property naming the recorded tests folder. */
    public static final String DIR_PROP = "swingui.recordedDir";
    /** The recorded tests folder, relative to the app's working directory, when nothing names one. */
    public static final String DEFAULT_DIR = "uitest";
    public static final String SUFFIX = ".json";

    /** Step fields of later formats. */
    static final Set<String> LATER_STEP_FIELDS = set("author");
    /** The longest sentence, in characters. */
    static final int MAX_SAY = 200;

    private static final List<String> TOP_FIELDS = Arrays.asList("version", "name", "description", "start", "steps");
    private static final String DO = "do";
    private static final String NEEDS_V3 = "needs format version 3 (\"version\": 3), the format of a recorded test with do steps";
    private static final String NEWER = "belongs to a newer recorded test format; update swing-ui-agent";
    private static final String STATIC = "a recorded test holds exact values only; values per run or read from the app belong in a Java test";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._-]{0,99}");
    private static final Pattern DEVICE = Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])");
    private static final String NAME_RULE = "a recorded test name is 1-100 characters: letters, digits, space, '.', '_', '-', "
            + "starting with a letter or digit, not ending in '.' or a space, and not a Windows device name (CON, "
            + "PRN, AUX, NUL, COM1-9, LPT1-9)";

    private RecordedTestFiles() {}

    private static Set<String> set(String... names) {
        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(names)));
    }

    private static DriverException bad(String source, String message) {
        return new DriverException(Reason.BAD_INPUT, source + ": " + message);
    }

    /** What one {@link #parse} reads into, and where it is in the file. */
    private static final class Reading {
        final ObjectReader reader = FlowJson.mapper().readerFor(Action.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        final Flow flow = new Flow();
        final String source;
        final int version;
        /** The line each JSON object of the file starts at, by JSON pointer. */
        final Map<String, Integer> lines;
        /** The label of each step read so far ("step 3", "check 3.1"), by flat index. */
        final List<String> labels = new ArrayList<String>();
        /** Steps read so far. */
        int number;

        Reading(String source, int version, Map<String, Integer> lines) {
            this.source = source;
            this.version = version;
            this.lines = lines;
        }

        void add(Action a, String label, String pointer) {
            flow.steps.add(a);
            labels.add(label);
            flow.lines.add(lines.get(pointer));
        }
    }

    /** Parses recorded test file text strictly; every refusal is BAD_INPUT starting with {@code source}. */
    public static Flow parse(String json, String source) {
        JsonNode root;
        try {
            root = FlowJson.mapper().readTree(json);
        } catch (IOException e) {
            throw bad(source, "not a recorded test file (not JSON: " + e.getMessage().split("\n")[0] + ")");
        }
        if (root == null || !root.isObject()) throw bad(source, "not a recorded test file (a JSON object is expected)");

        JsonNode version = root.get("version");
        if (version == null) throw bad(source, "needs \"version\": " + VERSION_WITHOUT_DO + " (" + VERSION + " for a recorded test with do steps)");
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() < 1) {
            throw bad(source, "\"version\" must be a whole number of 1 or more, not " + version);
        }
        int v = version.intValue();
        if (v < VERSION_WITHOUT_DO) {
            throw bad(source, "is format version " + v + " (one flat list); this agent reads versions " + VERSION_WITHOUT_DO
                    + " and " + VERSION + ", where checks sit under the step they check - export it again, or nest each check "
                    + "under the step before it");
        }
        if (v > VERSION) {
            throw bad(source, "is format version " + v + ", written by a newer swing-ui-agent; "
                    + "this one reads version " + VERSION + " - update the agent");
        }

        List<String> unknown = new ArrayList<String>();
        for (Iterator<String> it = root.fieldNames(); it.hasNext();) {
            String field = it.next();
            if (TOP_FIELDS.contains(field)) continue;
            if ("requires".equals(field)) {
                if (v < VERSION) unknown.add("'requires' " + NEEDS_V3);
            } else if ("params".equals(field)) {
                unknown.add("'params': " + STATIC);
            } else if ("groups".equals(field)) {
                unknown.add("'groups': a group is written as {\"say\": ..., \"steps\": [...]} inside steps");
            } else {
                unknown.add("unknown field '" + field + "'; a recorded test file has version, name, description, start, steps "
                        + "(and requires, from version " + VERSION + ")");
            }
        }
        if (!unknown.isEmpty()) throw bad(source, join(unknown, "; "));

        JsonNode start = root.get("start");
        if (start == null) throw bad(source, "needs \"start\": \"" + START_RESET + "\"");
        if (!start.isTextual() || !START_RESET.equals(start.textValue())) {
            String named = start.isTextual() ? start.textValue() : start.toString();
            throw bad(source, "starts at '" + named + "'; this agent knows only '" + START_RESET + "'");
        }
        List<String> requires = requires(root.get("requires"), source);

        JsonNode steps = root.get("steps");
        if (steps == null || !steps.isArray()) throw bad(source, "\"steps\" must be an array of steps");
        if (steps.size() == 0) throw bad(source, "has no steps (a recorded test that does nothing would always pass)");
        for (String field : new String[] {"name", "description"}) {
            JsonNode n = root.get(field);
            if (n != null && !n.isTextual()) throw bad(source, "\"" + field + "\" must be text");
        }

        Reading r = new Reading(source, v, lines(json));
        Flow flow = r.flow;
        flow.version = v;
        flow.start = start.textValue();
        flow.name = root.has("name") ? root.get("name").textValue() : null;
        flow.description = root.has("description") ? root.get("description").textValue() : null;
        flow.requires.addAll(requires);
        int i = 0;
        for (JsonNode element : steps) {
            String pointer = "/steps/" + i++;
            if (element.isObject() && element.has("steps")) {
                group(r, element, pointer);
            } else {
                step(r, element, false, pointer);
            }
        }
        doSteps(r);
        return flow;
    }

    /** The plugin names of {@code "requires"} (absent: none); BAD_INPUT unless an array of distinct names, one or more. */
    private static List<String> requires(JsonNode node, String source) {
        List<String> names = new ArrayList<String>();
        if (node == null) return names;
        if (!node.isArray()) throw bad(source, "\"requires\" must be an array of plugin names");
        if (node.size() == 0) throw bad(source, "\"requires\" names at least one plugin (leave it out for none)");
        for (JsonNode n : node) {
            if (!n.isTextual() || n.textValue().trim().isEmpty()) {
                throw bad(source, "\"requires\" holds plugin names (text), not " + n);
            }
            if (names.contains(n.textValue())) throw bad(source, "\"requires\" names '" + n.textValue() + "' twice");
            names.add(n.textValue());
        }
        return names;
    }

    /** Refuses do steps without a step id, without {@code "requires"}, or naming a plugin {@code "requires"} does not. */
    private static void doSteps(Reading r) {
        List<String> named = new ArrayList<String>();
        boolean any = false;
        for (int i = 0; i < r.flow.steps.size(); i++) {
            if (!(r.flow.steps.get(i) instanceof DoAction)) continue;
            any = true;
            DoAction d = (DoAction) r.flow.steps.get(i);
            String label = r.labels.get(i) + " (do)";
            if (d.step == null || d.step.trim().isEmpty()) {
                throw bad(r.source, label + ": needs \"step\": the id of a plugin's domain step");
            }
            String plugin = pluginOf(d);
            if (plugin == null) continue;
            if (!named.contains(plugin)) named.add(plugin);
            if (!r.flow.requires.isEmpty() && !r.flow.requires.contains(plugin)) {
                throw bad(r.source, label + ": its plugin '" + plugin + "' is not in \"requires\" " + r.flow.requires);
            }
        }
        if (any && r.flow.requires.isEmpty()) {
            throw bad(r.source, "needs \"requires\": the names of the plugins its do steps come from"
                    + (named.isEmpty() ? "" : ", here " + quoted(named)));
        }
    }

    /** The plugin a do step names ({@code <plugin>:<id>}), or null for a bare id. */
    public static String pluginOf(DoAction d) {
        int colon = d.step == null ? -1 : d.step.lastIndexOf(':');
        return colon > 0 ? d.step.substring(0, colon) : null;
    }

    private static String quoted(List<String> names) {
        List<String> q = new ArrayList<String>();
        for (String n : names) q.add("\"" + n + "\"");
        return "[" + join(q, ", ") + "]";
    }

    /** The line each JSON object in {@code json} starts at, by JSON pointer ({@code /steps/2/checks/0}). */
    private static Map<String, Integer> lines(String json) {
        Map<String, Integer> lines = new HashMap<String, Integer>();
        try (JsonParser p = FlowJson.mapper().getFactory().createParser(json)) {
            if (p.nextToken() != null) walk(p, "", lines);
        } catch (IOException e) {
            // read as a tree already: a file that does not tokenise has no lines to name
        }
        return lines;
    }

    private static void walk(JsonParser p, String pointer, Map<String, Integer> lines) throws IOException {
        if (p.currentToken() == JsonToken.START_OBJECT) {
            lines.put(pointer, p.getTokenLocation().getLineNr());
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.getCurrentName();
                p.nextToken();
                walk(p, pointer + "/" + name, lines);
            }
        } else if (p.currentToken() == JsonToken.START_ARRAY) {
            int i = 0;
            while (p.nextToken() != JsonToken.END_ARRAY) walk(p, pointer + "/" + i++, lines);
        }
    }

    private static void group(Reading r, JsonNode node, String pointer) {
        String source = r.source;
        Flow flow = r.flow;
        String where = "the group starting at step " + (r.number + 1);
        for (Iterator<String> it = node.fieldNames(); it.hasNext();) {
            String field = it.next();
            if (!"say".equals(field) && !"steps".equals(field)) {
                throw bad(source, where + ": unknown field '" + field + "'; a group has only say, steps");
            }
        }
        if (!node.has("say")) throw bad(source, where + ": needs a \"say\" (the group's sentence)");
        JsonNode members = node.get("steps");
        if (!members.isArray()) throw bad(source, where + ": \"steps\" must be an array of steps");
        int from = flow.steps.size();
        int first = r.number + 1;
        int i = 0;
        for (JsonNode member : members) {
            if (member.isObject() && member.has("steps")) throw bad(source, where + ": groups do not nest");
            step(r, member, true, pointer + "/steps/" + i++);
        }
        if (members.size() == 0) throw bad(source, "the group at step " + first + " holds no steps: a group holds at least 2 steps");
        String range = "the group of steps " + first + "-" + r.number;
        if (members.size() < 2) throw bad(source, range + ": a group holds at least 2 steps");
        JsonNode say = node.get("say");
        if (!say.isTextual()) throw bad(source, range + ": the sentence must be text");
        sayOrThrow(say.textValue(), range, source);
        flow.groups.add(new FlowGroup(from, flow.steps.size() - 1, say.textValue().trim()));
    }

    /** Reads one step element (an action with its checks, or a check-only first step) and appends it to the flat list. */
    private static void step(Reading r, JsonNode node, boolean inGroup, String pointer) {
        String source = r.source;
        int n = ++r.number;
        if (!node.isObject()) throw bad(source, "step " + n + ": a step is a JSON object with an 'action'");
        JsonNode checks = node.get("checks");
        String named = "step " + n + (node.has("action") ? " (" + typeOf(node) + ")" : "");
        if (checks != null && !checks.isArray()) throw bad(source, named + ": \"checks\" must be an array of checks");
        if (checks != null && checks.size() == 0) throw bad(source, named + ": \"checks\" is not empty when present - leave it out for none");

        if (!node.has("action")) {
            if (checks == null) {
                throw bad(source, "step " + n + ": a step is a JSON object with an 'action' (or only \"checks\", as the first step)");
            }
            if (n != 1) {
                throw bad(source, "step " + n + ": only the first step may hold only checks (they run before any action); "
                        + "put these under the step they check");
            }
            for (Iterator<String> it = node.fieldNames(); it.hasNext();) {
                String field = it.next();
                if ("say".equals(field)) {
                    throw bad(source, "step 1: a step with only checks has no sentence of its own; put it on a check");
                }
                if (!"checks".equals(field)) throw bad(source, "step 1: a step with only checks has no field '" + field + "'");
            }
        } else {
            JsonNode action = node.get("action");
            if (action.isTextual() && "expect".equals(action.textValue())) {
                throw bad(source, "step " + n + " (expect): a check goes under the step it checks (\"checks\"); "
                        + "checks before any action form the first step {\"checks\": [...]}");
            }
            ObjectNode own = ((ObjectNode) node).deepCopy();
            own.remove("checks");
            Action a = action(r, own, "step " + n);
            sayOrThrow(a.say, "step " + n + " (" + typeOf(own) + ")", source);
            if (a.say != null) a.say = a.say.trim();
            r.add(a, "step " + n, pointer);
        }
        if (checks == null) return;
        int k = 0;
        for (JsonNode check : checks) {
            k++;
            String label = "check " + n + "." + k;
            if (!check.isObject()) throw bad(source, label + ": a check is a JSON object (an expect without \"action\")");
            JsonNode kind = check.get("action");
            boolean domain = kind != null && kind.isTextual() && DO.equals(kind.textValue());
            if (kind != null && !domain && !(kind.isTextual() && "expect".equals(kind.textValue()))) {
                throw bad(source, label + ": only an expect or a domain check (do) goes in checks; an action is a step of its own");
            }
            ObjectNode own = ((ObjectNode) check).deepCopy();
            if (!domain) own.put("action", "expect");
            Action c = action(r, own, label);
            sayOrThrow(c.say, label + " (" + typeOf(own) + ")", source);
            if (c.say != null) c.say = c.say.trim();
            if (domain) ((DoAction) c).check = Boolean.TRUE;
            r.add(c, label, pointer + "/checks/" + (k - 1));
        }
    }

    private static String typeOf(JsonNode node) {
        JsonNode action = node.get("action");
        return action != null && action.isTextual() ? action.textValue() : "?";
    }

    private static Action action(Reading r, JsonNode node, String label) {
        String source = r.source;
        String type = typeOf(node);
        if (DO.equals(type) && r.version < VERSION) throw bad(source, label + ": 'do' " + NEEDS_V3);
        if (DO.equals(type) && node.has("check")) {
            throw bad(source, label + " (do): 'check' is not written in a file: a domain check goes under the step it checks "
                    + "(in its \"checks\")");
        }
        try {
            return r.reader.readValue(node);
        } catch (UnrecognizedPropertyException e) {
            String field = e.getPropertyName();
            String why = "capture".equals(field) ? "'capture': " + STATIC
                    : LATER_STEP_FIELDS.contains(field) ? "'" + field + "' " + NEWER
                    : "known: " + sorted(e.getKnownPropertyIds());
            throw bad(source, label + " (" + type + "): unknown field '" + field + "'; " + why);
        } catch (InvalidTypeIdException e) {
            DriverException named = FlowJson.unknownStepType(e);
            throw bad(source, label + ": " + DriverException.stripReason(named.getMessage(), named.reason()));
        } catch (JsonProcessingException e) {
            throw bad(source, label + ": " + e.getOriginalMessage());
        } catch (IOException e) {
            throw bad(source, label + ": " + e.getMessage());
        }
    }

    private static void sayOrThrow(String say, String where, String source) {
        try {
            if (say != null) checkSay(say, where);
        } catch (DriverException e) {
            throw bad(source, DriverException.stripReason(e.getMessage(), e.reason()));
        }
    }

    /** Refuses a sentence that is blank, has a line break, or is over 200 characters: BAD_INPUT naming {@code where}. */
    public static void checkSay(String say, String where) {
        String problem = say == null || say.trim().isEmpty() ? "a sentence is not empty (leave \"say\" out for none)"
                : say.indexOf('\n') >= 0 || say.indexOf('\r') >= 0 ? "a sentence is one line, without line breaks"
                : say.length() > MAX_SAY ? "a sentence is at most " + MAX_SAY + " characters, not " + say.length()
                : null;
        if (problem != null) throw new DriverException(Reason.BAD_INPUT, where + ": " + problem);
    }

    /**
     * What keeps a recorded test from being written as a file: groups off step boundaries, overlapping, with fewer than two steps
     * or a bad sentence; a bad step or check sentence; a check-only step that is not step 1. Empty when it can be written.
     */
    public static List<String> structureProblems(Flow flow) {
        List<String> problems = new ArrayList<String>();
        List<StepNumbers.Step> steps = StepNumbers.of(flow.steps);
        List<String> labels = StepNumbers.labels(flow.steps);
        for (int i = 0; i < flow.steps.size(); i++) {
            String say = flow.steps.get(i).say;
            if (say == null) continue;
            try {
                checkSay(say, (StepNumbers.isCheck(flow.steps.get(i)) ? "check " : "step ") + labels.get(i));
            } catch (DriverException e) {
                problems.add(DriverException.stripReason(e.getMessage(), e.reason()));
            }
        }
        for (StepNumbers.Step s : steps) {
            if (s.checkOnly() && s.number != 1) problems.add("step " + s.number + " holds only checks: only step 1 can");
        }
        for (int i = 0; i < flow.steps.size(); i++) {
            Action a = flow.steps.get(i);
            if (a instanceof DoAction && (((DoAction) a).step == null || ((DoAction) a).step.trim().isEmpty())) {
                problems.add((StepNumbers.isCheck(a) ? "check " : "step ") + labels.get(i) + ": a do step needs \"step\"");
            }
        }
        List<FlowGroup> groups = new ArrayList<FlowGroup>(flow.groups);
        Collections.sort(groups, new Comparator<FlowGroup>() {
            @Override public int compare(FlowGroup a, FlowGroup b) {
                return a.from != b.from ? Integer.compare(a.from, b.from) : Integer.compare(a.to, b.to);
            }
        });
        int previousTo = -1;
        for (FlowGroup g : groups) {
            int first = -1;
            int last = -1;
            for (StepNumbers.Step s : steps) {
                if (s.first() == g.from) first = s.number;
                if (s.last() == g.to) last = s.number;
            }
            String where = "the group at records " + g.from + "-" + g.to;
            if (first < 0 || last < 0 || last < first) {
                problems.add(where + " is not on step boundaries: it must start at a step's first record and end at a step's last");
            } else {
                where = "the group of steps " + first + "-" + last;
                if (last - first + 1 < 2) problems.add(where + ": a group holds at least 2 steps");
            }
            if (g.from <= previousTo) problems.add(where + " overlaps the group before it");
            previousTo = Math.max(previousTo, g.to);
            try {
                checkSay(g.say, where);
            } catch (DriverException e) {
                problems.add(DriverException.stripReason(e.getMessage(), e.reason()));
            }
        }
        return problems;
    }

    private static String sorted(java.util.Collection<Object> ids) {
        Set<String> out = new TreeSet<String>();
        if (ids != null) for (Object id : ids) out.add(String.valueOf(id));
        return out.toString();
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append(sb.length() == 0 ? "" : sep).append(p);
        return sb.toString();
    }

    /** Reads a recorded test file (UTF-8) as {@link #parse} does, naming the file; a missing name becomes the file's base name. */
    public static Flow read(Path file) {
        String source = file.toAbsolutePath().normalize().toString();
        String json;
        try {
            json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            DriverException bad = new DriverException(Reason.BAD_INPUT, "cannot read recorded test file " + source + ": " + e);
            bad.initCause(e);
            throw bad;
        }
        Flow flow = parse(json, source);
        if (flow.name == null || flow.name.trim().isEmpty()) {
            String base = file.getFileName().toString();
            flow.name = base.endsWith(SUFFIX) ? base.substring(0, base.length() - SUFFIX.length()) : base;
        }
        return flow;
    }

    /** The format version a recorded test is written as: {@link #VERSION} with a {@code do} step, else {@link #VERSION_WITHOUT_DO}. */
    public static int versionFor(Flow flow) {
        for (Action a : flow.steps) if (a instanceof DoAction) return VERSION;
        return VERSION_WITHOUT_DO;
    }

    /**
     * The plugins a recorded test's do steps need, as {@code "requires"} names them: those {@code flow.requires} names
     * (the ones its file named), then each plugin a do step names ({@code <plugin>:<id>}) in step order, once each.
     */
    public static List<String> requiresOf(Flow flow) {
        List<String> names = new ArrayList<String>(flow.requires);
        for (Action a : flow.steps) {
            String plugin = a instanceof DoAction ? pluginOf((DoAction) a) : null;
            if (plugin != null && !names.contains(plugin)) names.add(plugin);
        }
        return names;
    }

    /**
     * The file text of a recorded test: version 2, or version 3 with {@code "requires"} ({@link #requiresOf}) when it has do
     * steps; steps with their checks nested, groups wrapped, pretty-printed, {@code \n} line feeds on every OS, one final
     * line feed. IllegalArgumentException when the reader would refuse the result: a newer version, a start other than
     * {@code reset}, a bad structure ({@link #structureProblems}), or do steps whose plugins are not known.
     */
    public static String write(Flow flow) {
        Integer newer = flow.version;
        String start = flow.start;
        if (newer != null && newer > VERSION) {
            throw new IllegalArgumentException("cannot write format version " + newer + "; this agent writes version " + VERSION);
        }
        if (start != null && !START_RESET.equals(start)) {
            throw new IllegalArgumentException("cannot write a recorded test that starts at '" + start + "'; only '" + START_RESET + "'");
        }
        List<String> problems = structureProblems(flow);
        if (!problems.isEmpty()) throw new IllegalArgumentException(join(problems, "; "));
        int version = versionFor(flow);
        List<String> requires = requiresOf(flow);
        if (version == VERSION && requires.isEmpty()) {
            throw new IllegalArgumentException("cannot write do steps without their plugins: name each step as <plugin>:<id>, "
                    + "or the plugins in the recorded test's requires");
        }

        ObjectMapper mapper = FlowJson.mapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("version", version);
        if (flow.name != null) root.put("name", flow.name);
        if (flow.description != null) root.put("description", flow.description);
        root.put("start", START_RESET);
        if (version == VERSION) {
            ArrayNode names = root.putArray("requires");
            for (String r : requires) names.add(r);
        }
        ArrayNode steps = root.putArray("steps");
        List<StepNumbers.Step> list = StepNumbers.of(flow.steps);
        for (int i = 0; i < list.size(); i++) {
            FlowGroup group = null;
            for (FlowGroup g : flow.groups) if (g.from == list.get(i).first()) group = g;
            if (group == null) {
                steps.add(stepNode(mapper, flow, list.get(i)));
                continue;
            }
            ObjectNode g = steps.addObject();
            g.put("say", group.say);
            ArrayNode members = g.putArray("steps");
            while (true) {
                members.add(stepNode(mapper, flow, list.get(i)));
                if (list.get(i).last() >= group.to) break;
                i++;
            }
        }
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        DefaultIndenter indent = new DefaultIndenter("  ", "\n");
        printer.indentObjectsWith(indent);
        printer.indentArraysWith(indent);
        try {
            return mapper.writer(printer).writeValueAsString(root) + "\n";
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to write recorded test JSON: " + e.getMessage(), e);
        }
    }

    private static ObjectNode stepNode(ObjectMapper mapper, Flow flow, StepNumbers.Step step) {
        ObjectNode node = step.checkOnly() ? mapper.createObjectNode() : (ObjectNode) mapper.valueToTree(flow.steps.get(step.action));
        node.remove("check");   // a do step's place says whether it checks
        if (step.checks.isEmpty()) return node;
        ArrayNode checks = node.putArray("checks");
        for (int c : step.checks) {
            ObjectNode check = (ObjectNode) mapper.valueToTree(flow.steps.get(c));
            if (flow.steps.get(c) instanceof DoAction) check.remove("check");
            else check.remove("action");
            checks.add(check);
        }
        return node;
    }

    /** Validates a recorded test name (a trailing {@code .json} is accepted and stripped); BAD_INPUT states the rule. */
    public static String checkName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.endsWith(SUFFIX)) n = n.substring(0, n.length() - SUFFIX.length());
        int dot = n.indexOf('.');
        String stem = dot < 0 ? n : n.substring(0, dot);
        if (!NAME.matcher(n).matches() || n.endsWith(".") || n.endsWith(" ") || DEVICE.matcher(stem).matches()) {
            throw new DriverException(Reason.BAD_INPUT, "'" + name + "' is not a recorded test name; " + NAME_RULE);
        }
        return n;
    }

    /** The file of the recorded test {@code name} in {@code dir}. */
    public static Path file(Path dir, String name) {
        return dir.resolve(checkName(name) + SUFFIX);
    }

    /** The recorded test files directly in {@code dir}, sorted by file name on every OS; empty when {@code dir} is missing. */
    public static List<Path> list(Path dir) {
        List<Path> files = new ArrayList<Path>();
        if (!Files.isDirectory(dir)) return files;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(SUFFIX)) files.add(p);
            }
        } catch (IOException e) {
            DriverException bad = new DriverException(Reason.BAD_INPUT, "cannot list recorded tests in " + dir + ": " + e);
            bad.initCause(e);
            throw bad;
        }
        Collections.sort(files, new Comparator<Path>() {
            @Override public int compare(Path a, Path b) {
                return a.getFileName().toString().compareTo(b.getFileName().toString());
            }
        });
        return files;
    }

    /**
     * The recorded tests folder: the agent option, else the system property, else {@link #DEFAULT_DIR}; a relative one is under
     * {@code workingDir}.
     */
    public static Path dir(String agentOption, String property, Path workingDir) {
        String chosen = DEFAULT_DIR;
        if (agentOption != null && !agentOption.trim().isEmpty()) chosen = agentOption.trim();
        else if (property != null && !property.trim().isEmpty()) chosen = property.trim();
        Path p = Paths.get(chosen);
        return (p.isAbsolute() ? p : workingDir.resolve(chosen)).toAbsolutePath().normalize();
    }

    /** Creates {@code dir} if needed and checks it can be written; otherwise {@link RecordedTestFolderNotWritable}. */
    public static Path writableDir(Path dir) {
        Path abs = dir.toAbsolutePath();
        try {
            Files.createDirectories(abs);
        } catch (IOException | RuntimeException e) {
            throw new RecordedTestFolderNotWritable(abs, String.valueOf(e), e);
        }
        if (!Files.isWritable(abs)) throw new RecordedTestFolderNotWritable(abs, "the folder is not writable", null);
        return abs;
    }
}
