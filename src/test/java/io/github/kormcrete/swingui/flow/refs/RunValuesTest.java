package io.github.kormcrete.swingui.flow.refs;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RunValuesTest {
  private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 21, 9, 5, 7);

  private static Refs.Ref ref(String s) {
    return Refs.scan(s).get(0);
  }

  private static com.fasterxml.jackson.databind.JsonNode json(String s) {
    try {
      return new ObjectMapper().readTree(s);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void runIdIsEightLowercaseAlnum() {
    for (int i = 0; i < 50; i++) {
      assertTrue(RunValues.newRunId().matches("[a-z0-9]{8}"));
    }
  }

  @Test
  void runValuesAreIdDateAndTime() {
    RunValues v = new RunValues("abcd1234", T0);
    assertEquals("abcd1234", v.fill("${run.id}"));
    assertEquals("2026-10-21", v.fill("${run.date}"));
    assertEquals("09:05:07", v.fill("${run.time}"));
    assertTrue(v.has("run"));
  }

  @Test
  void latestPutWins() {
    RunValues v = new RunValues("abcd1234", T0);
    v.put("copy", json("{\"n\":\"one\"}"));
    v.put("copy", json("{\"n\":\"two\"}"));
    assertEquals("two", v.fill("${copy.n}"));
  }

  @Test
  void startRunRenewsRunValuesAndKeepsOutcomes() {
    RunValues v = new RunValues("abcd1234", T0);
    v.put("copy", json("{\"n\":\"one\"}"));
    v.remember("who", "Ada");
    v.startRun("zzzz9999", T0.plusDays(1));
    assertEquals("zzzz9999", v.fill("${run.id}"));
    assertEquals("2026-10-22", v.fill("${run.date}"));
    assertEquals("one", v.fill("${copy.n}"));
    assertEquals("Ada", v.fill("${who}"));
  }

  @Test
  void rememberedNameAloneIsItsValue() {
    RunValues v = new RunValues("abcd1234", T0);
    v.remember("number", "ORD-1640");
    assertEquals(Optional.of("ORD-1640"), v.resolve(ref("${number}")));
    assertEquals(Optional.of("ORD-1640"), v.resolve(ref("${number.value}")));
    assertTrue(v.has("number"));
    assertFalse(v.has("other"));
  }

  @Test
  void aSelectionOfSeveralValuesDoesNotResolve() {
    RunValues v = new RunValues("abcd1234", T0);
    v.put("copy", json("{\"rows\":[{\"n\":\"a\"},{\"n\":\"b\"}]}"));
    assertEquals(Optional.empty(), v.resolve(ref("${copy.rows[*].n}")));
    assertEquals(Optional.empty(), v.resolve(ref("${copy.rows[0]}")));
    assertEquals(Optional.of("b"), v.resolve(ref("${copy.rows[-1].n}")));
    assertEquals(Optional.empty(), v.resolve(ref("${nobody.x}")));
  }

  @Test
  void fillThrowsUnresolvedWithTheReference() {
    RunValues v = new RunValues("abcd1234", T0);
    RunValues.UnresolvedReference e =
        assertThrows(RunValues.UnresolvedReference.class, () -> v.fill("Order ${copy.n}"));
    assertEquals("${copy.n}", e.ref.source);
  }

  @Test
  void fillUnescapesLiteralDollars() {
    RunValues v = new RunValues("abcd1234", T0);
    assertEquals("a ${b} abcd1234", v.fill("a $${b} ${run.id}"));
  }
}
