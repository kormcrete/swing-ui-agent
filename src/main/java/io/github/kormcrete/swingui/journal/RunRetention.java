package io.github.kormcrete.swingui.journal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Which old run folders go. Only folders the library wrote count: a {@code run.json} of {@code "format": 1} whose
 * {@code runId} is the folder's name. They are ordered by their {@code started} time, newest first; the newest
 * {@code keep} stay (the current run is one of them), and the rest go, except the current run and any folder with a
 * file touched lately (another JVM of the same build may still write there). A folder that cannot be deleted gives a
 * warning; the rest go on.
 */
public final class RunRetention {

    /** A folder with a file modified this recently (ten minutes) is never deleted. */
    public static final long RECENT_MS = 600_000L;

    private static final ObjectMapper JSON = new ObjectMapper();

    private RunRetention() {}

    /**
     * Deletes the run folders under {@code runsDir} beyond the newest {@code keep} ({@code 0} keeps all), never
     * {@code current} nor one with a file modified within {@code recentMillis} before {@code nowMillis}. Returns the
     * folders deleted.
     */
    public static List<Path> prune(Path runsDir, Path current, int keep, long nowMillis, long recentMillis) {
        if (keep <= 0) return Collections.emptyList();
        List<Run> runs = new ArrayList<Run>();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(runsDir)) {
            for (Path d : dirs) {
                if (!Files.isDirectory(d) || isCurrent(d, current)) continue;
                Instant started = started(d);
                if (started != null) runs.add(new Run(d, started));
            }
        } catch (IOException | RuntimeException e) {
            warn("cannot list the run folders in " + runsDir + ": " + e);
            return Collections.emptyList();
        }
        Collections.sort(runs, Comparator.comparing((Run r) -> r.started).reversed());
        List<Path> deleted = new ArrayList<Path>();
        for (Run r : runs.subList(Math.min(runs.size(), keep - 1), runs.size())) {
            if (touchedSince(r.dir, nowMillis - recentMillis)) continue;
            try {
                deleteTree(r.dir);
                deleted.add(r.dir);
            } catch (IOException | RuntimeException e) {
                warn("cannot delete the old run folder " + r.dir + ": " + e);
            }
        }
        return deleted;
    }

    private static boolean isCurrent(Path dir, Path current) {
        return current != null && dir.toAbsolutePath().normalize().equals(current.toAbsolutePath().normalize());
    }

    /** The {@code started} time of a folder the library wrote; null for any other folder. */
    private static Instant started(Path dir) {
        Path runJson = dir.resolve("run.json");
        if (!Files.isRegularFile(runJson)) return null;
        try {
            JsonNode run = JSON.readTree(runJson.toFile());
            if (run == null || run.path("format").asInt() != 1) return null;
            if (!dir.getFileName().toString().equals(run.path("runId").asText(null))) return null;
            return Instant.parse(run.path("started").asText());
        } catch (IOException | RuntimeException notOurs) {
            return null;
        }
    }

    /** True when {@code dir} or anything in it was modified at or after {@code sinceMillis}, or that cannot be told. */
    private static boolean touchedSince(Path dir, long sinceMillis) {
        try (Stream<Path> all = Files.walk(dir)) {
            return all.anyMatch(p -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis() >= sinceMillis;
                } catch (IOException gone) {
                    return true;
                }
            });
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                if (e != null) throw e;
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void warn(String text) {
        System.err.println("[ui-warning] " + text);
    }

    private static final class Run {
        final Path dir;
        final Instant started;

        Run(Path dir, Instant started) {
            this.dir = dir;
            this.started = started;
        }
    }
}
