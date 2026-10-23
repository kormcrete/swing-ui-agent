package io.github.kormcrete.swingui.journal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.flow.Action;
import io.github.kormcrete.swingui.flow.Flow;
import io.github.kormcrete.swingui.flow.FlowGroup;
import io.github.kormcrete.swingui.flow.FlowJson;
import io.github.kormcrete.swingui.flow.StepNumbers;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A recorded test's run as journal lines, written when it ends from the agent's {@code run_recorded_test} reply: one
 * line per record that ran, in order, each with its step as it ran (references by the values they stood for) and, as
 * its text, the step's {@code say} or else its sentence; a group's {@code say} as a section before its first step; a
 * {@code do} step's own steps after it, pointing at it ({@code "parent"}); then the end line. The agent is unchanged:
 * its reply has what the journal needs.
 */
public final class RecordedJournal {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private RecordedJournal() {}

    /** {@link #write(TestJournal, Flow, JsonNode, long, String)} with no evidence. */
    public static void write(TestJournal journal, Flow flow, JsonNode reply, long startedAtMillis) {
        write(journal, flow, reply, startedAtMillis, null);
    }

    /**
     * Writes the lines of {@code reply}, the run of {@code flow} that started at {@code startedAtMillis}, then the end:
     * {@code passed}, or {@code failed} with the report's first line and {@code evidence} (the report file).
     */
    public static void write(TestJournal journal, Flow flow, JsonNode reply, long startedAtMillis, String evidence) {
        if (journal == null || !journal.stepsOn()) return;
        List<String> labels = StepNumbers.labels(flow.steps);
        Map<String, Integer> flat = new HashMap<String, Integer>();
        for (int i = 0; i < labels.size(); i++) flat.put(labels.get(i), i);
        Map<Integer, String> sections = new HashMap<Integer, String>();
        for (FlowGroup g : flow.groups) {
            if (g.say != null && !g.say.trim().isEmpty()) sections.put(g.from, g.say);
        }
        long t = startedAtMillis;
        for (JsonNode ran : reply.path("steps")) {
            Integer i = flat.get(ran.path("n").asText());
            if (i != null && sections.containsKey(i)) journal.append(JournalLines.section(sections.get(i), t), 0);
            Action record = i == null ? null : flow.steps.get(i);
            JsonNode step = record == null ? NODES.objectNode()
                    : asRan(FlowJson.mapper().valueToTree(record), values(ran.path("resolved")));
            ObjectNode line = line(journal, step, ran, t);
            String say = step.path("say").asText("");
            if (!say.trim().isEmpty()) line.set("text", JournalLines.capTexts(TextNode.valueOf(say)));
            else if (record == null) line.put("text", ran.path("say").asText(ran.path("action").asText("Step")));
            if (ran.has("resolved")) line.set("resolved", ran.get("resolved"));
            children(journal, ran, journal.append(line, 0), t);
            t += Math.max(0, ran.path("ms").asLong(0));
        }
        boolean passed = reply.path("passed").asBoolean(false);
        journal.end(passed ? "passed" : "failed", passed ? null : reply.path("report").asText(null),
                passed ? null : evidence);
    }

    /** The steps a {@code do} step ran ({@code ran.steps}, each with its {@code step}), each line pointing at {@code parent}. */
    private static void children(TestJournal journal, JsonNode ran, int parent, long startedAt) {
        long t = startedAt;
        for (JsonNode child : ran.path("steps")) {
            ObjectNode line = line(journal, child.get("step"), child, t);
            if (parent > 0) line.put("parent", parent);
            children(journal, child, journal.append(line, 0), t);
            t += Math.max(0, child.path("ms").asLong(0));
        }
    }

    /** The line of {@code step}, as {@code ran} says it went; a password it types is a secret of the journal from now on. */
    private static ObjectNode line(TestJournal journal, JsonNode step, JsonNode ran, long t) {
        JsonNode given = step != null && step.isObject() ? step : NODES.objectNode();
        String password = TestJournal.typedPassword(given);
        if (password != null) journal.secret(password);   // its resolved value and later lines must not echo it either
        boolean passed = ran.path("passed").asBoolean(false);
        String reason = ran.path("reason").isTextual() ? ran.path("reason").asText() : null;
        String message = ran.path("message").isTextual() ? withoutReason(ran.path("message").asText(), reason) : null;
        return JournalLines.step(given, passed, message, reason, Math.max(0, ran.path("ms").asLong(0)), t);
    }

    private static String withoutReason(String message, String reason) {
        if (reason == null) return message;
        try {
            return DriverException.stripReason(message, Reason.valueOf(reason));
        } catch (IllegalArgumentException notADriverReason) {
            return message;
        }
    }

    /** A reply's {@code resolved}: each reference as written, and the value it stood for. */
    private static Map<String, String> values(JsonNode resolved) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Iterator<Map.Entry<String, JsonNode>> it = resolved.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getValue().isValueNode() && !e.getValue().isNull()) out.put(e.getKey(), e.getValue().asText());
        }
        return out;
    }

    /** {@code node} with each reference in its texts replaced by its value, and each {@code $${} back to {@code ${}. */
    static JsonNode asRan(JsonNode node, Map<String, String> values) {
        if (node.isTextual()) return TextNode.valueOf(asRan(node.asText(), values));
        if (node.isObject()) {
            ObjectNode out = NODES.objectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> f = it.next();
                out.set(f.getKey(), asRan(f.getValue(), values));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = NODES.arrayNode();
            for (JsonNode e : node) out.add(asRan(e, values));
            return out;
        }
        return node;
    }

    private static String asRan(String text, Map<String, String> values) {
        if (text.indexOf("${") < 0) return text;
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        next:
        while (i < text.length()) {
            if (text.startsWith("$${", i)) {   // a literal ${, never a reference
                sb.append("${");
                i += 3;
                continue;
            }
            if (text.startsWith("${", i)) {
                for (Map.Entry<String, String> v : values.entrySet()) {
                    if (text.startsWith(v.getKey(), i)) {
                        sb.append(v.getValue());
                        i += v.getKey().length();
                        continue next;
                    }
                }
            }
            sb.append(text.charAt(i++));
        }
        return sb.toString();
    }
}
