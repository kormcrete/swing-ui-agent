package io.github.kormcrete.swingui.spi;

import io.github.kormcrete.swingui.junit.Client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * One step of a plugin's {@link Vocabulary}: an id, a kind (an action, or a check), a sentence with {@code {param}}
 * placeholders, the params, and a body that does the step. Built with {@link #act} or {@link #check}:
 * <pre>{@code
 * DomainStep.act("addQtyToCart", "Add {qty} × {product} to the cart")
 *         .about("Adds a product to the cart of the open new order and sets its quantity")
 *         .param("product", ParamType.TEXT).choices(this::productNames)
 *         .param("qty", ParamType.INT)
 *         .code((ui, p) -> OrderDeskSteps.addQtyToCart(ui, p.text("product"), p.integer("qty")))
 *         .java("io.github.kormcrete.orderdesk.uitest.OrderDeskSteps", "addQtyToCart(ui, {product}, {qty})")
 *         .build();
 * }</pre>
 * The builder takes what it is given; the content is checked when the plugin becomes ready (see {@link Vocabulary}):
 * the id starts with a letter and holds letters, digits, {@code _} and {@code -}; param names start with a letter
 * and hold letters, digits and {@code _}, each declared once; every {@code {name}} in the sentence, in
 * {@link Builder#steps} and in the {@link Builder#java} call is a declared param; exactly one body; each
 * {@link Builder#steps}, {@link Builder#match} and {@link Builder#matchAlso} entry is a JSON object.
 * A step's values are concrete: nothing is captured or kept between steps.
 */
public final class DomainStep {

    /** What a step does: changes the app, or checks what the app shows. */
    public enum Kind { ACT, CHECK }

    /**
     * A Java body: does the step through {@code ui}, the same {@link Client} a UI test uses, with the step's values.
     * A failure throws (a failed action or check of {@code ui} already does). A value the step makes, such as a new
     * order number, belongs in a helper method that returns it (for Java tests, named by {@link Builder#java}); the
     * body itself returns nothing.
     */
    public interface Body {
        void run(Client ui, Params params);
    }

    /** One declared param. */
    public static final class Param {
        private final String name;
        private final ParamType type;
        private final boolean optional;
        private final String defaultValue;
        private final Supplier<List<String>> choices;

        Param(String name, ParamType type, boolean optional, String defaultValue, Supplier<List<String>> choices) {
            this.name = name;
            this.type = type;
            this.optional = optional;
            this.defaultValue = defaultValue;
            this.choices = choices;
        }

        public String name() { return name; }
        public ParamType type() { return type; }
        /** True when a call may leave it out: it then holds {@link #defaultValue}. */
        public boolean optional() { return optional; }
        /** The value an optional param holds when not given; null for a required one. */
        public String defaultValue() { return defaultValue; }
        /** The values that fit right now (e.g. the product names the app lists), asked for live; null when none. */
        public Supplier<List<String>> choices() { return choices; }

        Param optional(String dflt) { return new Param(name, type, true, dflt, choices); }
        Param choices(Supplier<List<String>> c) { return new Param(name, type, optional, defaultValue, c); }
    }

    private final String id;
    private final Kind kind;
    private final String sentence;
    private final String about;
    private final List<Param> params;
    private final Body body;
    private final List<String> steps;
    private final List<String> match;
    private final List<List<String>> matchAlso;
    private final String javaClass;
    private final String javaCall;
    private final boolean autoFold;

    private DomainStep(Builder b) {
        this.id = b.id;
        this.kind = b.kind;
        this.sentence = b.sentence;
        this.about = b.about;
        this.params = Collections.unmodifiableList(new ArrayList<Param>(b.params));
        this.body = b.body;
        this.steps = b.steps;
        this.match = b.match;
        this.matchAlso = Collections.unmodifiableList(new ArrayList<List<String>>(b.matchAlso));
        this.javaClass = b.javaClass;
        this.javaCall = b.javaCall;
        this.autoFold = b.autoFold;
    }

    /** A step that changes the app, e.g. {@code act("addToCart", "Add {product} to the cart")}. */
    public static Builder act(String id, String sentence) {
        return new Builder(id, Kind.ACT, sentence);
    }

    /** A step that checks what the app shows, e.g. {@code check("cartTotalIs", "The cart total is {total}")}. */
    public static Builder check(String id, String sentence) {
        return new Builder(id, Kind.CHECK, sentence);
    }

    public String id() { return id; }
    public Kind kind() { return kind; }
    /** The sentence with {@code {param}} placeholders, e.g. "Add {product} to the cart". */
    public String sentence() { return sentence; }
    /** One line for the agent on when to use the step; null when not given. */
    public String about() { return about; }
    public List<Param> params() { return params; }
    /** The Java body; null for a step given as {@link #steps}. */
    public Body body() { return body; }
    /** The declarative body: step JSON with {@code {param}} placeholders; empty for a Java body. */
    public List<String> steps() { return steps; }
    /** The explicit match pattern, one step JSON each; empty when the pattern is derived from the body. */
    public List<String> match() { return match; }
    /** Further patterns: other ways to do the same thing. */
    public List<List<String>> matchAlso() { return matchAlso; }
    /** The class a Java test calls for this step; null when the step names none. */
    public String javaClass() { return javaClass; }
    /** The call a Java test makes, e.g. {@code addQtyToCart(ui, {product}, {qty})}; null when the step names none. */
    public String javaCall() { return javaCall; }
    /** Whether a recording folds matching steps into this one at once (true) or only suggests it (false). */
    public boolean autoFold() { return autoFold; }

    /**
     * Builds a {@link DomainStep}. Its methods throw only on null arguments, and on {@link #optional} or
     * {@link #choices} before any {@link #param}; everything else is checked when the plugin becomes ready.
     */
    public static final class Builder {
        private final String id;
        private final Kind kind;
        private final String sentence;
        private String about;
        private final List<Param> params = new ArrayList<Param>();
        private Body body;
        private List<String> steps = Collections.emptyList();
        private List<String> match = Collections.emptyList();
        private final List<List<String>> matchAlso = new ArrayList<List<String>>();
        private String javaClass;
        private String javaCall;
        private boolean autoFold = true;

        private Builder(String id, Kind kind, String sentence) {
            this.id = Objects.requireNonNull(id, "id");
            this.kind = kind;
            this.sentence = Objects.requireNonNull(sentence, "sentence");
        }

        /** One line for the agent on when to use the step. */
        public Builder about(String text) {
            this.about = Objects.requireNonNull(text, "text");
            return this;
        }

        /** Declares a required param; {@link #optional} and {@link #choices} that follow apply to it. */
        public Builder param(String name, ParamType type) {
            params.add(new Param(Objects.requireNonNull(name, "name"), Objects.requireNonNull(type, "type"), false, null,
                    null));
            return this;
        }

        /** Makes the param declared last optional: left out, it holds {@code defaultValue}. */
        public Builder optional(String defaultValue) {
            Objects.requireNonNull(defaultValue, "defaultValue");
            params.set(lastParam("optional"), params.get(lastParam("optional")).optional(defaultValue));
            return this;
        }

        /**
         * The values that fit the param declared last, asked for live when the agent looks the step up (e.g. the
         * product names the app lists). They help the agent pick a value; they do not limit what a call may pass.
         */
        public Builder choices(Supplier<List<String>> choices) {
            Objects.requireNonNull(choices, "choices");
            params.set(lastParam("choices"), params.get(lastParam("choices")).choices(choices));
            return this;
        }

        /** The Java body (recommended): it can wait, read and check through the {@link Client}. */
        public Builder code(Body body) {
            this.body = Objects.requireNonNull(body, "body");
            return this;
        }

        /**
         * A declarative body instead of {@link #code}: step JSON as a recorded test holds it, with {@code {param}}
         * placeholders, e.g. {@code {"action":"click","target":{"role":"button","name":"Add to cart"}}}.
         */
        public Builder steps(String... stepJson) {
            this.steps = list(stepJson);
            return this;
        }

        /** The pattern a recording must match to fold into this step, one step JSON each; replaces the derived one. */
        public Builder match(String... patternStepJson) {
            this.match = list(patternStepJson);
            return this;
        }

        /** Another pattern for the same step (another way to do the same thing); may be given more than once. */
        public Builder matchAlso(String... patternStepJson) {
            this.matchAlso.add(list(patternStepJson));
            return this;
        }

        /**
         * The typed call a Java test makes for this step: {@code className}'s static method, e.g.
         * {@code java("io.github.kormcrete.orderdesk.uitest.OrderDeskSteps", "addQtyToCart(ui, {product}, {qty})")}.
         */
        public Builder java(String className, String call) {
            this.javaClass = Objects.requireNonNull(className, "className");
            this.javaCall = Objects.requireNonNull(call, "call");
            return this;
        }

        /** False: a recording only suggests this step instead of folding matching steps into it. Default true. */
        public Builder autoFold(boolean on) {
            this.autoFold = on;
            return this;
        }

        public DomainStep build() {
            return new DomainStep(this);
        }

        private int lastParam(String what) {
            if (params.isEmpty()) throw new IllegalStateException(what + "(...) applies to a param: declare one first");
            return params.size() - 1;
        }

        private static List<String> list(String[] json) {
            for (String s : Objects.requireNonNull(json, "json")) Objects.requireNonNull(s, "json");
            return Collections.unmodifiableList(new ArrayList<String>(Arrays.asList(json)));
        }
    }
}
