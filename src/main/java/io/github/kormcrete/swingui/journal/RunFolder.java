package io.github.kormcrete.swingui.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kormcrete.swingui.agent.AgentInfo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One run's folder under a {@code ui-runs} directory: one per directory per JVM, made on first need as
 * {@code <runsDir>/<run id>} ({@code -2}, {@code -3}, ... when that name is taken), with its {@code run.json} and
 * {@code <runsDir>/latest-run.txt}; older run folders are pruned right after ({@link RunRetention}). It hands out the
 * per-test journals and keeps their file names apart. After an IO failure ({@link #broken}) the steps journal is off
 * for the rest of the JVM's run, with one warning.
 */
public final class RunFolder {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String LIBRARY = AgentInfo.load().version;
    private static final Map<Path, RunFolder> FOLDERS = new HashMap<Path, RunFolder>();
    /** The app version a setup call named, for every run of this JVM; null when none did. */
    private static volatile String explicitAppVersion;

    private final Path runsDir;
    private final JournalSettings settings;
    private final AtomicBoolean warned = new AtomicBoolean();
    /** The file name bases taken so far, lower case: a disk that ignores case must not mix two tests up. */
    private final Set<String> taken = new HashSet<String>();
    /** The clients seen, by their {@code agent}, in the order first seen. */
    private final Map<String, ObjectNode> clients = new LinkedHashMap<String, ObjectNode>();
    private volatile boolean broken;
    private volatile Path dir;
    private long startedAt;
    private long finishedAt;
    private int[] counts;

    private RunFolder(Path runsDir, JournalSettings settings) {
        this.runsDir = runsDir;
        this.settings = settings;
    }

    /** This JVM's run folder under {@code runsDir}; the settings of the first call for a directory hold. */
    public static RunFolder of(Path runsDir, JournalSettings settings) {
        return of(runsDir, () -> settings);
    }

    /**
     * {@link #of(Path, JournalSettings)} with the settings read from {@code params} (e.g. JUnit configuration
     * parameters) by the first call for a directory only, so a setting that cannot be used warns once per run.
     */
    public static RunFolder of(Path runsDir, Function<String, Optional<String>> params) {
        return of(runsDir, () -> JournalSettings.from(params));
    }

    private static RunFolder of(Path runsDir, Supplier<JournalSettings> settings) {
        synchronized (FOLDERS) {
            Path key = runsDir.toAbsolutePath().normalize();
            RunFolder folder = FOLDERS.get(key);
            if (folder == null) {
                folder = new RunFolder(runsDir, settings.get());
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

    /**
     * Names the app version of every run of this JVM (a setup call; it beats {@code swingui.appVersion}); a
     * {@code run.json} written already is rewritten with it.
     */
    public static void appVersion(String version) {
        explicitAppVersion = version == null || version.trim().isEmpty() ? null : version.trim();
        List<RunFolder> all;
        synchronized (FOLDERS) {
            all = new ArrayList<RunFolder>(FOLDERS.values());
        }
        for (RunFolder f : all) f.writeRunJson();
    }

    /** The app version a setup call named; null when none did. */
    public static String appVersion() {
        return explicitAppVersion;
    }

    /** Forgets the setup call's app version (for tests). */
    static void resetAppVersion() {
        explicitAppVersion = null;
    }

    /** This run's app version: the setup call's, else {@code swingui.appVersion}, else null. */
    public String appVersionResolved() {
        String setup = explicitAppVersion;
        return setup != null ? setup : settings.appVersion;
    }

    /** The settings this run folder was made with. */
    public JournalSettings settings() {
        return settings;
    }

    /**
     * The run folder, made on the first call with its {@code run.json} and {@code <runsDir>/latest-run.txt}, older run
     * folders pruned right after; the same path after. Throws when the folder itself cannot be made; a file in it that
     * cannot be written turns the journal off ({@link #broken}) and keeps the folder, with no further writes.
     */
    public synchronized Path create() {
        if (dir != null) return dir;
        String base = settings.runId != null ? settings.runId : RunIds.JVM;
        Path made = null;
        try {
            Files.createDirectories(runsDir);
            for (int n = 1; made == null; n++) {
                try {
                    made = Files.createDirectory(runsDir.resolve(n == 1 ? base : base + "-" + n));
                } catch (FileAlreadyExistsException taken) {
                    // another run (or a file) has this name: try the next suffix
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot make the run folder under " + runsDir + ": " + e.getMessage(), e);
        }
        startedAt = System.currentTimeMillis();
        dir = made;
        writeRunJson();
        if (broken) return made;
        try {
            Files.write(runsDir.resolve("latest-run.txt"), (runId() + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            broken(e);
            return made;
        }
        RunRetention.prune(runsDir, made, settings.keep, System.currentTimeMillis(), RunRetention.RECENT_MS);
        return made;
    }

    /** The run folder; null until {@link #create} made it. */
    public Path dirOrNull() {
        return dir;
    }

    /** The run's id: the run folder's name, suffix included; null until {@link #create} made it. */
    public String runId() {
        Path d = dir;
        return d == null ? null : d.getFileName().toString();
    }

    /** Lists {@code client} in {@code run.json} the first time its {@code agent} is seen. */
    public void client(ObjectNode client) {
        if (client == null) return;
        synchronized (this) {
            String agent = client.path("agent").asText();
            if (clients.containsKey(agent)) return;
            clients.put(agent, client.deepCopy());
        }
        writeRunJson();
    }

    /** Appends {@code line} to the run's {@code report.txt}; nothing while the folder is not made or once broken. */
    public synchronized void report(String line) {
        if (dir == null || broken) return;
        try {
            Files.write(dir.resolve("report.txt"), (line + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException e) {
            broken(e);
        }
    }

    /**
     * The run ended: {@code run.json} gets {@code finished} and the passed, failed, skipped and aborted counts
     * ({@code null} when they are not known).
     */
    public void finish(int[] passedFailedSkippedAborted) {
        synchronized (this) {
            finishedAt = System.currentTimeMillis();
            counts = passedFailedSkippedAborted == null ? null : passedFailedSkippedAborted.clone();
        }
        writeRunJson();
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

    /** Rewrites {@code run.json} whole, {@code format} first; nothing while the folder is not made or once broken. */
    private synchronized void writeRunJson() {
        if (dir == null || broken) return;
        ObjectNode run = NODES.objectNode();
        run.put("format", 1);
        run.put("runId", runId());
        run.put("started", JournalLines.time(startedAt));
        if (finishedAt != 0) run.put("finished", JournalLines.time(finishedAt));
        run.put("library", LIBRARY);
        run.put("java", System.getProperty("java.version"));
        run.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                + System.getProperty("os.arch"));
        String app = appVersionResolved();
        if (app != null) run.putObject("app").put("version", app);
        ArrayNode list = run.putArray("clients");
        for (ObjectNode c : clients.values()) list.add(c);
        if (counts != null && counts.length == 4) {
            ObjectNode c = run.putObject("counts");
            c.put("passed", counts[0]);
            c.put("failed", counts[1]);
            c.put("skipped", counts[2]);
            c.put("aborted", counts[3]);
        }
        try {
            Files.write(dir.resolve("run.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(run));
        } catch (IOException e) {
            broken(e);
        }
    }
}
