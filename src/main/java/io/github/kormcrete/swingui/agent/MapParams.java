package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.spi.Params;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A domain step's values by param name, as text; a name the map does not hold is no param of the step. */
final class MapParams implements Params {

    private final Map<String, String> values;

    MapParams(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<String, String>(values));
    }

    @Override public String text(String name) {
        if (!values.containsKey(name)) throw new IllegalArgumentException("the step has no param '" + name + "'");
        return values.get(name);
    }

    @Override public int integer(String name) {
        String v = text(name);
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("param '" + name + "' is not a whole number: " + v);
        }
    }
}
