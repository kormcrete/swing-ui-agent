package io.github.kormcrete.swingui.junit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientStepTest {

    @TempDir Path runs;
    private RunFolder folder;

    @AfterEach
    void forget() {
        RunFolder.forget(runs);
    }

    /** A Client over {@code v} whose steps go to a journal in a fresh run folder. */
    private Client journalled(RecordingVerbs v) {
        folder = RunFolder.of(runs, JournalSettings.defaults());
        return new Client(v, null, "UIT-test", runs.resolve("shots"), RunInfo.start(),
                folder.test("ClientStepTest#steps"), 1);
    }

    /** The journal's lines after its test line. */
    private List<JsonNode> journalLines() throws IOException {
        List<JsonNode> out = new ArrayList<JsonNode>();
        Path file = folder.dirOrNull().resolve("steps").resolve("ClientStepTest#steps.jsonl");
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) out.add(new ObjectMapper().readTree(line));
        return out.subList(1, out.size());
    }

    @Test
    void aStepIsASectionLine() throws IOException {
        RecordingVerbs verbs = new RecordingVerbs();
        Client ui = journalled(verbs);
        ui.step("Place the order");
        ui.step("Check the Total\nand the Qty");

        List<JsonNode> lines = journalLines();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).path("kind").asText()).isEqualTo("section");
        assertThat(lines.get(0).path("text").asText()).isEqualTo("Place the order");
        assertThat(lines.get(1).path("text").asText()).isEqualTo("Check the Total and the Qty");
        assertThat(verbs.calls).isEmpty();
    }

    @Test
    void aStepNeedsASentence() {
        Client ui = journalled(new RecordingVerbs());
        assertThatThrownBy(() -> ui.step("")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a step needs a sentence");
        assertThatThrownBy(() -> ui.step(" \n ")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a step needs a sentence");
        assertThatThrownBy(() -> ui.step(null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a step needs a sentence");
        assertThat(folder.dirOrNull()).isNull();
    }

    @Test
    void aStepOnAClientWithoutAJournalDoesNothing() throws IOException {
        RecordingVerbs verbs = new RecordingVerbs();
        Client ui = new Client(verbs, null, "UIT-test", runs);
        ui.step("Place the order");

        assertThat(verbs.calls).isEmpty();
        assertThat(ui.warnings()).isEmpty();
        try (Stream<Path> files = Files.list(runs)) {
            assertThat(files).isEmpty();
        }
        assertThatThrownBy(() -> ui.step("")).isInstanceOf(IllegalArgumentException.class);
    }
}
