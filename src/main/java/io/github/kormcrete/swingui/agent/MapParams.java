package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.spi.DomainStep;
import io.github.kormcrete.swingui.spi.ParamType;
import io.github.kormcrete.swingui.spi.Params;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A domain step's values by param name, as text, checked against the step's params by {@link #of}: every run of a
 * step (do_action, a trace) goes through it, so bad values are refused the same way everywhere, before any body runs.
 * The body's own mistakes ({@link #text} of a name the step does not declare, {@link #integer} of a TEXT param) throw
 * IllegalArgumentException naming the param.
 */
final class MapParams implements Params {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z]\\w*)\\}");

    private final DomainStep step;
    private final Map<String, String> values;

    private MapParams(DomainStep step, Map<String, String> values) {
        this.step = step;
        this.values = Collections.unmodifiableMap(values);
    }

    /**
     * {@code given} (null: none) for {@code step}, an optional param left out holding its default. BAD_INPUT for a
     * name the step has no param for, a null value, a required param left out, or an INT value that is not a whole
     * number in int range (digits only).
     */
    static MapParams of(DomainStep step, Map<String, String> given) {
        Map<String, String> in = given == null ? Collections.<String, String>emptyMap() : given;
        List<String> names = new ArrayList<String>();
        for (DomainStep.Param p : step.params()) names.add(p.name());
        for (Map.Entry<String, String> e : in.entrySet()) {
            if (!names.contains(e.getKey())) {
                throw bad(step.id() + " has no param '" + e.getKey() + "' ("
                        + (names.isEmpty() ? "it has none" : "its params: " + String.join(", ", names)) + ")");
            }
            if (e.getValue() == null) throw bad("param '" + e.getKey() + "' of " + step.id() + " has no value");
        }
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (DomainStep.Param p : step.params()) {
            String v = in.containsKey(p.name()) ? in.get(p.name()) : p.optional() ? p.defaultValue() : null;
            if (v == null) throw bad(step.id() + " needs param '" + p.name() + "'");
            if (p.type() == ParamType.INT && !isInt(v)) {
                throw bad("param '" + p.name() + "' of " + step.id() + " is INT: a whole number in int range, not '" + v + "'");
            }
            values.put(p.name(), v);
        }
        return new MapParams(step, values);
    }

    /** True for digits only that fit an int: what an INT param holds. */
    static boolean isInt(String digits) {
        if (digits == null || !digits.matches("\\d+")) return false;
        try {
            Integer.parseInt(digits);
            return true;
        } catch (NumberFormatException tooBig) {
            return false;
        }
    }

    /** The values, every param of the step present. */
    Map<String, String> values() {
        return values;
    }

    /** {@code text} with each {@code {name}} of a param replaced by its value, e.g. the step's filled sentence. */
    String fill(String text) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String v = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(v != null ? v : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The param {@code name}'s type; null when the step has no such param. */
    ParamType type(String name) {
        for (DomainStep.Param p : step.params()) if (p.name().equals(name)) return p.type();
        return null;
    }

    @Override public String text(String name) {
        if (!values.containsKey(name)) throw new IllegalArgumentException(step.id() + " has no param '" + name + "'");
        return values.get(name);
    }

    @Override public int integer(String name) {
        String v = text(name);
        if (type(name) != ParamType.INT) {
            throw new IllegalArgumentException("param '" + name + "' of " + step.id() + " is " + type(name)
                    + ", not INT: read it with text(\"" + name + "\")");
        }
        return Integer.parseInt(v); // checked by of()
    }

    private static DriverException bad(String message) {
        return new DriverException(Reason.BAD_INPUT, message);
    }
}
