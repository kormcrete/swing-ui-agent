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

class FlowSelectionTest {

    @TempDir Path dir;

    private void flows(String... names) throws IOException {
        for (String n : names) Files.write(dir.resolve(n + ".json"), "{}".getBytes(StandardCharsets.UTF_8));
    }

    private void gate(String text) throws IOException {
        Files.write(dir.resolve("gate.txt"), text.getBytes(StandardCharsets.UTF_8));
    }

    private List<String> names(String requested) {
        List<String> out = new ArrayList<String>();
        for (Path p : FlowSelection.select(dir, requested)) out.add(p.getFileName().toString());
        return out;
    }

    @Test
    void aNamedFlowIsTheOnlyOne() throws IOException {
        flows("x", "y");
        assertThat(names("x")).containsExactly("x.json");
        assertThat(names("x.json")).containsExactly("x.json");
    }

    @Test
    void anUnknownNamedFlowListsTheOthers() throws IOException {
        flows("a", "b");
        assertThatThrownBy(() -> names("zz")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no flow 'zz' (named by -Dflow) in " + dir.toAbsolutePath())
                .hasMessageContaining("the flows there: a, b");
    }

    @Test
    void theGateDecidesWhichAndInWhatOrder() throws IOException {
        flows("a", "b", "c");
        gate("# the order\n\nc  # last in the folder, first here\n  a\n");
        assertThat(names(null)).containsExactly("c.json", "a.json");
        assertThat(names("  ")).containsExactly("c.json", "a.json");
    }

    @Test
    void anEmptyGateFails() throws IOException {
        flows("a");
        gate("# nothing\n\n");
        assertThatThrownBy(() -> names(null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gate.txt lists no flows: name at least one, or delete it to run all");
    }

    @Test
    void anUnknownGateEntryNamesItsLine() throws IOException {
        flows("a");
        gate("a\n\nnope\n");
        assertThatThrownBy(() -> names(null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gate.txt line 3").hasMessageContaining("nope");
    }

    @Test
    void aGateEntryTwiceFails() throws IOException {
        flows("a", "b");
        gate("a\nb\na.json\n");
        assertThatThrownBy(() -> names(null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gate.txt line 3").hasMessageContaining("listed twice");
    }

    @Test
    void withoutAGateAllRunSorted() throws IOException {
        flows("b", "a", "c");
        assertThat(names(null)).containsExactly("a.json", "b.json", "c.json");
    }

    @Test
    void noFolderFails() {
        Path missing = dir.resolve("nope");
        assertThatThrownBy(() -> FlowSelection.select(missing, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no flows folder " + missing.toAbsolutePath())
                .hasMessageContaining("export_flow").hasMessageContaining("-Dswingui.flowsDir");
    }

    @Test
    void aFolderWithoutFlowsFails() {
        assertThatThrownBy(() -> names(null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no flow files in " + dir.toAbsolutePath());
    }
}
