package io.github.kormcrete.swingui.flow.refs;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonPathsTest {
  private static JsonNode json(String s) {
    try {
      return new ObjectMapper().readTree(s);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static final JsonNode DOC =
      json(
          "{\"a\":{\"b c\":[{\"Number\":\"ORD-1640\",\"Status\":\"New\"},"
              + "{\"Number\":\"ORD-1641\",\"Status\":\"Paid\"}]},\"Number\":\"top\"}");

  @Test
  void dotAndBracketKeys() {
    assertEquals("ORD-1640", JsonPaths.select(DOC, "$.a['b c'][0].Number").get(0).asText());
    assertEquals("top", JsonPaths.select(DOC, "$['Number']").get(0).asText());
  }

  @Test
  void negativeIndexIsFromTheEnd() {
    assertEquals("ORD-1641", JsonPaths.select(DOC, "$.a['b c'][-1].Number").get(0).asText());
  }

  @Test
  void wildcardSelectsEveryElement() {
    assertEquals(2, JsonPaths.select(DOC, "$.a['b c'][*]").size());
    assertEquals(2, JsonPaths.select(DOC, "$.a['b c'][*].Status").size());
  }

  @Test
  void recursiveDescentFindsKeyAnywhere() {
    List<JsonNode> r = JsonPaths.select(DOC, "$..Number");
    assertEquals(3, r.size());
  }

  @Test
  void filterByEqualityAndInequality() {
    List<JsonNode> r = JsonPaths.select(DOC, "$.a['b c'][?(@.Status == 'New')].Number");
    assertEquals(1, r.size());
    assertEquals("ORD-1640", r.get(0).asText());
    assertEquals(
        "ORD-1641",
        JsonPaths.select(DOC, "$.a['b c'][?(@.Status != 'New')].Number").get(0).asText());
  }

  @Test
  void missingThingsSelectNothing() {
    assertTrue(JsonPaths.select(DOC, "$.nope").isEmpty());
    assertTrue(JsonPaths.select(DOC, "$.a['b c'][5]").isEmpty());
    assertTrue(JsonPaths.select(DOC, "$.a['b c'][-9]").isEmpty());
    assertTrue(JsonPaths.select(DOC, "$.Number.deeper").isEmpty());
  }

  @Test
  void checkRefusesBrokenPaths() {
    JsonPaths.check("$.a[0]");
    assertThrows(IllegalArgumentException.class, () -> JsonPaths.check("$.a["));
    assertThrows(IllegalArgumentException.class, () -> JsonPaths.check("a.b"));
    assertThrows(IllegalArgumentException.class, () -> JsonPaths.check("$.a['x"));
    assertThrows(IllegalArgumentException.class, () -> JsonPaths.check("$[?(@.k ~ 'v')]"));
  }

  @Test
  void putMakesThePlaceAndSelectFindsItAgain() {
    com.fasterxml.jackson.databind.node.ObjectNode root = new ObjectMapper().createObjectNode();
    JsonPaths.put(root, "$.rows.Orders.added[1].Number", "ORD-1641");
    JsonPaths.put(root, "$.rows.Orders.added[1]['Order date']", "2026-10-21");
    JsonPaths.put(root, "$.fields.Status", "New");
    assertEquals("ORD-1641", JsonPaths.select(root, "$.rows.Orders.added[1].Number").get(0).asText());
    assertEquals("2026-10-21", JsonPaths.select(root, "$.rows.Orders.added[-1]['Order date']").get(0).asText());
    assertTrue(JsonPaths.select(root, "$.rows.Orders.added[0]").get(0).isNull());
    assertEquals("New", JsonPaths.select(root, "$.fields.Status").get(0).asText());
    assertThrows(IllegalArgumentException.class, () -> JsonPaths.put(root, "$.rows[*].x", "v"));
    assertThrows(IllegalArgumentException.class, () -> JsonPaths.put(root, "$.rows.Orders.added[-1].Number", "v"));
    assertThrows(IllegalArgumentException.class, () -> JsonPaths.put(root, "$.fields.Status.deeper", "v"));
  }
}
