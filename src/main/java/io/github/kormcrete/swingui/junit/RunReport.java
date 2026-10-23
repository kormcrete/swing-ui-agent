package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.journal.RunFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * The run report a caller polls: STARTED, one line per finished test, DONE. Each line is flushed at once and, when the
 * run folder was made by the time of STARTED, also goes to its {@code report.txt}; DONE's counts go to its
 * {@code run.json}. Writing never throws: a failure gives one {@code [ui-warning]} line and the report, its
 * report.txt copy included, goes quiet.
 */
final class RunReport {

    private final Path file;
    /** The run folder; null when the report has none. */
    private final RunFolder folder;
    private int passed;
    private int failed;
    private int skipped;
    private int aborted;
    /** True when the lines also go to the run folder's report.txt. */
    private boolean tee;
    private boolean off;

    RunReport(Path file) {
        this(file, null);
    }

    RunReport(Path file, RunFolder folder) {
        this.file = file;
        this.folder = folder;
    }

    synchronized void started(long tests) {
        String runId = folder == null ? null : folder.runId();
        tee = runId != null;
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException | RuntimeException e) {
            off(e);
        }
        write("STARTED " + Instant.now() + " tests=" + tests + (runId == null ? "" : " run=" + runId), true);
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
        if (folder != null) folder.finish(new int[] {passed, failed, skipped, aborted});
    }

    private void write(String line, boolean truncate) {
        if (off) return;
        if (tee) folder.report(line);
        try {
            Files.write(file, (line + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
                    truncate ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException e) {
            off(e);
        }
    }

    /** The report writes no more lines and the run's journal goes off, with one warning. */
    private void off(Exception e) {
        off = true;
        if (folder != null) folder.broken(e);
        else RunFolder.warnOff(e);
    }
}
