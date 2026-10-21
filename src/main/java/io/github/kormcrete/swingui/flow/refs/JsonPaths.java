package io.github.kormcrete.swingui.flow.refs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A small JSON path evaluator. A path starts at {@code $} and continues with {@code .key},
 * {@code ['key']}, {@code [n]}, {@code [-n]} (from the end), {@code [*]}, {@code ..key} (anywhere
 * below) and {@code [?(@.k == 'v')]} / {@code !=} (elements whose field {@code k} has that text).
 * A key or index that is not there selects nothing.
 */
public final class JsonPaths {
  private JsonPaths() {}

  private enum Kind { KEY, INDEX, ALL, DEEP_KEY, DEEP_ALL, FILTER }

  private static final class Step {
    final Kind kind;
    final String key;
    final int index;
    final String value;
    final boolean equal;

    Step(Kind kind, String key, int index, String value, boolean equal) {
      this.kind = kind;
      this.key = key;
      this.index = index;
      this.value = value;
      this.equal = equal;
    }
  }

  /** The nodes the path selects, in document order. */
  public static List<JsonNode> select(JsonNode root, String path) {
    List<JsonNode> current = new ArrayList<JsonNode>();
    current.add(root);
    for (Step step : parse(path)) {
      List<JsonNode> next = new ArrayList<JsonNode>();
      for (JsonNode n : current) {
        apply(step, n, next);
      }
      current = next;
    }
    return current;
  }

  /** Parses only; throws {@link IllegalArgumentException} when the path is not valid. */
  public static void check(String path) {
    parse(path);
  }

  /**
   * Puts {@code value} at {@code path} in {@code root}, making the objects and arrays on the way (an array is filled up
   * with nulls to the index). Only keys and indexes from the start: throws for any other step, or a path that runs into a
   * value already there.
   */
  public static void put(ObjectNode root, String path, String value) {
    List<Step> steps = parse(path);
    if (steps.isEmpty()) {
      throw bad(path, "names no place");
    }
    JsonNode at = root;
    for (int k = 0; k < steps.size(); k++) {
      Step s = steps.get(k);
      boolean last = k == steps.size() - 1;
      JsonNode next = last ? null : steps.get(k + 1).kind == Kind.INDEX ? JsonNodeFactory.instance.arrayNode()
          : JsonNodeFactory.instance.objectNode();
      if (s.kind == Kind.KEY && at.isObject()) {
        ObjectNode o = (ObjectNode) at;
        if (last) {
          o.put(s.key, value);
          return;
        }
        if (!o.has(s.key)) {
          o.set(s.key, next);
        }
        at = o.get(s.key);
      } else if (s.kind == Kind.INDEX && s.index >= 0 && at.isArray()) {
        ArrayNode a = (ArrayNode) at;
        while (a.size() <= s.index) {
          a.addNull();
        }
        if (last) {
          a.set(s.index, JsonNodeFactory.instance.textNode(value));
          return;
        }
        if (a.get(s.index).isNull()) {
          a.set(s.index, next);
        }
        at = a.get(s.index);
      } else {
        throw bad(path, "only keys and indexes from the start name a place to put a value");
      }
    }
  }

  /**
   * The keys the path names one after another, indexes skipped, up to its first step of another kind
   * ({@code [*]}, {@code ..}, a filter): {@code $.rows.Orders.added[0].Number} gives rows, Orders, added, Number.
   */
  static List<String> keys(String path) {
    List<String> out = new ArrayList<String>();
    for (Step s : parse(path)) {
      if (s.kind == Kind.KEY) {
        out.add(s.key);
      } else if (s.kind != Kind.INDEX) {
        break;
      }
    }
    return out;
  }

  private static void apply(Step s, JsonNode n, List<JsonNode> out) {
    switch (s.kind) {
      case KEY:
        if (n.isObject() && n.has(s.key)) {
          out.add(n.get(s.key));
        }
        break;
      case INDEX:
        if (n.isArray()) {
          int i = s.index < 0 ? n.size() + s.index : s.index;
          if (i >= 0 && i < n.size()) {
            out.add(n.get(i));
          }
        }
        break;
      case ALL:
        children(n, out);
        break;
      case DEEP_KEY:
      case DEEP_ALL:
        deep(s, n, out);
        break;
      case FILTER:
        List<JsonNode> kids = new ArrayList<JsonNode>();
        children(n, kids);
        for (JsonNode k : kids) {
          JsonNode f = k.isObject() ? k.get(s.key) : null;
          boolean same = f != null && f.isValueNode() && f.asText().equals(s.value);
          if (same == s.equal) {
            out.add(k);
          }
        }
        break;
      default:
        throw new IllegalStateException(String.valueOf(s.kind));
    }
  }

  private static void children(JsonNode n, List<JsonNode> out) {
    if (n.isContainerNode()) {
      for (Iterator<JsonNode> it = n.elements(); it.hasNext(); ) {
        out.add(it.next());
      }
    }
  }

