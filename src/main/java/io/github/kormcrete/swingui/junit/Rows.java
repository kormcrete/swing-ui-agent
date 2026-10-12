package io.github.kormcrete.swingui.junit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Row specs: column header → cell text, or the row index. */
public final class Rows {
    private Rows() {}

    public static Map<String, Object> row(String column, Object value) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put(column, value);
        return m;
    }

    public static Map<String, Object> row(String c1, Object v1, String c2, Object v2) {
        Map<String, Object> m = row(c1, v1);
        m.put(c2, v2);
        return m;
    }

    public static Map<String, Object> index(int i) {
        return Collections.<String, Object>singletonMap("index", i);
    }
}
