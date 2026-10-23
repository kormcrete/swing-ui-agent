package io.github.kormcrete.swingui.journal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One run's folder under a {@code ui-runs} directory: one per directory per JVM, made on first need as
 * {@code <runsDir>/<run id>} ({@code -2}, {@code -3}, ... when that name is taken). It hands out the per-test journals
 * and keeps their file names apart. After an IO failure ({@link #broken}) the steps journal is off for the rest of
 * the JVM's run, with one warning.
 */
public final class RunFolder {

    private static final Map<Path, RunFolder> FOLDERS = new HashMap<Path, RunFolder>();

    private final Path runsDir;
    private final JournalSettings settings;
    private final AtomicBoolean warned = new AtomicBoolean();
    /** The file name bases taken so far, lower case: a disk that ignores case must not mix two tests up. */
    private final Set<String> taken = new HashSet<String>();
    private volatile boolean broken;
    private Path dir;

    private RunFolder(Path runsDir, JournalSettings settings) {
        this.runsDir = runsDir;
        this.settings = settings;
    }

    /** This JVM's run folder under {@code runsDir}; the settings of the first call for a directory hold. */
    public static RunFolder of(Path runsDir, JournalSettings settings) {
        synchronized (FOLDERS) {
            Path key = runsDir.toAbsolutePath().normalize();
            RunFolder folder = FOLDERS.get(key);
            if (folder == null) {
                folder = new RunFolder(runsDir, settings);
                FOLDERS.put(key, folder);
            }
            return folder;
        }
    }

    /** Lets the next {@link #of} for {@code runsDir} start a new run folder (for tests). */
    public static void forget(Path runsDir) {
        synchronized (FOLDERS) {
            FOLDERS.remove(runsDir.toAbsolutePath().normalize());
        }
    }

    /** The run folder, made on the first call; the same path after. */
    public synchronized Path create() {
        if (dir != null) return dir;
        String base = settings.runId != null ? settings.runId : RunIds.JVM;
        try {
            Files.createDirectories(runsDir);
            for (int n = 1; ; n++) {
                Path candidate = runsDir.resolve(n == 1 ? base : base + "-" + n);
                try {
                    dir = Files.createDirectory(candidate);
                    return dir;
                } catch (FileAlreadyExistsException taken) {
                    // another run (or a file) has this name: try the next suffix
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot make the run folder under " + runsDir + ": " + e.getMessage(), e);
        }
    }

    /** True while step journals are written: the journal is on and no write has failed. */
    public boolean stepsOn() {
        return settings.journal && !broken;
    }

    /** A write failed: the steps journal is off for the rest of this run; the first call prints one warning. */
    public void broken(Exception e) {
        broken = true;
        if (warned.compareAndSet(false, true)) {
            System.err.println("[ui-warning] run journal off for this run: " + e);
        }
    }

    /** The journal of the test named {@code name} ({@code <Class>#<method>}). */
    public TestJournal test(String name) {
        return new TestJournal(this, name);
    }

    /** The file name base for {@code test}: its safe file name, with {@code ~2}, {@code ~3}, ... once it was taken. */
    synchronized String reserve(String test) {
        String base = RunIds.fileName(test);
        for (int n = 1; ; n++) {
            String name = n == 1 ? base : base + "~" + n;
            if (taken.add(name.toLowerCase(Locale.ROOT))) return name;
        }
    }
}
