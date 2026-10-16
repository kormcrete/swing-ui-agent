package io.github.kormcrete.swingui.flow;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Recorded test files on disk, format version 2: strict where {@link FlowJson} is lenient, so a typo never silently drops a
 * step's meaning and a file from a newer agent says why it cannot run. Each step carries the checks that follow it
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
 * inside). Names reserved for later formats are refused with a hint to update the agent. Files always use {@code \n}.
 */
public final class RecordedTestFiles {

    /** The newest format this agent reads and writes. */
    public static final int VERSION = 2;
    /** The only start there is: right after a session reset. */
    public static final String START_RESET = "reset";
    /** System property naming the recorded tests folder. */
    public static final String DIR_PROP = "swingui.recordedDir";
    /** The recorded tests folder, relative to the app's working directory, when nothing names one. */
    public static final String DEFAULT_DIR = "uitest";
    public static final String SUFFIX = ".json";

    /** Top-level fields of later formats. */
    static final Set<String> LATER_FIELDS = set("requires");
    /** Step fields of later formats. */
    static final Set<String> LATER_STEP_FIELDS = set("author");
    /** Step types of later formats. */
    static final Set<String> LATER_STEP_TYPES = set("do");
    /** The longest sentence, in characters. */
    static final int MAX_SAY = 200;

