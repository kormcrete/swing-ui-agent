package io.github.kormcrete.swingui.driver.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A locator's {@code row}: key columns and their cell texts, or {@code {"index": n}}. */
public final class RowSpec {

    public final Integer index;
    public final Map<String, String> keys;

    private RowSpec(Integer index, Map<String, String> keys) {
        this.index = index;
        this.keys = keys;
    }

    public static RowSpec of(Map<String, Object> row) {
        if (row.isEmpty()) throw Locator.bad("'row' needs key columns or {\"index\": n}");
        if (row.containsKey("index")) {
            if (row.size() != 1 || !(row.get("index") instanceof Number)) {
                throw Locator.bad("'row' as {\"index\": n} takes one number and nothing else");
            }
            return new RowSpec(((Number) row.get("index")).intValue(), null);
        }
        Map<String, String> keys = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            keys.put(e.getKey(), String.valueOf(e.getValue()));
        }
        return new RowSpec(null, Collections.unmodifiableMap(keys));
    }

    @Override
    public String toString() {
        return index != null ? "row " + index : "row " + keys;
    }
}
