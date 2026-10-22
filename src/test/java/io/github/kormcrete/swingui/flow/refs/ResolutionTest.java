package io.github.kormcrete.swingui.flow.refs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.entry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kormcrete.swingui.flow.Action;
import io.github.kormcrete.swingui.flow.Flow;
import io.github.kormcrete.swingui.flow.FlowJson;
import io.github.kormcrete.swingui.flow.verb.ClickVerb;
import io.github.kormcrete.swingui.flow.verb.TypeVerb;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ResolutionTest {
  private final RunValues values = new RunValues("a1b2c3d4", LocalDateTime.of(2026, 10, 21, 9, 5, 7));

  private static Action step(String json) {
    return FlowJson.parse("{\"steps\":[" + json + "]}").steps.get(0);
  }

  private static Flow flow(String... steps) {
    return FlowJson.parse("{\"name\":\"t\",\"steps\":[" + String.join(",", steps) + "]}");
  }

  private void copied(String number) throws Exception {
    values.put(
        "copy",
        new ObjectMapper()
            .readTree("{\"rows\":{\"Orders\":{\"added\":[{\"Number\":\"" + number + "\"}],\"count\":2}}}"));
  }

  @Test
  void referencesInValueFieldsAreFilledAndTheSentenceIsLeftAlone() throws Exception {
    copied("ORD-1643");
    Action a =
        step(
            "{\"action\":\"click\",\"say\":\"Open ${copy.rows.Orders.added[0].Number}\",\"target\":{\"role\":\"table\","
                + "\"name\":\"Orders\",\"row\":{\"Number\":\"${copy.rows.Orders.added[0].Number}\"}}}");

    ClickVerb filled = (ClickVerb) Resolution.fill(a, values);

    assertThat(filled.target.row).containsExactly(entry("Number", "ORD-1643"));
    assertThat(filled.say).isEqualTo("Open ${copy.rows.Orders.added[0].Number}");
    assertThat(((ClickVerb) a).target.row).containsExactly(entry("Number", "${copy.rows.Orders.added[0].Number}"));
    assertThat(Resolution.resolved(a, values))
        .containsExactly(entry("${copy.rows.Orders.added[0].Number}", "ORD-1643"));
  }

  @Test
  void anEscapedDollarBraceBecomesALiteralOnce() {
    Action a =
        step("{\"action\":\"type\",\"target\":{\"role\":\"text\",\"name\":\"Note\"},\"text\":\"a $${b} ${run.id}\"}");

    assertThat(((TypeVerb) Resolution.fill(a, values)).text).isEqualTo("a ${b} a1b2c3d4");
    assertThat(Resolution.resolved(a, values)).containsExactly(entry("${run.id}", "a1b2c3d4"));
  }

  @Test
  void aStepWithNothingToFillIsReturnedAsItIs() {
    Action a = step("{\"action\":\"type\",\"target\":{\"role\":\"text\",\"name\":\"Note\"},\"text\":\"plain\"}");

    assertThat(Resolution.fill(a, values)).isSameAs(a);
    assertThat(Resolution.resolved(a, values)).isEmpty();
  }

  @Test
  void aMissingValueThrowsNamingTheReference() {
    Action a = step("{\"action\":\"type\",\"target\":{\"role\":\"text\",\"name\":\"Note\"},\"text\":\"${copy.fields.Number}\"}");

    RunValues.UnresolvedReference e =
        catchThrowableOfType(() -> Resolution.fill(a, values), RunValues.UnresolvedReference.class);

    assertThat(e.ref.source).isEqualTo("${copy.fields.Number}");
  }

  private static final String COPY =
      "{\"action\":\"click\",\"id\":\"copy\",\"target\":{\"role\":\"button\",\"name\":\"Copy\"}}";
  private static final String NAVIGATE = "{\"action\":\"navigate\",\"target\":\"Orders\"}";

  private static String words(String reference, Flow flow) {
    return Resolution.inWords(Refs.scan(reference).get(0), flow);
  }

  @Test
  void inWordsNamesTheStepAndWhatItDidNotChange() {
    Flow f = flow(NAVIGATE, NAVIGATE, COPY);

    assertThat(words("${copy.rows.Orders.added[0].Number}", f)).isEqualTo("Step 3 (Copy) added no row to Orders");
    assertThat(words("${copy.rows.Orders.removed[0].Number}", f)).isEqualTo("Step 3 (Copy) removed no row from Orders");
    assertThat(words("${copy.rows.Orders.changed[0].after.Status}", f)).isEqualTo("Step 3 (Copy) changed no row in Orders");
    assertThat(words("${copy.fields.Number}", f)).isEqualTo("Step 3 (Copy) changed no field Number");
    assertThat(words("${copy.fields['Billing › Name']}", f)).isEqualTo("Step 3 (Copy) changed no field Billing › Name");
    assertThat(words("${copy.texts.Total}", f)).isEqualTo("Step 3 (Copy) changed no text Total");
    assertThat(words("${copy.items.Categories.added[0]}", f)).isEqualTo("Step 3 (Copy) added no item to Categories");
    assertThat(words("${copy.opened.tabs[0]}", f)).isEqualTo("Step 3 (Copy) opened no tab");
    assertThat(words("${copy.closed.dialogs[0]}", f)).isEqualTo("Step 3 (Copy) closed no dialog");
    assertThat(words("${copy.selected.Orders[0].Number}", f)).isEqualTo("Step 3 (Copy) selected no row in Orders");
    assertThat(words("${copy.message}", f)).isEqualTo("Step 3 (Copy) showed no message");
  }

  @Test
  void inWordsForRememberedRunAndUnknownNames() {
    Flow f =
        flow(
            NAVIGATE,
            "{\"action\":\"remember\",\"target\":{\"role\":\"label\",\"name\":\"Number\"},\"as\":\"orderNumber\"}",
            "{\"action\":\"menu\",\"id\":\"newOrder\",\"path\":[\"Orders\",\"New order\"]}");

    assertThat(words("${orderNumber}", f)).isEqualTo("nothing remembered as orderNumber");
    assertThat(words("${run.week}", f)).isEqualTo("the run has no value week (it has id, date and time)");
    assertThat(words("${newOrder.opened.tabs[0]}", f)).isEqualTo("Step 2 (New order) opened no tab");
    assertThat(words("${nobody.fields.Number}", f)).isEqualTo("no earlier step is named nobody");
  }

  @Test
  void aTabTitleAndAWindowTitleAreFilledIn() throws Exception {
    copied("ORD-1641");
    Action a =
        step(
            "{\"action\":\"click\",\"target\":{\"window\":\"window:${copy.rows.Orders.added[0].Number}\","
                + "\"scope\":[\"tab:${copy.rows.Orders.added[0].Number}\"],\"role\":\"button\",\"name\":\"Save\"}}");

    ClickVerb filled = (ClickVerb) Resolution.fill(a, values);

    assertThat(filled.target.window).isEqualTo("window:ORD-1641");
    assertThat(filled.target.scope).containsExactly("tab:ORD-1641");
  }
}
