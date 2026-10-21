package io.github.kormcrete.swingui.flow.refs;

import static org.junit.jupiter.api.Assertions.*;

import io.github.kormcrete.swingui.flow.Action;
import io.github.kormcrete.swingui.flow.FlowJson;
import io.github.kormcrete.swingui.flow.NavigateAction;
import java.util.List;
import org.junit.jupiter.api.Test;

class RefsTest {

  @Test
  void findsReferencesAndSkipsEscapes() {
    List<Refs.Ref> refs = Refs.scan("Copy ${run.id} and $${x} ${copy.rows.Orders.added[0].Number}");
    assertEquals(2, refs.size());
    assertEquals("run", refs.get(0).id);
    assertEquals(".id", refs.get(0).path);
    assertEquals("copy", refs.get(1).id);
    assertEquals(".rows.Orders.added[0].Number", refs.get(1).path);
    assertEquals("${copy.rows.Orders.added[0].Number}", refs.get(1).source);
  }

  @Test
  void onlyValueFieldsAreScanned() throws Exception {
    Action a =
        FlowJson.mapper()
            .readValue(
                "{\"action\":\"click\",\"id\":\"copy\",\"say\":\"Opens ${a}\","
                    + "\"target\":{\"window\":\"${w}\",\"role\":\"table\",\"name\":\"Orders\","
                    + "\"scope\":[\"${s}\"],\"column\":\"${c}\",\"row\":{\"role\":\"${copy.x}\"}}}",
                Action.class);
    List<Refs.Ref> refs = Refs.of(a);
    assertEquals(1, refs.size());
    assertEquals("${copy.x}", refs.get(0).source);
  }

  @Test
  void bracketKeysWithSpacesAndDots() {
    List<Refs.Ref> refs = Refs.scan("${copy.rows['Proben-Nr.'].added[-1]['Order no.']}");
    assertEquals(1, refs.size());
    assertEquals(".rows['Proben-Nr.'].added[-1]['Order no.']", refs.get(0).path);
  }

  @Test
  void anUnclosedReferenceIsRefusedWithItsText() {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Refs.scan("Order ${copy.rows"));
    assertTrue(e.getMessage().contains("Order ${copy.rows"), e.getMessage());
  }

  @Test
  void aPathThatDoesNotParseIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> Refs.scan("${copy.rows[}"));
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Refs.scan("${copy.x[abc]}"));
    assertTrue(e.getMessage().contains("Bad path"), e.getMessage());
  }

  @Test
  void escapeRoundTrips() {
    assertEquals("a $${b}", Refs.escape("a ${b}"));
    assertEquals("a ${b}", Refs.unescape(Refs.escape("a ${b}")));
  }

  @Test
  void hasSeesOnlyRealReferences() {
    assertTrue(Refs.has("x ${run.id}"));
    assertFalse(Refs.has("x $${run.id}"));
    assertFalse(Refs.has("plain"));
  }

  @Test
  void ofWalksEveryStringValueOfAnAction() {
    NavigateAction a = new NavigateAction();
    a.target = "Orders > ${run.id}";
    List<Refs.Ref> refs = Refs.of(a);
    assertEquals(1, refs.size());
    assertEquals("run", refs.get(0).id);
  }
}
