package io.github.kormcrete.swingui.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLogTest {

    @TempDir
    Path dir;

    @Test
    void writesItsLinesIntoANewFolder() throws IOException {
        Path file = dir.resolve("Kormcrete Order Desk").resolve("agent.log");
        AgentLog log = AgentLog.open(file);
        log.out("swing-ui-agent listening on http://127.0.0.1:8770/mcp");
        log.err("swing-ui-agent: a warning");
        assertThat(lines(file)).containsExactly(
                "swing-ui-agent listening on http://127.0.0.1:8770/mcp", "swing-ui-agent: a warning");
    }

    @Test
    void eachStartEmptiesTheFile() throws IOException {
        Path file = dir.resolve("agent.log");
        Files.write(file, "the last start".getBytes(StandardCharsets.UTF_8));
        AgentLog.open(file);
        assertThat(Files.size(file)).isZero();
    }

    @Test
    void anUnwritableFileLeavesTheConsoleOnly() throws IOException {
        Path notAFolder = Files.createFile(dir.resolve("a-file"));
        String err = SwingUiAgentTest.captureErr(() -> {
            AgentLog log = AgentLog.open(notAFolder.resolve("logs").resolve("agent.log"));
            log.out("still printed");
        });
        assertThat(err).contains("swing-ui-agent").contains("cannot write the log");
        // Files.notExists is false under a regular file (undeterminable), so doesNotExist() would fail
        assertThat(Files.exists(notAFolder.resolve("logs"))).isFalse();
    }

    private static java.util.List<String> lines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }
}
