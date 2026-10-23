package io.github.kormcrete.swingui.journal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kormcrete.swingui.agent.AgentInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RunFolderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path runs;

    @AfterEach
    void forget() {
        RunFolder.forget(runs);
        RunFolder.resetAppVersion();
    }

    private static JournalSettings settings(String key, String value) {
        Map<String, String> given = new HashMap<String, String>();
        given.put(key, value);
        return JournalSettings.from(k -> Optional.ofNullable(given.get(k)));
    }

    private static JsonNode runJson(Path dir) throws IOException {
        return JSON.readTree(dir.resolve("run.json").toFile());
    }

    private static ObjectNode client(String agent) {
        return JsonNodeFactory.instance.objectNode().put("alias", "clerk").put("agent", agent);
    }

    private List<String> names() throws IOException {
        try (Stream<Path> s = Files.list(runs)) {
            return s.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    @Test
    void createMakesTheFolderRunJsonAndLatestRun() throws IOException {
        Instant before = Instant.now().minusSeconds(1);
        RunFolder folder = RunFolder.of(runs, JournalSettings.defaults());
        assertThat(folder.dirOrNull()).isNull();
        assertThat(folder.runId()).isNull();

        Path dir = folder.create();

        assertThat(dir).isDirectory().isEqualTo(runs.resolve(RunIds.JVM));
        assertThat(folder.dirOrNull()).isEqualTo(dir);
        assertThat(folder.runId()).isEqualTo(RunIds.JVM);
        JsonNode run = runJson(dir);
        List<String> fields = new ArrayList<String>();
        run.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("format", "runId", "started", "library", "java", "os", "clients");
        assertThat(run.path("format").asInt()).isEqualTo(1);
        assertThat(run.path("runId").asText()).isEqualTo(dir.getFileName().toString());
        assertThat(Instant.parse(run.path("started").asText())).isAfter(before);
        assertThat(run.path("library").asText()).isEqualTo(AgentInfo.load().version);
        assertThat(run.path("java").asText()).isEqualTo(System.getProperty("java.version"));
        assertThat(run.path("os").asText()).isEqualTo(System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        assertThat(run.path("clients").isArray()).isTrue();
        assertThat(new String(Files.readAllBytes(runs.resolve("latest-run.txt")), StandardCharsets.UTF_8))
                .isEqualTo(RunIds.JVM + "\n");
    }

    @Test
    void aRunIdThatNamesAnExistingFolderGetsMinusTwo() throws IOException {
        Files.createDirectories(runs.resolve("99"));
        RunFolder folder = RunFolder.of(runs, settings(JournalSettings.RUN_ID, "99"));

        assertThat(folder.create()).isEqualTo(runs.resolve("99-2"));
        assertThat(folder.runId()).isEqualTo("99-2");
        assertThat(runJson(runs.resolve("99-2")).path("runId").asText()).isEqualTo("99-2");
        assertThat(new String(Files.readAllBytes(runs.resolve("latest-run.txt")), StandardCharsets.UTF_8))
                .isEqualTo("99-2\n");
    }

    @Test
    void finishAddsFinishedAndCounts() throws IOException {
        RunFolder folder = RunFolder.of(runs, JournalSettings.defaults());
        Path dir = folder.create();
        String started = runJson(dir).path("started").asText();

        folder.finish(new int[] {3, 1, 2, 0});

        JsonNode run = runJson(dir);
        List<String> fields = new ArrayList<String>();
        run.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("format", "runId", "started", "finished", "library", "java", "os", "clients",
                "counts");
        assertThat(run.path("started").asText()).isEqualTo(started);
        assertThat(Instant.parse(run.path("finished").asText())).isAfterOrEqualTo(Instant.parse(started));
        assertThat(run.path("counts").toString()).isEqualTo("{\"passed\":3,\"failed\":1,\"skipped\":2,\"aborted\":0}");

        folder.finish(null);
        assertThat(runJson(dir).has("finished")).isTrue();
        assertThat(runJson(dir).has("counts")).isFalse();
    }

    @Test
    void eachAgentIsListedOnceInRunJson() throws IOException {
        RunFolder folder = RunFolder.of(runs, JournalSettings.defaults());
        folder.client(client("localhost:8799"));
        Path dir = folder.create();
        folder.client(client("localhost:8799"));
        folder.client(client("localhost:8800"));

        JsonNode clients = runJson(dir).path("clients");
        assertThat(clients).hasSize(2);
        assertThat(clients.get(0).path("agent").asText()).isEqualTo("localhost:8799");
        assertThat(clients.get(1).path("agent").asText()).isEqualTo("localhost:8800");
    }

    @Test
    void anExplicitAppVersionIsTheRunsAppVersion() throws IOException {
        RunFolder folder = RunFolder.of(runs, settings(JournalSettings.APP_VERSION, "2.4.1"));
        Path dir = folder.create();
        assertThat(runJson(dir).path("app").toString()).isEqualTo("{\"version\":\"2.4.1\"}");

        RunFolder.appVersion("2.5.0");

        assertThat(RunFolder.appVersion()).isEqualTo("2.5.0");
        assertThat(runJson(dir).path("app").toString()).isEqualTo("{\"version\":\"2.5.0\"}");

        Path other = runs.resolve("other");
        RunFolder.resetAppVersion();
        try {
            assertThat(RunFolder.appVersion()).isNull();
            assertThat(runJson(RunFolder.of(other, JournalSettings.defaults()).create()).has("app")).isFalse();
        } finally {
            RunFolder.forget(other);
        }
    }

    @Test
    void oneFolderPerRunsDirectoryPerJvm() throws IOException {
        RunFolder folder = RunFolder.of(runs, JournalSettings.defaults());
        RunFolder again = RunFolder.of(runs.resolve("x").resolve(".."), settings(JournalSettings.RUN_ID, "nightly-42"));

        assertThat(again).isSameAs(folder);
        assertThat(again.create()).isEqualTo(folder.create());
        assertThat(names()).containsExactly(RunIds.JVM, "latest-run.txt");
    }

    @Test
    void theSettingsAreReadOnlyForTheFirstCallOfAFolder() {
        List<String> asked = new ArrayList<String>();
        RunFolder folder = RunFolder.of(runs, key -> {
            asked.add(key);
            return Optional.empty();
        });
        int first = asked.size();

        assertThat(RunFolder.of(runs, key -> {
            asked.add(key);
            return Optional.empty();
        })).isSameAs(folder);
        assertThat(first).isPositive();
        assertThat(asked).hasSize(first);
    }

    @Test
    void reportLinesGoToReportTxtOnceTheFolderIsMade() throws IOException {
        RunFolder folder = RunFolder.of(runs, JournalSettings.defaults());
        folder.report("STARTED before");
        Path dir = folder.create();
        folder.report("PASS OrdersUiIT#placesAnOrder 4100ms");
        folder.report("DONE passed=1 failed=0 skipped=0 aborted=0");

        assertThat(Files.readAllLines(dir.resolve("report.txt"), StandardCharsets.UTF_8)).containsExactly(
                "PASS OrdersUiIT#placesAnOrder 4100ms", "DONE passed=1 failed=0 skipped=0 aborted=0");
    }

    @Test
    void theRunsAppVersionIsTheSetupCallsElseTheSettingElseNone() {
        Path other = runs.resolve("other");
        try {
            RunFolder given = RunFolder.of(runs, settings(JournalSettings.APP_VERSION, "2.4.1"));
            RunFolder none = RunFolder.of(other, JournalSettings.defaults());
            assertThat(given.appVersionResolved()).isEqualTo("2.4.1");
            assertThat(none.appVersionResolved()).isNull();

            RunFolder.appVersion("2.5.0");

            assertThat(given.appVersionResolved()).isEqualTo("2.5.0");
            assertThat(none.appVersionResolved()).isEqualTo("2.5.0");
            assertThat(RunFolder.appVersion()).isEqualTo("2.5.0");
        } finally {
            RunFolder.forget(other);
        }
    }

    @Test
    void aBrokenFolderTriesNoMoreWrites() throws IOException {
        RunFolder folder = RunFolder.of(runs, JournalSettings.defaults());
        Path dir = folder.create();
        String before = new String(Files.readAllBytes(dir.resolve("run.json")), StandardCharsets.UTF_8);
        PrintStream err = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream(), true));
        try {
            folder.broken(new IOException("disk full"));
        } finally {
            System.setErr(err);
        }

        folder.report("PASS OrdersUiIT#placesAnOrder 4100ms");
        folder.client(client("localhost:8799"));
        folder.finish(new int[] {1, 0, 0, 0});
        RunFolder.appVersion("2.5.0");

        assertThat(dir.resolve("report.txt")).doesNotExist();
        assertThat(new String(Files.readAllBytes(dir.resolve("run.json")), StandardCharsets.UTF_8)).isEqualTo(before);
    }
}
