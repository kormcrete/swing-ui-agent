package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.outcome.Outcomes;
import io.github.kormcrete.swingui.flow.FlowJson;
import io.github.kormcrete.swingui.flow.refs.JsonPaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a fold's raw steps changed, as the one outcome of the fold: a later step links to what the fold made as it would
 * to one raw step. Their outcomes are merged in order: a value (a field, a text, what is selected) the later step changed
 * again is the later one; the rows and items added, removed or changed, and the titles opened or closed, add up (so a
 * table two raw steps each added a row to has two added rows, which are never linked by position). The value index is
 * theirs with each path moved to where the value sits in the merged outcome; a value whose place a later step took
 * leaves it.
 */
final class FoldOutcomes {

    private FoldOutcomes() {}

    /** The merged outcome of {@code raw} and its value index; null when none of them changed anything known. */
    static final class Merged {
        final ObjectNode outcome;
        final Map<String, String> newValues;

        Merged(ObjectNode outcome, Map<String, String> newValues) {
            this.outcome = outcome;
            this.newValues = newValues;
        }
    }

    static Merged of(List<RecordedStep> raw) {
        ObjectNode merged = null;
        List<Map<String, String>> moved = new ArrayList<Map<String, String>>();
        for (RecordedStep r : raw) {
            ObjectNode o = r.outcome != null ? r.outcome : r.newValues.isEmpty() ? null : indexTree(r.newValues);
            if (o == null) continue;
            if (merged == null) merged = FlowJson.mapper().createObjectNode();
            Map<String, Integer> offsets = new HashMap<String, Integer>();
            merge(merged, o, "", offsets);
            Map<String, String> paths = new LinkedHashMap<String, String>();
            for (Map.Entry<String, String> e : r.newValues.entrySet()) paths.put(e.getKey(), shifted(e.getValue(), offsets));
            moved.add(paths);
        }
        if (merged == null) return null;
        // the latest step's values first, as a step's own come in its order
        Map<String, String> index = new LinkedHashMap<String, String>();
        for (int k = moved.size() - 1; k >= 0; k--) {
            for (Map.Entry<String, String> e : moved.get(k).entrySet()) {
                if (index.containsKey(e.getKey()) || !holds(merged, e.getValue(), e.getKey())) continue;
                index.put(e.getKey(), e.getValue());
            }
        }
        return new Merged(merged, index);
    }

    /** {@code src} into {@code into} at {@code path}; each list it appended to, with the length it had, in {@code offsets}. */
    private static void merge(ObjectNode into, JsonNode src, String path, Map<String, Integer> offsets) {
        for (Iterator<Map.Entry<String, JsonNode>> it = src.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String at = path + Outcomes.key(e.getKey());
            JsonNode was = into.get(e.getKey());
            JsonNode now = e.getValue();
            if (was instanceof ObjectNode && now.isObject()) {
                merge((ObjectNode) was, now, at, offsets);
            } else if (was instanceof ArrayNode && now.isArray() && !at.startsWith(".selected")) {
                // added, removed, changed rows; opened or closed titles: both steps made them
                ArrayNode list = (ArrayNode) was;
                offsets.put(at, list.size());
                for (JsonNode n : now) if (list.size() < Outcomes.MAX_ENTRIES) list.add(n.deepCopy());
            } else if ("partial".equals(e.getKey()) && path.isEmpty()) {
                if (now.asBoolean(false)) into.put("partial", true);
            } else {
                into.set(e.getKey(), now.deepCopy());
            }
        }
    }

    private static final Pattern INDEX = Pattern.compile("\\[(\\d+)\\]");

    /** {@code path} with its position in a list another step's entries now come before moved on. */
    private static String shifted(String path, Map<String, Integer> offsets) {
        for (Map.Entry<String, Integer> o : offsets.entrySet()) {
            if (!path.startsWith(o.getKey() + "[")) continue;
            Matcher m = INDEX.matcher(path);
            if (!m.find(o.getKey().length()) || m.start() != o.getKey().length()) continue;
            int at = Integer.parseInt(m.group(1)) + o.getValue();
            return path.substring(0, m.start()) + "[" + at + "]" + path.substring(m.end());
        }
        return path;
    }

    /** Whether {@code outcome} holds {@code value}, and only it, at {@code path}. */
    private static boolean holds(ObjectNode outcome, String path, String value) {
        if (path.isEmpty()) return false;
        List<JsonNode> hits = JsonPaths.select(outcome, "$" + path);
        return hits.size() == 1 && hits.get(0).isValueNode() && value.equals(hits.get(0).asText());
    }

    /** An outcome holding only the values of a value index, each at its path. */
    static ObjectNode indexTree(Map<String, String> newValues) {
        ObjectNode root = FlowJson.mapper().createObjectNode();
        for (Map.Entry<String, String> v : newValues.entrySet()) {
            try {
                JsonPaths.put(root, "$" + v.getValue(), v.getKey());
            } catch (IllegalArgumentException clash) {
                // two values on one path cannot both be kept: the first stays
            }
        }
        return root;
    }
}
