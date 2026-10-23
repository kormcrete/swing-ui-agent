package io.github.kormcrete.swingui.journal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.flow.FlowJson;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Builds the journal's lines (without {@code seq}, which {@link TestJournal} adds) from a step's JSON, a read, a
 * section or a checkpoint. Pure: no files, no clock. Each line stays small: a {@code value} longer than
 * {@link #VALUE_CAP} characters is cut and marked {@code "capped": true}, each text inside {@code step} is cut the
 * same, and the sentence holds at most {@link StepWords#VALUE_CAP} characters of a value.
 */
public final class JournalLines {

    /** Longest {@code value} (its text, or its JSON text) and longest text inside {@code step}. */
    public static final int VALUE_CAP = 2000;

    /** What a password typed into a {@code password} target becomes. */
    static final String MASK = "***";

    private static final List<String> EXPECTATIONS =
            Arrays.asList("state", "text", "value", "tooltip", "rows", "cell", "selected", "absent", "shown");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final ObjectMapper JSON = FlowJson.registerDriverTypes(new ObjectMapper())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private JournalLines() {}

    /** {@code millis} as {@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'} in UTC, always with milliseconds. */
    public static String time(long millis) {
        return TIME.format(Instant.ofEpochMilli(millis));
    }

    /**
     * The line of a step as do_action takes it: kind {@code check} for {@code expect}, {@code read} for
     * {@code remember}, {@code action} for every other step. {@code message} and {@code reason} are kept only when
     * it failed.
     */
    public static ObjectNode step(JsonNode step, boolean passed, String message, String reason, long ms, long startedAtMillis) {
        JsonNode given = step != null && step.isObject() ? step : NODES.objectNode();
        String verb = given.path("action").isTextual() ? given.path("action").asText() : null;
        String typed = "type".equals(verb) ? textOf(given, "text") : null;
        boolean password = typed != null && "password".equals(textOf(given.path("target"), "role"));
        JsonNode kept = given;
        if (password) {
            ObjectNode hidden = given.deepCopy();
            hidden.put("text", MASK);
            kept = hidden;
            if (message != null && !typed.isEmpty()) message = message.replace(typed, MASK);
        }
        JsonNode capped = capTexts(kept);

        ObjectNode line = NODES.objectNode();
        line.put("t", time(startedAtMillis));
        line.put("kind", "expect".equals(verb) ? "check" : "remember".equals(verb) ? "read" : "action");
        if (verb != null) line.put("verb", verb);
        JsonNode where = "drag".equals(verb) ? given.get("from") : given.get("target");
        if (where != null && where.isObject()) {
            Locator target = locator(where);
            if (target != null) line.put("target", target.toString());
            line.set("locator", capTexts(where));
        }
        String value = password ? MASK : value(verb, given);
        if (value != null) putValue(line, TextNode.valueOf(value));
        if ("expect".equals(verb)) {
            ObjectNode expected = NODES.objectNode();
            for (String field : EXPECTATIONS) {
                if (given.has(field)) expected.set(field, capTexts(given.get(field)));
            }
            line.set("expected", expected);
        }
        line.set("step", capped);
        outcome(line, passed, message, reason);
        line.put("ms", ms);
        line.put("text", StepWords.sentence(capped));
        return line;
    }

    /**
     * The line of a read: {@code verb} is the read's name ({@code text}, {@code rows}, ...), {@code value} what it came
     * back with (a string, number, boolean, list or map is kept as JSON, anything else by its {@code toString()}).
     */
    public static ObjectNode read(String verb, Locator target, Object value, boolean passed, String message, long ms,
                                  long startedAtMillis) {
        return read(verb, target, value, passed, message, null, ms, startedAtMillis);
    }

    /** {@link #read(String, Locator, Object, boolean, String, long, long)} with the failure's reason. */
    static ObjectNode read(String verb, Locator target, Object value, boolean passed, String message, String reason,
                           long ms, long startedAtMillis) {
        ObjectNode line = NODES.objectNode();
        line.put("t", time(startedAtMillis));
        line.put("kind", "read");
        if (verb != null) line.put("verb", verb);
        if (target != null) {
            line.put("target", target.toString());
            line.set("locator", capTexts(JSON.valueToTree(target)));
        }
        if (passed && value != null) putValue(line, toJson(value));
        outcome(line, passed, message, reason);
        line.put("ms", ms);
        line.put("text", StepWords.read(verb, target, passed ? shortText(value) : null));
        return line;
    }

    /** The line that starts a section of the test, named by the test's author. */
    public static ObjectNode section(String text, long at) {
        ObjectNode line = NODES.objectNode();
        line.put("t", time(at));
        line.put("kind", "section");
        line.put("text", text);
        return line;
    }

    /** The line of a checkpoint: its picture and sidecar ({@code png}, {@code json}, relative to the run folder). */
    public static ObjectNode checkpoint(String name, Locator target, List<Locator> masks, String png, String json,
                                        boolean passed, String message, long ms, long at) {
        ObjectNode line = NODES.objectNode();
        line.put("t", time(at));
        line.put("kind", "checkpoint");
        line.put("name", name);
        if (target != null) {
            line.put("target", target.toString());
            line.set("locator", capTexts(JSON.valueToTree(target)));
        }
        if (png != null) line.put("png", png);
        if (json != null) line.put("json", json);
        line.put("masks", masks == null ? 0 : masks.size());
        outcome(line, passed, message, null);
        line.put("ms", ms);
        line.put("text", StepWords.checkpoint(name, target, masks));
        return line;
    }

    private static void outcome(ObjectNode line, boolean passed, String message, String reason) {
        line.put("outcome", passed ? "ok" : "failed");
        if (passed) return;
        if (message != null) line.put("message", message);
        if (reason != null) line.put("reason", reason);
    }

    /** The value of a step, per verb, as one string; null for a step that has none. */
    private static String value(String verb, JsonNode s) {
        if (verb == null) return null;
        switch (verb) {
            case "type":
            case "setClipboard":
                return textOf(s, "text");
            case "press":
                return textOf(s, "keys");
            case "select": {
                List<String> items = strings(s.get("items"));
                return items.isEmpty() ? textOf(s, "item") : join(items, ", ");
            }
            case "menu":
            case "pick":
            case "contextMenu": {
                List<String> path = strings(s.get("path"));
                return path.isEmpty() ? null : join(path, " > ");
            }
            case "check":
                return s.path("checked").asBoolean(true) ? "checked" : "unchecked";
            case "drag": {
                Locator to = locator(s.get("to"));
                return to == null ? null : to.toString();
            }
            case "drop": {
                List<String> files = strings(s.get("files"));
                return files.isEmpty() ? textOf(s, "text") : join(files, ", ");
            }
            case "scroll":
                if (textOf(s, "to") != null) return textOf(s, "to");
                return s.hasNonNull("pages") ? s.get("pages").asText() + " pages" : null;
            case "resizeColumn":
                return s.hasNonNull("delta") ? s.get("delta").asText() : null;
            case "resizeWindow":
                return s.hasNonNull("width") && s.hasNonNull("height")
                        ? s.get("width").asText() + "x" + s.get("height").asText() : null;
            case "answer":
                return textOf(s, "button");
            case "chooseFile":
                return textOf(s, "path");
            case "navigate":
                return textOf(s, "target");
            case "loginAs":
                return textOf(s, "as") != null ? textOf(s, "as") : textOf(s, "user");
            default:
                return null;
        }
    }

    /** Puts {@code value} as {@code "value"}, cut to {@link #VALUE_CAP} characters (with {@code "capped": true}). */
    private static void putValue(ObjectNode line, JsonNode value) {
        String text = value.isTextual() ? value.asText() : value.toString();
        if (text.length() <= VALUE_CAP) {
            line.set("value", value);
            return;
        }
        line.put("value", cut(text, VALUE_CAP));
        line.put("capped", true);
    }

    /** A read's value as JSON. */
    private static JsonNode toJson(Object value) {
        if (value == null) return NODES.nullNode();
        if (value instanceof String) return TextNode.valueOf((String) value);
        if (value instanceof Number || value instanceof Boolean) return JSON.valueToTree(value);
        if (value instanceof Object[]) return toJson(Arrays.asList((Object[]) value));
        if (value instanceof Collection) {
            ArrayNode out = NODES.arrayNode();
            for (Object e : (Collection<?>) value) out.add(toJson(e));
            return out;
        }
        if (value instanceof Map) {
            ObjectNode out = NODES.objectNode();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) out.set(String.valueOf(e.getKey()), toJson(e.getValue()));
            return out;
        }
        return TextNode.valueOf(value.toString());
    }

    /** Enough of a read's value for a sentence, which cuts it again. */
    private static String shortText(Object value) {
        if (value == null) return null;
        if (value instanceof Collection) {
            StringBuilder sb = new StringBuilder();
            for (Object e : (Collection<?>) value) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(e);
                if (sb.length() > StepWords.VALUE_CAP) break;
            }
            return cut(sb.toString(), VALUE_CAP);
        }
        return cut(String.valueOf(value), VALUE_CAP);
    }

    /** {@code node} with each text inside cut to {@link #VALUE_CAP} characters; the same node when none is longer. */
    static JsonNode capTexts(JsonNode node) {
        if (node == null) return null;
        if (node.isTextual()) {
            String text = node.asText();
            return text.length() <= VALUE_CAP ? node : TextNode.valueOf(cut(text, VALUE_CAP));
        }
        if (node.isObject()) {
            ObjectNode out = NODES.objectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> f = it.next();
                out.set(f.getKey(), capTexts(f.getValue()));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = NODES.arrayNode();
            for (JsonNode e : node) out.add(capTexts(e));
            return out;
        }
        return node;
    }

    /** The first {@code max} characters of {@code text}, one fewer when that would split a surrogate pair. */
    private static String cut(String text, int max) {
        if (text.length() <= max) return text;
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end);
    }

    private static Locator locator(JsonNode n) {
        if (n == null || !n.isObject()) return null;
        try {
            return JSON.treeToValue(n, Locator.class);
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static String textOf(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || !v.isValueNode() || v.isNull() ? null : v.asText();
    }

    private static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<String>();
        if (n != null && n.isArray()) {
            for (JsonNode e : n) {
                if (e.isValueNode() && !e.isNull()) out.add(e.asText());
            }
        } else if (n != null && n.isValueNode() && !n.isNull()) {
            out.add(n.asText());
        }
        return out;
    }

    private static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String i : items) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(i);
        }
        return sb.toString();
    }
}
