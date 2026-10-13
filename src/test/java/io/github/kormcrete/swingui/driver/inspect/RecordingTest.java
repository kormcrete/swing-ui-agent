package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.model.Locator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The person starts a recording and ends it; while it runs no test run may take the agent, after Done one may. */
class RecordingTest {

    private final Scene scene = new Scene();
    private final FakeSink sink = new FakeSink();
    private final ExecutorService sinkThread = Executors.newSingleThreadExecutor();
    private final JFrame frame = new JFrame();
    private Recording recording;

    @AfterEach
    void tearDown() throws Exception {
        SwingUtilities.invokeAndWait(() -> { if (recording != null) recording.dispose(); });
        sinkThread.shutdownNow();
        frame.dispose();
    }

    @Test
    void recordingKeepsTestRunsOutUntilThePersonsDone() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            recording = new Recording(scene.inspector, sink, sinkThread, scene.inspector.overlay(frame.getRootPane()), () -> { });
            recording.start();
        });
        assertThat(recording.active()).isTrue();
        assertThat(scene.inspector.engaged()).contains("recording");

        SwingUtilities.invokeAndWait(recording::done);
        List<String> lines = recording.done.get(5, TimeUnit.SECONDS);
        assertThat(lines).isEmpty();
        assertThat(recording.active()).isFalse();
        assertThat(scene.inspector.engaged()).isNull();
    }

    @Test
    void afterDoneThePaletteSaysRecordedAndOffersANewRecording() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            recording = new Recording(scene.inspector, sink, sinkThread, scene.inspector.overlay(frame.getRootPane()), () -> { });
            recording.start();
            recording.done();
        });
        recording.done.get(5, TimeUnit.SECONDS);
        assertThat(recording.bar).isNotNull();
        assertThat(recording.state).isEqualTo(Recording.State.STOPPED);
        // Done twice is still one Done
        SwingUtilities.invokeAndWait(recording::done);
        assertThat(recording.state).isEqualTo(Recording.State.STOPPED);
    }

    /** A recording kept in a list: what the session does, without a session. */
    private static final class FakeSink implements Inspector.Sink {
        final List<String> steps = Collections.synchronizedList(new ArrayList<String>());

        @Override public void begin() { steps.clear(); }
        @Override public Inspector.Result done(Map<String, Object> step) { steps.add(String.valueOf(step.get("action"))); return new Inspector.Result(true, "ok"); }
        @Override public Inspector.Result check(Map<String, Object> step) { return done(step); }
        @Override public List<String> steps() { return new ArrayList<String>(steps); }
        @Override public Locator target(int index) { return null; }
        @Override public Inspector.Result replay(int index) { return new Inspector.Result(true, "ok"); }
        @Override public void truncate(int keep) { while (steps.size() > keep) steps.remove(steps.size() - 1); }
        @Override public void remove(int index) { steps.remove(index); }
    }
}
