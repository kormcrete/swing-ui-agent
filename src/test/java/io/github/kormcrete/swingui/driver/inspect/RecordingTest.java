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

    @Test
    void afterDoneThePersonExportsTheRecordingAndThePaletteSaysWhereItWent() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            recording = new Recording(scene.inspector, sink, sinkThread, scene.inspector.overlay(frame.getRootPane()), () -> { });
            recording.start();
        });
        assertThat(recording.bar.labels()).doesNotContain("Export…");
        SwingUtilities.invokeAndWait(recording::done);
        recording.done.get(5, TimeUnit.SECONDS);
        assertThat(recording.bar.labels()).contains("Export…");

        recording.export("OrderUiIT", "com.example").get(5, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(sink.exports).containsExactly("com.example.OrderUiIT");
        assertThat(recording.bar.text()).contains("OrderUiIT.java");

        sink.refuse = "1Order is not a class name";
        recording.export("1Order", "com.example").get(5, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(recording.bar.text()).contains("not exported").contains("1Order is not a class name");
    }

    @Test
    void exportOpensAPanelWithTheAppsPackageAndKeepsItOpenWithTheReasonWhenRefused() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            recording = new Recording(scene.inspector, sink, sinkThread, scene.inspector.overlay(frame.getRootPane()), () -> { });
            recording.start();
            recording.done();
        });
        recording.done.get(5, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(recording::toggleExport);
        ExportPanel panel = eventually(() -> recording.exportPanel);
        assertThat(panel.names()).containsExactly("RecordedUiIT", "com.example");

        sink.refuse = "RecordedUiIT already exists";
        SwingUtilities.invokeAndWait(panel::submit);
        String why = eventually(() -> panel.message().trim().isEmpty() ? null : panel.message());
        assertThat(why).contains("already exists");
        assertThat(recording.exportPanel).isSameAs(panel);

        sink.refuse = null;
        SwingUtilities.invokeAndWait(panel::submit);
        eventually(() -> recording.exportPanel == null ? Boolean.TRUE : null);
        assertThat(sink.exports).containsExactly("com.example.RecordedUiIT");
    }

    /** Polls the EDT until {@code read} gives something, for up to 5 s. */
    private static <T> T eventually(java.util.concurrent.Callable<T> read) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            Object[] got = new Object[1];
            SwingUtilities.invokeAndWait(() -> {
                try { got[0] = read.call(); } catch (Exception e) { throw new RuntimeException(e); }
            });
            if (got[0] != null) {
                @SuppressWarnings("unchecked") T t = (T) got[0];
                return t;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("nothing within 5 s");
    }

    /** A recording kept in a list: what the session does, without a session. */
    private static final class FakeSink implements Inspector.Sink {
        final List<String> steps = Collections.synchronizedList(new ArrayList<String>());
        final List<String> exports = Collections.synchronizedList(new ArrayList<String>());
        volatile String refuse;

        @Override public void begin() { steps.clear(); }
        @Override public Inspector.Result done(Map<String, Object> step) { steps.add(String.valueOf(step.get("action"))); return new Inspector.Result(true, "ok"); }
        @Override public Inspector.Result check(Map<String, Object> step) { return done(step); }
        @Override public List<String> steps() { return new ArrayList<String>(steps); }
        @Override public Locator target(int index) { return null; }
        @Override public Inspector.Result replay(int index) { return new Inspector.Result(true, "ok"); }
        @Override public void truncate(int keep) { while (steps.size() > keep) steps.remove(steps.size() - 1); }
        @Override public void remove(int index) { steps.remove(index); }
        @Override public String exportPackage() { return "com.example"; }
        @Override public Inspector.Result export(String className, String pkg) {
            if (refuse != null) return new Inspector.Result(false, refuse);
            exports.add(pkg + "." + className);
            return new Inspector.Result(true, "wrote src/test/java/" + pkg.replace('.', '/') + "/" + className + ".java");
        }
    }
}
