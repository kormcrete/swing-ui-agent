package io.github.kormcrete.swingui.journal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kormcrete.swingui.flow.Flow;
import io.github.kormcrete.swingui.flow.RecordedTestFiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A recorded test's reply as journal lines: one per record, in the recorded test's own words. */
class RecordedJournalTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NUMBER = "${copy.rows.Orders.added[0].Number}";
    private static final long STARTED = 1_790_000_000_000L;

    @TempDir Path runs;

    @AfterEach
    void forget() {
        RunFolder.forget(runs);
    }

    /** Fill the cart (two steps, a group), Copy, then type what Copy added and check the Total under it. */
    private static final String PLACE_ORDER = "{\"version\":4,\"name\":\"place-order\",\"start\":\"reset\",\"steps\":["
            + "{\"say\":\"Fill the cart\",\"steps\":["
            + "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Add to cart\"}},"
            + "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Add to cart\"},\"say\":\"Add the second product\"}]},"
            + "{\"action\":\"click\",\"id\":\"copy\",\"target\":{\"role\":\"button\",\"name\":\"Copy\"}},"
            + "{\"action\":\"type\",\"target\":{\"id\":\"search\"},\"text\":\"" + NUMBER + "\",\"checks\":["
            + "{\"action\":\"expect\",\"target\":{\"role\":\"label\",\"name\":\"Total\"},\"text\":\"€7.32 $${kept}\"}]}]}";

    private static final String PASSED = "{\"passed\":true,\"test\":\"place-order\",\"ran\":4,\"total\":4,\"ms\":40,\"steps\":["
            + "{\"n\":1,\"action\":\"click button 'Add to cart'\",\"passed\":true,\"ms\":10},"
            + "{\"n\":2,\"say\":\"Add the second product\",\"action\":\"click button 'Add to cart'\",\"passed\":true,\"ms\":10},"
            + "{\"n\":3,\"action\":\"click button 'Copy'\",\"passed\":true,\"ms\":5},"
            + "{\"n\":4,\"action\":\"type ORD-1640\",\"passed\":true,\"ms\":5,\"resolved\":{\"" + NUMBER + "\":\"ORD-1640\"}},"
            + "{\"n\":\"4.1\",\"action\":\"expect label 'Total'\",\"passed\":true,\"ms\":5}],"
            + "\"report\":\"Recorded test 'place-order' passed: 4 steps\\n1. ...\"}";

    private TestJournal journal() {
        return RunFolder.of(runs, JournalSettings.defaults()).test("RecordedUiIT#recorded[place-order]");
    }

    private List<JsonNode> lines() throws IOException {
        Path file = RunFolder.of(runs, JournalSettings.defaults()).create().resolve("steps")
                .resolve("RecordedUiIT#recorded[place-order].jsonl");
        List<JsonNode> out = new ArrayList<JsonNode>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) out.add(JSON.readTree(line));
        return out;
    }

    private static Flow flow(String json) {
        return RecordedTestFiles.parse(json, "place-order.json");
    }

    private List<JsonNode> written(String flow, String reply, String evidence) throws IOException {
        RecordedJournal.write(journal(), flow(flow), JSON.readTree(reply), STARTED, evidence);
        return lines();
    }

    @Test
    void eachRecordIsALineWithItsSayAsText() throws IOException {
        List<JsonNode> lines = written(PLACE_ORDER, PASSED, null);

        assertThat(lines.get(0).path("kind").asText()).isEqualTo("test");
        // test, section, five records, end
        assertThat(lines).hasSize(8);
        JsonNode first = lines.get(2);
        assertThat(first.path("kind").asText()).isEqualTo("action");
        assertThat(first.path("verb").asText()).isEqualTo("click");
        assertThat(first.path("target").asText()).contains("Add to cart");
        assertThat(first.path("outcome").asText()).isEqualTo("ok");
        assertThat(first.path("ms").asLong()).isEqualTo(10);
        assertThat(first.path("t").asText()).isEqualTo(JournalLines.time(STARTED));
        assertThat(first.path("text").asText()).isEqualTo(StepWords.sentence(first.path("step")));
        // the say wins over the sentence
        JsonNode second = lines.get(3);
        assertThat(second.path("text").asText()).isEqualTo("Add the second product");
        assertThat(second.path("t").asText()).isEqualTo(JournalLines.time(STARTED + 10));
        assertThat(lines.get(4).path("t").asText()).isEqualTo(JournalLines.time(STARTED + 20));
        JsonNode check = lines.get(6);
        assertThat(check.path("kind").asText()).isEqualTo("check");
        assertThat(check.path("verb").asText()).isEqualTo("expect");
        assertThat(check.path("t").asText()).isEqualTo(JournalLines.time(STARTED + 30));
        JsonNode end = lines.get(7);
        assertThat(end.path("kind").asText()).isEqualTo("end");
        assertThat(end.path("outcome").asText()).isEqualTo("passed");
        assertThat(end.has("message")).isFalse();
        assertThat(end.has("evidence")).isFalse();
        for (int i = 1; i < lines.size(); i++) assertThat(lines.get(i).path("seq").asInt()).isEqualTo(i + 1);
    }

    @Test
    void aGroupIsASectionBeforeItsFirstStep() throws IOException {
        List<JsonNode> lines = written(PLACE_ORDER, PASSED, null);

        JsonNode section = lines.get(1);
        assertThat(section.path("kind").asText()).isEqualTo("section");
        assertThat(section.path("text").asText()).isEqualTo("Fill the cart");
        assertThat(section.path("t").asText()).isEqualTo(JournalLines.time(STARTED));
        long sections = lines.stream().filter(l -> "section".equals(l.path("kind").asText())).count();
        assertThat(sections).isEqualTo(1);
    }

    @Test
    void aResolvedValueIsWrittenAsItRan() throws IOException {
        List<JsonNode> lines = written(PLACE_ORDER, PASSED, null);

        JsonNode typed = lines.get(5);
        assertThat(typed.path("verb").asText()).isEqualTo("type");
        assertThat(typed.path("value").asText()).isEqualTo("ORD-1640");
        assertThat(typed.path("step").path("text").asText()).isEqualTo("ORD-1640");
        assertThat(typed.path("text").asText()).contains("ORD-1640").doesNotContain("${");
        assertThat(typed.path("resolved").path(NUMBER).asText()).isEqualTo("ORD-1640");
        // a literal ${ in the file ($${) is written as the run used it
        assertThat(lines.get(6).path("step").path("text").asText()).isEqualTo("€7.32 ${kept}");
        assertThat(lines.get(6).has("resolved")).isFalse();
    }

    @Test
    void aPasswordTypedFromAnEarlierStepIsMaskedAlsoInResolved() throws IOException {
        String ref = "${login.fields.PIN}";
        String flow = "{\"version\":4,\"name\":\"place-order\",\"start\":\"reset\",\"steps\":["
                + "{\"action\":\"click\",\"id\":\"login\",\"target\":{\"role\":\"button\",\"name\":\"Sign in\"}},"
                + "{\"action\":\"type\",\"target\":{\"role\":\"password\",\"name\":\"PIN\"},\"text\":\"" + ref + "\"},"
                + "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Place order\"}}]}";
        String reply = "{\"passed\":false,\"steps\":["
                + "{\"n\":1,\"action\":\"click\",\"passed\":true,\"ms\":1},"
                + "{\"n\":2,\"action\":\"type\",\"passed\":true,\"ms\":1,\"resolved\":{\"" + ref + "\":\"4711\"}},"
                + "{\"n\":3,\"action\":\"click\",\"passed\":false,\"ms\":1,\"message\":\"PIN 4711 was refused\"}],"
                + "\"report\":\"Recorded test 'place-order' failed: PIN 4711 was refused\"}";

        List<JsonNode> lines = written(flow, reply, null);

        for (JsonNode line : lines) assertThat(line.toString()).doesNotContain("4711");
        JsonNode typed = lines.get(2);
        assertThat(typed.path("value").asText()).isEqualTo("***");
        assertThat(typed.path("resolved").path(ref).asText()).isEqualTo("***");
    }

    @Test
    void aDoStepsChildrenPointAtTheirParent() throws IOException {
        String flow = "{\"version\":4,\"name\":\"place-order\",\"start\":\"reset\",\"requires\":[\"order-desk\"],\"steps\":["
                + "{\"action\":\"do\",\"step\":\"order-desk:setQty\",\"params\":{\"qty\":\"3\"}},"
                + "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Place order\"}}]}";
        String reply = "{\"passed\":true,\"steps\":["
                + "{\"n\":1,\"action\":\"Set the quantity to 3\",\"passed\":true,\"ms\":8,\"steps\":["
                + "{\"n\":1,\"action\":\"type spinner 'Qty' 3\",\"passed\":true,\"ms\":3,"
                + "\"step\":{\"action\":\"type\",\"target\":{\"role\":\"spinner\",\"name\":\"Qty\"},\"text\":\"3\"}},"
                + "{\"n\":2,\"action\":\"press Enter\",\"passed\":true,\"ms\":2,"
                + "\"step\":{\"action\":\"press\",\"target\":{\"role\":\"spinner\",\"name\":\"Qty\"},\"keys\":\"ENTER\"}}]},"
                + "{\"n\":2,\"action\":\"click button 'Place order'\",\"passed\":true,\"ms\":4}],"
                + "\"report\":\"Recorded test 'place-order' passed\"}";

        List<JsonNode> lines = written(flow, reply, null);

        // test, do, its two children, Place order, end
        assertThat(lines).hasSize(6);
        JsonNode parent = lines.get(1);
        assertThat(parent.path("verb").asText()).isEqualTo("do");
        assertThat(parent.has("parent")).isFalse();
        JsonNode qty = lines.get(2);
        assertThat(qty.path("verb").asText()).isEqualTo("type");
        assertThat(qty.path("value").asText()).isEqualTo("3");
        assertThat(qty.path("parent").asInt()).isEqualTo(parent.path("seq").asInt());
        assertThat(qty.path("t").asText()).isEqualTo(JournalLines.time(STARTED));
        JsonNode enter = lines.get(3);
        assertThat(enter.path("verb").asText()).isEqualTo("press");
        assertThat(enter.path("parent").asInt()).isEqualTo(parent.path("seq").asInt());
        assertThat(enter.path("t").asText()).isEqualTo(JournalLines.time(STARTED + 3));
        JsonNode place = lines.get(4);
        assertThat(place.path("target").asText()).contains("Place order");
        assertThat(place.has("parent")).isFalse();
        assertThat(place.path("t").asText()).isEqualTo(JournalLines.time(STARTED + 8));
    }

    private static final String SET_QTY = "{\"version\":4,\"name\":\"place-order\",\"start\":\"reset\","
            + "\"requires\":[\"order-desk\"],\"steps\":["
            + "{\"action\":\"do\",\"step\":\"order-desk:setQty\",\"params\":{\"qty\":\"3\"}}]}";

    @Test
    void aDoStepWithoutSaySaysThePluginsSentence() throws IOException {
        List<JsonNode> lines = written(SET_QTY, "{\"passed\":true,\"steps\":["
                + "{\"n\":1,\"action\":\"Set the quantity to 3\",\"passed\":true,\"ms\":8}]}", null);

        // the sentence the person sees in the palette
        assertThat(lines.get(1).path("text").asText()).isEqualTo("Set the quantity to 3");
    }

    @Test
    void aDoStepWithoutSayOrPluginSentenceSaysItsStep() throws IOException {
        List<JsonNode> lines = written(SET_QTY, "{\"passed\":true,\"steps\":["
                + "{\"n\":1,\"action\":\" \",\"passed\":true,\"ms\":8}]}", null);

        assertThat(lines.get(1).path("text").asText()).isEqualTo("Do order-desk:setQty");
    }

    @Test
    void aFailedRecordEndsTheLinesAndTheEndSaysFailed() throws IOException {
        String reply = "{\"passed\":false,\"test\":\"place-order\",\"ran\":2,\"total\":4,\"steps\":["
                + "{\"n\":1,\"action\":\"click button 'Add to cart'\",\"passed\":true,\"ms\":10},"
                + "{\"n\":2,\"say\":\"Add the second product\",\"action\":\"click button 'Add to cart'\",\"passed\":false,"
                + "\"ms\":10,\"reason\":\"NOT_ACTIONABLE\",\"message\":\"NOT_ACTIONABLE: button 'Add to cart' is disabled\"}],"
                + "\"report\":\"Recorded test 'place-order' failed at step 2\\n2. FAIL Add the second product\"}";

        List<JsonNode> lines = written(PLACE_ORDER, reply, "target/failsafe-reports/recorded-test-failures/place-order.txt");

        // test, section, two records, end: the records after the failed one never ran
        assertThat(lines).hasSize(5);
        JsonNode failed = lines.get(3);
        assertThat(failed.path("outcome").asText()).isEqualTo("failed");
        assertThat(failed.path("reason").asText()).isEqualTo("NOT_ACTIONABLE");
        assertThat(failed.path("message").asText()).isEqualTo("button 'Add to cart' is disabled");
        JsonNode end = lines.get(4);
        assertThat(end.path("kind").asText()).isEqualTo("end");
        assertThat(end.path("outcome").asText()).isEqualTo("failed");
        assertThat(end.path("message").asText()).isEqualTo("Recorded test 'place-order' failed at step 2");
        assertThat(end.path("evidence").asText()).isEqualTo("target/failsafe-reports/recorded-test-failures/place-order.txt");
    }

    @Test
    void aSecretLongerThanACapLeavesNoPartOfItInAnyLine() throws IOException {
        // one longer than a sentence's value (60), one longer than any text of a line (2,000); a say holds at
        // most 200 characters, so only the shorter one is in one
        String token = TestJournalTest.secretOf(70, 3);
        String key = TestJournalTest.secretOf(2100, 4);
        String flow = "{\"version\":4,\"name\":\"place-order\",\"start\":\"reset\",\"steps\":["
                + "{\"say\":\"Paste the token " + token + "\",\"steps\":["
                + "{\"action\":\"type\",\"target\":{\"role\":\"text\",\"name\":\"Token\"},\"text\":\"" + token + "\"},"
                + "{\"action\":\"type\",\"target\":{\"role\":\"text\",\"name\":\"Key\"},\"text\":\"" + key + "\","
                + "\"say\":\"Type the key for " + token + "\"}]},"
                + "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Place order\"}}]}";
        String reply = "{\"passed\":false,\"steps\":["
                + "{\"n\":1,\"action\":\"type " + token + "\",\"passed\":true,\"ms\":1},"
                + "{\"n\":2,\"action\":\"type " + key + "\",\"passed\":true,\"ms\":1},"
                + "{\"n\":3,\"action\":\"click\",\"passed\":false,\"ms\":1,"
                + "\"message\":\"Token " + token + " was refused\"}],"
                + "\"report\":\"Recorded test 'place-order' failed: Token " + token + " was refused\"}";
        TestJournal journal = journal();
        journal.secret(token);
        journal.secret(key);

        RecordedJournal.write(journal, flow(flow), JSON.readTree(reply), STARTED, null);

        List<JsonNode> lines = lines();
        StringBuilder all = new StringBuilder();
        for (JsonNode line : lines) all.append(line).append('\n');
        TestJournalTest.assertNoPartOf(token, all.toString(), "journal");
        TestJournalTest.assertNoPartOf(key, all.toString(), "journal");
        assertThat(lines.get(1).path("text").asText()).isEqualTo("Paste the token ***");
        assertThat(lines.get(2).path("text").asText()).isEqualTo("Type *** into Token");
        assertThat(lines.get(3).path("text").asText()).isEqualTo("Type the key for ***");
        assertThat(lines.get(4).path("message").asText()).isEqualTo("Token *** was refused");
    }
}
