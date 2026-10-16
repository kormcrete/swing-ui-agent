package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.EdtClock;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Logger;

/**
 * One parseable line per MCP request, so where a driven run spends its time can be grepped and summed:
 *
 * <pre>
 * UIMCP-TIMING tool=click status=ok ms=153 invokeMs=150 edtMs=41 edtWaitMs=2 edtCalls=7 offEdtMs=107 emitMs=1 bytes=842 target=button:Save
 * </pre>
 *
 * <ul>
 *   <li>{@code ms} - the whole dispatch: parsing the request, the tool, and emitting the answer;</li>
 *   <li>{@code invokeMs} - the tool alone, split into {@code edtMs} (tasks running on the EDT),
 *       {@code edtWaitMs} (waiting for the EDT to take them) and {@code offEdtMs} (the rest: settle and
 *       expect polling, building and serialising the answer) - see
 *       {@link EdtClock} for what is and is not counted;</li>
 *   <li>{@code emitMs} - wrapping the answer in the JSON-RPC response and handing it to the transport;</li>
 *   <li>{@code bytes} - the UTF-8 size of the tool's answer text, which is what costs the model tokens;</li>
 *   <li>{@code status} - {@code ok}, {@code failed} (an answer with {@code passed:false}) or
 *       {@code error:<errorType>};</li>
 *   <li>{@code target} - a short summary of what the call addressed. Always LAST, so it may contain
 *       spaces: everything after {@code target=} to the end of the line is the target.</li>
 * </ul>
 *
 * <p>A batched call ({@code batch}, {@code do_actions}) also logs one {@code UIMCP-STEP} line per step
 * in the same shape, so a batch's total can be taken apart without a re-run.
 *
 * <p>Lines go to the client's log (java.util.logging, logger {@code UIMCP}) and, when a file is configured, are
 * appended to it too: {@code -Dswingui.timingLog=<path>}, by default {@code .swing-ui-agent/timing.log}
 * when the client's working directory has a {@code .swing-ui-agent} directory, and
 * {@code off} to disable.
 */
final class UiMcpTiming {

    static final String PREFIX = "UIMCP-TIMING";
    static final String STEP_PREFIX = "UIMCP-STEP";
    static final String FILE_PROP = "swingui.timingLog";
    static final Path DEFAULT_FILE = Paths.get(".swing-ui-agent", "timing.log");

    private static final Logger LOG = Logger.getLogger("UIMCP");
    private static final int MAX_TARGET = 120;

    private UiMcpTiming() {}

    /** The request line. Every duration is in milliseconds. */
    static String line(String tool, String status, long ms, long invokeMs, EdtClock.Reading edt, long emitMs,
                       long bytes, String target) {
        return PREFIX + " " + fields(tool, status, ms, edt, invokeMs) + " emitMs=" + emitMs + " bytes=" + bytes
                + " target=" + clean(target);
    }

    /** One step of a batch: {@code tool} is {@code <batch tool>.<verb>}, {@code step} is {@code k/n}. */
    static String stepLine(String tool, String step, String status, long ms, EdtClock.Reading edt, String target) {
        return STEP_PREFIX + " " + fields(tool, status, ms, edt, ms).replace(" status=", " step=" + step + " status=")
                + " target=" + clean(target);
    }

    private static String fields(String tool, String status, long ms, EdtClock.Reading edt, long invokeMs) {
        long edtMs = edt.runMs();
        long waitMs = edt.waitMs();
        return "tool=" + tool + " status=" + status + " ms=" + ms + " invokeMs=" + invokeMs + " edtMs=" + edtMs
                + " edtWaitMs=" + waitMs + " edtCalls=" + edt.calls + " offEdtMs=" + Math.max(0, invokeMs - edtMs - waitMs);
    }

    /** {@code ok}, {@code failed} for an answer that says {@code passed:false}, or {@code error:<type>}. */
    static String status(String text, boolean isError, String errorType) {
        if (isError) return "error:" + (errorType == null || errorType.isEmpty() ? "UNKNOWN" : errorType);
        // Our answers start with their own fields and escape every nested quote, so this cannot match
        // text inside a node's value; a batch's nested failed step fails the batch as well.
        return text != null && text.contains("\"passed\":false") ? "failed" : "ok";
    }

