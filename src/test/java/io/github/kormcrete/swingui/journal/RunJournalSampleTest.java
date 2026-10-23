package io.github.kormcrete.swingui.journal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sample run folder in the test resources (linked from the run journal reference, for consumers to use as a
 * fixture) is a real run's folder: it keeps to format 1 and shows every kind of line.
 */
class RunJournalSampleTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Path samples() throws Exception {
        return Paths.get(RunJournalSampleTest.class.getResource("/run-journal-sample/latest-run.txt").toURI()).getParent();
    }

    private static Path sample() throws Exception {
        String runId = new String(Files.readAllBytes(samples().resolve("latest-run.txt")), StandardCharsets.UTF_8).trim();
        return samples().resolve(runId);
    }

    @Test
    void theSampleRunFolderConforms() throws Exception {
        Path sample = sample();
        assertThat(sample.getFileName().toString()).isEqualTo("UIT-20261023-081204-a3f9");
        assertThat(RunFolderFormat.check(sample)).isEmpty();
    }

    @Test
    void theSampleHasEveryKind() throws Exception {
        Set<String> kinds = new LinkedHashSet<String>();
        Set<String> ends = new LinkedHashSet<String>();
        List<Path> journals;
        try (Stream<Path> files = Files.list(sample().resolve("steps"))) {
            journals = files.collect(Collectors.toList());
        }
        assertThat(journals).hasSize(2);
        for (Path journal : journals) {
            for (String line : Files.readAllLines(journal, StandardCharsets.UTF_8)) {
                JsonNode l = JSON.readTree(line);
                kinds.add(l.path("kind").asText());
                if ("end".equals(l.path("kind").asText())) ends.add(l.path("outcome").asText());
            }
        }
        assertThat(kinds).containsExactlyInAnyOrder("test", "section", "action", "check", "read", "checkpoint", "end");
        assertThat(ends).containsExactlyInAnyOrder("passed", "failed");
    }

    @Test
    void theRunFormatCheckFindsABrokenFolder() throws Exception {
        Path broken = Files.createTempDirectory("run-journal-broken").resolve("UIT-20261023-081204-a3f9");
        try {
            copy(sample(), broken);
            Path journal = broken.resolve("steps").resolve("FixtureAppDriveIT$JournalFailing#failsAtTheStatus.jsonl");
            List<String> lines = Files.readAllLines(journal, StandardCharsets.UTF_8);
            lines.remove(1);   // a gap in seq
            lines.add(lines.remove(0));   // the test line last, the end line no longer last
            Files.write(journal, lines, StandardCharsets.UTF_8);
            Files.delete(broken.resolve("checkpoints").resolve("FixtureAppDriveIT$JournalConformance#placesAnOrder")
                    .resolve("paper-qty.png"));
            assertThat(RunFolderFormat.check(broken)).anySatisfy(p -> assertThat(p).contains("seq is 3, not 1"))
                    .anySatisfy(p -> assertThat(p).contains("the first line is check, not test"))
                    .anySatisfy(p -> assertThat(p).contains("the last line is test, not end"))
                    .anySatisfy(p -> assertThat(p).contains("paper-qty.png"));
        } finally {
            try (Stream<Path> all = Files.walk(broken.getParent())) {
                all.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static void copy(Path from, Path to) throws Exception {
        try (Stream<Path> all = Files.walk(from)) {
            for (Path p : all.collect(Collectors.toList())) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target);
            }
        }
    }
}
