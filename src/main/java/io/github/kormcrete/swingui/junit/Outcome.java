package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.flow.refs.JsonPaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * What one action changed in the app, as {@link Client#outcomeOf} read it: a JSON object with only what changed -
 * {@code opened} and {@code closed} windows, dialogs and tabs, {@code fields} and {@code texts} by their names,
 * {@code rows} of each table ({@code added}, {@code removed}, {@code changed}, {@code count}), {@code items} of lists and
 * trees, what is {@code selected}, a {@code message} dialog's text, and {@code partial} when a reading ran out of time.
 * A value is read by a path from {@code $}: {@code text("$.rows.Orders.added[0].Number")}; a key with spaces, dots or
 * quotes in brackets: {@code ['Order no.']}.
 */
public final class Outcome {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** How much of the outcome a failure quotes. */
    private static final int QUOTED = 500;

    private final JsonNode json;

    Outcome(JsonNode json) {
        this.json = json.deepCopy();
    }

    /** What {@code changed} holds, JSON-like ({@code Outcomes.diff}'s map). */
    static Outcome of(Map<String, Object> changed) {
        return new Outcome(JSON.valueToTree(changed));
    }

    /** The whole outcome, a copy. */
    public JsonNode json() {
        return json.deepCopy();
    }

    /**
     * The values {@code path} selects, in order: {@code .key}, {@code ['key']}, {@code [n]}, {@code [-n]} (from the end),
     * {@code [*]}, {@code ..key} (anywhere below) and {@code [?(@.key == 'text')]}; none when it selects nothing. Throws
     * IllegalArgumentException for a path that does not read, or does not start with {@code $}.
     */
    public List<JsonNode> read(String path) {
        return Collections.unmodifiableList(JsonPaths.select(json, path));
    }

    /**
     * The text of the one value {@code path} selects ({@link #read}): a number or a yes/no as its text. Fails as a
     * UiActionFailed naming the path when it selects nothing, several values, or an object or list.
     */
    public String text(String path) {
        List<JsonNode> hits = read(path);
        String problem = hits.isEmpty() ? "no value"
                : hits.size() > 1 ? hits.size() + " values, not one"
                : !hits.get(0).isValueNode() || hits.get(0).isNull() ? (hits.get(0).isNull() ? "null" : "an object or list")
                        + ", not one value"
                : null;
        if (problem == null) return hits.get(0).asText();
        String all = json.toString();
        if (all.length() > QUOTED) all = all.substring(0, QUOTED) + "…";
        throw new UiActionFailed("outcome " + path, null, new DriverException(Reason.NOT_FOUND,
                "what the action changed holds " + problem + " at " + path + " (it changed " + all + ")"),
                Collections.<String>emptyList());
    }

    @Override
    public String toString() {
        return json.toString();
    }
}
