package io.github.kormcrete.swingui.flow.refs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The values one run of a recorded test can refer to: the outcome of each step (by id, latest
 * wins), remembered values and the {@code run} values ({@code id}, {@code date}, {@code time}).
 */
public final class RunValues {
  private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private final Map<String, JsonNode> outcomes = new HashMap<String, JsonNode>();
  private final Set<String> remembered = new HashSet<String>();

  public RunValues(String runId, LocalDateTime start) {
    startRun(runId, start);
  }

  /** Eight characters from {@code [a-z0-9]}. */
  public static String newRunId() {
    StringBuilder sb = new StringBuilder(8);
    for (int i = 0; i < 8; i++) {
      sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return sb.toString();
  }

  /** Stores an outcome under the id; a later one for the same id replaces it. */
  public synchronized void put(String id, JsonNode outcome) {
    remembered.remove(id);
    outcomes.put(id, outcome);
  }

  /** Renews the {@code run} values only; outcomes and remembered values stay. */
  public synchronized void startRun(String runId, LocalDateTime start) {
    ObjectNode run = NODES.objectNode();
    run.put("id", runId);
    run.put("date", start.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
    run.put("time", start.format(DateTimeFormatter.ofPattern("HH:mm:ss")));
    outcomes.put("run", run);
  }

  /** Keeps a text under a name; {@code ${name}} then gives it. */
  public synchronized void remember(String name, String value) {
    ObjectNode o = NODES.objectNode();
    o.put("value", value);
    outcomes.put(name, o);
    remembered.add(name);
  }

  public synchronized boolean has(String id) {
    return outcomes.containsKey(id);
  }

  /**
   * The text of the single value the reference selects; empty when nothing, several values or an
   * object or array is selected.
   */
  public synchronized Optional<String> resolve(Refs.Ref ref) {
    JsonNode outcome = outcomes.get(ref.id);
    if (outcome == null) {
      return Optional.empty();
    }
    String path = ref.path.isEmpty() && remembered.contains(ref.id) ? ".value" : ref.path;
    List<JsonNode> hits = JsonPaths.select(outcome, "$" + path);
    if (hits.size() != 1) {
      return Optional.empty();
    }
    JsonNode hit = hits.get(0);
    if (!hit.isValueNode() || hit.isNull()) {
      return Optional.empty();
    }
    return Optional.of(hit.asText());
  }

  /** The text with every reference replaced and {@code $${} undone; throws when one has no value. */
  public String fill(String text) {
    return Refs.replace(
        text,
        r -> {
          Optional<String> v = resolve(r);
          if (!v.isPresent()) {
            throw new UnresolvedReference(r);
          }
          return v.get();
        });
  }

  /** A reference that selects no single value. */
  public static final class UnresolvedReference extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final transient Refs.Ref ref;

    UnresolvedReference(Refs.Ref ref) {
      super("No value for " + ref.source);
      this.ref = ref;
    }
  }
}
