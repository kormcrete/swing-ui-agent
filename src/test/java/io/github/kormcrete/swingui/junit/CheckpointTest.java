package io.github.kormcrete.swingui.junit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kormcrete.swingui.driver.CheckpointShot;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.journal.JournalSettings;
import io.github.kormcrete.swingui.journal.RunFolder;
import io.github.kormcrete.swingui.journal.TestJournal;
import io.github.kormcrete.swingui.testing.RecordingApp;
import io.github.kormcrete.swingui.testing.RecordingVerbs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class CheckpointTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 7, 3, 2};

    @TempDir Path runs;
    private RecordingVerbs verbs;
    private RunFolder folder;
    private TestJournal journal;
    private Client ui;

    @BeforeEach
    void start() {
        verbs = new RecordingVerbs();
        verbs.checkpointAnswer = new CheckpointShot(PNG, 412, 180,
                Collections.singletonList(new CheckpointShot.Mask(12, 40, 90, 18, false)));
        folder = RunFolder.of(runs, JournalSettings.defaults());
        journal = folder.test("K#m");
        ui = client(verbs, journal);
    }

    @AfterEach
    void forget() {
        RunFolder.forget(runs);
        RunFolder.forgetWarning();
    }

    private static Client client(RecordingVerbs verbs, TestJournal journal) {
        return new Client(verbs, new RecordingApp(verbs.calls), "run-1", null, RunInfo.start(), journal, 1);
    }

    private Path checkpoints() {
        return folder.create().resolve("checkpoints").resolve("K#m");
    }

    private List<JsonNode> lines() throws IOException {
        List<JsonNode> out = new ArrayList<JsonNode>();
        Path file = folder.create().resolve("steps").resolve("K#m.jsonl");
        if (!Files.exists(file)) return out;
        for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) out.add(JSON.readTree(l));
        return out;
    }

    @Test
    void aCheckpointWritesPngSidecarAndALine() throws IOException {
        ui.tab("Orders").table("Lines").checkpoint("cart-totals", ui.label("Date"));

        assertThat(verbs.calls).containsExactly("checkpoint table 'Lines' [tab:Orders] masks=[label 'Date']");
        assertThat(Files.readAllBytes(checkpoints().resolve("cart-totals.png"))).isEqualTo(PNG);
        JsonNode side = JSON.readTree(checkpoints().resolve("cart-totals.json").toFile());
        List<String> fields = new ArrayList<String>();
        side.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("format", "name", "test", "target", "locator", "width", "height", "masks");
        assertThat(side.path("format").asInt()).isEqualTo(1);
        assertThat(side.path("name").asText()).isEqualTo("cart-totals");
        assertThat(side.path("test").asText()).isEqualTo("K#m");
        assertThat(side.path("target").asText()).isEqualTo("table 'Lines' [tab:Orders]");
        assertThat(side.path("locator").path("role").asText()).isEqualTo("table");
        assertThat(side.path("locator").path("scope").get(0).asText()).isEqualTo("tab:Orders");
        assertThat(side.path("width").asInt()).isEqualTo(412);
        assertThat(side.path("height").asInt()).isEqualTo(180);
        assertThat(side.path("masks")).hasSize(1);
        JsonNode mask = side.path("masks").get(0);
        List<String> maskFields = new ArrayList<String>();
        mask.fieldNames().forEachRemaining(maskFields::add);
        assertThat(maskFields).containsExactly("x", "y", "w", "h", "target", "locator", "clipped");
        assertThat(mask.path("x").asInt()).isEqualTo(12);
        assertThat(mask.path("y").asInt()).isEqualTo(40);
        assertThat(mask.path("w").asInt()).isEqualTo(90);
        assertThat(mask.path("h").asInt()).isEqualTo(18);
        assertThat(mask.path("target").asText()).isEqualTo("label 'Date'");
        assertThat(mask.path("locator").path("name").asText()).isEqualTo("Date");
        assertThat(mask.path("clipped").asBoolean()).isFalse();

        List<JsonNode> lines = lines();
        assertThat(lines).hasSize(2);
        JsonNode line = lines.get(1);
        assertThat(line.path("kind").asText()).isEqualTo("checkpoint");
        assertThat(line.path("name").asText()).isEqualTo("cart-totals");
        assertThat(line.path("png").asText()).isEqualTo("checkpoints/K#m/cart-totals.png");
        assertThat(line.path("json").asText()).isEqualTo("checkpoints/K#m/cart-totals.json");
        assertThat(line.path("outcome").asText()).isEqualTo("ok");
        assertThat(line.path("text").asText()).isEqualTo("Checkpoint cart-totals of Lines (tab Orders), masking Date");
    }

    @Test
    void aClientCheckpointIsTheMainWindow() throws IOException {
        ui.checkpoint("order-form");
        ui.checkpoint("order-form-masked", ui.field("Customer"));

        assertThat(verbs.calls).containsExactly("checkpoint in main masks=[]",
                "checkpoint in main masks=[text 'Customer']");
        JsonNode side = JSON.readTree(checkpoints().resolve("order-form.json").toFile());
        assertThat(side.path("target").asText()).isEqualTo("in main");
        assertThat(side.path("locator").path("window").asText()).isEqualTo("main");
        assertThat(lines().get(1).path("text").asText()).isEqualTo("Checkpoint order-form of the main window");
    }

    @Test
    void aNameUsedTwiceFailsBeforeAnyCall() {
        ui.checkpoint("cart-totals");
        verbs.calls.clear();

        Throwable twice = catchThrowable(() -> ui.table("Lines").checkpoint("cart-totals"));
        Throwable byCase = catchThrowable(() -> ui.table("Lines").checkpoint("Cart-Totals"));

        assertThat(twice).isInstanceOf(IllegalStateException.class).hasMessageContaining("used twice");
        assertThat(byCase).isInstanceOf(IllegalStateException.class).hasMessageContaining("differ only in case");
        assertThat(verbs.calls).isEmpty();
    }

    @Test
    void anUnsafeNameFailsLoudly() {
        for (String name : Arrays.asList("cart totals", "../up", "..", "a/b", "", null)) {
            Throwable t = catchThrowable(() -> ui.table("Lines").checkpoint(name));
            assertThat(t).as(String.valueOf(name)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("may use only A-Z a-z 0-9 . _ -");
        }
        assertThat(verbs.calls).isEmpty();
        assertThat(folder.dirOrNull()).isNull();
    }

    @Test
    void aMaskOfAnotherClientFailsBeforeAnyCall() {
        RecordingVerbs other = new RecordingVerbs();
        Client second = client(other, journal);

        Throwable t = catchThrowable(() -> ui.table("Lines").checkpoint("cart-totals", second.label("Date")));

        assertThat(t).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("label 'Date'")
                .hasMessageContaining("another client");
        assertThat(verbs.calls).isEmpty();
        assertThat(other.calls).isEmpty();
        // a client with another wait is the same client
        ui.table("Lines").checkpoint("cart-totals-2", ui.timeout(java.time.Duration.ofSeconds(2)).label("Date"));
        assertThat(verbs.calls).hasSize(1);
    }

    @Test
    void withTheJournalOffTheFilesAreStillWritten(@TempDir Path offRuns) throws IOException {
        RunFolder off = RunFolder.of(offRuns, k -> JournalSettings.JOURNAL.equals(k) ? Optional.of("off")
                : Optional.<String>empty());
        try {
            Client quiet = client(verbs, off.test("K#m"));

            quiet.table("Lines").checkpoint("cart-totals", quiet.label("Date"));

            Path dir = off.create().resolve("checkpoints").resolve("K#m");
            assertThat(Files.readAllBytes(dir.resolve("cart-totals.png"))).isEqualTo(PNG);
            assertThat(JSON.readTree(dir.resolve("cart-totals.json").toFile()).path("masks")).hasSize(1);
            assertThat(off.create().resolve("steps")).doesNotExist();
            assertThat(off.create().resolve("run.json")).exists();
        } finally {
            RunFolder.forget(offRuns);
        }
    }

    @Test
    void aCheckpointThatFailsFailsTheTestAndIsJournalled() throws IOException {
        verbs.failNext = new DriverException(Reason.NOT_ACTIONABLE,
                "table 'Lines' is scrolled out of view: scroll it into view first");

        Throwable t = catchThrowable(() -> ui.table("Lines").checkpoint("cart-totals", ui.label("Date")));

        assertThat(t).isInstanceOf(UiActionFailed.class).hasMessageContaining("checkpoint cart-totals")
                .hasMessageContaining("scroll it into view");
        assertThat(((UiActionFailed) t).reason()).isEqualTo(Reason.NOT_ACTIONABLE);
        assertThat(checkpoints().resolve("cart-totals.png")).doesNotExist();
        JsonNode line = lines().get(1);
        assertThat(line.path("kind").asText()).isEqualTo("checkpoint");
        assertThat(line.path("outcome").asText()).isEqualTo("failed");
        assertThat(line.path("message").asText())
                .isEqualTo("table 'Lines' is scrolled out of view: scroll it into view first");
        assertThat(line.has("png")).isFalse();
        assertThat(line.has("json")).isFalse();
    }

    @Test
    void aCheckpointThatCannotBeWrittenFailsTheTestAndIsJournalled() throws IOException {
        Files.createDirectories(checkpoints());
        Files.createDirectories(checkpoints().resolve("cart-totals.png"));   // a folder where the picture goes

        Throwable t = catchThrowable(() -> ui.table("Lines").checkpoint("cart-totals"));

        assertThat(t).isInstanceOf(IllegalStateException.class).hasMessageStartingWith("could not write checkpoint ")
                .hasMessageContaining("cart-totals.png");
        JsonNode line = lines().get(1);
        assertThat(line.path("outcome").asText()).isEqualTo("failed");
        assertThat(line.path("message").asText()).startsWith("could not write checkpoint ");
    }

    @Test
    void aCheckpointWithoutAUiTestClientFails() {
        Client plain = new Client(verbs, new RecordingApp(verbs.calls), "run-1");

        Throwable t = catchThrowable(() -> plain.table("Lines").checkpoint("cart-totals"));
        Throwable main = catchThrowable(() -> plain.checkpoint("cart-totals"));

        assertThat(t).isInstanceOf(IllegalStateException.class)
                .hasMessage("a checkpoint needs the Client of a @UiTest, written into the test's run folder: "
                        + "this Client is not one, or the run journal could not be set up (see the warning above)");
        assertThat(main).isInstanceOf(IllegalStateException.class).hasMessage(t.getMessage());
        assertThat(verbs.calls).isEmpty();
    }

    @Test
    void aSecretAsAMaskTargetsNameIsMaskedInTheSidecarAndTheLine() throws IOException {
        journal.secret("Discount-4471-kq");

        ui.checkpoint("order-form", ui.field("Discount-4471-kq"));

        String side = new String(Files.readAllBytes(checkpoints().resolve("order-form.json")), StandardCharsets.UTF_8);
        assertThat(side).doesNotContain("Discount-4471-kq");
        JsonNode mask = JSON.readTree(side).path("masks").get(0);
        assertThat(mask.path("target").asText()).isEqualTo("text '***'");
        assertThat(mask.path("locator").path("name").asText()).isEqualTo("***");
        JsonNode line = lines().get(1);
        assertThat(line.toString()).doesNotContain("Discount-4471-kq");
        assertThat(line.path("text").asText()).isEqualTo("Checkpoint order-form of the main window, masking ***");
    }
}