    static long bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * What the call addressed, short: a target locator as {@code role:name} (or its ref, id, row and
     * column), a drag's two ends, a menu path, keys, a do_action step's action name, a batch's size.
     */
    static String target(String tool, JsonNode args) {
        if (args == null || !args.isObject() || args.size() == 0) return "-";
        StringBuilder out = new StringBuilder();
        if (args.hasNonNull("verb")) append(out, "verb", args.get("verb").asText());
        if (args.hasNonNull("target")) out.append(locator(args.get("target")));
        if (args.hasNonNull("from")) append(out, "from", locator(args.get("from")));
        if (args.hasNonNull("to") && args.get("to").isObject()) append(out, "to", locator(args.get("to")));
        for (String key : new String[] {"path", "keys", "text", "item", "button", "window", "ref", "tab", "pane",
                "caption", "depth", "maxNodes", "maxRows", "name"}) {
            if (args.hasNonNull(key)) append(out, key, scalar(args.get(key)));
        }
        if (args.hasNonNull("action")) {
            JsonNode a = args.get("action");
            append(out, "action", a.isObject() ? a.path("action").asText() : a.asText());
        }
        if (args.has("actions") && args.get("actions").isArray()) append(out, "actions", String.valueOf(args.get("actions").size()));
        if (args.has("steps") && args.get("steps").isArray()) append(out, "steps", String.valueOf(args.get("steps").size()));
        return out.length() == 0 ? "-" : out.toString();
    }

    static String locator(JsonNode l) {
        if (l == null || !l.isObject()) return scalar(l);
        if (l.hasNonNull("ref")) return l.get("ref").asText();
        StringBuilder out = new StringBuilder();
        if (l.hasNonNull("window")) out.append(l.get("window").asText()).append('/');
        if (l.hasNonNull("scope")) out.append(scalar(l.get("scope"))).append('/');
        out.append(l.hasNonNull("role") ? l.get("role").asText() : "*");
        if (l.hasNonNull("name")) out.append(':').append(l.get("name").asText());
        if (l.hasNonNull("id")) out.append("#").append(l.get("id").asText());
        if (l.hasNonNull("row")) out.append(" row=").append(scalar(l.get("row")));
        if (l.hasNonNull("column")) out.append(" col=").append(l.get("column").asText());
        if (l.hasNonNull("item")) out.append(" item=").append(scalar(l.get("item")));
        return out.toString();
    }

    private static String scalar(JsonNode v) {
        if (v == null || v.isNull()) return "";
        if (v.isValueNode()) return v.asText();
        if (v.isArray()) {
            StringBuilder b = new StringBuilder();
            for (JsonNode e : v) b.append(b.length() == 0 ? "" : ">").append(scalar(e));
            return b.toString();
        }
        StringBuilder b = new StringBuilder("{");
        Iterator<Map.Entry<String, JsonNode>> it = v.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            b.append(b.length() == 1 ? "" : ",").append(e.getKey()).append(':').append(scalar(e.getValue()));
        }
        return b.append('}').toString();
    }

    private static void append(StringBuilder out, String key, String value) {
        if (out.length() > 0) out.append(' ');
        out.append(key).append('=').append(value);
    }

    /** One line, bounded: a target never breaks the line or runs on. */
    private static String clean(String target) {
        String t = target == null || target.isEmpty() ? "-" : target.replaceAll("[\\r\\n\\t]+", " ");
        return t.length() > MAX_TARGET ? t.substring(0, MAX_TARGET - 3) + "..." : t;
    }

    /** Writes the line to the log and, when one is configured, appends it to the timing file. */
    static void log(String line) {
        LOG.fine(line);
        Path file = file();
        if (file == null) return;
        synchronized (UiMcpTiming.class) {
            try {
                Files.write(file, (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                LOG.warning("could not append the timing line to " + file + ": " + e);
            }
        }
    }

    /** The configured timing file, or null. Resolved per call: a -D set while running takes effect. */
    static Path file() {
        String configured = System.getProperty(FILE_PROP);
        if (configured != null) {
            String c = configured.trim();
            return c.isEmpty() || "off".equalsIgnoreCase(c) ? null : Paths.get(c);
        }
        return Files.isDirectory(DEFAULT_FILE.getParent()) ? DEFAULT_FILE : null;
    }
}
