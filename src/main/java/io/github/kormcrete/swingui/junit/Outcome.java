package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.flow.FlowExecutor;
import io.github.kormcrete.swingui.flow.refs.JsonPaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What one action changed in the app, as {@link Client#outcomeOf} read it: a JSON object with only what changed -
 * {@code opened} and {@code closed} windows, dialogs and tabs, {@code fields} and {@code texts} by their names,
 * {@code rows} of each table ({@code added}, {@code removed}, {@code changed}, {@code count}), {@code items} of lists and
 * trees, what is {@code selected}, a {@code message} dialog's text, and {@code partial} when a reading ran out of time.
 * A value is read by a path from {@code $}: {@code text("$.rows.Orders.added[0].Number")}; a key with spaces, dots or
 * quotes in brackets: {@code ['Order no.']}.
 *
 * <p>An app may show a change late (a row added after the action returned). {@link #read}, {@link #text} and
 * {@link #number} then read the app again, against the same reading before the action, every
 * {@value FlowExecutor#LINK_POLL_MS} ms until the path has its value or the client's wait is over, as a recorded test's
 * link waits.
 */
public final class Outcome {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** How much of the outcome a failure quotes. */
    private static final int QUOTED = 500;

    /** The latest reading: the one taken right after the action, or a later one a read waited for. */
    private JsonNode json;
    /** Reads what the action changed so far again; null when it cannot. */
    private final Supplier<Map<String, Object>> again;
    private final long waitMillis;

    /** An outcome that is read once and never again. */
    Outcome(JsonNode json) {
        this(json, null, 0);
    }

    Outcome(JsonNode json, Supplier<Map<String, Object>> again, long waitMillis) {
        this.json = json.deepCopy();
        this.again = again;
        this.waitMillis = waitMillis;
    }

    /** What {@code changed} holds ({@code Outcomes.diff}'s map), read again by {@code again} for up to {@code waitMillis}. */
    static Outcome of(Map<String, Object> changed, Supplier<Map<String, Object>> again, long waitMillis) {
        return new Outcome(JSON.valueToTree(changed), again, waitMillis);
    }

    /**
     * The whole outcome, a copy: the reading taken right after the action, or the latest one a {@link #read},
     * {@link #text} or {@link #number} waited for.
     */
    public synchronized JsonNode json() {
        return json.deepCopy();
    }

    /**
     * The values {@code path} selects, in order (copies): {@code .key}, {@code ['key']}, {@code [n]}, {@code [-n]} (from
     * the end), {@code [*]}, {@code ..key} (anywhere below) and {@code [?(@.key == 'text')]}. While it selects nothing, the
     * app is read again until the client's wait is over; then the list is empty. Throws IllegalArgumentException for a
     * path that does not read, or does not start with {@code $}.
     */
    public List<JsonNode> read(String path) {
        JsonPaths.check(path);
        List<JsonNode> hits = until(path, false);
        List<JsonNode> copies = new ArrayList<JsonNode>();
        for (JsonNode h : hits) copies.add(h.deepCopy());
        return Collections.unmodifiableList(copies);
    }

    /**
     * The text of the one value {@code path} selects ({@link #read}): a number or a yes/no as its text. Waits like
     * {@link #read} for exactly one value; then fails as a UiActionFailed naming the path when it selects nothing, several
     * values, or an object or list.
     */
    public String text(String path) {
        JsonPaths.check(path);
        List<JsonNode> hits = until(path, true);
        String problem = problem(hits);
        if (problem == null) return hits.get(0).asText();
        throw failed(path, Reason.NOT_FOUND, "what the action changed holds " + problem + " at " + path);
    }

    /**
     * {@link #text} as a whole number, e.g. for a plugin call's number param; fails as a UiActionFailed (BAD_INPUT) when
     * the text is no whole number in int range.
     */
    public int number(String path) {
        String text = text(path);
        if (text.matches("\\d+")) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException tooBig) {
                // said below
            }
        }
        throw failed(path, Reason.BAD_INPUT, "what the action changed holds '" + text + "' at " + path
                + ", not a whole number in int range");
    }

    /** Why {@code hits} is not one value; null when it is. */
    private static String problem(List<JsonNode> hits) {
        if (hits.isEmpty()) return "no value";
        if (hits.size() > 1) return hits.size() + " values, not one";
        if (hits.get(0).isNull()) return "null, not one value";
        if (!hits.get(0).isValueNode()) return "an object or list, not one value";
        return null;
    }

    /** What {@code path} selects, reading again while it selects nothing (or, with {@code one}, not one value). */
    private synchronized List<JsonNode> until(String path, boolean one) {
        long deadline = System.nanoTime() + waitMillis * 1_000_000L;
        while (true) {
            List<JsonNode> hits = JsonPaths.select(json, path);
            boolean done = one ? problem(hits) == null : !hits.isEmpty();
            if (done || again == null || System.nanoTime() - deadline >= 0) return hits;
            try {
                Thread.sleep(FlowExecutor.LINK_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return hits;
            }
            try {
                json = JSON.valueToTree(again.get());
            } catch (DriverException e) {
                throw new UiActionFailed("outcome " + path, null, e, Collections.<String>emptyList());
            }
        }
    }

    private UiActionFailed failed(String path, Reason reason, String message) {
        String all = json().toString();
        if (all.length() > QUOTED) all = all.substring(0, QUOTED) + "…";
        return new UiActionFailed("outcome " + path, null, new DriverException(reason, message + " (it changed " + all + ")"),
                Collections.<String>emptyList());
    }

    @Override
    public synchronized String toString() {
        return json.toString();
    }
}
