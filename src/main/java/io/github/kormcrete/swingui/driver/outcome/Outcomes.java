package io.github.kormcrete.swingui.driver.outcome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The outcome of a step: how the readable UI changed between a reading before it and one after it, as a JSON-shaped
 * map ({@code Map}, {@code List}, {@code String}, {@code Integer}, {@code Boolean}) that the caller turns into JSON.
 * Only what changed is in it:
 *
 * <pre>
 * opened   {windows, dialogs, tabs}: titles that appeared       closed {windows, dialogs, tabs}: titles that went
 * fields   {label: value}: values that changed                  texts  {name: text}: labels and buttons that changed
 * rows     {table: {added, removed, changed [{before, after}], count}}, each row {column header: shown text}
 * items    {list or tree: {added, removed}}                     selected {table, list or tree: the rows now selected}
 * message  the text of a message dialog that opened           partial  true when a reading ran out of time, so a
 *                                                             change outside view and selection may be missing
 * </pre>
 *
 * At most {@value #MAX_ENTRIES} entries per list ({@code count} stays true); texts are cut at {@value #MAX_TEXT}
 * characters.
 */
public final class Outcomes {

    public static final int MAX_ENTRIES = 50;
    public static final int MAX_TEXT = 500;

    /** A key a path names as {@code .key}; any other is quoted, {@code ['key']}. */
    private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");

    private Outcomes() {}

    public static Map<String, Object> diff(UiReading before, UiReading after) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Map<String, Object> opened = new LinkedHashMap<String, Object>();
        Map<String, Object> closed = new LinkedHashMap<String, Object>();
        titles("windows", before.windows, after.windows, opened, closed);
        titles("dialogs", before.dialogs, after.dialogs, opened, closed);
        titles("tabs", before.tabs, after.tabs, opened, closed);
        putIfAny(out, "opened", opened);
        putIfAny(out, "closed", closed);
        putIfAny(out, "fields", values(before.fields, after.fields));
        putIfAny(out, "texts", values(before.texts, after.texts));
        Map<String, Object> rows = new LinkedHashMap<String, Object>();
        Map<String, Object> items = new LinkedHashMap<String, Object>();
        Map<String, Object> selected = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, UiReading.Rows> e : after.tables.entrySet()) {
            UiReading.Rows was = before.tables.get(e.getKey());
            Map<String, Object> change = rows(was, e.getValue(), true);
            if (change != null) rows.put(e.getKey(), change);
            selection(e.getKey(), was, e.getValue(), selected);
        }
        for (Map.Entry<String, UiReading.Rows> e : after.items.entrySet()) {
            UiReading.Rows was = before.items.get(e.getKey());
            Map<String, Object> change = rows(was, e.getValue(), false);
            if (change != null) items.put(e.getKey(), change);
            selection(e.getKey(), was, e.getValue(), selected);
        }
        putIfAny(out, "rows", rows);
        putIfAny(out, "items", items);
        putIfAny(out, "selected", selected);
        if (after.message != null && !after.message.equals(before.message)) out.put("message", cut(after.message));
        if (before.partial || after.partial) out.put("partial", Boolean.TRUE);
        return out;
    }

    /**
     * The texts in {@code outcome} that {@code before} showed nowhere, each with the path where it sits (no leading
     * {@code $}: {@code .rows.Orders.added[0].Order}). A value found in several places keeps the first in this order:
     * fields, rows, items, texts, opened, then selected and message; the map's order is that order.
     */
    public static Map<String, String> newValues(UiReading before, Map<String, Object> outcome) {
        Set<String> seen = before.values();
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Object> e : section(outcome, "fields").entrySet()) {
            add(out, seen, e.getValue(), ".fields" + key(e.getKey()));
        }
        for (Map.Entry<String, Object> t : section(outcome, "rows").entrySet()) {
            String at = ".rows" + key(t.getKey());
            Map<String, Object> change = map(t.getValue());
            List<?> added = (List<?>) change.get("added");
            for (int i = 0; i < added.size(); i++) cells(out, seen, added.get(i), at + ".added[" + i + "]");
            List<?> changed = (List<?>) change.get("changed");
            for (int i = 0; i < changed.size(); i++) {
                cells(out, seen, map(changed.get(i)).get("after"), at + ".changed[" + i + "].after");
            }
        }
        for (Map.Entry<String, Object> t : section(outcome, "items").entrySet()) {
            List<?> added = (List<?>) map(t.getValue()).get("added");
            for (int i = 0; i < added.size(); i++) add(out, seen, added.get(i), ".items" + key(t.getKey()) + ".added[" + i + "]");
        }
        for (Map.Entry<String, Object> e : section(outcome, "texts").entrySet()) {
            add(out, seen, e.getValue(), ".texts" + key(e.getKey()));
        }
        for (Map.Entry<String, Object> e : section(outcome, "opened").entrySet()) {
            List<?> titles = (List<?>) e.getValue();
            for (int i = 0; i < titles.size(); i++) add(out, seen, titles.get(i), ".opened" + key(e.getKey()) + "[" + i + "]");
        }
        for (Map.Entry<String, Object> e : section(outcome, "selected").entrySet()) {
            List<?> chosen = (List<?>) e.getValue();
            for (int i = 0; i < chosen.size(); i++) cells(out, seen, chosen.get(i), ".selected" + key(e.getKey()) + "[" + i + "]");
        }
        add(out, seen, outcome.get("message"), ".message");
        return out;
    }

    private static Map<String, Object> section(Map<String, Object> outcome, String name) {
        Object section = outcome.get(name);
        return section == null ? Collections.<String, Object>emptyMap() : map(section);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    // ------------------------------------------------------------------ diff

    private static void titles(String kind, List<String> before, List<String> after,
                               Map<String, Object> opened, Map<String, Object> closed) {
        List<String> came = minus(after, before);
        List<String> went = minus(before, after);
        if (!came.isEmpty()) opened.put(kind, came);
        if (!went.isEmpty()) closed.put(kind, went);
    }

    /** {@code a} without {@code b}, as multisets, in {@code a}'s order; empty titles left out, capped. */
    private static List<String> minus(List<String> a, List<String> b) {
        Map<String, Integer> left = counts(b);
        List<String> out = new ArrayList<String>();
        for (String s : a) {
            Integer n = left.get(s);
            if (n != null && n > 0) {
                left.put(s, n - 1);
            } else if (!s.isEmpty() && out.size() < MAX_ENTRIES) {
                out.add(cut(s));
            }
        }
        return out;
    }

    private static Map<String, Integer> counts(List<String> values) {
        Map<String, Integer> out = new HashMap<String, Integer>();
        for (String s : values) {
            Integer n = out.get(s);
            out.put(s, n == null ? 1 : n + 1);
        }
        return out;
    }

    /** The values that changed; one that came with its element counts only when it is not empty. */
    private static Map<String, Object> values(Map<String, String> before, Map<String, String> after) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, String> e : after.entrySet()) {
            String now = e.getValue() == null ? "" : e.getValue();
            String was = before.get(e.getKey());
            if (was == null ? now.isEmpty() : was.equals(now)) continue;
            out.put(e.getKey(), cut(now));
        }
        return out;
    }

    /**
     * Rows or items added, removed and (same count, same position) changed; null when nothing did. Matched by their
     * hash as multisets, so a row that only moved is no change. Where a reading was partial, only rows read on both
     * sides (and the selected ones) are judged: a row read on one side only is not known to be new.
     */
    private static Map<String, Object> rows(UiReading.Rows was, UiReading.Rows now, boolean table) {
        UiReading.Rows before = was != null ? was : new UiReading.Rows(now.headers, Collections.<Integer>emptyList(),
                Collections.emptyList(), Collections.<Integer>emptyList(), Collections.<Integer>emptyList(), 0, true);
        boolean full = before.full && now.full;
        TreeMap<Integer, Object> added = unmatched(now, before, full);
        TreeMap<Integer, Object> removed = unmatched(before, now, full);
        List<Object> changed = new ArrayList<Object>();
        if (table && was != null && before.count == now.count) {
            for (Integer i : new ArrayList<Integer>(added.keySet())) {
                if (!removed.containsKey(i)) continue;
                if (changed.size() < MAX_ENTRIES) {
                    Map<String, Object> pair = new LinkedHashMap<String, Object>();
                    pair.put("before", value(removed.get(i)));
                    pair.put("after", value(added.get(i)));
                    changed.add(pair);
                }
                added.remove(i);
                removed.remove(i);
            }
        }
        boolean counted = was != null && before.count != now.count;
        if (added.isEmpty() && removed.isEmpty() && changed.isEmpty() && !(table && counted)) return null;
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("added", capped(added.values()));
        out.put("removed", capped(removed.values()));
        if (table) {
            out.put("changed", changed);
            out.put("count", now.count);
        }
        return out;
    }

    /** {@code side}'s rows that {@code other} has no match for, by index, among those that can be judged. */
    private static TreeMap<Integer, Object> unmatched(UiReading.Rows side, UiReading.Rows other, boolean full) {
        Map<Integer, Integer> left = new HashMap<Integer, Integer>();
        for (Integer h : other.hashes) {
            Integer n = left.get(h);
            left.put(h, n == null ? 1 : n + 1);
        }
        Set<Integer> judged = new HashSet<Integer>(other.indices);
        judged.addAll(side.selected);
        TreeMap<Integer, Object> out = new TreeMap<Integer, Object>();
        for (int k = 0; k < side.indices.size(); k++) {
            int index = side.indices.get(k);
            if (!full && !judged.contains(index)) continue;
            Integer h = side.hashes.get(k);
            Integer n = left.get(h);
            if (n != null && n > 0) {
                left.put(h, n - 1);
            } else {
                out.put(index, side.values.get(k));
            }
        }
        return out;
    }

    /** The rows now selected, when that is not what was selected before. */
    private static void selection(String name, UiReading.Rows was, UiReading.Rows now, Map<String, Object> out) {
        List<Object> then = was == null ? Collections.emptyList() : was.selectedValues();
        List<Object> chosen = now.selectedValues();
        if (chosen.equals(then)) return;
        out.put(name, capped(chosen));
    }

    private static List<Object> capped(Iterable<Object> values) {
        List<Object> out = new ArrayList<Object>();
        for (Object v : values) {
            if (out.size() == MAX_ENTRIES) break;
            out.add(value(v));
        }
        return out;
    }

    /** A row or an item as the outcome holds it: texts cut, a row a fresh map. */
    private static Object value(Object v) {
        if (!(v instanceof Map)) return cut(String.valueOf(v));
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
            row.put(String.valueOf(e.getKey()), cut(String.valueOf(e.getValue())));
        }
        return row;
    }

    private static String cut(String s) {
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT);
    }

    private static void putIfAny(Map<String, Object> out, String key, Map<String, Object> value) {
        if (!value.isEmpty()) out.put(key, value);
    }

    // ------------------------------------------------------------------ new values

    private static void cells(Map<String, String> out, Set<String> seen, Object row, String at) {
        if (!(row instanceof Map)) {
            add(out, seen, row, at);
            return;
        }
        for (Map.Entry<?, ?> e : ((Map<?, ?>) row).entrySet()) {
            add(out, seen, e.getValue(), at + key(String.valueOf(e.getKey())));
        }
    }

    private static void add(Map<String, String> out, Set<String> seen, Object value, String path) {
        if (!(value instanceof String)) return;
        String v = (String) value;
        if (v.isEmpty() || seen.contains(v) || out.containsKey(v)) return;
        out.put(v, path);
    }

    /** {@code .key}, or {@code ['key']} with {@code \} before a quote or backslash in it. */
    static String key(String key) {
        if (PLAIN_KEY.matcher(key).matches()) return "." + key;
        return "['" + key.replace("\\", "\\\\").replace("'", "\\'") + "']";
    }
}
