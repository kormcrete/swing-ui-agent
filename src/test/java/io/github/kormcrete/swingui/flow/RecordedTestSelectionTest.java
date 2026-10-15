package io.github.kormcrete.swingui.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordedTestSelectionTest {

    @TempDir Path dir;

    private void writeTests(String... names) throws IOException {
        for (String n : names) Files.write(dir.resolve(n + ".json"), "{}".getBytes(StandardCharsets.UTF_8));
    }

    private void gate(String text) throws IOException {
        Files.write(dir.resolve("gate.txt"), text.getBytes(StandardCharsets.UTF_8));
    }

    private List<String> names(String requested) {
        List<String> out = new ArrayList<String>();
        for (Path p : RecordedTestSelection.select(dir, requested)) out.add(p.getFileName().toString());
        return out;
    }

    @Test
    void aNamedRecordedTestIsTheOnlyOne() throws IOException {
        writeTests("x", "y");
        assertThat(names("x")).containsExactly("x.json");
        assertThat(names("x.json")).containsExactly("x.json");
    }

    @Test
    void anUnknownNamedRecordedTestListsTheOthers() throws IOException {
        writeTests("a", "b");
        assertThatThrownBy(() -> names("zz")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no recorded test 'zz' (named by -Drecorded) in " + dir.toAbsolutePath())
                .hasMessageContaining("the recorded tests there: a, b");
    }

    @Test
    void theGateDecidesWhichAndInWhatOrder() throws IOException {
        writeTests("a", "b", "c");
        gate("# the order\n\nc  # last in the folder, first here\n  a\n");
        assertThat(names(null)).containsExactly("c.json", "a.json");
        assertThat(names("  ")).containsExactly("c.json", "a.json");
    }

    @Test
    void anEmptyGateFails() throws IOException {
        writeTests("a");
        gate("# nothing\n\n");
        assertThatThrownBy(() -> names(null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gate.txt lists no recorded tests: name at least one, or delete it to run all");
    }

    @Test
    void anUnknownGateEntryNamesItsLine() throws IOException {
        writeTests("a");
        gate("a\n\nnope\n");
        assertThatThrownBy(() -> names(null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gate.txt line 3").hasMessageContaining("nope");
    }

    @Test
    void aGateEntryTwiceFails() throws IOException {
        writeTests("a", "b");
        gate("a\nb\na.json\n");
        assertThatThrownBy(() -> names(null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gate.txt line 3").hasMessageContaining("listed twice");
    }

    @Test
    void withoutAGateAllRunSorted() throws IOException {
        writeTests("b", "a", "c");
        assertThat(names(null)).containsExactly("a.json", "b.json", "c.json");
    }

    @Test
    void noFolderFails() {
        Path missing = dir.resolve("nope");
        assertThatThrownBy(() -> RecordedTestSelection.select(missing, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no recorded tests folder " + missing.toAbsolutePath())
                .hasMessageContaining("export_recorded_test").hasMessageContaining("-Dswingui.recordedDir");
    }

    @Test
    void aFolderWithoutRecordedTestsFails() {
        assertThatThrownBy(() -> names(null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no recorded test files in " + dir.toAbsolutePath());
    }
}
