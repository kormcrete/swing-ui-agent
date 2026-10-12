package io.github.kormcrete.swingui.junit;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The test JVM starts no AWT and knows no app: junit and client import neither AWT nor Swing. */
class JunitImportRuleTest {

    @Test
    void junitAndClientStayOffAwtAndSwing() throws Exception {
        List<String> bad = new ArrayList<String>();
        for (String pkg : new String[] {"junit", "client"}) {
            Path dir = Paths.get("src/main/java/io/github/kormcrete/swingui/" + pkg);
            if (!Files.isDirectory(dir)) continue;
            List<Path> files;
            try (Stream<Path> s = Files.list(dir)) {
                files = s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
            }
            for (Path f : files) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.matches("^import (static )?(java\\.awt|javax\\.swing)\\..*")) {
                        bad.add(f.getFileName() + ": " + line);
                    }
                }
            }
        }
        assertThat(bad).isEmpty();
    }
}
