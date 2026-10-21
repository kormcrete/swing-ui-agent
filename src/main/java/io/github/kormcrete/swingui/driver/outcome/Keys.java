package io.github.kormcrete.swingui.driver.outcome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The keys of the elements of one kind (fields, texts, tables, lists and trees) in two readings, settled together so an
 * element both readings hold gets the same key in each. A key is the element's name when no other element of the kind
 * shares it, else the name prefixed with the scopes around it ("Billing › Name"), then also with its window's title,
 * then numbered in reading order ("Name #2"). Of several elements with the same name, scopes and window in one reading,
 * the n-th is the same element as the n-th in the other.
 */
final class Keys {

    static final String SEP = " › ";

    private Keys() {}

    /** {@code a} and {@code b} keyed alike: the first map is {@code a}'s, the second {@code b}'s. */
    static <T> List<Map<String, T>> settle(List<UiReading.Named<T>> a, List<UiReading.Named<T>> b) {
        Map<List<Object>, UiReading.Named<T>> union = new LinkedHashMap<List<Object>, UiReading.Named<T>>();
        List<List<Object>> idsA = ids(a);
        List<List<Object>> idsB = ids(b);
        for (int i = 0; i < a.size(); i++) union.put(idsA.get(i), a.get(i));
        for (int i = 0; i < b.size(); i++) if (!union.containsKey(idsB.get(i))) union.put(idsB.get(i), b.get(i));
        List<String> keys = keys(new ArrayList<UiReading.Named<T>>(union.values()));
        Map<List<Object>, String> keyOf = new LinkedHashMap<List<Object>, String>();
        int k = 0;
        for (List<Object> id : union.keySet()) keyOf.put(id, keys.get(k++));
        return Arrays.asList(keyed(a, idsA, keyOf), keyed(b, idsB, keyOf));
    }

    /** One reading's elements keyed on their own. */
    static <T> Map<String, T> of(List<UiReading.Named<T>> elements) {
        return settle(elements, Collections.<UiReading.Named<T>>emptyList()).get(0);
    }

    private static <T> Map<String, T> keyed(List<UiReading.Named<T>> elements, List<List<Object>> ids,
                                            Map<List<Object>, String> keyOf) {
        Map<String, T> out = new LinkedHashMap<String, T>();
        for (int i = 0; i < elements.size(); i++) out.put(keyOf.get(ids.get(i)), elements.get(i).value);
        return out;
    }

    /** Name, scopes, window and how many before it in the reading had those three: what tells an element apart. */
    private static <T> List<List<Object>> ids(List<UiReading.Named<T>> elements) {
        Map<List<Object>, Integer> seen = new LinkedHashMap<List<Object>, Integer>();
        List<List<Object>> out = new ArrayList<List<Object>>();
        for (UiReading.Named<T> e : elements) {
            List<Object> where = Arrays.<Object>asList(e.name, e.scopes, e.window);
            Integer n = seen.get(where);
            n = n == null ? 0 : n + 1;
            seen.put(where, n);
            out.add(Arrays.<Object>asList(e.name, e.scopes, e.window, n));
        }
        return out;
    }

    /** One key per element, in order, as the class comment says. */
    private static <T> List<String> keys(List<UiReading.Named<T>> elements) {
        List<String> keys = new ArrayList<String>();
        for (UiReading.Named<T> e : elements) keys.add(e.name);
        Map<String, Integer> seen = count(keys);
        for (int i = 0; i < elements.size(); i++) {
            if (seen.get(keys.get(i)) > 1) keys.set(i, join(elements.get(i).scopes, elements.get(i).name));
        }
        // the window tells apart only those that sit in different windows
        seen = count(keys);
        Map<String, Set<String>> windows = new LinkedHashMap<String, Set<String>>();
        for (int i = 0; i < elements.size(); i++) {
            Set<String> in = windows.get(keys.get(i));
            if (in == null) windows.put(keys.get(i), in = new HashSet<String>());
            in.add(elements.get(i).window);
        }
        for (int i = 0; i < elements.size(); i++) {
            UiReading.Named<T> e = elements.get(i);
            if (seen.get(keys.get(i)) > 1 && windows.get(keys.get(i)).size() > 1 && !e.window.isEmpty()) {
                keys.set(i, e.window + SEP + keys.get(i));
            }
        }
        seen = count(keys);
        Map<String, Integer> nth = new LinkedHashMap<String, Integer>();
        List<String> settled = new ArrayList<String>(keys);
        for (int i = 0; i < elements.size(); i++) {
            if (seen.get(keys.get(i)) < 2) continue;
            Integer n = nth.get(keys.get(i));
            n = n == null ? 1 : n + 1;
            nth.put(keys.get(i), n);
            settled.set(i, keys.get(i) + " #" + n);
        }
        return settled;
    }

    private static Map<String, Integer> count(List<String> keys) {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (String k : keys) {
            Integer n = out.get(k);
            out.put(k, n == null ? 1 : n + 1);
        }
        return out;
    }

    private static String join(List<String> scopes, String name) {
        StringBuilder sb = new StringBuilder();
        for (String s : scopes) sb.append(s).append(SEP);
        return sb.append(name).toString();
    }
}
