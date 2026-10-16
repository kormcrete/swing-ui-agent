package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.client.TracingVerbs;
import io.github.kormcrete.swingui.junit.Client;
import io.github.kormcrete.swingui.spi.DomainStep;
import io.github.kormcrete.swingui.spi.ParamType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A domain step's Java body as the steps it makes, with {@code {param}} placeholders where its values went: e.g.
 * {@code addToCart(ui, product)} becomes a click on the table row whose Product is {@code {product}} and a click on
 * Add to cart. The body runs, in the calling thread, on a {@link Client} over {@link TracingVerbs}, so nothing reaches
 * the app.
 *
 * <p>It runs twice. First with sentinels that are found again in the steps: a TEXT param holds {@code {name}}, an INT
 * param a large number of its own (970001, 970011, ...), mapped back to {@code {name}} wherever it shows, in text or
 * as a number. Then with other values (a TEXT param {@code " Ab1, c "}: spaces, a capital, a comma; an INT param 1, one
 * rather than many) and another run id. The first run's steps with those values put in must be the second run's steps; when they
 * differ, the body branches on, changes or computes with a value ({@link #DEPENDS_ON_VALUES}), and its steps are no
 * pattern. Neither run catches every such body (a branch on a value both runs share goes unseen); a step whose body
 * is like that declares its pattern with {@code match(...)}.
 *
 * <p>Not derivable either: a body that reads or checks the app, uses a call with no step form, makes too many steps,
 * or fails (see {@link TracingVerbs}).
 */
public final class BodyTrace {

    /** Why a body whose steps differ between the two runs is not derivable. */
    public static final String DEPENDS_ON_VALUES =
            "the steps depend on the values: the body branches on, changes or computes with a param, or uses the run id";

    private static final int FIRST_INT = 970001;
    private static final int INT_STRIDE = 10;

    /** A body's steps, or why it has none. */
    public static final class Result {
        private final List<ObjectNode> steps;
        private final String why;

        private Result(List<ObjectNode> steps, String why) {
            this.steps = Collections.unmodifiableList(steps);
            this.why = why;
        }

        /** True when {@link #steps} are the body's steps for any values. */
        public boolean derivable() { return why == null; }

        /** The steps, as do_action takes them, with {@code {param}} placeholders; empty when not derivable. */
        public List<ObjectNode> steps() { return steps; }

        /** Why the body has no steps of its own, e.g. "the body reads the app (read), ..."; null when derivable. */
        public String why() { return why; }
    }

    private BodyTrace() {}

    /** Traces {@code step}'s Java body; a step given as declarative steps has none to trace. */
    public static Result of(DomainStep step) {
        if (step.body() == null) {
            throw new IllegalArgumentException(step.id() + " has no Java body: its declarative steps are its steps");
        }
        Values first = new Values(step, 1);
        Values second = new Values(step, 2);
        Run a = run(step, first, "trace-1");
        if (a.why != null) return notDerivable(a.why);
        Run b = run(step, second, "trace-2");
        if (b.why != null) return notDerivable(b.failed ? DEPENDS_ON_VALUES : b.why);
        List<ObjectNode> placeholders = new ArrayList<ObjectNode>();
        for (int i = 0; i < a.steps.size(); i++) {
            ObjectNode s = a.steps.get(i);
            if (i >= b.steps.size() || !first.swap(s, second).equals(b.steps.get(i))) return notDerivable(DEPENDS_ON_VALUES);
            placeholders.add((ObjectNode) first.swap(s, null));
        }
        if (b.steps.size() != a.steps.size()) return notDerivable(DEPENDS_ON_VALUES);
        return new Result(placeholders, null);
    }

    private static Result notDerivable(String why) {
        return new Result(Collections.<ObjectNode>emptyList(), why);
    }

    /** One run's steps, or why it has none ({@code failed}: the body threw on its own, not a trace limit). */
    private static final class Run {
        final List<ObjectNode> steps;
        final String why;
        final boolean failed;

        Run(List<ObjectNode> steps, String why, boolean failed) {
            this.steps = steps;
            this.why = why;
            this.failed = failed;
        }
    }

    private static Run run(DomainStep step, Values values, String runId) {
        TracingVerbs verbs = new TracingVerbs();
        try {
            step.body().run(new Client(verbs, verbs.app(), runId), new MapParams(values.text));
        } catch (RuntimeException | AssertionError | LinkageError e) {
            if (!verbs.derivable()) return new Run(null, verbs.notDerivable(), false);
            return new Run(null, "the body failed: " + (e.getMessage() != null ? e.getMessage() : e.toString()), true);
        }
        if (!verbs.derivable()) return new Run(null, verbs.notDerivable(), false);
        return new Run(verbs.steps(), null, false);
    }

    /** The values one run gives the params, and how to find the first run's again in a step. */
    private static final class Values {
        /** Param name → value as the body reads it. */
        final Map<String, String> text = new LinkedHashMap<String, String>();
        /** INT param name → its number. */
        final Map<String, Integer> numbers = new LinkedHashMap<String, Integer>();
        /** Value as found in a step's text → param name. */
        private final Map<String, String> names = new LinkedHashMap<String, String>();
        private final Pattern found;

        Values(DomainStep step, int run) {
            List<String> alternatives = new ArrayList<String>();
            int i = 0;
            for (DomainStep.Param p : step.params()) {
                if (p.type() == ParamType.INT) {
                    int n = run == 1 ? FIRST_INT + INT_STRIDE * i : 1;
                    numbers.put(p.name(), n);
                    text.put(p.name(), String.valueOf(n));
                    alternatives.add("(?<![0-9])" + Pattern.quote(String.valueOf(n)) + "(?![0-9])");
                } else {
                    text.put(p.name(), run == 1 ? "{" + p.name() + "}" : " Ab" + (i + 1) + ", c ");
                    alternatives.add(Pattern.quote(text.get(p.name())));
                }
                names.put(text.get(p.name()), p.name());
                i++;
            }
            found = alternatives.isEmpty() ? null : Pattern.compile(String.join("|", alternatives));
        }

        /**
         * {@code node} with every value of this run swapped for {@code other}'s value of the same param, or for a
         * {@code {name}} placeholder when {@code other} is null. A number that is an INT param's value stays a number
         * for {@code other}, and becomes the placeholder's text otherwise.
         */
        JsonNode swap(JsonNode node, Values other) {
            if (found == null) return node.deepCopy();
            if (node.isTextual()) return TextNode.valueOf(swapText(node.asText(), other));
            if (node.isIntegralNumber() && node.canConvertToLong()) {
                String name = numberName(node.asLong());
                if (name == null) return node.deepCopy();
                if (other == null) return TextNode.valueOf("{" + name + "}");
                int n = other.numbers.get(name);
                return node.isInt() ? IntNode.valueOf(n) : LongNode.valueOf(n);
            }
            if (node.isObject()) {
                ObjectNode out = JsonNodeFactory.instance.objectNode();
                for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> f = it.next();
                    out.set(swapText(f.getKey(), other), swap(f.getValue(), other));
                }
                return out;
            }
            if (node.isArray()) {
                ArrayNode out = JsonNodeFactory.instance.arrayNode();
                for (JsonNode e : node) out.add(swap(e, other));
                return out;
            }
            return node.deepCopy();
        }

        private String swapText(String s, Values other) {
            Matcher m = found.matcher(s);
            StringBuffer out = new StringBuffer();
            while (m.find()) {
                String name = names.get(m.group());
                m.appendReplacement(out, Matcher.quoteReplacement(other == null ? "{" + name + "}" : other.text.get(name)));
            }
            m.appendTail(out);
            return out.toString();
        }

        private String numberName(long n) {
            for (Map.Entry<String, Integer> e : numbers.entrySet()) {
                if (e.getValue() == n) return e.getKey();
            }
            return null;
        }
    }
}
