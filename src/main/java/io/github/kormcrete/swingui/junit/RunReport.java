package io.github.kormcrete.swingui.junit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** The run report a caller polls: STARTED, one line per finished test, DONE. Each line is flushed at once. */
final class RunReport {

    private final Path file;
    private int passed;
    private int failed;
    private int skipped;
    private int aborted;

    RunReport(Path file) {
        this.file = file;
    }

    synchronized void started(long tests) {
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        write("STARTED " + Instant.now() + " tests=" + tests, true);
    }

    synchronized void finished(String test, String status, long ms, String failure, String evidence) {
        if ("PASS".equals(status)) passed++;
        else if ("FAIL".equals(status)) failed++;
        else if ("SKIP".equals(status)) skipped++;
        else aborted++;
        StringBuilder line = new StringBuilder(status).append(' ').append(test).append(' ').append(ms).append("ms");
        if (failure != null) line.append(" — ").append(failure.split("\n", 2)[0].replaceAll("\\r$", ""));
        if (evidence != null) line.append(" — evidence ").append(evidence);
        write(line.toString(), false);
    }

    synchronized void done() {
        write("DONE passed=" + passed + " failed=" + failed + " skipped=" + skipped + " aborted=" + aborted, false);
    }

    private void write(String line, boolean truncate) {
        try {
            Files.write(file, (line + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
                    truncate ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException e) {
            System.err.println("[ui-warning] run report " + file + ": " + e);
        }
    }
}