    private static final List<String> TOP_FIELDS = Arrays.asList("version", "name", "description", "start", "steps");
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
        if (version == null) throw bad(source, "needs \"version\": " + VERSION);
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() < 1) {
            throw bad(source, "\"version\" must be a whole number of 1 or more, not " + version);
        }
        if (version.intValue() < VERSION) {
            throw bad(source, "is format version " + version.intValue() + " (one flat list); this agent reads version " + VERSION
                    + ", where checks sit under the step they check - export it again, or nest each check under the step before it");
        }
        if (version.intValue() > VERSION) {
            throw bad(source, "is format version " + version.intValue() + ", written by a newer swing-ui-agent; "
                    + "this one reads version " + VERSION + " - update the agent");
        }

        List<String> unknown = new ArrayList<String>();
        for (Iterator<String> it = root.fieldNames(); it.hasNext();) {
            String field = it.next();
            if (TOP_FIELDS.contains(field)) continue;
            if (LATER_FIELDS.contains(field)) unknown.add("'" + field + "' " + NEWER);
            else if ("params".equals(field)) unknown.add("'params': " + STATIC);
            else if ("groups".equals(field)) unknown.add("'groups': a group is written as {\"say\": ..., \"steps\": [...]} inside steps");
            else unknown.add("unknown field '" + field + "'; a recorded test file has version, name, description, start, steps");
        }
        if (!unknown.isEmpty()) throw bad(source, join(unknown, "; "));

        JsonNode start = root.get("start");
        if (start == null) throw bad(source, "needs \"start\": \"" + START_RESET + "\"");
        if (!start.isTextual() || !START_RESET.equals(start.textValue())) {
            String named = start.isTextual() ? start.textValue() : start.toString();
            throw bad(source, "starts at '" + named + "'; this agent knows only '" + START_RESET + "'");
        }

        JsonNode steps = root.get("steps");
        if (steps == null || !steps.isArray()) throw bad(source, "\"steps\" must be an array of steps");
        if (steps.size() == 0) throw bad(source, "has no steps (a recorded test that does nothing would always pass)");
        for (String field : new String[] {"name", "description"}) {
            JsonNode n = root.get(field);
            if (n != null && !n.isTextual()) throw bad(source, "\"" + field + "\" must be text");
        }

        Flow flow = new Flow();
        flow.version = version.intValue();
        flow.start = start.textValue();
        flow.name = root.has("name") ? root.get("name").textValue() : null;
        flow.description = root.has("description") ? root.get("description").textValue() : null;
        ObjectReader reader = FlowJson.mapper().readerFor(Action.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        int[] number = {0};   // steps read so far
        for (JsonNode element : steps) {
            if (element.isObject() && element.has("steps")) {
                group(reader, element, flow, number, source);
            } else {
                step(reader, element, flow, number, false, source);
            }
        }
        return flow;
    }

    private static void group(ObjectReader reader, JsonNode node, Flow flow, int[] number, String source) {
        String where = "the group starting at step " + (number[0] + 1);
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
        int first = number[0] + 1;
        for (JsonNode member : members) {
            if (member.isObject() && member.has("steps")) throw bad(source, where + ": groups do not nest");
            step(reader, member, flow, number, true, source);
        }
        if (members.size() == 0) throw bad(source, "the group at step " + first + " holds no steps: a group holds at least 2 steps");
        String range = "the group of steps " + first + "-" + number[0];
        if (members.size() < 2) throw bad(source, range + ": a group holds at least 2 steps");
        JsonNode say = node.get("say");
        if (!say.isTextual()) throw bad(source, range + ": the sentence must be text");
        sayOrThrow(say.textValue(), range, source);
        flow.groups.add(new FlowGroup(from, flow.steps.size() - 1, say.textValue().trim()));
    }

    /** Reads one step element (an action with its checks, or a check-only first step) and appends it to the flat list. */
    private static void step(ObjectReader reader, JsonNode node, Flow flow, int[] number, boolean inGroup, String source) {
        int n = ++number[0];
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
            Action a = action(reader, own, "step " + n, source);
            sayOrThrow(a.say, "step " + n + " (" + typeOf(own) + ")", source);
            if (a.say != null) a.say = a.say.trim();
            flow.steps.add(a);
        }
        if (checks == null) return;
        int k = 0;
        for (JsonNode check : checks) {
            k++;
            String label = "check " + n + "." + k;
            if (!check.isObject()) throw bad(source, label + ": a check is a JSON object (an expect without \"action\")");
            JsonNode kind = check.get("action");
            if (kind != null && !(kind.isTextual() && "expect".equals(kind.textValue()))) {
                throw bad(source, label + ": only an expect goes in checks; an action is a step of its own");
            }
            ObjectNode own = ((ObjectNode) check).deepCopy();
            own.put("action", "expect");
            Action c = action(reader, own, label, source);
            sayOrThrow(c.say, label + " (expect)", source);
            if (c.say != null) c.say = c.say.trim();
            flow.steps.add(c);
        }
    }

    private static String typeOf(JsonNode node) {
        JsonNode action = node.get("action");
        return action != null && action.isTextual() ? action.textValue() : "?";
    }

    private static Action action(ObjectReader reader, JsonNode node, String label, String source) {
        String type = typeOf(node);
        try {
            return reader.readValue(node);
        } catch (UnrecognizedPropertyException e) {
            String field = e.getPropertyName();
            String why = "capture".equals(field) ? "'capture': " + STATIC
                    : LATER_STEP_FIELDS.contains(field) ? "'" + field + "' " + NEWER
                    : "known: " + sorted(e.getKnownPropertyIds());
            throw bad(source, label + " (" + type + "): unknown field '" + field + "'; " + why);
        } catch (InvalidTypeIdException e) {
            String hint = LATER_STEP_TYPES.contains(e.getTypeId()) ? "; '" + e.getTypeId() + "' " + NEWER : "";
            DriverException named = FlowJson.unknownStepType(e);
            throw bad(source, label + ": " + DriverException.stripReason(named.getMessage(), named.reason()) + hint);
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

    /** The format version a recorded test is written as: always {@link #VERSION}. */
    public static int versionFor(Flow flow) {
        return VERSION;
    }

    /**
     * The file text of a recorded test: version 2, steps with their checks nested, groups wrapped, pretty-printed, {@code \n}
     * line feeds on every OS, one final line feed. IllegalArgumentException when the reader would refuse the result: a newer
     * version, a start other than {@code reset}, or a bad structure ({@link #structureProblems}).
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

        ObjectMapper mapper = FlowJson.mapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("version", VERSION);
        if (flow.name != null) root.put("name", flow.name);
        if (flow.description != null) root.put("description", flow.description);
        root.put("start", START_RESET);
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
        if (step.checks.isEmpty()) return node;
        ArrayNode checks = node.putArray("checks");
        for (int c : step.checks) {
            ObjectNode check = (ObjectNode) mapper.valueToTree(flow.steps.get(c));
            check.remove("action");
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
