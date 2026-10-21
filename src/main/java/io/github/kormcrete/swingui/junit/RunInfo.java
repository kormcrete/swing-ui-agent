package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.flow.refs.RunValues;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The values of one test's run, as a recorded test's {@code ${run.id}}, {@code ${run.date}} and {@code ${run.time}} give
 * them: one per {@link Client}, so one per {@code @UiTest} method. Name what a test creates with {@link #id} to find
 * it again, and to tell it from what other runs made.
 */
public final class RunInfo {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String id;
    private final String date;
    private final String time;

    RunInfo(String id, LocalDateTime start) {
        this.id = id;
        this.date = start.format(DATE);
        this.time = start.format(TIME);
    }

    /** A new run starting now, with a fresh id. */
    static RunInfo start() {
        return new RunInfo(RunValues.newRunId(), LocalDateTime.now());
    }

    /** Eight letters and digits ({@code [a-z0-9]}), new for each run. */
    public String id() { return id; }

    /** The day the run started, {@code yyyy-MM-dd}, local time. */
    public String date() { return date; }

    /** When the run started, {@code HH:mm:ss}, local time. */
    public String time() { return time; }

    @Override
    public String toString() {
        return id + " " + date + " " + time;
    }
}
