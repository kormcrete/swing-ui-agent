package io.github.kormcrete.swingui.journal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kormcrete.swingui.client.StepCallVerbs;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.StepCall;
import io.github.kormcrete.swingui.driver.model.Locator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class TestJournalTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path runs;

    @AfterEach
    void forget() {
        RunFolder.forget(runs);
    }

    private RunFolder folder() {
        return RunFolder.of(runs, JournalSettings.defaults());
    }

    private static ObjectNode client(String alias) {
        return JsonNodeFactory.instance.objectNode().put("alias", alias);
    }

    private static ObjectNode click(String button) {
        ObjectNode step = JsonNodeFactory.instance.objectNode().put("action", "click");
        step.putObject("target").put("role", "button").put("name", button);
        return JournalLines.step(step, true, null, null, 5, System.currentTimeMillis());
    }

    private static List<JsonNode> lines(Path file) throws IOException {
        List<JsonNode> out = new ArrayList<JsonNode>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) out.add(JSON.readTree(line));
        return out;
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    @Test
    void theHeaderComesWithTheFirstLineAndSeqCountsEveryLine() throws IOException {
        RunFolder folder = folder();
        TestJournal journal = folder.test("K#m");
        journal.clients(Collections.singletonList(client("main")));

        assertThat(runs.resolve(RunIds.JVM)).doesNotExist();
        assertThat(journal.append(click("Add to cart"), 1)).isEqualTo(2);
        journal.end("passed", null, null);

        Path run = folder.create();
        assertThat(run.getFileName().toString()).isEqualTo(RunIds.JVM);
        List<JsonNode> lines = lines(run.resolve("steps").resolve("K#m.jsonl"));
        assertThat(lines).hasSize(3);
        JsonNode header = lines.get(0);
        assertThat(header.path("seq").asInt()).isEqualTo(1);
        assertThat(header.path("kind").asText()).isEqualTo("test");
        assertThat(header.path("format").asInt()).isEqualTo(1);
        assertThat(header.path("test").asText()).isEqualTo("K#m");
        assertThat(header.path("runId").asText()).isEqualTo(RunIds.JVM);
        assertThat(header.path("clients")).isEqualTo(JSON.readTree("[{\"alias\":\"main\"}]"));
        assertThat(header.path("t").asText()).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z");
        assertThat(lines.get(1).path("seq").asInt()).isEqualTo(2);
        assertThat(lines.get(1).path("kind").asText()).isEqualTo("action");
        assertThat(lines.get(1).has("client")).isFalse();
        assertThat(lines.get(2).path("seq").asInt()).isEqualTo(3);
        assertThat(lines.get(2).path("kind").asText()).isEqualTo("end");
        assertThat(lines.get(2).path("outcome").asText()).isEqualTo("passed");
        assertThat(lines.get(2).path("ms").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(lines.get(2).has("message")).isFalse();
        assertThat(lines.get(2).has("evidence")).isFalse();
    }

    @Test
    void anEndCarriesTheFirstLineOfItsMessageAndTheEvidence() throws IOException {
        TestJournal journal = folder().test("K#m");

        journal.end("failed", "expected text '€7.32'\nbut was '€0.00'", "target/ui-evidence/K#m");

        JsonNode end = lines(folder().create().resolve("steps/K#m.jsonl")).get(1);
        assertThat(end.path("outcome").asText()).isEqualTo("failed");
        assertThat(end.path("message").asText()).isEqualTo("expected text '€7.32'");
        assertThat(end.path("evidence").asText()).isEqualTo("target/ui-evidence/K#m");
    }

    @Test
    void aJournalWithNoLinesMakesNoFile() {
        TestJournal journal = folder().test("K#m");

        journal.discard();

        assertThat(journal.stepsOn()).isFalse();
        assertThat(journal.append(click("Add to cart"), 1)).isZero();
        journal.section("Fill the cart");
        journal.end("passed", null, null);
        assertThat(runs.resolve(RunIds.JVM).resolve("steps")).doesNotExist();
    }

    @Test
    void theSameTestTwiceInOneRunGetsASecondFile() throws IOException {
        RunFolder folder = folder();
        TestJournal first = folder.test("X#y");
        TestJournal second = folder.test("X#y");

        first.append(click("Add to cart"), 1);
        second.append(click("Place order"), 1);

        Path steps = folder.create().resolve("steps");
        assertThat(names(steps)).containsExactly("X#y.jsonl", "X#y~2.jsonl");
        assertThat(lines(steps.resolve("X#y~2.jsonl")).get(0).path("test").asText()).isEqualTo("X#y");
        assertThat(lines(steps.resolve("X#y~2.jsonl")).get(1).path("target").asText()).isEqualTo("button 'Place order'");
        assertThat(lines(steps.resolve("X#y.jsonl"))).hasSize(2);
        assertThat(second.checkpointDir().getFileName().toString()).isEqualTo("X#y~2");
        assertThat(first.checkpointDir().getFileName().toString()).isEqualTo("X#y");
    }

    @Test
    void aTestNameWithCharactersWindowsRefusesIsSafeOnDisk() throws IOException {
        RunFolder folder = folder();
        TestJournal journal = folder.test("K#m[a:b]");

        journal.append(click("Add to cart"), 1);

        Path file = folder.create().resolve("steps").resolve("K#m[a_b].jsonl");
        assertThat(lines(file).get(0).path("test").asText()).isEqualTo("K#m[a:b]");
        assertThat(RunIds.fileName("a<b>c:d\"e/f\\g|h?i*j\u0001k\u007fl")).isEqualTo("a_b_c_d_e_f_g_h_i_j_k_l");
        assertThat(RunIds.fileName("Outer$Inner#place order[1]")).isEqualTo("Outer$Inner#place order[1]");
    }

    @Test
    void withSeveralClientsEachLineSaysWhichClient() throws IOException {
        RunFolder folder = folder();
        TestJournal two = folder.test("K#two");
        two.clients(Arrays.asList(client("clerk"), client("manager")));
        TestJournal one = folder.test("K#one");
        one.clients(Collections.singletonList(client("main")));

        two.append(click("Add to cart"), 2);
        two.section("Approve");
        one.append(click("Add to cart"), 1);

        Path steps = folder.create().resolve("steps");
        List<JsonNode> twoLines = lines(steps.resolve("K#two.jsonl"));
        assertThat(twoLines.get(0).path("clients")).hasSize(2);
        assertThat(twoLines.get(1).path("client").asInt()).isEqualTo(2);
        assertThat(twoLines.get(2).has("client")).isFalse();
        assertThat(lines(steps.resolve("K#one.jsonl")).get(1).has("client")).isFalse();
    }

    @Test
    void aSecretIsMaskedInEveryField() throws IOException {
        RunFolder folder = folder();
        TestJournal journal = folder.test("K#m");
        StepCallVerbs.Sink sink = journal.sink(1);
        sink.secret("pa55word");

        StepCall call = new StepCall("{\"action\":\"type\",\"target\":{\"role\":\"text\",\"name\":\"Note\"},\"text\":\"pa55word\"}",
                "type");
        call.passed = false;
        call.message = "NOT_ACTIONABLE: Note refused 'pa55word'";
        call.reason = Reason.NOT_ACTIONABLE;
        sink.ended(call, System.currentTimeMillis());
        journal.section("Log in with pa55word");
        journal.read(1, "text", Locator.of("label", "Note"), "pa55word", System.currentTimeMillis(), null);
        journal.read(1, "text", Locator.of("label", "Note"), null, System.currentTimeMillis(),
                new DriverException(Reason.EXPECTATION, "Note shows pa55word"));
        journal.end("failed", "Note shows pa55word", null);

        Path file = folder.create().resolve("steps/K#m.jsonl");
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertThat(text).doesNotContain("pa55word");
        List<JsonNode> lines = lines(file);
        assertThat(lines.get(1).path("value").asText()).isEqualTo("***");
        assertThat(lines.get(1).path("step").path("text").asText()).isEqualTo("***");
        assertThat(lines.get(1).path("message").asText()).isEqualTo("Note refused '***'");
        assertThat(lines.get(1).path("reason").asText()).isEqualTo("NOT_ACTIONABLE");
        assertThat(lines.get(1).path("text").asText()).isEqualTo("Type *** into Note");
        assertThat(lines.get(2).path("text").asText()).isEqualTo("Log in with ***");
        assertThat(lines.get(3).path("value").asText()).isEqualTo("***");
        assertThat(lines.get(4).path("message").asText()).isEqualTo("Note shows ***");
        assertThat(lines.get(4).path("reason").asText()).isEqualTo("EXPECTATION");
        assertThat(lines.get(5).path("message").asText()).isEqualTo("Note shows ***");
        assertThat(journal.mask("pa55word and pa55word")).isEqualTo("*** and ***");
    }

    @Test
    void aTypedPasswordIsMaskedInEveryLaterLine() throws IOException {
        RunFolder folder = folder();
        TestJournal journal = folder.test("K#m");
        StepCallVerbs.Sink sink = journal.sink(1);

        StepCall typed = new StepCall(
                "{\"action\":\"type\",\"target\":{\"role\":\"password\",\"name\":\"Password\"},\"text\":\"pa55word\"}", "type");
        typed.passed = true;
        sink.ended(typed, System.currentTimeMillis());
        StepCall check = new StepCall("{\"action\":\"expect\",\"target\":{\"role\":\"label\",\"name\":\"Total\"},\"text\":\"€7.32\"}",
                "expect");
        check.message = "EXPECTATION: Total shows 'pa55word'";
        check.reason = Reason.EXPECTATION;
        sink.ended(check, System.currentTimeMillis());
        journal.end("failed", "expect label 'Total': EXPECTATION: Total shows 'pa55word'", null);

        Path file = folder.create().resolve("steps/K#m.jsonl");
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).doesNotContain("pa55word");
        List<JsonNode> lines = lines(file);
        assertThat(lines.get(1).path("text").asText()).isEqualTo("Type *** into Password");
        assertThat(lines.get(2).path("message").asText()).isEqualTo("Total shows '***'");
        assertThat(lines.get(3).path("message").asText()).isEqualTo("expect label 'Total': EXPECTATION: Total shows '***'");
    }

    @Test
    void anEndMessageLosesItsReasonPrefix() throws IOException {
        TestJournal journal = folder().test("K#m");

        journal.end("failed", "NOT_FOUND: no button 'Place order'\n  nearest: button 'Place order…'", null);

        JsonNode end = lines(folder().create().resolve("steps/K#m.jsonl")).get(1);
        assertThat(end.path("message").asText()).isEqualTo("no button 'Place order'");
    }

    @Test
    void withTheJournalOffNothingIsWrittenButCheckpointNamesStillWork() {
        JournalSettings off = JournalSettings.from(key -> JournalSettings.JOURNAL.equals(key) ? Optional.of("OFF") : Optional.empty());
        RunFolder folder = RunFolder.of(runs, off);
        TestJournal journal = folder.test("K#m");

        assertThat(off.journal).isFalse();
        assertThat(folder.stepsOn()).isFalse();
        assertThat(journal.stepsOn()).isFalse();
        assertThat(journal.append(click("Add to cart"), 1)).isZero();
        journal.claimCheckpoint("cart-totals");
        Path dir = journal.checkpointDir();

        assertThat(dir).isDirectory();
        assertThat(dir).isEqualTo(folder.create().resolve("checkpoints").resolve("K#m"));
        assertThat(journal.relative(dir.resolve("cart-totals.png"))).isEqualTo("checkpoints/K#m/cart-totals.png");
        assertThat(folder.create().resolve("steps")).doesNotExist();
    }

    @Test
    void aBrokenFolderWarnsOnceAndWritesNothingMore() {
        RunFolder.forgetWarning();
        RunFolder folder = folder();
        TestJournal journal = folder.test("K#m");
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
        try {
            folder.broken(new IOException("disk full"));
            folder.broken(new IOException("disk full again"));
        } finally {
            System.setErr(err);
        }

        assertThat(folder.stepsOn()).isFalse();
        assertThat(journal.append(click("Add to cart"), 1)).isZero();
        assertThat(new String(captured.toByteArray(), StandardCharsets.UTF_8).trim())
                .isEqualTo("[ui-warning] run journal off for this run: java.io.IOException: disk full");
        journal.claimCheckpoint("cart-totals");
        assertThat(journal.checkpointDir()).isDirectory();
    }

    @Test
    void anExistingRunFolderGivesTheRunTheNextSuffix() throws IOException {
        Files.createDirectories(runs.resolve(RunIds.JVM));

        assertThat(folder().create()).isEqualTo(runs.resolve(RunIds.JVM + "-2"));
        assertThat(folder().create()).isEqualTo(runs.resolve(RunIds.JVM + "-2"));
    }

    @Test
    void aFileInTheWayWarnsOnceAndTheJournalGoesQuiet() throws IOException {
        RunFolder.forgetWarning();
        RunFolder folder = folder();
        Files.write(folder.create().resolve("steps"), new byte[0]);
        TestJournal journal = folder.test("K#m");
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
        try {
            assertThat(journal.append(click("Add to cart"), 1)).isZero();
            assertThat(journal.append(click("Place order"), 1)).isZero();
            assertThat(folder.test("K#n").append(click("Add to cart"), 1)).isZero();
        } finally {
            System.setErr(err);
        }

        assertThat(folder.stepsOn()).isFalse();
        assertThat(journal.stepsOn()).isFalse();
        String warnings = new String(captured.toByteArray(), StandardCharsets.UTF_8);
        assertThat(warnings).startsWith("[ui-warning] run journal off for this run: ");
        assertThat(warnings.split("\\[ui-warning]", -1)).hasSize(2);
    }

    @Test
    void aChildJournalIsARecordedTestOfItsClass() throws IOException {
        RunFolder folder = folder();
        TestJournal parent = folder.test("K#recordedTests");
        parent.clients(Arrays.asList(client("clerk"), client("manager")));
        parent.secret("pa55word");

        TestJournal child = parent.child("recorded[place-order]");
        parent.discard();
        child.append(click("pa55word"), 2);

        assertThat(child.test()).isEqualTo("K#recorded[place-order]");
        Path steps = folder.create().resolve("steps");
        assertThat(names(steps)).containsExactly("K#recorded[place-order].jsonl");
        List<JsonNode> lines = lines(steps.resolve("K#recorded[place-order].jsonl"));
        assertThat(lines.get(0).path("test").asText()).isEqualTo("K#recorded[place-order]");
        assertThat(lines.get(0).path("clients")).hasSize(2);
        assertThat(lines.get(1).path("client").asInt()).isEqualTo(2);
        assertThat(lines.get(1).path("target").asText()).isEqualTo("button '***'");
    }

    @Test
    void aCheckpointNameIsSafeAndUsedOncePerTest() {
        TestJournal journal = folder().test("K#m");

        Throwable unsafe = catchThrowable(() -> journal.claimCheckpoint("cart totals"));
        journal.claimCheckpoint("cart-totals");
        Throwable twice = catchThrowable(() -> journal.claimCheckpoint("cart-totals"));

        assertThat(unsafe).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'cart totals'").hasMessageContaining("A-Z a-z 0-9 . _ -");
        assertThat(twice).isInstanceOf(IllegalStateException.class)
                .hasMessage("checkpoint name 'cart-totals' is used twice in K#m");
        assertThat(catchThrowable(() -> journal.claimCheckpoint("Cart-Totals"))).isInstanceOf(IllegalStateException.class)
                .hasMessage("checkpoint names 'cart-totals' and 'Cart-Totals' in K#m differ only in case,"
                        + " which a disk may ignore: give them names that differ in letters");
        assertThat(catchThrowable(() -> journal.claimCheckpoint(".."))).isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> journal.claimCheckpoint(null))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runIdsAndSafeNames() {
        assertThat(RunIds.JVM).matches("UIT-\\d{8}-\\d{6}-[0-9a-f]{4}");
        assertThat(RunIds.next()).matches("UIT-\\d{8}-\\d{6}-[0-9a-f]{4}");
        assertThat(RunIds.isSafeName("nightly-42.b_1")).isTrue();
        assertThat(RunIds.isSafeName(".")).isFalse();
        assertThat(RunIds.isSafeName("..")).isFalse();
        assertThat(RunIds.isSafeName("")).isFalse();
        assertThat(RunIds.isSafeName("a/b")).isFalse();
        assertThat(RunIds.isSafeName(null)).isFalse();
    }

    @Test
    void settingsComeFromTheParametersWithWarningsForBadOnes() {
        Map<String, String> given = new HashMap<String, String>();
        given.put(JournalSettings.JOURNAL, "on");
        given.put(JournalSettings.KEEP, "0");
        given.put(JournalSettings.RUN_ID, "nightly-42");
        given.put(JournalSettings.APP_VERSION, "2.4.1");
        JournalSettings s = JournalSettings.from(key -> Optional.ofNullable(given.get(key)));
        assertThat(s.journal).isTrue();
        assertThat(s.keep).isZero();
        assertThat(s.runId).isEqualTo("nightly-42");
        assertThat(s.appVersion).isEqualTo("2.4.1");

        JournalSettings d = JournalSettings.defaults();
        assertThat(d.journal).isTrue();
        assertThat(d.keep).isEqualTo(10);
        assertThat(d.runId).isNull();
        assertThat(d.appVersion).isNull();

        given.put(JournalSettings.KEEP, "ten");
        given.put(JournalSettings.RUN_ID, "../up");
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
        JournalSettings bad;
        try {
            bad = JournalSettings.from(key -> Optional.ofNullable(given.get(key)));
        } finally {
            System.setErr(err);
        }
        assertThat(bad.keep).isEqualTo(10);
        assertThat(bad.runId).isNull();
        assertThat(new String(captured.toByteArray(), StandardCharsets.UTF_8)).isEqualTo(
                "[ui-warning] swingui.journal.keep 'ten' is not a whole number of runs of 0 or more; keeping 10" + System.lineSeparator()
                + "[ui-warning] swingui.runId '../up' may use only A-Z a-z 0-9 . _ - (not . or ..); using the run's own id"
                + System.lineSeparator());
    }

    @Test
    void theRunFolderUsesTheGivenRunId() {
        JournalSettings s = JournalSettings.from(key -> JournalSettings.RUN_ID.equals(key) ? Optional.of("nightly-42") : Optional.empty());
        RunFolder folder = RunFolder.of(runs, s);

        assertThat(RunFolder.of(runs, JournalSettings.defaults())).isSameAs(folder);
        assertThat(folder.create()).isEqualTo(runs.resolve("nightly-42"));
        assertThat(folder.create()).isDirectory();
    }

    /** A secret of {@code length} letters and digits that no line holds by chance. */
    static String secretOf(int length, long seed) {
        java.util.Random random = new java.util.Random(seed);
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(chars.charAt(random.nextInt(chars.length())));
        return sb.toString();
    }

    /** Fails when {@code text} holds any 8 characters in a row of {@code secret}. */
    static void assertNoPartOf(String secret, String text, String where) {
        for (int i = 0; i + 8 <= secret.length(); i++) {
            assertThat(text).as(where + ": a part of the secret at " + i).doesNotContain(secret.substring(i, i + 8));
        }
    }

    @Test
    void aSecretLongerThanACapLeavesNoPartOfItInAnyLine() throws IOException {
        // one longer than a sentence's value (60), one longer than any text of a line (2,000)
        String token = secretOf(70, 1);
        String key = secretOf(2100, 2);
        RunFolder folder = folder();
        TestJournal journal = folder.test("K#m");
        StepCallVerbs.Sink sink = journal.sink(1);
        sink.secret(token);
        sink.secret(key);
        Locator field = Locator.of("text", "Token " + token);

        for (String secret : Arrays.asList(token, key)) {
            StepCall typed = new StepCall("{\"action\":\"type\",\"target\":{\"role\":\"text\",\"name\":\"Token\"},"
                    + "\"text\":\"" + secret + "\"}", "type");
            typed.passed = true;
            sink.ended(typed, System.currentTimeMillis());
            StepCall check = new StepCall("{\"action\":\"expect\",\"target\":{\"role\":\"text\",\"name\":\"Token\"},"
                    + "\"text\":\"" + secret + "\"}", "expect");
            check.passed = false;
            check.message = "EXPECTATION: Token shows '" + secret + "'";
            check.reason = Reason.EXPECTATION;
            sink.ended(check, System.currentTimeMillis());
            journal.section("Paste the token " + secret);
            journal.read(1, "text", field, secret, System.currentTimeMillis(), null);
            journal.read(1, "rows", field, Arrays.asList(secret, Collections.singletonMap("Token", secret)),
                    System.currentTimeMillis(), null);
            journal.readMenu(1, Arrays.asList("File", secret), Arrays.asList(secret), System.currentTimeMillis(), null);
            journal.eventually(1, "Token shows " + secret, field, secret, System.currentTimeMillis(), null);
            journal.checkpoint(1, "token", field, Collections.singletonList(Locator.of("label", secret)),
                    null, null, false, "could not write " + secret, 3, System.currentTimeMillis());
        }
        journal.end("failed", "Token shows " + token, null);

        Path file = folder.create().resolve("steps/K#m.jsonl");
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertNoPartOf(token, text, "journal");
        assertNoPartOf(key, text, "journal");
        List<JsonNode> lines = lines(file);
        assertThat(lines.get(1).path("text").asText()).isEqualTo("Type *** into Token");
        assertThat(lines.get(1).path("value").asText()).isEqualTo("***");
        assertThat(lines.get(2).path("text").asText()).isEqualTo("Check that Token shows ***");
        assertThat(lines.get(4).path("text").asText()).isEqualTo("Read the text of Token ***: ***");
        assertThat(lines.get(8).path("text").asText()).isEqualTo("Checkpoint token of Token ***, masking ***");
    }

    @Test
    void aSectionsTextIsCappedLikeEveryOtherText() throws IOException {
        RunFolder folder = folder();
        TestJournal journal = folder.test("K#m");
        StringBuilder sentence = new StringBuilder("Place the order");
        while (sentence.length() < 2500) sentence.append(" and check it");

        journal.section(sentence.toString());

        JsonNode line = lines(folder.create().resolve("steps/K#m.jsonl")).get(1);
        assertThat(line.path("kind").asText()).isEqualTo("section");
        assertThat(line.path("text").asText()).isEqualTo(sentence.substring(0, JournalLines.VALUE_CAP));
    }

    @Test
    void aSecondEndIsIgnored() throws IOException {
        RunFolder folder = folder();
        TestJournal journal = folder.test("K#m");
        journal.append(click("Add to cart"), 1);

        journal.end("failed", "expected text '€7.32'", null);
        journal.end("passed", null, null);

        List<JsonNode> lines = lines(folder.create().resolve("steps/K#m.jsonl"));
        assertThat(lines).hasSize(3);
        assertThat(lines.get(2).path("kind").asText()).isEqualTo("end");
        assertThat(lines.get(2).path("outcome").asText()).isEqualTo("failed");
    }

    @Test
    void aCheckpointFileNotWrittenNeverSaysNull() {
        CheckpointFiles.NotWritten bare = new CheckpointFiles.NotWritten("cart-totals.png", new IOException());
        CheckpointFiles.NotWritten said =
                new CheckpointFiles.NotWritten("cart-totals.png", new IOException("disk full"));

        assertThat(bare.getMessage()).isEqualTo("cart-totals.png: java.io.IOException");
        assertThat(said.getMessage()).isEqualTo("cart-totals.png: disk full");
    }
}
