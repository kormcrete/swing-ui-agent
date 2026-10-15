package io.github.kormcrete.swingui.driver.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The two ways to name a table row a recorded step meant: "by position" (the row's index) and "this value" (the row's
 * key column text, null when the table has no unique column).
 */
public final class RowChoice {

    public final int index;
    /** The row as {@code {"<key column>": "<text>"}}; null when there is no unique column. */
    public final Map<String, Object> value;

    public RowChoice(int index, Map<String, Object> value) {
        this.index = index;
        this.value = value == null ? null : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(value));
    }

    /** The row locator "by position": {@code {"index": n}}. */
    public Map<String, Object> byPosition() {
        return Collections.<String, Object>singletonMap("index", index);
    }

    /** The row locator "this value", or null when there is none. */
    public Map<String, Object> byValue() {
        return value;
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof RowChoice)) return false;
        RowChoice r = (RowChoice) o;
        return r.index == index && (value == null ? r.value == null : value.equals(r.value));
    }

    @Override public int hashCode() {
        return index * 31 + (value == null ? 0 : value.hashCode());
    }

    @Override public String toString() {
        return "RowChoice[index=" + index + ", value=" + value + "]";
    }
}
