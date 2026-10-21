package io.github.kormcrete.swingui.flow.refs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.flow.Action;
import io.github.kormcrete.swingui.flow.ContextMenuAction;
import io.github.kormcrete.swingui.flow.DoAction;
import io.github.kormcrete.swingui.flow.Flow;
import io.github.kormcrete.swingui.flow.FlowJson;
import io.github.kormcrete.swingui.flow.NavigateAction;
import io.github.kormcrete.swingui.flow.StepNumbers;
import io.github.kormcrete.swingui.flow.verb.MenuVerb;
import io.github.kormcrete.swingui.flow.verb.PickVerb;
import io.github.kormcrete.swingui.flow.verb.RememberVerb;
import io.github.kormcrete.swingui.flow.verb.VerbAction;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A step of a recorded test made ready to run: its references replaced by this run's values, and what to say when one
 * has none.
 */
public final class Resolution {
  private static final String RUN = "run";

  private Resolution() {}

  /**
   * A copy of the step with every reference in its value strings (the fields {@link Refs#mapValues} walks) replaced by
   * its value and each {@code $${} turned into {@code ${}; the step itself when that changes nothing. Throws {@link
   * RunValues.UnresolvedReference} for the first reference without a value.
   */
  public static Action fill(Action a, RunValues values) {
    JsonNode tree = FlowJson.mapper().valueToTree(a);
    // RunValues.fill undoes the escapes too, so the literal text is unescaped exactly once
    JsonNode filled = Refs.mapValues(tree, values::fill);
    if (filled.equals(tree)) {
      return a;
    }
    try {
      return FlowJson.mapper().treeToValue(filled, Action.class);
    } catch (JsonProcessingException e) {
      throw new DriverException(
          Reason.BAD_INPUT, "the step with its values filled in is not a step: " + e.getOriginalMessage());
    }
  }

  /** The value of each reference in the step, by the reference as written, for those that have one now. */
  public static Map<String, String> resolved(Action a, RunValues values) {
    Map<String, String> out = new LinkedHashMap<String, String>();
    for (Refs.Ref r : Refs.of(a)) {
      Optional<String> v = values.resolve(r);
      if (v.isPresent()) {
        out.put(r.source, v.get());
      }
    }
    return out;
  }

  /**
   * Why the reference has no value, in words a person reads: {@code Step 3 (Copy) added no row to Orders}, {@code
   * nothing remembered as orderNumber}. Never a value the recording saw.
   */
  public static String inWords(Refs.Ref ref, Flow flow) {
    if (RUN.equals(ref.id)) {
      String name = ref.path.startsWith(".") ? ref.path.substring(1) : ref.path;
      return name.isEmpty()
          ? "${run} names no value: use run.id, run.date or run.time"
          : "the run has no value " + name + " (it has id, date and time)";
    }
    List<String> labels = StepNumbers.labels(flow.steps);
    for (int i = 0; i < flow.steps.size(); i++) {
      Action a = flow.steps.get(i);
      if (a instanceof RememberVerb && ref.id.equals(((RememberVerb) a).as)) {
        return ref.path.isEmpty() || ".value".equals(ref.path)
            ? "nothing remembered as " + ref.id
            : "the value remembered as " + ref.id + " has no " + ref.path.substring(1);
      }
      if (ref.id.equals(a.id)) {
        String name = shownName(a);
        return (StepNumbers.isCheck(a) ? "Check " : "Step ")
            + labels.get(i)
            + (name == null ? "" : " (" + name + ")")
            + " "
            + missing(ref.path);
      }
    }
    return "no earlier step is named " + ref.id;
  }

  /** What a step's outcome lacks for the path, e.g. {@code added no row to Orders}. */
  private static String missing(String path) {
    List<String> keys;
    try {
      keys = path.isEmpty() ? Collections.<String>emptyList() : JsonPaths.keys("$" + path);
    } catch (IllegalArgumentException e) {
      keys = Collections.emptyList();
    }
    if (keys.isEmpty()) {
      return path.isEmpty() ? "is no single value: name a value in what it changed" : "changed nothing at " + path;
    }
    String what = key(keys, 1);
    String kind = key(keys, 2);
    switch (keys.get(0)) {
      case "rows":
        return change(kind, "row", what);
      case "items":
        return change(kind, "item", what);
      case "fields":
        return "changed no field" + named(what);
      case "texts":
        return "changed no text" + named(what);
      case "opened":
      case "closed":
        return keys.get(0) + " no " + (what == null ? "window, dialog or tab" : singular(what));
      case "selected":
        return "selected no row" + (what == null ? "" : " in " + what);
      case "message":
        return "showed no message";
      default:
        return "changed nothing at " + path;
    }
  }

  private static String change(String kind, String noun, String in) {
    if ("added".equals(kind)) {
      return "added no " + noun + (in == null ? "" : " to " + in);
    }
    if ("removed".equals(kind)) {
      return "removed no " + noun + (in == null ? "" : " from " + in);
    }
    return "changed no " + noun + (in == null ? "" : " in " + in);
  }

  private static String singular(String plural) {
    return plural.endsWith("s") ? plural.substring(0, plural.length() - 1) : plural;
  }

  private static String named(String name) {
    return name == null ? "" : " " + name;
  }

  private static String key(List<String> keys, int i) {
    return i < keys.size() ? keys.get(i) : null;
  }

  /** The name a person sees on what the step acts on: a button's, the last menu item; null when it has none. */
  private static String shownName(Action a) {
    List<String> path = null;
    if (a instanceof MenuVerb) {
      path = ((MenuVerb) a).path;
    } else if (a instanceof PickVerb) {
      path = ((PickVerb) a).path;
    } else if (a instanceof ContextMenuAction) {
      path = ((ContextMenuAction) a).path;
    }
    if (path != null && !path.isEmpty()) {
      return path.get(path.size() - 1);
    }
    if (a instanceof DoAction && ((DoAction) a).step != null) {
      String step = ((DoAction) a).step;
      return step.substring(step.indexOf(':') + 1);
    }
    if (a instanceof NavigateAction) {
      return ((NavigateAction) a).target;
    }
    if (a instanceof VerbAction) {
      for (Locator l : ((VerbAction) a).targets().values()) {
        if (l == null) {
          continue;
        }
        if (l.name != null) {
          return l.name;
        }
        if (l.item != null && !l.item.isEmpty()) {
          return l.item.get(l.item.size() - 1);
        }
      }
    }
    return null;
  }
}
