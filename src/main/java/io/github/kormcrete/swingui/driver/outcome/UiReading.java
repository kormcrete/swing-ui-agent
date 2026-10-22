package io.github.kormcrete.swingui.driver.outcome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the app's windows showed at one moment, as a person reads it: field values, label and button texts, table
 * rows, list and tree items, tab and window titles, a message dialog's text. Each element keeps the name a person reads
 * and where it sits; its key is settled when two readings are compared ({@link Keys}), so one element has one key on
 * both sides. Immutable; {@link Outcomes#diff} compares two of them.
 */
public final class UiReading {

    /** One element as read: its name, the tabs and titled sections around it (outermost first), its window's title. */
    static final class Named<T> {
        final String name;
        final List<String> scopes;
        final String window;
        final T value;

        Named(String name, List<String> scopes, String window, T value) {
            this.name = name;
            this.scopes = Collections.unmodifiableList(new ArrayList<String>(scopes));
            this.window = window == null ? "" : window;
            this.value = value;
        }

        static <T> Named<T> plain(String name, T value) {
            return new Named<T>(name, Collections.<String>emptyList(), "", value);
        }
    }

    /**
     * The rows of a table, or the items of a list or tree, as far as they were read: all of them ({@link #full}), or
     * only those in view, the selected ones and five on either side of each when the reading ran out of time.
     */
    static final class Rows {
        /** A table's column headers; null for a list or tree. */
        final List<String> headers;
        /** The view index of each row read, ascending. */
        final List<Integer> indices;
        /** Each row read: {header: shown text} for a table, the item's text for a list or tree. */
        final List<Object> values;
        final List<Integer> hashes;
        /** The view indices selected. */
        final List<Integer> selected;
        /** How many rows there are, read or not. */
        final int count;
        final boolean full;

        Rows(List<String> headers, List<Integer> indices, List<Object> values, List<Integer> hashes,
             List<Integer> selected, int count, boolean full) {
            this.headers = headers == null ? null : Collections.unmodifiableList(new ArrayList<String>(headers));
            this.indices = Collections.unmodifiableList(new ArrayList<Integer>(indices));
            this.values = Collections.unmodifiableList(new ArrayList<Object>(values));
            this.hashes = Collections.unmodifiableList(new ArrayList<Integer>(hashes));
            this.selected = Collections.unmodifiableList(new ArrayList<Integer>(selected));
            this.count = count;
            this.full = full;
        }

        /** The value read at view index {@code index}, or null when that row was not read. */
        Object at(int index) {
            int k = Collections.binarySearch(indices, index);
            return k < 0 ? null : values.get(k);
        }

        /** The selected rows that were read, in order. */
        List<Object> selectedValues() {
            List<Object> out = new ArrayList<Object>();
            for (Integer i : selected) {
                Object v = at(i);
                if (v != null) out.add(v);
            }
            return out;
        }
    }

    final boolean partial;
    final List<Named<String>> fields;
    final List<Named<String>> texts;
    /** The fields that are text fields (also in {@link #fields}): a combo is not one. */
    final List<Named<String>> textFields;
    final List<Named<Rows>> tables;
    /** Lists and trees together, as the outcome's {@code items} holds them; a tree's items are "A > B" paths. */
    final List<Named<Rows>> items;
    final List<String> tabs;
    final List<String> windows;
    final List<String> dialogs;
    /** The text of the message dialog showing, or null. */
    final String message;

    private UiReading(Builder b) {
        this.partial = b.partial;
        this.fields = Collections.unmodifiableList(new ArrayList<Named<String>>(b.fields));
        this.texts = Collections.unmodifiableList(new ArrayList<Named<String>>(b.texts));
        this.textFields = Collections.unmodifiableList(new ArrayList<Named<String>>(b.textFields));
        this.tables = Collections.unmodifiableList(new ArrayList<Named<Rows>>(b.tables));
        this.items = Collections.unmodifiableList(new ArrayList<Named<Rows>>(b.items));
        this.tabs = Collections.unmodifiableList(new ArrayList<String>(b.tabs));
        this.windows = Collections.unmodifiableList(new ArrayList<String>(b.windows));
        this.dialogs = Collections.unmodifiableList(new ArrayList<String>(b.dialogs));
        this.message = b.message;
    }

    /** True when some table, list or tree was read only around its view and selection: the reading ran out of time. */
    public boolean partial() {
        return partial;
    }

    /** The field values keyed as this reading alone tells them apart. */
    Map<String, String> fields() {
        return Keys.of(fields);
    }

    Map<String, String> texts() {
        return Keys.of(texts);
    }

    Map<String, Rows> tables() {
        return Keys.of(tables);
    }

    Map<String, Rows> items() {
        return Keys.of(items);
    }

    /** Every text this reading holds as a value (not as a key): what was on screen already. */
    Set<String> values() {
        Set<String> out = new LinkedHashSet<String>();
        for (Named<String> f : fields) out.add(f.value);
        for (Named<String> t : texts) out.add(t.value);
        for (Named<Rows> r : tables) {
            for (Object row : r.value.values) {
                for (Object cell : ((Map<?, ?>) row).values()) out.add(String.valueOf(cell));
            }
        }
        for (Named<Rows> r : items) {
            for (Object item : r.value.values) out.add(String.valueOf(item));
        }
        out.addAll(tabs);
        out.addAll(windows);
        out.addAll(dialogs);
        if (message != null) out.add(message);
        return out;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builds a reading: {@link UiReader} does, and a test double scripting what a step changed. */
    public static final class Builder {
        private boolean partial;
        private final List<Named<String>> fields = new ArrayList<Named<String>>();
        private final List<Named<String>> texts = new ArrayList<Named<String>>();
        private final List<Named<String>> textFields = new ArrayList<Named<String>>();
        private final List<Named<Rows>> tables = new ArrayList<Named<Rows>>();
        private final List<Named<Rows>> items = new ArrayList<Named<Rows>>();
        private final List<String> tabs = new ArrayList<String>();
        private final List<String> windows = new ArrayList<String>();
        private final List<String> dialogs = new ArrayList<String>();
        private String message;

        private Builder() {}

        public Builder partial(boolean partial) {
            this.partial = partial;
            return this;
        }

        /** A field by its name, in no tab or section. */
        public Builder field(String name, String value) {
            return field(Named.plain(name, value));
        }

        Builder field(Named<String> field) {
            fields.add(field);
            return this;
        }

        /** A field that is a text field, by its name, in no tab or section. */
        public Builder textField(String name, String value) {
            return textField(Named.plain(name, value));
        }

        /** A field that is also a text field. */
        Builder textField(Named<String> field) {
            fields.add(field);
            textFields.add(field);
            return this;
        }

        /** A label's or button's text by its name, in no tab or section. */
        public Builder text(String name, String value) {
            return text(Named.plain(name, value));
        }

        Builder text(Named<String> text) {
            texts.add(text);
            return this;
        }

        /** A table read in full, nothing selected: each row one text per header. */
        public Builder table(String name, List<String> headers, List<List<String>> rows) {
            List<Integer> indices = new ArrayList<Integer>();
            List<Object> values = new ArrayList<Object>();
            List<Integer> hashes = new ArrayList<Integer>();
            for (int r = 0; r < rows.size(); r++) {
                String[] cells = rows.get(r).toArray(new String[0]);
                indices.add(r);
                values.add(row(headers, cells));
                hashes.add(Arrays.hashCode(cells));
            }
            return rows(true, Named.plain(name, new Rows(headers, indices, values, hashes, Collections.<Integer>emptyList(),
                    rows.size(), true)));
        }

        /** A list's items, or a tree's visible rows as "A > B" paths, read in full, nothing selected. */
        public Builder items(String name, List<String> items) {
            List<Integer> indices = new ArrayList<Integer>();
            List<Integer> hashes = new ArrayList<Integer>();
            for (int i = 0; i < items.size(); i++) {
                indices.add(i);
                hashes.add(items.get(i).hashCode());
            }
            return rows(false, Named.plain(name, new Rows(null, indices, new ArrayList<Object>(items), hashes,
                    Collections.<Integer>emptyList(), items.size(), true)));
        }

        /** A table's rows ({@code table}), or a list's or tree's items. */
        Builder rows(boolean table, Named<Rows> rows) {
            (table ? tables : items).add(rows);
            return this;
        }

        public Builder tab(String title) {
            tabs.add(title);
            return this;
        }

        public Builder window(String title) {
            windows.add(title);
            return this;
        }

        public Builder dialog(String title) {
            dialogs.add(title);
            return this;
        }

        public Builder message(String text) {
            message = text;
            return this;
        }

        public UiReading build() {
            return new UiReading(this);
        }
    }

    /** A row as {header: text}; of several columns with one header, the first. */
    static Map<String, String> row(List<String> headers, String[] cells) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (int c = 0; c < cells.length && c < headers.size(); c++) {
            if (!out.containsKey(headers.get(c))) out.put(headers.get(c), cells[c]);
        }
        return out;
    }
}