  private static void deep(Step s, JsonNode n, List<JsonNode> out) {
    if (s.kind == Kind.DEEP_KEY && n.isObject() && n.has(s.key)) {
      out.add(n.get(s.key));
    }
    List<JsonNode> kids = new ArrayList<JsonNode>();
    children(n, kids);
    for (JsonNode k : kids) {
      if (s.kind == Kind.DEEP_ALL) {
        out.add(k);
      }
      deep(s, k, out);
    }
  }

  private static List<Step> parse(String path) {
    if (path == null || path.isEmpty() || path.charAt(0) != '$') {
      throw bad(path, "must start with $");
    }
    List<Step> steps = new ArrayList<Step>();
    int i = 1;
    int n = path.length();
    while (i < n) {
      char c = path.charAt(i);
      if (c == '.') {
        boolean deep = i + 1 < n && path.charAt(i + 1) == '.';
        i += deep ? 2 : 1;
        if (i < n && path.charAt(i) == '*') {
          steps.add(new Step(deep ? Kind.DEEP_ALL : Kind.ALL, null, 0, null, true));
          i++;
          continue;
        }
        int start = i;
        while (i < n && path.charAt(i) != '.' && path.charAt(i) != '[') {
          i++;
        }
        if (i == start) {
          throw bad(path, "key expected at " + start);
        }
        steps.add(new Step(deep ? Kind.DEEP_KEY : Kind.KEY, path.substring(start, i), 0, null, true));
      } else if (c == '[') {
        i = bracket(path, i, steps);
      } else {
        throw bad(path, "unexpected '" + c + "' at " + i);
      }
    }
    return steps;
  }

  private static int bracket(String path, int open, List<Step> steps) {
    int n = path.length();
    int i = open + 1;
    if (i >= n) {
      throw bad(path, "unclosed [");
    }
    char c = path.charAt(i);
    if (c == '\'' || c == '"') {
      StringBuilder key = new StringBuilder();
      i = quoted(path, i, key);
      i = expect(path, i, ']');
      steps.add(new Step(Kind.KEY, key.toString(), 0, null, true));
    } else if (c == '*') {
      i = expect(path, i + 1, ']');
      steps.add(new Step(Kind.ALL, null, 0, null, true));
    } else if (c == '?') {
      i = filter(path, i + 1, steps);
    } else {
      int start = i;
      if (c == '-') {
        i++;
      }
      int digits = i;
      while (i < n && Character.isDigit(path.charAt(i))) {
        i++;
      }
      if (i == digits) {
        throw bad(path, "index expected at " + start);
      }
      int index;
      try {
        index = Integer.parseInt(path.substring(start, i));
      } catch (NumberFormatException e) {
        throw bad(path, "index too large at " + start);
      }
      i = expect(path, i, ']');
      steps.add(new Step(Kind.INDEX, null, index, null, true));
    }
    return i;
  }

  /** {@code ?(@.key == 'value')} after the {@code ?}. */
  private static int filter(String path, int from, List<Step> steps) {
    int n = path.length();
    int i = expect(path, from, '(');
    i = expect(path, skip(path, i), '@');
    i = expect(path, i, '.');
    int start = i;
    while (i < n && (Character.isLetterOrDigit(path.charAt(i)) || "_-".indexOf(path.charAt(i)) >= 0)) {
      i++;
    }
    if (i == start) {
      throw bad(path, "filter key expected at " + start);
    }
    String key = path.substring(start, i);
    i = skip(path, i);
    boolean equal;
    if (path.startsWith("==", i)) {
      equal = true;
    } else if (path.startsWith("!=", i)) {
      equal = false;
    } else {
      throw bad(path, "== or != expected at " + i);
    }
    i = skip(path, i + 2);
    if (i >= n || (path.charAt(i) != '\'' && path.charAt(i) != '"')) {
      throw bad(path, "quoted value expected at " + i);
    }
    StringBuilder value = new StringBuilder();
    i = quoted(path, i, value);
    i = expect(path, skip(path, i), ')');
    i = expect(path, i, ']');
    steps.add(new Step(Kind.FILTER, key, 0, value.toString(), equal));
    return i;
  }

  /** Reads a quoted text ({@code \} escapes the quote) starting at the quote; returns the index after it. */
  private static int quoted(String path, int at, StringBuilder out) {
    char q = path.charAt(at);
    int i = at + 1;
    while (i < path.length()) {
      char c = path.charAt(i);
      if (c == '\\' && i + 1 < path.length()) {
        out.append(path.charAt(i + 1));
        i += 2;
      } else if (c == q) {
        return i + 1;
      } else {
        out.append(c);
        i++;
      }
    }
    throw bad(path, "unclosed quote");
  }

  private static int skip(String path, int i) {
    while (i < path.length() && path.charAt(i) == ' ') {
      i++;
    }
    return i;
  }

  private static int expect(String path, int i, char c) {
    if (i >= path.length() || path.charAt(i) != c) {
      throw bad(path, "'" + c + "' expected at " + i);
    }
    return i + 1;
  }

  private static IllegalArgumentException bad(String path, String why) {
    return new IllegalArgumentException("Bad path \"" + path + "\": " + why);
  }
}
