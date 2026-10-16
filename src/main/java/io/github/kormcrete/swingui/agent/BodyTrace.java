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
 * A domain step's Java body as the steps it makes. {@link #of} gives them with {@code {param}} placeholders where its
 * values went, as a pattern: e.g. {@code addToCart(ui, product)} becomes a click on the table row whose Product is
 * {@code {product}} and a click on Add to cart. {@link #steps} gives them for concrete values. The body runs, in the
 * calling thread, on a {@link Client} over {@link TracingVerbs}: its Client calls reach no app. Anything else the
 * body does (plugin or app code, files, a database) runs for real, once per run, with the values below.
 *
 * <p>{@link #of} runs the body twice. First with sentinels that are found again in the steps: a TEXT param holds
 * {@code {name}}, an INT param a large number of its own (970001, 970011, ...), mapped back to {@code {name}} wherever
 * it shows, in text or as a number. Then with other values (a TEXT param {@code " Ab1, c "}: spaces, a capital, a
 * comma; an INT param 1, one rather than many) and another run id. The first run's steps with those values put in must
 * be the second run's steps; when they differ, the body branches on, changes or computes with a value
 * ({@link #DEPENDS_ON_VALUES}), and its steps are no pattern. Two runs do not catch every such body: a branch both runs
 * take alike goes unseen ({@code qty > 0}, {@code product.isEmpty()}, {@code product.equals("Gel pen, black")}), and a
 * value from outside the params that is the same in both runs (e.g. {@code LocalDate.now()} typed into a field) is
 * kept as a literal. A step whose body is like that declares its pattern with {@code match(...)}.
 *
 * <p>Not derivable either: a body that reads or checks the app, reads a secret, uses a call with no step form, makes
 * too many steps, or fails (see {@link TracingVerbs}). Neither method throws for a misbehaving body.
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
        private final List<String> numberPlaceholders;
        private final String why;

        private Result(List<ObjectNode> steps, List<String> numberPlaceholders, String why) {
            this.steps = Collections.unmodifiableList(steps);
            this.numberPlaceholders = Collections.unmodifiableList(numberPlaceholders);
            this.why = why;
        }

        /** True when {@link #steps} are the body's steps for any values ({@link #of}), or for the values given. */
        public boolean derivable() { return why == null; }

        /**
         * The steps, one step JSON each, in the order the body made them; empty when not derivable. From {@link #steps}
         * they are as do_action takes them. From {@link #of} they hold {@code {param}} placeholders, which do_action
         * does not take: a placeholder in text stands for the value as text, and one where the step has a number
         * (an INT param passed as a count, a row index, ...) is the text {@code "{name}"} at a
         * {@link #numberPlaceholders} pointer.
         */
        public List<ObjectNode> steps() { return steps; }

        /**
         * JSON pointers into {@link #steps} (as a list: {@code /0/target/row/index}) of the placeholders that stand
         * for a number; empty from {@link #steps}.
         */
        public List<String> numberPlaceholders() { return numberPlaceholders; }

        /** Why the body has no steps of its own, e.g. "the body reads the app (read), ..."; null when derivable. */
        public String why() { return why; }
    }

    private BodyTrace() {}

    /** Traces {@code step}'s Java body as a pattern; a step given as declarative steps has none to trace. */
    public static Result of(DomainStep step) {
        requireBody(step);
        Values first = new Values(step, 1);
        Values second = new Values(step, 2);
        Run a = run(step, first.text, "trace-1");
        if (a.why != null) return notDerivable(a.why);
        Run b = run(step, second.text, "trace-2");
        if (b.why != null) return notDerivable(b.failed ? DEPENDS_ON_VALUES : b.why);
        if (b.steps.size() != a.steps.size()) return notDerivable(DEPENDS_ON_VALUES);
        List<ObjectNode> placeholders = new ArrayList<ObjectNode>();
        List<String> numbers = new ArrayList<String>();
        for (int i = 0; i < a.steps.size(); i++) {
            ObjectNode s = a.steps.get(i);
            if (!first.swap(s, second, "", null).equals(b.steps.get(i))) return notDerivable(DEPENDS_ON_VALUES);
            placeholders.add((ObjectNode) first.swap(s, null, "/" + i, numbers));
        }
        return new Result(placeholders, numbers, null);
    }

    /**
     * The steps {@code step}'s Java body makes for {@code values} (param name to value as text; an optional param
     * left out holds its default), e.g. for a Java test that writes them out. Run twice with two run ids: a body
     * whose steps differ is not derivable ({@link #DEPENDS_ON_VALUES}).
     */
    public static Result steps(DomainStep step, Map<String, String> values) {
        requireBody(step);
        Map<String, String> all = new LinkedHashMap<String, String>();
        for (DomainStep.Param p : step.params()) {
            if (p.optional()) all.put(p.name(), p.defaultValue());
        }
        all.putAll(values);
        Run a = run(step, all, "trace-1");
        if (a.why != null) return notDerivable(a.why);
        Run b = run(step, all, "trace-2");
        if (b.why != null) return notDerivable(b.failed ? DEPENDS_ON_VALUES : b.why);
        if (!a.steps.equals(b.steps)) return notDerivable(DEPENDS_ON_VALUES);
        return new Result(a.steps, Collections.<String>emptyList(), null);
    }

    private static void requireBody(DomainStep step) {
        if (step.body() == null) {
            throw new IllegalArgumentException(step.id() + " has no Java body: its declarative steps are its steps");
        }
    }

    private static Result notDerivable(String why) {
        return new Result(Collections.<ObjectNode>emptyList(), Collections.<String>emptyList(), why);
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

    private static Run run(DomainStep step, Map<String, String> values, String runId) {
        TracingVerbs verbs = new TracingVerbs();
        try {
            step.body().run(new Client(verbs, verbs.app(), runId), new MapParams(values));
        } catch (Exception | AssertionError | LinkageError | StackOverflowError e) {
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
         * for {@code other}; for a placeholder it becomes the placeholder's text, and its JSON pointer ({@code path}
         * below the list of steps) goes into {@code numbers}.
         */
        JsonNode swap(JsonNode node, Values other, String path, List<String> numbers) {
            if (found == null) return node.deepCopy();
            if (node.isTextual()) return TextNode.valueOf(swapText(node.asText(), other));
            if (node.isIntegralNumber() && node.canConvertToLong()) {
                String name = numberName(node.asLong());
                if (name == null) return node.deepCopy();
                if (other != null) {
                    int n = other.numbers.get(name);
                    return node.isInt() ? IntNode.valueOf(n) : LongNode.valueOf(n);
                }
                if (numbers != null) numbers.add(path);
                return TextNode.valueOf("{" + name + "}");
            }
            if (node.isObject()) {
                ObjectNode out = JsonNodeFactory.instance.objectNode();
                for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> f = it.next();
                    String key = swapText(f.getKey(), other);
                    out.set(key, swap(f.getValue(), other, path + "/" + key.replace("~", "~0").replace("/", "~1"), numbers));
                }
                return out;
            }
            if (node.isArray()) {
                ArrayNode out = JsonNodeFactory.instance.arrayNode();
                int i = 0;
                for (JsonNode e : node) out.add(swap(e, other, path + "/" + i++, numbers));
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
