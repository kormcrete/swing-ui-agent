package io.github.kormcrete.swingui.journal;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kormcrete.swingui.driver.model.Locator;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes one English line for a step, a check or a read: a verb, the place it acts on, the value or expectation.
 * Pure text over a Jackson tree and {@link Locator}; it never throws (a step it cannot read becomes its action name
 * with a capital, or "Step"). Values in a sentence are one line and cut at {@link #VALUE_CAP} characters.
 */
public final class StepWords {

    /** Longest value a sentence holds; longer text is cut after this many characters and ends with an ellipsis. */
    public static final int VALUE_CAP = 60;

    private StepWords() {}

    /** The sentence of a step as do_action takes it, or as a recorded test holds it. */
    public static String sentence(JsonNode step) {
        try {
            if (step != null && step.isObject()) {
                String made = build(step);
                if (made != null) return made;
            }
        } catch (RuntimeException e) {
            // fall through to the action name
        }
        return fallback(step);
    }

    private static String fallback(JsonNode step) {
        String action = step == null ? null : text(step, "action");
        if (action == null || action.isEmpty()) return "Step";
        return Character.toUpperCase(action.charAt(0)) + action.substring(1);
    }

    private static String build(JsonNode s) {
        String action = text(s, "action");
        if (action == null) return null;
        Locator target = locator(s.get("target"));
        switch (action) {
            case "type": {
                String typed = text(s, "text");
                if (typed != null && typed.isEmpty()) return "Clear " + cellPlace(target, null, s);
                return "Type " + req(typed) + " into " + cellPlace(target, null, s);
            }
            case "click":
                return clickVerb(s) + " " + place(target);
            case "contextMenu":
                return "Right-click " + place(target) + " and choose " + path(strings(s.get("path")));
            case "menu":
                return "Choose " + path(strings(s.get("path"))) + " in the menu bar";
            case "pick":
                return "Pick " + path(strings(s.get("path")));
            case "select": {
                List<String> items = strings(s.get("items"));
                if (items.isEmpty() && text(s, "item") != null) items.add(text(s, "item"));
                return "Choose " + (items.isEmpty() ? "(missing)" : join(items)) + " in " + place(target);
            }
            case "check":
                return (s.path("checked").asBoolean(true) ? "Tick " : "Untick ") + place(target);
            case "press":
                return "Press " + req(text(s, "keys")) + (target == null ? "" : " in " + place(target));
            case "answer": {
                String button = text(s, "button");
                return "Press " + ("escape".equals(button) ? "Escape" : req(button)) + " in the dialog";
            }
            case "chooseFile": {
                String path = text(s, "path");
                boolean approve = s.path("approve").asBoolean(true);
                if (path == null) return approve ? "Choose a file" : "Cancel the file chooser";
                return "Choose the file " + req(path) + (approve ? "" : " without approving");
            }
            case "focusFileChooserName":
                return "Put the focus in the file name field";
            case "drag":
                return "Drag " + place(locator(s.get("from"))) + " onto " + place(locator(s.get("to")));
            case "drop": {
                List<String> files = strings(s.get("files"));
                String what = files.isEmpty() ? "the text " + req(text(s, "text")) : join(files);
                return "Drop " + what + " on " + place(target);
            }
            case "resizeColumn": {
                int delta = s.path("delta").asInt(0);
                return (delta < 0 ? "Narrow " : "Widen ") + place(target) + " by " + Math.abs(delta) + " px";
            }
            case "resizeWindow":
                return "Resize " + windowPhrase(text(s, "window")) + " to " + s.path("width").asInt() + "x"
                        + s.path("height").asInt();
            case "closeWindow":
                return "Close " + windowPhrase(text(s, "window"));
            case "hover":
                return s.path("inside").asBoolean(true) ? "Point at " + place(target) : "Move the pointer off " + place(target);
            case "scroll":
                return scroll(s, target);
            case "setClipboard":
                return "Put " + req(text(s, "text")) + " on the clipboard";
            case "navigate":
                return "Go to " + req(text(s, "target"));
            case "close":
                return "Close the active tab";
            case "loginAs": {
                String user = text(s, "as");
                if (user == null) user = text(s, "user");
                return user == null ? "Log in" : "Log in as " + value(user);
            }
            case "relogin":
                return "Log in again";
            case "expect":
                return expect(s, target);
            case "remember":
                return "Remember what " + (s.path("selected").asBoolean(false) ? "is selected in " : "")
                        + place(target) + (s.path("selected").asBoolean(false) ? "" : " shows") + " as " + req(text(s, "as"));
            case "screenshot": {
                if (target != null) return "Take a picture of " + place(target);
                String window = text(s, "window");
                return "Take a picture of " + (window == null || "main".equals(window) ? "the window" : windowPhrase(window));
            }
            case "do": {
                String say = text(s, "say");
                return say != null && !say.trim().isEmpty() ? value(say) : "Do " + req(text(s, "step"));
            }
            default:
                return null;
        }
    }

    private static String clickVerb(JsonNode s) {
        int count = s.path("count").asInt(1);
        String verb = "right".equals(text(s, "button")) ? "right-click"
                : count == 2 ? "double-click" : count == 3 ? "triple-click" : "click";
        List<String> mods = strings(s.get("modifiers"));
        if (mods.isEmpty()) return Character.toUpperCase(verb.charAt(0)) + verb.substring(1);
        return join(mods, "+") + "-" + verb;
    }

    private static String scroll(JsonNode s, Locator target) {
        String to = text(s, "to");
        if (to != null) return "Scroll " + place(target) + " to the " + to;
        if (s.hasNonNull("pages")) {
            int pages = s.get("pages").asInt();
            String count = Math.abs(pages) + (Math.abs(pages) == 1 ? " page" : " pages");
            return "Scroll " + place(target) + (pages < 0 ? " back by " : " by ") + count;
        }
        return "Scroll to " + place(target);
    }

    private static String expect(JsonNode s, Locator target) {
        String where = place(target);
        String head = "Check that ";
        if (s.has("text")) return head + where + " shows " + shown(text(s, "text"));
        if (s.has("value")) return head + where + " holds " + shown(text(s, "value"));
        if (s.has("tooltip")) {
            String tip = text(s, "tooltip");
            return head + where + (tip == null || tip.isEmpty() ? " has no tooltip" : " has the tooltip " + value(tip));
        }
        if (s.has("state")) return head + where + " is " + states(strings(s.get("state")));
        if (s.has("rows")) {
            JsonNode rows = s.get("rows");
            int n = rows.path("value").asInt();
            String op = rows.path("op").asText("eq");
            String count = n + (n == 1 ? " row" : " rows");
            String phrase;
            switch (op) {
                case "gt": phrase = "has more than " + count; break;
                case "gte": phrase = "has at least " + count; break;
                case "lt": phrase = "has fewer than " + count; break;
                case "lte": phrase = "has at most " + count; break;
                default: phrase = "has " + count;
            }
            return head + where + " " + phrase;
        }
        if (s.has("cell")) {
            JsonNode cell = s.get("cell");
            return head + cellPlace(target, cell, null) + " shows " + shown(text(cell, "expected"));
        }
        if (s.has("selected")) {
            List<String> rows = new ArrayList<>();
            for (JsonNode i : s.get("selected")) rows.add(String.valueOf(i.asLong() + 1));
            if (rows.isEmpty()) return head + "nothing is selected in " + where;
            return head + (rows.size() == 1 ? "row " : "rows ") + join(rows) + " of " + where
                    + (rows.size() == 1 ? " is selected" : " are selected");
        }
        if (s.path("absent").asBoolean(false)) return head + where + " is absent";
        if (s.path("shown").asBoolean(false)) return head + where + " is shown";
        return null;
    }

    private static String states(List<String> tokens) {
        List<String> words = new ArrayList<>();
        for (String t : tokens) {
            boolean not = t.startsWith("!");
            String name = not ? t.substring(1) : t;
            switch (name) {
                case "disabled": words.add(not ? "enabled" : "disabled"); break;
                case "hidden": words.add(not ? "visible" : "hidden"); break;
                case "checked": words.add(not ? "unchecked" : "checked"); break;
                case "readonly": words.add(not ? "editable" : "read-only"); break;
                default: words.add((not ? "not " : "") + name);
            }
        }
        return join(words, " and ");
    }

    /** A target with the sub-target of a cell check ({@code cell}) or of a typed cell ({@code step}) merged in. */
    private static String cellPlace(Locator target, JsonNode cell, JsonNode step) {
        if (target == null) return place(null);
        Locator merged = target.copy();
        if (cell != null) {
            merged.row = rowMap(cell.get("row"));
            merged.column = text(cell, "column");
        } else if (step != null) {
            if (merged.row == null && step.has("row")) merged.row = rowMap(step.get("row"));
            if (merged.column == null && step.has("column")) merged.column = text(step, "column");
        }
        return place(merged);
    }

    /** The place phrase: where in the app a locator points, in words. */
    public static String place(Locator t) {
        if (t == null) return "the window";
        String name = t.name == null ? null : t.containsMatch() && t.name.endsWith("~")
                ? t.name.substring(0, t.name.length() - 1) : t.name;
        String base;
        boolean bare = false;
        if (name != null && !name.isEmpty()) {
            base = rolePrefix(t.role) + name;
        } else if (t.id != null) {
            base = rolePrefix(t.role) + t.id;
        } else if (t.role != null) {
            base = "the " + t.role;
        } else if (t.ref != null) {
            base = t.ref;
        } else {
            base = windowPhrase(t.window);
            bare = true;
        }
        String sub = base;
        String row = t.row == null ? null : rowPhrase(t.row);
        if (t.item != null && !t.item.isEmpty()) {
            sub = path(t.item) + " in " + base;
        } else if (row != null && t.column != null) {
            sub = t.column + " of " + row + " in " + base;
        } else if (row != null) {
            sub = row + " in " + base;
        } else if (t.column != null) {
            sub = "column " + t.column + " in " + base;
        }
        StringBuilder sb = new StringBuilder(sub);
        if (t.scope != null && !t.scope.isEmpty()) {
            List<String> steps = new ArrayList<>();
            for (String step : t.scope) steps.add(scopeWords(step));
            sb.append(" (").append(join(steps)).append(")");
        }
        if (!bare && t.window != null) {
            String w = t.window;
            if ("dialog".equals(w)) sb.append(" in the dialog");
            else if ("popup".equals(w)) sb.append(" in the popup");
            else if (w.startsWith("dialog:")) sb.append(" in dialog ").append(w.substring(7));
            else if (w.startsWith("window:")) sb.append(" in window ").append(w.substring(7));
        }
        if (t.nth != null) sb.append(" (match ").append(t.nth + 1).append(")");
        return sb.toString();
    }

    private static String rolePrefix(String role) {
        if ("tab".equals(role)) return "tab ";
        if ("section".equals(role)) return "section ";
        if ("menuitem".equals(role)) return "menu item ";
        return "";
    }

    private static String scopeWords(String step) {
        int colon = step.indexOf(':');
        if (colon < 0) return step;
        String kind = step.substring(0, colon);
        String rest = step.substring(colon + 1);
        switch (kind) {
            case "tab": return "tab " + rest;
            case "section": return "section " + rest;
            case "id": return rest;
            case "text": return "field " + rest;
            default: return step;
        }
    }

    private static String windowPhrase(String window) {
        if (window == null || "main".equals(window)) return "the main window";
        if ("dialog".equals(window)) return "the dialog";
        if ("popup".equals(window)) return "the popup";
        if (window.startsWith("dialog:")) return "dialog " + window.substring(7);
        if (window.startsWith("window:")) return "window " + window.substring(7);
        return window;
    }

    private static String rowPhrase(Map<String, Object> row) {
        Object index = row.get("index");
        if (row.size() == 1 && index instanceof Number) {
            long i = ((Number) index).longValue();
            if (i >= 0) return "row " + (i + 1);
            if (i == -1) return "the last row";
            return "row " + (-i) + " from the end";
        }
        List<String> values = new ArrayList<>();
        for (Object v : row.values()) values.add(String.valueOf(v));
        return "row " + join(values);
    }

    /** The sentence of a read: {@code verb} is the read's name, {@code shortValue} what it came back with (or null). */
    public static String read(String verb, Locator target, String shortValue) {
        String p = place(target);
        String head;
        boolean appendValue = true;
        switch (verb == null ? "" : verb) {
            case "text": head = "Read the text of " + p; break;
            case "value": head = "Read the value of " + p; break;
            case "shownText": head = "Read what " + p + " shows"; break;
            case "selectedText": head = "Read what is selected in " + p; break;
            case "tooltip": head = "Read the tooltip of " + p; break;
            case "columns": case "rows": case "items": case "states":
                head = "Read the " + verb + " of " + p; break;
            case "expandedItems": head = "Read the expanded items of " + p; break;
            case "selectedRows": head = "Read the selected rows of " + p; break;
            case "rowCount": head = "Count the rows of " + p; break;
            case "count": head = "Count the matches of " + p; break;
            case "values": case "texts": head = "Read the " + verb + " of every " + p; break;
            case "bounds": head = "Read where " + p + " is"; break;
            case "presence": case "exists": head = "Look whether " + p + " is there"; break;
            case "menuItems":
                head = "Read the items of the menu " + (shortValue == null ? "" : shortValue);
                appendValue = false;
                break;
            case "clipboardText": head = "Read the clipboard"; break;
            case "fileChooser": head = "Read the open file chooser"; break;
            case "modalShowing": head = "Look whether a modal dialog shows"; break;
            case "describeModal": head = "Read the open dialog"; break;
            default: head = "Read the " + verb + " of " + p;
        }
        return appendValue && shortValue != null ? head + ": " + value(shortValue) : head;
    }

    /** The sentence of reading a menu's items: {@code path} is the menu as "File > Export"; items come back as text. */
    public static String readMenu(String path, String shortValue) {
        String head = "Read the items of the menu " + path;
        return shortValue == null ? head : head + ": " + value(shortValue);
    }

    /** The sentence of a checkpoint: its name, where it was taken and what was masked out. */
    public static String checkpoint(String name, Locator target, List<Locator> masks) {
        StringBuilder sb = new StringBuilder("Checkpoint ").append(name).append(" of ")
                .append(target == null ? "the main window" : place(target));
        if (masks != null && !masks.isEmpty()) {
            List<String> places = new ArrayList<>();
            for (Locator m : masks) places.add(place(m));
            sb.append(", masking ").append(join(places));
        }
        return sb.toString();
    }

    /** An expected text: the value, or "nothing" when it is empty (never a trailing space). */
    private static String shown(String v) {
        return v == null || v.isEmpty() ? "nothing" : value(v);
    }

    /** A value a step must have: the value in a sentence, or the fixed word "(missing)" when it is absent or empty. */
    private static String req(String v) {
        return v == null || v.trim().isEmpty() ? "(missing)" : value(v);
    }

    /** A value inside a sentence: one line, at most {@link #VALUE_CAP} characters. */
    private static String value(String v) {
        if (v == null) return "";
        String line = v.replaceAll("\\r\\n|\\r|\\n", " ");
        if (line.length() <= VALUE_CAP) return line;
        int end = VALUE_CAP;
        if (Character.isHighSurrogate(line.charAt(end - 1))) end--;
        return line.substring(0, end) + "\u2026";
    }

    private static String path(List<String> items) {
        return items.isEmpty() ? "(missing)" : join(items, " > ");
    }

    private static String join(List<String> items) {
        return join(items, ", ");
    }

    private static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String i : items) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(i);
        }
        return sb.toString();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || !v.isValueNode() || v.isNull() ? null : v.asText();
    }

    private static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            for (JsonNode e : n) {
                if (e.isValueNode() && !e.isNull()) out.add(e.asText());
            }
        } else if (n != null && n.isValueNode() && !n.isNull()) {
            out.add(n.asText());
        }
        return out;
    }

    private static Map<String, Object> rowMap(JsonNode n) {
        if (n == null || !n.isObject() || n.size() == 0) return null;
        Map<String, Object> row = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = n.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            row.put(e.getKey(), v.isIntegralNumber() ? (Object) v.asLong() : v.asText());
        }
        return row;
    }

    private static Locator locator(JsonNode n) {
        if (n == null || !n.isObject()) return null;
        Locator l = new Locator();
        l.ref = text(n, "ref");
        l.window = text(n, "window");
        l.role = text(n, "role");
        l.name = text(n, "name");
        l.match = text(n, "match");
        l.id = text(n, "id");
        l.column = text(n, "column");
        l.row = rowMap(n.get("row"));
        if (n.has("scope")) l.scope = strings(n.get("scope"));
        if (n.has("item")) l.item = strings(n.get("item"));
        if (n.hasNonNull("nth")) l.nth = n.get("nth").asInt();
        return l;
    }
}
