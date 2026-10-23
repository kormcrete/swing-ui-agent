package io.github.kormcrete.swingui.junit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.journal.JournalSettings;
import io.github.kormcrete.swingui.journal.RunFolder;
import io.github.kormcrete.swingui.testing.RecordingVerbs;

import io.github.kormcrete.swingui.driver.model.Node;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TargetReadTest {

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
                folder.test("TargetReadTest#reads"), 1);
    }

    /** The journal's lines after its test line. */
    private List<JsonNode> journalLines() throws IOException {
        List<JsonNode> out = new ArrayList<JsonNode>();
        Path file = folder.dirOrNull().resolve("steps").resolve("TargetReadTest#reads.jsonl");
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) out.add(new ObjectMapper().readTree(line));
        return out.subList(1, out.size());
    }

    @Test
    void aReadIsJournalledWithItsValue() throws IOException {
        Node n = new Node();
        n.rowCount = 17;
        RecordingVerbs v = new RecordingVerbs();
        v.readAnswer = n;
        Client journalled = journalled(v);
        assertThat(journalled.table("Orders").rowCount()).isEqualTo(17);
        n.rowCount = 3;
        assertThat(journalled.tab("Orders").table("Lines").rowCount()).isEqualTo(3);

        List<JsonNode> lines = journalLines();
        assertThat(lines).hasSize(2);
        JsonNode read = lines.get(0);
        assertThat(read.path("kind").asText()).isEqualTo("read");
        assertThat(read.path("verb").asText()).isEqualTo("rowCount");
        assertThat(read.path("value").asInt()).isEqualTo(17);
        assertThat(read.path("target").asText()).isEqualTo("table 'Orders'");
        assertThat(read.path("outcome").asText()).isEqualTo("ok");
        assertThat(read.has("ms")).isTrue();
        assertThat(read.path("text").asText()).isEqualTo("Count the rows of Orders: 17");
        assertThat(lines.get(1).path("text").asText()).isEqualTo("Count the rows of Lines (tab Orders): 3");
    }

    @Test
    void existsJournalsOnceNotAlsoPresence() throws IOException {
        Client journalled = journalled(new RecordingVerbs());
        assertThat(journalled.button("Place order").exists()).isTrue();
        assertThat(journalled.button("Place order").presence()).isEqualTo(Target.Presence.ONE);

        List<JsonNode> lines = journalLines();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).path("verb").asText()).isEqualTo("exists");
        assertThat(lines.get(0).path("value").asBoolean()).isTrue();
        assertThat(lines.get(0).path("text").asText()).isEqualTo("Look whether Place order is there: true");
        assertThat(lines.get(1).path("verb").asText()).isEqualTo("presence");
        assertThat(lines.get(1).path("value").asText()).isEqualTo("ONE");
    }

    @Test
    void aFailedReadIsJournalledAndStillThrows() throws IOException {
        RecordingVerbs v = new RecordingVerbs();
        v.failNext = new DriverException(Reason.NOT_FOUND, "no table 'Orders'");
        Client journalled = journalled(v);
        assertThatThrownBy(() -> journalled.table("Orders").rowCount())
                .isInstanceOf(UiActionFailed.class).hasMessageContaining("no table 'Orders'");

        List<JsonNode> lines = journalLines();
        assertThat(lines).hasSize(1);
        JsonNode read = lines.get(0);
        assertThat(read.path("verb").asText()).isEqualTo("rowCount");
        assertThat(read.path("outcome").asText()).isEqualTo("failed");
        assertThat(read.path("message").asText()).isEqualTo("no table 'Orders'");
        assertThat(read.path("reason").asText()).isEqualTo("NOT_FOUND");
        assertThat(read.has("value")).isFalse();
        assertThat(read.path("text").asText()).isEqualTo("Count the rows of Orders");
    }

    @Test
    void rowsZipColumnsAndCells() {
        Node n = new Node();
        n.columns = Arrays.asList("Order no.", "Customer");
        n.rows = Arrays.asList(Arrays.asList("26-1", "A"), Arrays.asList("26-2", "B"));
        n.rowCount = 17;
        verbs.readAnswer = n;
        assertThat(ui.table("Orders").rows()).hasSize(2);
        assertThat(ui.table("Orders").rows().get(1)).containsEntry("Customer", "B");
        assertThat(ui.table("Orders").rowCount()).isEqualTo(17);
        assertThat(ui.table("Orders").columns()).containsExactly("Order no.", "Customer");
    }

    @Test
    void textIsTheNameAndValueIsTheValue() {
        Node n = new Node();
        n.name = "Description";
        n.value = "Widget 3";
        verbs.readAnswer = n;
        assertThat(ui.field("Description").text()).isEqualTo("Description");
        assertThat(ui.field("Description").value()).isEqualTo("Widget 3");
        assertThat(ui.field("Description").states()).isEmpty();
    }

    @Test
    void countAndTextsReadEveryMatch() {
        verbs.readAllAnswer = Arrays.asList(new Node("menuitem", "Profile"), new Node("menuitem", "Log out"));
        Target entries = ui.popup().role("menuitem", null);
        assertThat(entries.count()).isEqualTo(2);
        assertThat(entries.texts()).containsExactly("Profile", "Log out");
        assertThat(verbs.calls).contains("readAll " + entries.locator());
    }

    @Test
    void countRefusesAnNth() {
        assertThatThrownBy(() -> ui.button("OK").nth(1).count()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void selectionItemsAndExpansionComeFromTheNode() {
        Node tree = new Node("tree", "Grouping");
        tree.items = Arrays.asList("A", "A > B", "C");
        tree.expanded = Collections.singletonList("A");
        tree.selected = Collections.singletonList(1);
        verbs.readAnswer = tree;
        Target t = ui.tree("Grouping");
        assertThat(t.items()).containsExactly("A", "A > B", "C");
        assertThat(t.expandedItems()).containsExactly("A");
        assertThat(t.selectedRows()).containsExactly(1);
    }

    @Test
    void anUnselectedTableHasNoSelectedRows() {
        verbs.readAnswer = new Node("table", "Orders");
        assertThat(ui.table("Orders").selectedRows()).isEmpty();
    }
}
