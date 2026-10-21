package io.github.kormcrete.swingui.flow.refs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.kormcrete.swingui.flow.Action;
import io.github.kormcrete.swingui.flow.FlowJson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * References to values of earlier steps inside a text: {@code ${id}} or {@code ${id.path}}, where
 * {@code id} is a step id, a remembered name or {@code run}. {@code $${} stands for a literal
 * {@code ${}.
 */
public final class Refs {
  private Refs() {}

  /** One reference found in a text. */
  public static final class Ref {
    /** The reference as written, e.g. {@code ${copy.rows.Orders.added[0].Number}}. */
    public final String source;
    /** The step id, remembered name or {@code run}. */
    public final String id;
    /** Empty, or the path after the id, e.g. {@code .rows.Orders.added[0].Number}. */
    public final String path;
    final int start;
    final int end;

    Ref(String source, String id, String path, int start, int end) {
      this.source = source;
      this.id = id;
      this.path = path;
      this.start = start;
      this.end = end;
    }

    @Override
    public String toString() {
      return source;
    }
  }

  /** The references in the text, in order. Throws when one is unclosed or its path is invalid. */
  public static List<Ref> scan(String text) {
    List<Ref> refs = new ArrayList<Ref>();
    int i = 0;
    while (i < text.length()) {
      if (text.startsWith("$${", i)) {
        i += 3;
      } else if (text.startsWith("${", i)) {
        Ref r = read(text, i);
        refs.add(r);
        i = r.end;
      } else {
        i++;
      }
    }
    return refs;
  }

  public static boolean has(String text) {
    return !scan(text).isEmpty();
  }

  /** Makes a literal text safe to store: {@code ${} becomes {@code $${}. */
  public static String escape(String literal) {
    return literal.replace("${", "$${");
  }

  /** Turns {@code $${} back into {@code ${}. */
  public static String unescape(String text) {
    return text.replace("$${", "${");
  }

  /**
   * The references in the value strings of the action, as it is written to JSON (see {@link #mapValues}). Throws when
   * one is unclosed or its path is invalid.
   */
  public static List<Ref> of(Action a) {
    final List<Ref> out = new ArrayList<Ref>();
    mapValues(
        FlowJson.mapper().valueToTree(a),
        text -> {
          out.addAll(scan(text));
          return text;
        });
    return out;
  }

  /** Field names whose strings are never values: a sentence, the step type, names and the locator's identity. */
  private static final Set<String> NOT_VALUES = new HashSet<String>(Arrays.asList(
      "say", "action", "id", "as", "window", "role", "scope", "column", "match", "ref"));

  /**
   * A copy of {@code node} with {@code fn} applied to every string value, in objects, arrays and the node itself. The
   * strings of the fields in {@code NOT_VALUES} are left alone (references live in value fields only), except
   * inside {@code params} and {@code row}, whose keys are the app's. The one walk that scanning, escaping and
   * unescaping share.
   */
  public static JsonNode mapValues(JsonNode node, Function<String, String> fn) {
    return map(node, fn, false);
  }

  private static JsonNode map(JsonNode n, Function<String, String> fn, boolean free) {
    if (n.isTextual()) {
      return TextNode.valueOf(fn.apply(n.textValue()));
    }
    if (n.isObject()) {
      ObjectNode out = FlowJson.mapper().createObjectNode();
      for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> f = it.next();
        boolean skip = !free && NOT_VALUES.contains(f.getKey());
        boolean appKeys = free || "params".equals(f.getKey()) || "row".equals(f.getKey());
        out.set(f.getKey(), skip ? f.getValue() : map(f.getValue(), fn, appKeys));
      }
      return out;
    }
    if (n.isArray()) {
      ArrayNode out = FlowJson.mapper().createArrayNode();
      for (JsonNode e : n) {
        out.add(map(e, fn, free));
      }
      return out;
    }
    return n;
  }

  /** Text with every reference replaced by what {@code values} gives and escapes undone. */
  static String replace(String text, Function<Ref, String> values) {
    StringBuilder sb = new StringBuilder();
    int last = 0;
    for (Ref r : scan(text)) {
      sb.append(unescape(text.substring(last, r.start))).append(values.apply(r));
      last = r.end;
    }
    return sb.append(unescape(text.substring(last))).toString();
  }

  private static Ref read(String text, int start) {
    int n = text.length();
    int i = start + 2;
    int idStart = i;
    while (i < n && (Character.isLetterOrDigit(text.charAt(i)) || "_-".indexOf(text.charAt(i)) >= 0)) {
      i++;
    }
    String id = text.substring(idStart, i);
    int pathStart = i;
    int depth = 0;
    char quote = 0;
    while (i < n) {
      char c = text.charAt(i);
      if (quote != 0) {
        if (c == '\\') {
          i++;
        } else if (c == quote) {
          quote = 0;
        }
      } else if (c == '\'' || c == '"') {
        quote = c;
      } else if (c == '[') {
        depth++;
      } else if (c == ']') {
        depth--;
      } else if (c == '}' && depth <= 0) {
        break;
      }
      i++;
    }
    if (i >= n) {
      throw new IllegalArgumentException("Unclosed reference in: " + text);
    }
    String path = text.substring(pathStart, i);
    if (id.isEmpty()) {
      throw new IllegalArgumentException("Reference without an id in: " + text);
    }
    if (!path.isEmpty()) {
      try {
        JsonPaths.check("$" + path);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(e.getMessage() + " (in: " + text + ")", e);
      }
    }
    return new Ref(text.substring(start, i + 1), id, path, start, i + 1);
  }
}
