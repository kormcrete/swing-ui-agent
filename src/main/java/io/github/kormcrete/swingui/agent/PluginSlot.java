package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.spi.DomainStep;
import io.github.kormcrete.swingui.spi.PluginParts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** One {@code plugins=} entry: where it comes from, its state, and its parts once ready. */
public final class PluginSlot {

    public enum State { NONE, WAITING, STARTING, READY, FAILED }

    private final String name;
    private final Path location;
    private volatile State state = State.NONE;
    private volatile String detail = "not started";
    private volatile PluginParts parts;
    private volatile VocabularyCheck vocabulary = VocabularyCheck.NONE;
    private volatile long locationTime;

    public PluginSlot(String name, Path location) {
        this.name = name;
        this.location = location;
    }

    public String name() { return name; }
    public Path location() { return location; }
    /** The newest modification time under the location when the plugin became ready; 0 before. */
    public long locationTime() { return locationTime; }
    public State state() { return state; }
    public String detail() { return detail; }
    /** Set by {@link #ready} (just before the state turns READY), null again on {@link #failed}; null before. */
    public PluginParts parts() { return parts; }
    /**
     * The vocabulary's steps that passed the checks ({@link VocabularyCheck}): set by {@link #ready} before
     * {@link #parts}, emptied on {@link #failed}; empty before.
     */
    public List<DomainStep> vocabulary() { return vocabulary.steps; }
    /** One line per dropped step (or a vocabulary that failed), naming the plugin; set and emptied with {@link #vocabulary}. */
    public List<String> vocabularyProblems() { return vocabulary.problems; }

    public synchronized void waiting(String detail) { set(State.WAITING, detail); }
    public synchronized void starting(String detail) { set(State.STARTING, detail); }

    /** A failure after READY drops the parts: a half-working plugin is worse than a failed one. */
    public synchronized void failed(String detail) {
        boolean wasReady = state == State.READY;
        parts = null;
        vocabulary = VocabularyCheck.NONE;
        set(State.FAILED, wasReady ? "failed after ready: " + detail : detail);
    }

    /** Reads and checks the vocabulary first (plugin code, so outside the lock); a step that fails a check is dropped. */
    public void ready(PluginParts parts, String detail) {
        VocabularyCheck checked = VocabularyCheck.of(name, parts == null ? null : parts.vocabulary);
        readyChecked(parts, checked, detail);
    }

    private synchronized void readyChecked(PluginParts parts, VocabularyCheck checked, String detail) {
        this.vocabulary = checked; // before parts: a reader that sees the parts sees their vocabulary
        this.parts = parts;
        this.locationTime = newestTime(location);
        set(State.READY, detail);
    }

    /** The parts, or a CAPABILITY_GAP naming this plugin's state. */
    public PluginParts readyParts(String what) {
        PluginParts p = parts;
        if (p == null) {
            throw new DriverException(Reason.CAPABILITY_GAP,
                    what + " needs plugin '" + name + "', which is " + stateName() + ": " + detail);
        }
        return p;
    }

    String stateName() { return state.name().toLowerCase(Locale.ROOT); }

    private void set(State state, String detail) {
        this.state = state;
        this.detail = detail;
    }

    static long newestTime(Path location) {
        if (location == null || !Files.exists(location)) return 0L;
        try {
            if (!Files.isDirectory(location)) return Files.getLastModifiedTime(location).toMillis();
            try (Stream<Path> files = Files.walk(location)) {
                return files.filter(Files::isRegularFile).mapToLong(p -> {
                    try {
                        return Files.getLastModifiedTime(p).toMillis();
                    } catch (IOException e) {
                        return 0L;
                    }
                }).max().orElse(0L);
            }
        } catch (IOException e) {
            return 0L;
        }
    }
}
