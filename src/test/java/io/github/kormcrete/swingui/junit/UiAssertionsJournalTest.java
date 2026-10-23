package io.github.kormcrete.swingui.junit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kormcrete.swingui.driver.model.Node;
import io.github.kormcrete.swingui.journal.JournalSettings;
import io.github.kormcrete.swingui.journal.RunFolder;
import io.github.kormcrete.swingui.testing.RecordingVerbs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UiAssertionsJournalTest {

    @TempDir Path runs;
    private RunFolder folder;

    @AfterEach
    void forget() {
        RunFolder.forget(runs);
    }

    /** A Client over {@code v} whose reads go to a journal in a fresh run folder. */
    private Client journalled(RecordingVerbs v) {
        folder = RunFolder.of(runs, JournalSettings.defaults());
        return new Client(v, null, "UIT-test", runs.resolve("shots"), RunInfo.start(),
                folder.test("UiAssertionsJournalTest#checks"), 1);
    }

    /** The journal's lines after its test line. */
    private List<JsonNode> journalLines() throws IOException {
        List<JsonNode> out = new ArrayList<JsonNode>();
        Path file = folder.dirOrNull().resolve("steps").resolve("UiAssertionsJournalTest#checks.jsonl");
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) out.add(new ObjectMapper().readTree(line));
        return out.subList(1, out.size());
    }

    /** The Total label shows {@code €0.00} until the {@code ready}th read. */
    private static RecordingVerbs totalAfter(int ready, AtomicInteger reads) {
        RecordingVerbs v = new RecordingVerbs();
        v.reads = target -> new Node("label", reads.incrementAndGet() >= ready ? "€7.32" : "€0.00");
        return v;
    }

    @Test
    void eventuallyJournalsOneCheckNotEveryPoll() throws IOException {
        AtomicInteger reads = new AtomicInteger();
        Client ui = journalled(totalAfter(5, reads));
        String total = UiAssertions.eventually("the Total shows €7.32", Duration.ofSeconds(5),
                () -> ui.label("Total").text(), "€7.32"::equals);
        assertThat(total).isEqualTo("€7.32");
        assertThat(reads.get()).isEqualTo(5);

        List<JsonNode> lines = journalLines();
        assertThat(lines).hasSize(1);
        JsonNode check = lines.get(0);
        assertThat(check.path("kind").asText()).isEqualTo("check");
        assertThat(check.path("verb").asText()).isEqualTo("eventually");
        assertThat(check.path("target").asText()).isEqualTo("label 'Total'");
        assertThat(check.path("expected").path("what").asText()).isEqualTo("the Total shows €7.32");
        assertThat(check.path("value").asText()).isEqualTo("€7.32");
        assertThat(check.path("outcome").asText()).isEqualTo("ok");
        assertThat(check.has("ms")).isTrue();
        assertThat(check.path("text").asText()).isEqualTo("Check that the Total shows €7.32");

        ui.label("Total").text();   // after the eventually, a read journals again
        assertThat(journalLines()).hasSize(2);
        assertThat(journalLines().get(1).path("kind").asText()).isEqualTo("read");
    }

    @Test
    void aFailedEventuallyIsOneFailedCheck() throws IOException {
        AtomicInteger reads = new AtomicInteger();
        Client ui = journalled(totalAfter(Integer.MAX_VALUE, reads));
        assertThatThrownBy(() -> UiAssertions.eventually("the Total shows €7.32", Duration.ofMillis(300),
                () -> ui.label("Total").text(), "€7.32"::equals)).isInstanceOf(UiAssertionError.class);
        assertThat(reads.get()).isGreaterThan(1);

        List<JsonNode> lines = journalLines();
        assertThat(lines).hasSize(1);
        JsonNode check = lines.get(0);
        assertThat(check.path("kind").asText()).isEqualTo("check");
        assertThat(check.path("verb").asText()).isEqualTo("eventually");
        assertThat(check.path("value").asText()).isEqualTo("€0.00");
        assertThat(check.path("outcome").asText()).isEqualTo("failed");
        assertThat(check.path("message").asText()).startsWith("Expected the Total shows €7.32");
    }

    @Test
    void anEventuallyWithoutAClientReadWritesNothing() {
        journalled(new RecordingVerbs());
        AtomicInteger reads = new AtomicInteger();
        assertThat(UiAssertions.eventually("the count to reach 2", Duration.ofSeconds(5),
                reads::incrementAndGet, n -> n >= 2)).isEqualTo(2);
        assertThat(folder.dirOrNull()).isNull();
    }
}
