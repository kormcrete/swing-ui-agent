package io.github.kormcrete.swingui.spi;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plugin contract names java.*, spi and driver only - never flow, mcp, agent or Jackson. One exception:
 * {@code junit.Client}, which a {@link DomainStep.Body} runs with, so that one step class written against the Client
 * a UI test uses runs in the app (the agent) and in a Java test unchanged. Client imports no JUnit.
 */
class SpiImportRuleTest {

    @Test
    void spiImportsOnlyJavaSpiDriverAndTheClient() throws IOException {
        Path dir = Paths.get("src/main/java/io/github/kormcrete/swingui/spi");
        List<String> bad = new ArrayList<String>();
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
        assertThat(files).hasSizeGreaterThanOrEqualTo(9);
        for (Path f : files) {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (!t.startsWith("import ")) continue;
                String name = t.replace("import static ", "").replace("import ", "");
                if (name.startsWith("java.") || name.startsWith("io.github.kormcrete.swingui.spi.")
                        || name.startsWith("io.github.kormcrete.swingui.driver.")
                        || name.equals("io.github.kormcrete.swingui.junit.Client;")) continue;
                bad.add(f.getFileName() + ": " + t);
            }
        }
        assertThat(bad).isEmpty();
    }
}
