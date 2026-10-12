package io.github.kormcrete.swingui.agent;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The agent is generic: its main sources import only the JDK, its own packages and its declared libraries. */
class AgentNeutralityTest {

    /** An import of anything else - an application's classes above all - is a hit. */
    private static final Pattern FOREIGN_IMPORT = Pattern.compile(
            "^import (static )?(?!(java|javax|com\\.sun|com\\.fasterxml|org\\.junit|io\\.github\\.kormcrete\\.swingui)\\.)");

    @Test
    void mainSourcesImportNoApplicationCode() throws Exception {
        List<String> hits = new ArrayList<String>();
        List<Path> files;
        Path main = Paths.get("src/main"); // relative to the project root, where Maven runs the tests
        try (Stream<Path> s = Files.walk(main)) {
            files = s.filter(Files::isRegularFile).collect(Collectors.toList());
        }
        assertThat(files).isNotEmpty();
        for (Path f : files) {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (FOREIGN_IMPORT.matcher(lines.get(i)).find()) hits.add(f + ":" + (i + 1) + ": " + lines.get(i).trim());
            }
        }
        assertThat(hits).isEmpty();
    }
}
