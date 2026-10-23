package io.github.kormcrete.swingui.junit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kormcrete.swingui.journal.JournalSettings;
import io.github.kormcrete.swingui.journal.RunFolder;
import io.github.kormcrete.swingui.testing.RecordingVerbs;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientMenuItemsTest {
    private final RecordingVerbs verbs = new RecordingVerbs();
    private final Client ui = new Client(verbs, null, "UIT-test");

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
                folder.test("ClientMenuItemsTest#reads"), 1);
    }

    /** The journal's lines after its test line. */
    private List<JsonNode> journalLines() throws IOException {
        List<JsonNode> out = new ArrayList<JsonNode>();
        Path file = folder.dirOrNull().resolve("steps").resolve("ClientMenuItemsTest#reads.jsonl");
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) out.add(new ObjectMapper().readTree(line));
        return out.subList(1, out.size());
    }

    @Test
    void menuItemsAreJournalledWithTheMenuPath() throws IOException {
        RecordingVerbs v = new RecordingVerbs();
        v.menuItemsAnswer = Arrays.asList("PDF", "CSV");
        assertThat(journalled(v).menuItems("File", "Export")).containsExactly("PDF", "CSV");

        List<JsonNode> lines = journalLines();
        assertThat(lines).hasSize(1);
        JsonNode read = lines.get(0);
        assertThat(read.path("kind").asText()).isEqualTo("read");
        assertThat(read.path("verb").asText()).isEqualTo("menuItems");
        assertThat(read.has("target")).isFalse();
        assertThat(read.path("path").toString()).isEqualTo("[\"File\",\"Export\"]");
        assertThat(read.path("value").toString()).isEqualTo("[\"PDF\",\"CSV\"]");
        assertThat(read.path("outcome").asText()).isEqualTo("ok");
        assertThat(read.path("text").asText()).isEqualTo("Read the items of the menu File > Export: PDF, CSV");
    }

    @Test
    void readsThePathItNames() {
        verbs.menuItemsAnswer = Arrays.asList("Groups", "Users");
        assertThat(ui.menuItems("Administration", "System")).containsExactly("Groups", "Users");
        assertThat(verbs.calls).containsExactly("menuItems [Administration, System]");
    }

    @Test
    void aDriverFailureIsAUiActionFailed() {
        verbs.failNext = new DriverException(Reason.NOT_FOUND, "no menu 'X'");
        assertThatThrownBy(() -> ui.menuItems("X")).isInstanceOf(UiActionFailed.class).hasMessageContaining("no menu 'X'");
    }
}
