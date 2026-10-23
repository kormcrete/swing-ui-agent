package io.github.kormcrete.swingui.journal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.flow.FlowJson;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JournalLinesTest {

    private static final long AT = 1792742400123L; // 2026-10-23T08:00:00.123Z

    private static JsonNode json(String text) {
        try {
            return FlowJson.mapper().readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(text, e);
        }
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    @Test
    void aTypeIntoACellIsAnActionWithTargetLocatorValueAndSentence() {
        JsonNode step = json("{\"action\":\"type\",\"target\":{\"role\":\"table\",\"name\":\"Lines\",\"scope\":[\"tab:Orders\"],"
                + "\"row\":{\"Number\":\"ORD-1640\"},\"column\":\"Qty\"},\"text\":\"16\"}");

        ObjectNode line = JournalLines.step(step, true, null, null, 42, AT);

        assertThat(line.path("t").asText()).isEqualTo("2026-10-23T08:00:00.123Z");
        assertThat(line.path("kind").asText()).isEqualTo("action");
        assertThat(line.path("verb").asText()).isEqualTo("type");
        assertThat(line.path("target").asText()).isEqualTo("table 'Lines' [tab:Orders] row={Number=ORD-1640} column=Qty");
        assertThat(line.path("locator").path("column").asText()).isEqualTo("Qty");
        assertThat(line.path("locator")).isEqualTo(step.get("target"));
        assertThat(line.path("value").asText()).isEqualTo("16");
        assertThat(line.has("capped")).isFalse();
        assertThat(line.has("expected")).isFalse();
        assertThat(line.path("step")).isEqualTo(step);
        assertThat(line.path("step").path("action").asText()).isEqualTo("type");
        assertThat(line.path("outcome").asText()).isEqualTo("ok");
        assertThat(line.has("message")).isFalse();
        assertThat(line.has("reason")).isFalse();
        assertThat(line.path("ms").asLong()).isEqualTo(42);
        assertThat(line.path("text").asText()).isEqualTo("Type 16 into Qty of row ORD-1640 in Lines (tab Orders)");
        List<String> fields = new ArrayList<String>();
        line.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("t", "kind", "verb", "target", "locator", "value", "step", "outcome", "ms", "text");
    }

    @Test
    void anExpectIsACheckWithItsExpectation() {
        ObjectNode line = JournalLines.step(json("{\"action\":\"expect\",\"target\":{\"role\":\"label\",\"name\":\"Total\"},"
                + "\"text\":\"€7.32\",\"timeout\":5000}"), true, null, null, 3, AT);

        assertThat(line.path("kind").asText()).isEqualTo("check");
        assertThat(line.path("verb").asText()).isEqualTo("expect");
        assertThat(line.path("expected")).isEqualTo(json("{\"text\":\"€7.32\"}"));
        assertThat(line.has("value")).isFalse();
        assertThat(line.path("text").asText()).isEqualTo("Check that Total shows €7.32");
        assertThat(JournalLines.step(json("{\"action\":\"remember\",\"target\":{\"role\":\"label\",\"name\":\"Total\"},"
                + "\"as\":\"total\"}"), true, null, null, 3, AT).path("kind").asText()).isEqualTo("read");
    }

    @Test
    void aFailedStepCarriesMessageAndReason() {
        ObjectNode line = JournalLines.step(json("{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Place order\"}}"),
                false, "no button 'Place order'", "NOT_FOUND", 1500, AT);

        assertThat(line.path("outcome").asText()).isEqualTo("failed");
        assertThat(line.path("message").asText()).isEqualTo("no button 'Place order'");
        assertThat(line.path("reason").asText()).isEqualTo("NOT_FOUND");
        assertThat(line.path("text").asText()).isEqualTo("Click Place order");
    }

    @Test
    void valuesPerVerb() {
        assertThat(value("{\"action\":\"press\",\"keys\":\"ctrl S\"}")).isEqualTo("ctrl S");
        assertThat(value("{\"action\":\"select\",\"target\":{\"role\":\"list\",\"name\":\"Products\"},\"items\":[\"Pen\",\"Ink\"]}"))
                .isEqualTo("Pen, Ink");
        assertThat(value("{\"action\":\"select\",\"item\":\"Pen\"}")).isEqualTo("Pen");
        assertThat(value("{\"action\":\"menu\",\"path\":[\"File\",\"Export\"]}")).isEqualTo("File > Export");
        assertThat(value("{\"action\":\"contextMenu\",\"path\":[\"Copy\"]}")).isEqualTo("Copy");
        assertThat(value("{\"action\":\"check\",\"target\":{\"name\":\"Gift wrap\"}}")).isEqualTo("checked");
        assertThat(value("{\"action\":\"check\",\"target\":{\"name\":\"Gift wrap\"},\"checked\":false}")).isEqualTo("unchecked");
        assertThat(value("{\"action\":\"drop\",\"files\":[\"a.csv\",\"b.csv\"]}")).isEqualTo("a.csv, b.csv");
        assertThat(value("{\"action\":\"drop\",\"text\":\"ORD-1640\"}")).isEqualTo("ORD-1640");
        assertThat(value("{\"action\":\"scroll\",\"to\":\"bottom\"}")).isEqualTo("bottom");
        assertThat(value("{\"action\":\"scroll\",\"pages\":2}")).isEqualTo("2 pages");
        assertThat(value("{\"action\":\"resizeColumn\",\"delta\":-40}")).isEqualTo("-40");
        assertThat(value("{\"action\":\"resizeWindow\",\"width\":800,\"height\":600}")).isEqualTo("800x600");
        assertThat(value("{\"action\":\"answer\",\"button\":\"Yes\"}")).isEqualTo("Yes");
        assertThat(value("{\"action\":\"chooseFile\",\"path\":\"/tmp/orders.csv\"}")).isEqualTo("/tmp/orders.csv");
        assertThat(value("{\"action\":\"navigate\",\"target\":\"Orders\"}")).isEqualTo("Orders");
        assertThat(value("{\"action\":\"loginAs\",\"as\":\"ada\"}")).isEqualTo("ada");
        assertThat(value("{\"action\":\"loginAs\",\"user\":\"ada.shop\"}")).isEqualTo("ada.shop");
        assertThat(value("{\"action\":\"setClipboard\",\"text\":\"ORD-1640\"}")).isEqualTo("ORD-1640");
        assertThat(value("{\"action\":\"click\",\"target\":{\"name\":\"Save\"}}")).isNull();

        ObjectNode drag = JournalLines.step(json("{\"action\":\"drag\",\"from\":{\"role\":\"list\",\"name\":\"Products\","
                + "\"item\":[\"Pen\"]},\"to\":{\"role\":\"table\",\"name\":\"Lines\"}}"), true, null, null, 1, AT);
        assertThat(drag.path("target").asText()).isEqualTo("list 'Products' item=[Pen]");
        assertThat(drag.path("locator").path("name").asText()).isEqualTo("Products");
        assertThat(drag.path("value").asText()).isEqualTo("table 'Lines'");
        assertThat(JournalLines.step(json("{\"action\":\"navigate\",\"target\":\"Orders\"}"), true, null, null, 1, AT).has("target"))
                .isFalse();
    }

    private static String value(String step) {
        JsonNode v = JournalLines.step(json(step), true, null, null, 1, AT).get("value");
        return v == null ? null : v.asText();
    }

    @Test
    void typingIntoAPasswordTargetIsMasked() {
        ObjectNode line = JournalLines.step(json("{\"action\":\"type\",\"target\":{\"role\":\"password\",\"name\":\"Password\"},"
                + "\"text\":\"pa55word\"}"), false, "could not type 'pa55word' into Password", "NOT_ACTIONABLE", 1, AT);

        assertThat(line.path("value").asText()).isEqualTo("***");
        assertThat(line.path("step").path("text").asText()).isEqualTo("***");
        assertThat(line.path("text").asText()).isEqualTo("Type *** into Password");
        assertThat(line.path("message").asText()).isEqualTo("could not type '***' into Password");
        assertThat(line.toString()).doesNotContain("pa55word");
    }

    @Test
    void aLongValueIsCappedInValueStepAndSentence() {
        String big = repeat('a', 50_000);
        ObjectNode typed = JournalLines.step(json("{\"action\":\"type\",\"target\":{\"role\":\"text\",\"name\":\"Note\"},"
                + "\"text\":\"" + big + "\"}"), true, null, null, 1, AT);

        assertThat(typed.path("value").asText()).hasSize(2000);
        assertThat(typed.path("capped").asBoolean()).isTrue();
        assertThat(typed.path("step").path("text").asText()).hasSize(2000);
        assertThat(typed.path("text").asText().length()).isLessThanOrEqualTo(120);
        assertThat(typed.toString().length()).isLessThan(5000);

        List<List<String>> rows = new ArrayList<List<String>>();
        for (int i = 0; i < 5000; i++) rows.add(Arrays.asList("ORD-" + i, "Ada Shop Ltd", "€7.32"));
        ObjectNode read = JournalLines.read("rows", Locator.of("table", "Orders"), rows, true, null, 80, AT);

        assertThat(read.path("value").isTextual()).isTrue();
        assertThat(read.path("value").asText()).hasSize(2000).startsWith("[[\"ORD-0\",\"Ada Shop Ltd\",\"€7.32\"]");
        assertThat(read.path("capped").asBoolean()).isTrue();
        assertThat(read.path("text").asText().length()).isLessThanOrEqualTo(120);
        assertThat(read.toString().length()).isLessThan(5000);
    }

    @Test
    void aReadKeepsItsValueAsJson() {
        ObjectNode read = JournalLines.read("text", Locator.of("label", "Total"), "€7.32", true, null, 4, AT);

        assertThat(read.path("kind").asText()).isEqualTo("read");
        assertThat(read.path("verb").asText()).isEqualTo("text");
        assertThat(read.path("target").asText()).isEqualTo("label 'Total'");
        assertThat(read.path("locator")).isEqualTo(json("{\"role\":\"label\",\"name\":\"Total\"}"));
        assertThat(read.path("value").asText()).isEqualTo("€7.32");
        assertThat(read.path("outcome").asText()).isEqualTo("ok");
        assertThat(read.path("text").asText()).isEqualTo("Read the text of Total: €7.32");
        assertThat(JournalLines.read("rowCount", Locator.of("table", "Lines"), 3, true, null, 1, AT).path("value").asInt())
                .isEqualTo(3);
        assertThat(JournalLines.read("columns", Locator.of("table", "Lines"), Arrays.asList("Product", "Qty"), true, null, 1, AT)
                .path("value")).isEqualTo(json("[\"Product\",\"Qty\"]"));
        assertThat(JournalLines.read("columns", Locator.of("table", "Lines"), new String[] {"Product", "Qty"}, true, null, 1, AT)
                .path("text").asText())
                .isEqualTo(JournalLines.read("columns", Locator.of("table", "Lines"), Arrays.asList("Product", "Qty"), true, null, 1, AT)
                        .path("text").asText())
                .contains("Product, Qty");
        assertThat(JournalLines.read("bounds", Locator.of("table", "Lines"), new StringBuilder("10,20"), true, null, 1, AT)
                .path("value").asText()).isEqualTo("10,20");

        ObjectNode failed = JournalLines.read("text", Locator.of("label", "Total"), null, false, "no label 'Total'", 1000, AT);
        assertThat(failed.has("value")).isFalse();
        assertThat(failed.path("outcome").asText()).isEqualTo("failed");
        assertThat(failed.path("message").asText()).isEqualTo("no label 'Total'");
        assertThat(failed.path("text").asText()).isEqualTo("Read the text of Total");
    }

    @Test
    void sectionAndCheckpointLines() {
        ObjectNode section = JournalLines.section("Fill the cart", AT);
        assertThat(section.toString()).isEqualTo("{\"t\":\"2026-10-23T08:00:00.123Z\",\"kind\":\"section\",\"text\":\"Fill the cart\"}");

        ObjectNode cp = JournalLines.checkpoint("cart-totals", Locator.of("table", "Lines"),
                Collections.singletonList(Locator.of("label", "Date")), "checkpoints/K#m/cart-totals.png",
                "checkpoints/K#m/cart-totals.json", true, null, 120, AT);
        List<String> fields = new ArrayList<String>();
        cp.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("t", "kind", "name", "target", "locator", "png", "json", "masks", "outcome", "ms", "text");
        assertThat(cp.path("kind").asText()).isEqualTo("checkpoint");
        assertThat(cp.path("masks").asInt()).isEqualTo(1);
        assertThat(cp.path("text").asText()).isEqualTo("Checkpoint cart-totals of Lines, masking Date");
        ObjectNode failed = JournalLines.checkpoint("cart-totals", null, null, null, null, false, "no window", 5, AT);
        assertThat(failed.has("target")).isFalse();
        assertThat(failed.path("masks").asInt()).isEqualTo(0);
        assertThat(failed.path("message").asText()).isEqualTo("no window");
    }

    @Test
    void timesAlwaysHaveMilliseconds() {
        assertThat(JournalLines.time(0)).isEqualTo("1970-01-01T00:00:00.000Z");
        assertThat(JournalLines.time(AT)).isEqualTo("2026-10-23T08:00:00.123Z");
    }
}
