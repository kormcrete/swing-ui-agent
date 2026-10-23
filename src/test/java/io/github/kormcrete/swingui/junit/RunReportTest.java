package io.github.kormcrete.swingui.junit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kormcrete.swingui.journal.JournalSettings;
import io.github.kormcrete.swingui.journal.RunFolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunReportTest {

    @Test
    void oneLinePerTestThenDone(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ui-runs/latest.txt");
        RunReport r = new RunReport(file);
        r.started(3);
        r.finished("OrdersSmokeUiIT#opens", "PASS", 4100, null, null);
        r.finished("OrderRoundTripUiIT#order", "FAIL", 9000, "NOT_FOUND: no button 'Save'\nat …",
                "/r/app-uitest/target/ui-failures/OrderRoundTripUiIT/order");
        r.finished("X#y", "SKIP", 0, "disabled", null);
        r.done();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines.get(0)).startsWith("STARTED ").endsWith(" tests=3");
        assertThat(lines.subList(1, lines.size())).containsExactly(
                "PASS OrdersSmokeUiIT#opens 4100ms",
                "FAIL OrderRoundTripUiIT#order 9000ms — NOT_FOUND: no button 'Save' — evidence "
                        + "/r/app-uitest/target/ui-failures/OrderRoundTripUiIT/order",
                "SKIP X#y 0ms — disabled",
                "DONE passed=1 failed=1 skipped=1 aborted=0");
    }

    @Test
    void aNewRunReplacesTheOldReport(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("latest.txt");
        RunReport first = new RunReport(file);
        first.started(1);
        first.done();
        RunReport second = new RunReport(file);
        second.started(2);
        assertThat(Files.readAllLines(file, StandardCharsets.UTF_8)).hasSize(1).first().asString().endsWith("tests=2");
    }

    @Test
    void theStartedLineEndsWithTheRunIdAndEveryLineGoesToReportTxt(@TempDir Path dir) throws Exception {
        RunFolder folder = RunFolder.of(dir, JournalSettings.defaults());
        try {
            Path run = folder.create();
            Path file = dir.resolve("latest.txt");
            RunReport r = new RunReport(file, folder);
            r.started(2);
            r.finished("OrdersUiIT#placesAnOrder", "PASS", 4100, null, null);
            r.finished("OrdersUiIT#totals", "FAIL", 900, "Total shows €7.31, expected €7.32", null);
            r.done();

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            assertThat(lines.get(0)).startsWith("STARTED ").endsWith(" tests=2 run=" + folder.runId());
            assertThat(Files.readAllLines(run.resolve("report.txt"), StandardCharsets.UTF_8)).isEqualTo(lines);
            assertThat(new ObjectMapper().readTree(run.resolve("run.json").toFile()).path("counts").toString())
                    .isEqualTo("{\"passed\":1,\"failed\":1,\"skipped\":0,\"aborted\":0}");
        } finally {
            RunFolder.forget(dir);
        }
    }

    @Test
    void aReportThatCannotBeWrittenWarnsOnceAndNeverThrows(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("ui-runs"), new byte[0]);
        RunReport r = new RunReport(dir.resolve("ui-runs").resolve("latest.txt"));
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
        try {
            r.started(1);
            r.finished("OrdersUiIT#placesAnOrder", "PASS", 1, null, null);
            r.done();
        } finally {
            System.setErr(err);
        }
        String warnings = new String(captured.toByteArray(), StandardCharsets.UTF_8);
        assertThat(warnings).startsWith("[ui-warning] run journal off for this run: ");
        assertThat(warnings.split("\\[ui-warning]", -1)).hasSize(2);
    }
}
