package io.github.kormcrete.swingui.flow;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

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
 * Recorded test files on disk, format version 1: strict where {@link FlowJson} is lenient, so a typo never silently drops a
 * step's meaning and a file from a newer agent says why it cannot run. A minimal file:
 * <pre>
 * {
 *   "version" : 1,
 *   "name" : "place-order",
 *   "description" : "Ada orders two gel pens",
 *   "start" : "reset",
 *   "steps" : [ { "action" : "menu", "path" : [ "Orders", "New order" ] }, ... ]
 * }
 * </pre>
 * Names reserved for later formats are refused with a hint to update the agent. Files always use {@code \n}.
 */
public final class RecordedTestFiles {

    /** The newest format this agent reads and writes. */
    public static final int VERSION = 1;
    /** The only start there is: right after a session reset. */
    public static final String START_RESET = "reset";
    /** System property naming the recorded tests folder. */
    public static final String DIR_PROP = "swingui.recordedDir";
    /** The recorded tests folder, relative to the app's working directory, when nothing names one. */
    public static final String DEFAULT_DIR = "uitest";
    public static final String SUFFIX = ".json";

    /** Top-level fields of later formats. */
    static final Set<String> LATER_FIELDS = set("params", "groups");
    /** Step fields of later formats. */
    static final Set<String> LATER_STEP_FIELDS = set("say", "author", "capture");
    /** Step types of later formats. */
    static final Set<String> LATER_STEP_TYPES = set("do");

    private static final List<String> TOP_FIELDS = Arrays.asList("version", "name", "description", "start", "steps");
    private static final String NEWER = "belongs to a newer recorded test format; update swing-ui-agent";
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
        if (version.intValue() > VERSION) {
            throw bad(source, "is format version " + version.intValue() + ", written by a newer swing-ui-agent; "
                    + "this one reads version " + VERSION + " - update the agent");
        }

        List<String> unknown = new ArrayList<String>();
        for (Iterator<String> it = root.fieldNames(); it.hasNext();) {
            String field = it.next();
            if (TOP_FIELDS.contains(field)) continue;
            unknown.add(LATER_FIELDS.contains(field)
                    ? "'" + field + "' " + NEWER
                    : "unknown field '" + field + "'; a recorded test file has version, name, description, start, steps");
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
        for (int i = 0; i < steps.size(); i++) {
            flow.steps.add(step(reader, steps.get(i), i + 1, source));
        }
        return flow;
    }

    private static Action step(ObjectReader reader, JsonNode node, int number, String source) {
        if (!node.isObject()) throw bad(source, "step " + number + ": a step is a JSON object with an 'action'");
        JsonNode action = node.get("action");
        String label = action != null && action.isTextual() ? action.textValue() : "?";
        try {
            return reader.readValue(node);
        } catch (UnrecognizedPropertyException e) {
            String field = e.getPropertyName();
            String why = LATER_STEP_FIELDS.contains(field)
                    ? "'" + field + "' " + NEWER
                    : "known: " + sorted(e.getKnownPropertyIds());
            throw bad(source, "step " + number + " (" + label + "): unknown field '" + field + "'; " + why);
        } catch (InvalidTypeIdException e) {
            String hint = LATER_STEP_TYPES.contains(e.getTypeId()) ? "; '" + e.getTypeId() + "' " + NEWER : "";
            DriverException named = FlowJson.unknownStepType(e);
            throw bad(source, "step " + number + ": "
                    + DriverException.stripReason(named.getMessage(), named.reason()) + hint);
        } catch (JsonProcessingException e) {
            throw bad(source, "step " + number + ": " + e.getOriginalMessage());
        } catch (IOException e) {
            throw bad(source, "step " + number + ": " + e.getMessage());
        }
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

    /**
     * The lowest format version that holds the given steps, the one writers write so plain recorded tests stay readable by older
     * agents: 1 today. A later format returns its version only when the recorded test uses that format's fields.
     */
    public static int versionFor(Flow flow) {
        return 1;
    }

    /**
     * The file text of a recorded test: pretty-printed, {@code \n} line feeds on every OS, one final line feed. Without a
     * version, the lowest that holds it ({@link #versionFor}).
     */
    public static String write(Flow flow) {
        Flow out = new Flow();
        out.version = flow.version == null ? versionFor(flow) : flow.version;
        out.name = flow.name;
        out.description = flow.description;
        out.start = flow.start == null ? START_RESET : flow.start;
        out.steps = flow.steps;
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        DefaultIndenter indent = new DefaultIndenter("  ", "\n");
        printer.indentObjectsWith(indent);
        printer.indentArraysWith(indent);
        try {
            return FlowJson.mapper().writer(printer).writeValueAsString(out) + "\n";
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to write recorded test JSON: " + e.getMessage(), e);
        }
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
