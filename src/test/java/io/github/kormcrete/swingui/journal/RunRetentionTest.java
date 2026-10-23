package io.github.kormcrete.swingui.journal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RunRetentionTest {

    private static final long NOW = System.currentTimeMillis();
    private static final long HOUR = 3_600_000L;

    @TempDir Path runs;

    /** A run folder as the library writes it, started {@code hoursAgo} hours before NOW, last touched an hour ago. */
    private Path run(String id, int hoursAgo, boolean finished) throws IOException {
        Path dir = Files.createDirectories(runs.resolve(id));
        String started = Instant.ofEpochMilli(NOW - hoursAgo * HOUR).toString();
        String json = "{\"format\":1,\"runId\":\"" + id + "\",\"started\":\"" + started + "\""
                + (finished ? ",\"finished\":\"" + started + "\"" : "") + ",\"clients\":[]}";
        Files.write(dir.resolve("run.json"), json.getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(dir.resolve("steps"));
        Files.write(dir.resolve("steps").resolve("OrdersUiIT#placesAnOrder.jsonl"), new byte[0]);
        return age(dir);
    }

    /** Every file in {@code dir}, and {@code dir}, last modified an hour before NOW. */
    private static Path age(Path dir) throws IOException {
        List<Path> all;
        try (Stream<Path> s = Files.walk(dir)) {
            all = s.collect(Collectors.toList());
        }
        for (Path p : all) Files.setLastModifiedTime(p, FileTime.fromMillis(NOW - HOUR));
        return dir;
    }

    private List<Path> prune(Path current, int keep) {
        return RunRetention.prune(runs, current, keep, NOW, RunRetention.RECENT_MS);
    }

    @Test
    void keepsTheNewestByStartedTimeNotByName() throws IOException {
        Path r99 = run("99", 3, true);
        Path r100 = run("100", 2, true);
        Path r101 = run("101", 1, false);

        assertThat(prune(r101, 2)).containsExactly(r99);
        assertThat(r99).doesNotExist();
        assertThat(r100).isDirectory();
        assertThat(r101).isDirectory();
    }

    @Test
    void neverDeletesTheCurrentOrARecentlyTouchedFolder() throws IOException {
        Path touched = run("UIT-20261020-080000-0001", 5, true);
        Path old = run("UIT-20261021-080000-0002", 4, true);
        Path current = run("UIT-20261019-080000-0003", 9, false);
        Files.setLastModifiedTime(touched.resolve("steps").resolve("OrdersUiIT#placesAnOrder.jsonl"),
                FileTime.fromMillis(NOW - 5 * 60_000L));

        assertThat(prune(current, 1)).containsExactly(old);
        assertThat(touched).isDirectory();
        assertThat(current).isDirectory();
    }

    @Test
    void ignoresFoldersWithoutTheLibrarysRunJson() throws IOException {
        Path notes = Files.createDirectories(runs.resolve("notes"));
        Files.write(notes.resolve("todo.txt"), "Place order".getBytes(StandardCharsets.UTF_8));
        age(notes);
        Path copied = run("copy-of-99", 8, true);
        Files.write(copied.resolve("run.json"),
                "{\"format\":1,\"runId\":\"99\",\"started\":\"2026-10-20T08:00:00.000Z\"}".getBytes(StandardCharsets.UTF_8));
        age(copied);
        Path otherFormat = run("100", 7, true);
        Files.write(otherFormat.resolve("run.json"),
                "{\"format\":2,\"runId\":\"100\",\"started\":\"2026-10-20T08:00:00.000Z\"}".getBytes(StandardCharsets.UTF_8));
        age(otherFormat);
        Path broken = run("101", 6, true);
        Files.write(broken.resolve("run.json"), "{\"format\":1,".getBytes(StandardCharsets.UTF_8));
        age(broken);
        Files.write(runs.resolve("latest.txt"), "DONE".getBytes(StandardCharsets.UTF_8));
        Path older = run("102", 5, true);
        Path current = run("103", 1, false);

        assertThat(prune(current, 1)).containsExactly(older);
        assertThat(notes).isDirectory();
        assertThat(copied).isDirectory();
        assertThat(otherFormat).isDirectory();
        assertThat(broken).isDirectory();
        assertThat(runs.resolve("latest.txt")).isRegularFile();
    }

    @Test
    void keepZeroKeepsAll() throws IOException {
        run("99", 3, true);
        run("100", 2, true);
        Path current = run("101", 1, false);

        assertThat(prune(current, 0)).isEmpty();
        assertThat(runs.resolve("99")).isDirectory();
        assertThat(runs.resolve("100")).isDirectory();
    }

    @Test
    void aRunWithoutFinishedIsStillPrunedByItsStartedTime() throws IOException {
        Path crashed = run("UIT-20261022-080000-0001", 3, false);
        Path finished = run("UIT-20261022-090000-0002", 2, true);
        Path current = run("UIT-20261022-100000-0003", 1, false);

        assertThat(prune(current, 2)).containsExactly(crashed);
        assertThat(finished).isDirectory();
    }

    @Test
    void aFolderThatIsNotWhollyDeletedKeepsItsRunJsonAndGoesOnALaterRun() throws IOException {
        Path old = run("99", 3, true);
        for (String name : new String[] {"report.txt", "a.txt", "z.txt"}) Files.write(old.resolve(name), new byte[] {1});
        Files.write(Files.createDirectories(old.resolve("checkpoints").resolve("OrdersUiIT#placesAnOrder"))
                .resolve("cart-totals.png"), new byte[] {1});
        age(old);
        Path current = run("100", 1, false);
        Path held = old.resolve("steps").resolve("OrdersUiIT#placesAnOrder.jsonl"); // still open elsewhere
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
        List<Path> first;
        try {
            first = RunRetention.prune(runs, current, 1, NOW, RunRetention.RECENT_MS, p -> {
                if (p.equals(held)) throw new IOException("in use");
                Files.delete(p);
            });
        } finally {
            System.setErr(err);
        }

        assertThat(first).isEmpty();
        assertThat(old.resolve("run.json")).isRegularFile();
        assertThat(new String(captured.toByteArray(), StandardCharsets.UTF_8))
                .startsWith("[ui-warning] cannot delete the old run folder ");

        List<Path> deleted = new ArrayList<Path>();
        List<Path> later = RunRetention.prune(runs, current, 1, NOW + RunRetention.RECENT_MS + 60_000L,
                RunRetention.RECENT_MS, p -> {
                    deleted.add(p);
                    Files.delete(p);
                });
        assertThat(later).containsExactly(old);
        assertThat(old).doesNotExist();
        assertThat(deleted.subList(deleted.size() - 2, deleted.size())).containsExactly(old.resolve("run.json"), old);
    }

    @Test
    void runJsonIsTheLastFileOfAFolderToGo() throws IOException {
        Path old = run("99", 3, true);
        for (String name : new String[] {"a.txt", "report.txt", "s.txt", "z.txt"}) {
            Files.write(old.resolve(name), new byte[] {1});
        }
        Files.write(Files.createDirectories(old.resolve("checkpoints").resolve("OrdersUiIT#placesAnOrder"))
                .resolve("cart-totals.png"), new byte[] {1});
        age(old);
        Path current = run("100", 1, false);
        List<Path> deleted = new ArrayList<Path>();

        assertThat(RunRetention.prune(runs, current, 1, NOW, RunRetention.RECENT_MS, p -> {
            deleted.add(p);
            Files.delete(p);
        })).containsExactly(old);
        assertThat(deleted.subList(deleted.size() - 2, deleted.size())).containsExactly(old.resolve("run.json"), old);
    }

    @Test
    void aLinkToAFolderOutsideGoesAsALinkAndWhatItPointsAtStays() throws IOException {
        Path outside = Files.createTempDirectory("ui-runs-outside");
        try {
            // what a link points at: a folder outside the runs dir that even looks like a run folder of the link's name
            String started = Instant.ofEpochMilli(NOW - 5 * HOUR).toString();
            Files.write(outside.resolve("run.json"), ("{\"format\":1,\"runId\":\"UIT-linked\",\"started\":\""
                    + started + "\",\"clients\":[]}").getBytes(StandardCharsets.UTF_8));
            Files.write(outside.resolve("keep.txt"), new byte[] {1});
            age(outside);
            Path old = run("99", 3, true);
            Path inside = old.resolve("steps").resolve("shared");
            Path top = runs.resolve("UIT-linked");
            boolean links;
            try {
                Files.createSymbolicLink(inside, outside);
                Files.createSymbolicLink(top, outside);
                links = true;
            } catch (UnsupportedOperationException | IOException | SecurityException refused) {
                links = false;   // Windows without the privilege: only the part without links is checked
                Files.deleteIfExists(inside);
            }
            age(old);   // making the link touched its folder
            Path current = run("100", 1, false);
            List<Path> deleted = new ArrayList<Path>();

            List<Path> pruned = RunRetention.prune(runs, current, 1, NOW, RunRetention.RECENT_MS, p -> {
                deleted.add(p);
                Files.delete(p);
            });

            assertThat(old).doesNotExist();
            assertThat(outside.resolve("run.json")).isRegularFile();
            assertThat(outside.resolve("keep.txt")).isRegularFile();
            assertThat(current).isDirectory();
            if (links) {
                assertThat(pruned).containsExactlyInAnyOrder(old, top);
                // each link went once, as a link; nothing behind it was handed to the deleter
                assertThat(deleted).containsOnlyOnce(inside, top);
                assertThat(deleted).noneMatch(p -> p.startsWith(inside) && !p.equals(inside)
                        || p.startsWith(top) && !p.equals(top) || p.startsWith(outside));
                assertThat(Files.exists(top, java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse();
            } else {
                assertThat(pruned).containsExactly(old);
            }
        } finally {
            Files.deleteIfExists(outside.resolve("run.json"));
            Files.deleteIfExists(outside.resolve("keep.txt"));
            Files.deleteIfExists(outside);
        }
    }
}
