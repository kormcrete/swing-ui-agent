package io.github.kormcrete.swingui.driver.inspect;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
    private final JDialog dialog = new JDialog(frame, true);
    private Recording recording;

    @AfterEach
    void tearDown() throws Exception {
        SwingUtilities.invokeAndWait(() -> { if (recording != null) recording.dispose(); });
        sinkThread.shutdownNow();
        dialog.dispose();
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
    void doneStopsTheSessionsRecording() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            recording = new Recording(scene.inspector, sink, sinkThread, scene.inspector.overlay(frame.getRootPane()), () -> { });
            recording.start();
        });
        assertThat(sink.log).doesNotContain("end");
        SwingUtilities.invokeAndWait(recording::done);
        recording.done.get(5, TimeUnit.SECONDS);
        assertThat(sink.log).containsOnlyOnce("end");
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

    /** Done, then Export…: the panel as the person sees it. */
    private ExportPanel exportPanelAfterDone() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            recording = new Recording(scene.inspector, sink, sinkThread, scene.inspector.overlay(frame.getRootPane()), () -> { });
            recording.start();
            recording.done();
        });
        recording.done.get(5, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(recording::toggleExport);
        return eventually(() -> recording.exportPanel);
    }

    @Test
    void exportOffersAJavaTestOrARecordedTest() throws Exception {
        ExportPanel panel = exportPanelAfterDone();
        assertThat(panel.kind()).isEqualTo(ExportPanel.JAVA);
        assertThat(panel.names()).containsExactly("RecordedUiIT", "com.example");
        assertThat(panel.actionLabel()).isEqualTo("Export");

        SwingUtilities.invokeAndWait(() -> panel.kind(ExportPanel.RECORDED));
        assertThat(panel.kind()).isEqualTo(ExportPanel.RECORDED);
        assertThat(panel.recordedFields()).containsExactly("recorded-test", sink.recordedFolder.toString());
        assertThat(panel.actionLabel()).isEqualTo("Export");

        SwingUtilities.invokeAndWait(panel::submit);
        eventually(() -> recording.exportPanel == null ? Boolean.TRUE : null);
        assertThat(sink.recordedExports).containsExactly(sink.recordedFolder.resolve("recorded-test.json").toString());
        assertThat(sink.exports).isEmpty();
        assertThat(recording.bar.text()).contains("exported").contains("recorded-test.json");

        // the kind used last is where the panel opens next time
        SwingUtilities.invokeAndWait(recording::toggleExport);
        ExportPanel again = eventually(() -> recording.exportPanel);
        assertThat(again.kind()).isEqualTo(ExportPanel.RECORDED);
    }

    @Test
    void aFolderThatCannotBeWrittenSaysSoAndOpensChoose() throws Exception {
        ExportPanel panel = exportPanelAfterDone();
        List<java.nio.file.Path> asked = Collections.synchronizedList(new java.util.ArrayList<java.nio.file.Path>());
        java.nio.file.Path other = sink.recordedFolder.resolveSibling("elsewhere");
        SwingUtilities.invokeAndWait(() -> {
            panel.kind(ExportPanel.RECORDED);
            panel.chooser((start, picked) -> asked.add(start));
        });
        sink.refuseFolder = true;
        SwingUtilities.invokeAndWait(panel::submit);
        String why = eventually(() -> panel.message().trim().isEmpty() ? null : panel.message());
        assertThat(why).contains("Cannot write").contains("choose another folder");
        assertThat(recording.exportPanel).isSameAs(panel);
        assertThat(asked).containsExactly(sink.recordedFolder);
        assertThat(sink.recordedExports).isEmpty();

        // the person picks another folder: the field takes it
        SwingUtilities.invokeAndWait(() -> panel.chooser((start, picked) -> picked.accept(other)));
        SwingUtilities.invokeAndWait(panel::choose);
        assertThat(panel.recordedFields()[1]).isEqualTo(other.toString());
    }

    @Test
    void withARecordedTestLoadedThePanelOpensOnRecordedTestAndSaysSave() throws Exception {
        sink.loaded = sink.recordedFolder.resolve("place-order.json");
        ExportPanel panel = exportPanelAfterDone();
        assertThat(panel.kind()).isEqualTo(ExportPanel.RECORDED);
        assertThat(panel.recordedFields()).containsExactly("place-order", sink.recordedFolder.toString());
        assertThat(panel.actionLabel()).isEqualTo("Save");

        SwingUtilities.invokeAndWait(() -> panel.testName("add-to-cart"));
        assertThat(panel.actionLabel()).isEqualTo("Export");
        SwingUtilities.invokeAndWait(() -> panel.testName("place-order.json"));
        assertThat(panel.actionLabel()).isEqualTo("Save");
        SwingUtilities.invokeAndWait(() -> panel.kind(ExportPanel.JAVA));
        assertThat(panel.actionLabel()).isEqualTo("Export");
    }

    // ---- the palette follows the open modal dialog --------------------------------------------------------------

    /** The inspector with the palette in the (unshown) frame, as the person sees it before a dialog opens. EDT. */
    private Overlay paletteInFrame() {
        Scene.size(frame, 800, 600);
        Scene.size(dialog, 500, 300);
        scene.inspector.install(sink);
        scene.inspector.roots = () -> Collections.singletonList(frame.getRootPane());
        return showIn(frame.getRootPane());
    }

    /** The palette's window is now {@code root}. EDT. */
    private Overlay showIn(JRootPane root) {
        scene.inspector.paletteRoot = () -> root;
        scene.inspector.attachPalette();
        return scene.inspector.overlay(root);
    }

    /** ● Record pressed in the frame's palette: the recording the inspector started. */
    private Recording recordInFrame() throws Exception {
        SwingUtilities.invokeAndWait(() -> paletteInFrame().bar().press("● Record"));
        recording = eventually(scene.inspector::recording);
        return recording;
    }

    @Test
    void aRecordingStartedWhileThePaletteMovesStartsWhereThePaletteNowIs() throws Exception {
        // ● Record pressed, then - while the session is busy with an agent step - a modal dialog opens
        sink.onBegin = () -> {
            try {
                SwingUtilities.invokeAndWait(() -> showIn(dialog.getRootPane()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        recordInFrame();
        SwingUtilities.invokeAndWait(() -> {
            Overlay inDialog = scene.inspector.overlay(dialog.getRootPane());
            assertThat(recording.home).isSameAs(inDialog);
            assertThat(inDialog.bar()).isSameAs(recording.bar);
            assertThat(recording.bar.getParent()).isSameAs(dialog.getLayeredPane());
        });
    }

    @Test
    void theRecordingFollowsThePaletteIntoTheDialogWithItsStepsPanel() throws Exception {
        recordInFrame();
        SwingUtilities.invokeAndWait(() -> {
            Overlay inFrame = scene.inspector.overlay(frame.getRootPane());
            recording.toggleSteps();
            Bar active = recording.bar;
            assertThat(inFrame.bar()).isSameAs(active);
            assertThat(inFrame.holds(recording.panel)).isTrue();

            Overlay inDialog = showIn(dialog.getRootPane());

            assertThat(recording.home).isSameAs(inDialog);
            assertThat(inDialog.bar()).isSameAs(active);
            assertThat(active.getParent()).isSameAs(dialog.getLayeredPane());
            assertThat(recording.panel.getParent()).isSameAs(dialog.getLayeredPane());
            assertThat(inFrame.bar()).isNull();
            assertThat(inFrame.holds(recording.panel)).isFalse();
        });
    }

    @Test
    void checkPressedInTheDialogCoversTheDialog() throws Exception {
        recordInFrame();
        SwingUtilities.invokeAndWait(() -> {
            scene.inspector.roots = () -> Arrays.asList(frame.getRootPane(), dialog.getRootPane());
            Overlay inDialog = showIn(dialog.getRootPane());

            inDialog.bar().press("Check");

            assertThat(recording.state).isEqualTo(Recording.State.CHECKING);
            assertThat(scene.inspector.overlay(frame.getRootPane()).catching()).isTrue();
            assertThat(inDialog.catching()).isTrue();
        });
    }

    @Test
    void aDialogThatOpensInCheckModeIsCoveredToo() throws Exception {
        recordInFrame();
        SwingUtilities.invokeAndWait(() -> {
            recording.mode(true);
            assertThat(scene.inspector.overlay(frame.getRootPane()).catching()).isTrue();

            scene.inspector.roots = () -> Arrays.asList(frame.getRootPane(), dialog.getRootPane());
            Overlay inDialog = showIn(dialog.getRootPane());

            assertThat(recording.home).isSameAs(inDialog);
            assertThat(inDialog.catching()).isTrue();
        });
    }

    @Test
    void theReplayBarFollowsTheDialog() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Overlay inFrame = scene.inspector.overlay(frame.getRootPane());
            Overlay inDialog = scene.inspector.overlay(dialog.getRootPane());
            recording = new Recording(scene.inspector, sink, sinkThread, inFrame, () -> { });
            recording.start();
            recording.toggleSteps();
            Bar replayBar = new Bar("Replaying", "").button("Stop", Overlay.NO, () -> { });
            inFrame.bar(replayBar);
            inFrame.controlsAway(true);
            recording.replaying = true;

            recording.rehome(inDialog);

            assertThat(recording.home).isSameAs(inDialog);
            assertThat(inDialog.bar()).isSameAs(replayBar);
            assertThat(replayBar.getParent()).isSameAs(dialog.getLayeredPane());
            assertThat(inFrame.bar()).isNull();
            // the steps panel stays out of the way until the replay ends
            assertThat(inDialog.holds(recording.panel)).isTrue();
            assertThat(recording.panel.getParent()).isNull();
            recording.replaying = false;
        });
    }

    /** A "Next" button where the bar goes by default in the dialog: its bottom right corner. EDT. */
    private Rectangle nextInDialog(Bar bar) {
        JLayeredPane layers = dialog.getLayeredPane();
        Container content = dialog.getContentPane();
        content.setLayout(null);
        Dimension size = bar.getPreferredSize();
        Rectangle corner = Overlay.placeBar(size.width, size.height, layers.getWidth(), layers.getHeight(), null,
                Collections.<Rectangle>emptyList());
        JButton next = new JButton("Next");
        next.setBounds(SwingUtilities.convertRectangle(layers, corner, content));
        content.add(next);
        return corner;
    }

    @Test
    void barsThatAppearInTheDialogStayOffItsButtonsToo() throws Exception {
        Rectangle[] next = new Rectangle[1];
        SwingUtilities.invokeAndWait(() -> {
            Bar idle = paletteInFrame().bar();
            next[0] = nextInDialog(idle);
            showIn(dialog.getRootPane()).bar().press("● Record");
        });
        recording = eventually(scene.inspector::recording);
        SwingUtilities.invokeAndWait(() -> {
            Overlay inDialog = scene.inspector.overlay(dialog.getRootPane());
            assertThat(inDialog.bar()).isSameAs(recording.bar);
            assertThat(recording.bar.getBounds().intersects(next[0])).isFalse();

            recording.done();

            assertThat(inDialog.bar()).isSameAs(recording.bar);
            assertThat(recording.bar.labels()).contains("Export…");
            assertThat(recording.bar.getBounds().intersects(next[0])).isFalse();
        });
    }

    @Test
    void aReplayFollowsTheDialogThroughTheInspector() throws Exception {
        recordInFrame();
        SwingUtilities.invokeAndWait(() -> {
            Overlay inFrame = scene.inspector.overlay(frame.getRootPane());
            recording.toggleSteps();
            // as a replay starts: its own bar, the steps panel out of the way
            Bar replayBar = new Bar("Replaying", "").button("Stop", Overlay.NO, () -> { });
            inFrame.bar(replayBar);
            inFrame.controlsAway(true);
            recording.replaying = true;
            // a box of the replay keeps the frame's overlay alive after the palette leaves
            inFrame.mark(new Overlay.Mark(frame.getContentPane(), null, -1, -1, "1/2", Overlay.HOVER, false), null);

            Overlay inDialog = showIn(dialog.getRootPane());

            assertThat(recording.home).isSameAs(inDialog);
            assertThat(inDialog.bar()).isSameAs(replayBar);
            assertThat(replayBar.getParent()).isSameAs(dialog.getLayeredPane());
            assertThat(inDialog.controlsAreAway()).isTrue();
            assertThat(inDialog.holds(recording.panel)).isTrue();
            assertThat(recording.panel.getParent()).isNull();
            assertThat(inFrame.bar()).isNull();
            assertThat(inFrame.controlsAreAway()).isFalse();
            recording.replaying = false;
        });
    }

    // ---- Load recorded test…, Run -------------------------------------------------------------------------------------------

    private static final String ADD_TO_CART = "add-to-cart";

    /** Two recorded tests in the fake folder - one that cannot be read - and the steps the readable one loads. */
    private void twoRecordedTests() {
        java.nio.file.Path folder = sink.recordedFolder;
        sink.recordedTests = Arrays.asList(
                new Inspector.RecordedTestEntry(ADD_TO_CART, "Adds a product to the cart", 2, null, folder.resolve("add-to-cart.json")),
                new Inspector.RecordedTestEntry("broken", null, 0, folder.resolve("broken.json") + ": line 1: unexpected end",
                        folder.resolve("broken.json")));
        sink.loadedSteps = Arrays.asList("click(button 'Add to cart')", "click(button 'Checkout…')");
    }

    /** Load recorded test… pressed on the idle palette in the frame: the panel as the person sees it. */
    private RecordedTestsPanel openLoadPanel() throws Exception {
        SwingUtilities.invokeAndWait(() -> paletteInFrame().bar().press("Load recorded test…"));
        return eventually(() -> scene.inspector.testsPanel);
    }

    /** add-to-cart loaded from the panel: the loaded recording. */
    private Recording loadAddToCart() throws Exception {
        twoRecordedTests();
        RecordedTestsPanel panel = openLoadPanel();
        SwingUtilities.invokeAndWait(() -> {
            panel.select(0);
            panel.load();
        });
        recording = eventually(() -> scene.inspector.recording() != null && scene.inspector.recording().panel != null
                && scene.inspector.recording().panel.rows().size() == 2 ? scene.inspector.recording() : null);
        return recording;
    }

    /** Presses ▶ Run in the steps panel at {@code speed} (0 fast … 3 step by step). */
    private void run(Recording r, int speed) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            r.panel.speed(speed);
            r.panel.press("▶ Run");
        });
    }

    @Test
    void loadRecordedTestOpensTheFolderWithItsRecordedTests() throws Exception {
        twoRecordedTests();
        RecordedTestsPanel panel = openLoadPanel();
        SwingUtilities.invokeAndWait(() -> {
            assertThat(panel.title()).isEqualTo("Load a recorded test");
            assertThat(panel.folder()).isEqualTo(sink.recordedFolder.toString());
            assertThat(scene.inspector.overlay(frame.getRootPane()).holds(panel)).isTrue();
            assertThat(panel.rows()).hasSize(2);
            assertThat(panel.rows().get(0)).contains(ADD_TO_CART).contains("Adds a product to the cart").contains("2 steps");
            assertThat(panel.rows().get(1)).contains("broken").contains("unexpected end");
            assertThat(panel.loadable(0)).isTrue();
            assertThat(panel.loadable(1)).isFalse();
            // the unreadable one cannot be loaded
            panel.select(1);
            panel.load();
        });
        assertThat(sink.listed).containsExactly(sink.recordedFolder);
        Thread.sleep(100);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(scene.inspector.testsPanel).isSameAs(panel);
            assertThat(scene.inspector.recording()).isNull();
        });
        assertThat(sink.loaded).isNull();

        // Choose… lists the recorded tests of another folder
        java.nio.file.Path other = sink.recordedFolder.resolveSibling("other-recorded tests");
        SwingUtilities.invokeAndWait(() -> {
            panel.chooser((start, picked) -> picked.accept(other));
            panel.choose();
        });
        eventually(() -> sink.listed.contains(other) ? Boolean.TRUE : null);
        SwingUtilities.invokeAndWait(() -> assertThat(panel.folder()).isEqualTo(other.toString()));

        // Cancel closes it
        SwingUtilities.invokeAndWait(panel::cancel);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(scene.inspector.testsPanel).isNull();
            assertThat(scene.inspector.overlay(frame.getRootPane()).holds(panel)).isFalse();
        });
    }

    @Test
    void theRecordedTestsPanelFollowsThePaletteIntoTheDialog() throws Exception {
        twoRecordedTests();
        RecordedTestsPanel panel = openLoadPanel();
        SwingUtilities.invokeAndWait(() -> {
            Overlay inDialog = showIn(dialog.getRootPane());
            assertThat(inDialog.holds(panel)).isTrue();
            assertThat(panel.getParent()).isSameAs(dialog.getLayeredPane());
            assertThat(scene.inspector.overlay(frame.getRootPane()).holds(panel)).isFalse();
        });
    }

    @Test
    void loadRecordedTestIsOfferedOnlyWhileNothingIsRecordedOrReplayed() throws Exception {
        recordInFrame();
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.bar.labels()).containsExactly("Record", "Check", "Steps", "Done");
            recording.done();
            assertThat(recording.bar.labels()).containsExactly("● Record", "Load…", "Steps", "Export…");
        });
    }

    @Test
    void loadingARecordedTestShowsItsStepsReadyToRun() throws Exception {
        Recording r = loadAddToCart();
        SwingUtilities.invokeAndWait(() -> {
            assertThat(r.state).isEqualTo(Recording.State.STOPPED);
            assertThat(r.bar.title()).isEqualTo("Loaded");
            assertThat(r.bar.text()).isEqualTo("add-to-cart - 2 steps");
            assertThat(r.bar.labels()).containsExactly("● Record", "Load…", "Steps", "Export…");
            assertThat(scene.inspector.overlay(frame.getRootPane()).bar()).isSameAs(r.bar);
            assertThat(r.panel.replayAllLabel()).isEqualTo("▶ Run");
            assertThat(r.panel.rows()).containsExactly("1  ●  click(button 'Add to cart')", "2  ●  click(button 'Checkout…')");
            assertThat(scene.inspector.testsPanel).isNull();
            assertThat(scene.inspector.engaged()).isNull();
        });
        assertThat(sink.loaded).isEqualTo(sink.recordedFolder.resolve("add-to-cart.json"));
    }

    @Test
    void aRefusedLoadSaysWhyAndKeepsThePanel() throws Exception {
        twoRecordedTests();
        sink.refuseLoad = "add-to-cart cannot be loaded into the recording: loginAs is not the first step";
        RecordedTestsPanel panel = openLoadPanel();
        SwingUtilities.invokeAndWait(() -> {
            panel.select(0);
            panel.load();
        });
        String why = eventually(() -> panel.message().trim().isEmpty() ? null : panel.message());
        assertThat(why).contains("cannot be loaded").contains("loginAs");
        SwingUtilities.invokeAndWait(() -> {
            assertThat(scene.inspector.testsPanel).isSameAs(panel);
            assertThat(scene.inspector.recording()).isNull();
        });
    }

    @Test
    void runResetsFirstThenReplaysEveryStep() throws Exception {
        Recording r = loadAddToCart();
        run(r, 0);
        eventually(() -> !r.replaying && sink.log.size() == 3 ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("reset", "replay 0", "replay 1");
        SwingUtilities.invokeAndWait(() -> {
            assertThat(r.bar.text()).isEqualTo("replayed steps 1-2: all passed");
            assertThat(scene.inspector.overlay(frame.getRootPane()).bar()).isSameAs(r.bar);
            assertThat(r.panel.replayAllLabel()).isEqualTo("▶ Run");
        });
    }

    @Test
    void aFailedResetRunsNoStep() throws Exception {
        Recording r = loadAddToCart();
        sink.reset = new Inspector.Result(false, "this session has no reset");
        run(r, 0);
        eventually(() -> !r.replaying && !sink.log.isEmpty() ? Boolean.TRUE : null);
        Thread.sleep(100);
        assertThat(sink.log).containsExactly("reset");
        SwingUtilities.invokeAndWait(() -> {
            assertThat(r.bar.text()).isEqualTo("✗ reset failed: this session has no reset");
            assertThat(scene.inspector.driving()).isNull();
            assertThat(scene.inspector.engaged()).isNull();
        });
    }

    @Test
    void stepByStepWaitsForNext() throws Exception {
        Recording r = loadAddToCart();
        run(r, 3);
        eventually(() -> sink.log.contains("reset") ? Boolean.TRUE : null);
        Thread.sleep(200);
        assertThat(sink.log).containsExactly("reset");
        r.next.release();
        eventually(() -> sink.log.contains("replay 0") ? Boolean.TRUE : null);
        Thread.sleep(200);
        assertThat(sink.log).containsExactly("reset", "replay 0");
        r.next.release();
        eventually(() -> !r.replaying ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("reset", "replay 0", "replay 1");
    }

    @Test
    void whileARecordedTestRunsOtherDriversAreBusy() throws Exception {
        Recording r = loadAddToCart();
        SwingUtilities.invokeAndWait(() -> assertThat(scene.inspector.driving()).isNull());
        run(r, 3);
        eventually(() -> sink.log.contains("reset") ? Boolean.TRUE : null);
        assertThat(scene.inspector.driving()).isEqualTo("a person is running add-to-cart in the app");
        // no test run takes the agent meanwhile either
        assertThat(scene.inspector.engaged()).isEqualTo("a person is running add-to-cart in the app");
        r.next.release();
        r.next.release();
        eventually(() -> !r.replaying ? Boolean.TRUE : null);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(scene.inspector.driving()).isNull();
            assertThat(scene.inspector.engaged()).isNull();
        });

        // a replay of the person's own recording keeps them out too
        SwingUtilities.invokeAndWait(() -> {
            r.panel.speed(3);
            r.panel.select(0);
            r.panel.press("▶ From selected");
        });
        eventually(() -> r.replaying ? Boolean.TRUE : null);
        assertThat(scene.inspector.driving()).isEqualTo("a person is replaying their recording in the app");
        r.next.release();
        r.next.release();
        eventually(() -> !r.replaying ? Boolean.TRUE : null);
        assertThat(scene.inspector.driving()).isNull();
    }

    @Test
    void aReplayThatBreaksEndsAndLetsEveryoneBackIn() throws Exception {
        Recording r = loadAddToCart();
        sink.replayThrows = new IllegalStateException("the session went away");
        run(r, 0);
        eventually(() -> !r.replaying ? Boolean.TRUE : null);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(r.bar.text()).isEqualTo("✗ replay failed: the session went away");
            assertThat(scene.inspector.overlay(frame.getRootPane()).bar()).isSameAs(r.bar);
            assertThat(scene.inspector.driving()).isNull();
            assertThat(scene.inspector.engaged()).isNull();
        });

        // Run works again afterwards
        sink.replayThrows = null;
        sink.log.clear();
        run(r, 0);
        eventually(() -> !r.replaying && sink.log.size() == 3 ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("reset", "replay 0", "replay 1");
        SwingUtilities.invokeAndWait(() -> assertThat(r.bar.text()).isEqualTo("replayed steps 1-2: all passed"));
    }

    @Test
    void aReplayThatBreaksWithoutAMessageSaysWhatBrokeAndLogsIt() throws Exception {
        Recording r = loadAddToCart();
        sink.replayThrows = new NullPointerException();
        java.io.PrintStream err = System.err;
        java.io.ByteArrayOutputStream logged = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(logged, true));
        try {
            run(r, 0);
            eventually(() -> !r.replaying ? Boolean.TRUE : null);
        } finally {
            System.setErr(err);
        }
        SwingUtilities.invokeAndWait(() -> assertThat(r.bar.text()).isEqualTo("✗ replay failed: NullPointerException"));
        assertThat(logged.toString()).contains("swing-ui-agent inspector: replay failed")
                .contains("java.lang.NullPointerException").contains("\tat ");
    }

    @Test
    void recordAfterALoadStartsFresh() throws Exception {
        Recording loaded = loadAddToCart();
        SwingUtilities.invokeAndWait(() -> loaded.bar.press("● Record"));
        recording = eventually(() -> scene.inspector.recording() != loaded ? scene.inspector.recording() : null);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.state).isEqualTo(Recording.State.RECORDING);
            assertThat(recording.bar.title()).isEqualTo("Recording");
            assertThat(loaded.panel).isNull();
        });
        assertThat(sink.steps).isEmpty();
        assertThat(sink.loaded).isNull();
    }

    @Test
    void loadingSaysItReplacesTheRecording() throws Exception {
        twoRecordedTests();
        RecordedTestsPanel empty = openLoadPanel();
        SwingUtilities.invokeAndWait(() -> {
            assertThat(empty.notice()).isNull();
            empty.cancel();
        });
        sink.person("click(#save)", "click(#new)", "type(#customer, Ada)");
        SwingUtilities.invokeAndWait(() -> scene.inspector.overlay(frame.getRootPane()).bar().press("Load recorded test…"));
        RecordedTestsPanel panel = eventually(() -> scene.inspector.testsPanel);
        SwingUtilities.invokeAndWait(() ->
                assertThat(panel.notice()).isEqualTo("Loading replaces the current recording (3 steps)."));
    }

    // ---- the steps list by step id: who made each step, edits, undo, the agent's changes at once -------------------

    /** A recording with its steps panel open in the frame and its live re-read off: the panel changes only when told. */
    private Recording withSteps() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            recording = new Recording(scene.inspector, sink, sinkThread, scene.inspector.overlay(frame.getRootPane()), () -> { });
            recording.start();
            recording.toggleSteps();
            recording.live.stop();
        });
        settle();
        return recording;
    }

    /** One round through the EDT, the sink thread and the EDT again: what a refresh of the panel takes. */
    private void settle() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
        sinkThread.submit(() -> { }).get(5, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(() -> { });
    }

    private List<String> rows() throws Exception {
        java.util.concurrent.atomic.AtomicReference<List<String>> rows = new java.util.concurrent.atomic.AtomicReference<List<String>>();
        SwingUtilities.invokeAndWait(() -> rows.set(recording.panel.rows()));
        return rows.get();
    }

    @Test
    void theAgentsStepsAreMarkedAsTheAgents() throws Exception {
        sink.person("click(#new)");
        sink.agent("click(#save)", "expect(#status text 'saved')");
        withSteps();
        List<String> rows = rows();
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).isEqualTo("1  ●  click(#new)").doesNotContain("agent: ");
        assertThat(rows.get(1)).isEqualTo("2  ●  agent: click(#save)");
        // a check stays indented under its action, numbered as its step's, and says whose it is
        assertThat(rows.get(2)).isEqualTo("      └ 2.1  ●  agent: check: #status text 'saved'");
    }

    @Test
    void anEditedStepShowsTheEditMarkUntilItIsReplayed() throws Exception {
        sink.person("click(#new)");
        sink.edited("click(#save)");
        withSteps();
        assertThat(rows()).containsExactly("1  ●  click(#new)", "2  ✎  agent: click(#save)");

        SwingUtilities.invokeAndWait(() -> {
            recording.panel.speed(0);
            recording.panel.press("▶ Replay all");
        });
        eventually(() -> !recording.replaying && recording.panel.rows().get(1).startsWith("2  ✓") ? Boolean.TRUE : null);
        assertThat(rows()).containsExactly("1  ✓  click(#new)", "2  ✓  agent: click(#save)");
    }

    @Test
    void aDeleteHitsTheStepThePersonSawEvenWhenTheAgentInsertedBeforeIt() throws Exception {
        sink.person("click(#a)", "click(#b)", "click(#c)");
        int b = sink.steps.get(1).id;
        withSteps();
        assertThat(rows()).hasSize(3);
        // the agent inserts a step at the top; the panel has not shown it yet
        sink.insert(0, "click(#x)");

        SwingUtilities.invokeAndWait(() -> {
            recording.panel.select(1);
            recording.panel.press("✕ Delete");
        });
        // the panel shows the result of the delete: the recording re-read after it
        eventually(() -> recording.panel.rows().get(0).contains("#x") ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("delete " + b);
        assertThat(sink.lines()).containsExactly("click(#x)", "click(#a)", "click(#c)");
        assertThat(rows()).containsExactly("1  ✎  agent: click(#x)", "2  ●  click(#a)", "3  ●  click(#c)");
    }

    @Test
    void theSelectionStaysOnTheStepWhenTheAgentChangesTheRecordingAboveIt() throws Exception {
        sink.person("click(#a)", "click(#b)", "click(#c)");
        int b = sink.steps.get(1).id;
        withSteps();
        SwingUtilities.invokeAndWait(() -> recording.panel.select(1));

        // the agent inserts a step at the top and the panel re-reads it before the person presses Delete
        sink.insert(0, "click(#x)");
        settleRefresh();
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.panel.selected()).isEqualTo(2);
            assertThat(recording.panel.idAt(recording.panel.selected())).isEqualTo(b);
        });
        // the 500 ms backstop's refresh changes nothing either
        settleRefresh();
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.panel.selected()).isEqualTo(2);
            recording.panel.press("✕ Delete");
        });
        eventually(() -> recording.panel.rows().size() == 3 && !recording.panel.rows().get(2).contains("#b")
                ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("delete " + b);
        assertThat(sink.lines()).containsExactly("click(#x)", "click(#a)", "click(#c)");
    }

    @Test
    void aSelectedStepTheAgentDeletesLeavesNothingSelected() throws Exception {
        sink.person("click(#a)", "click(#b)", "click(#c)");
        withSteps();
        SwingUtilities.invokeAndWait(() -> recording.panel.select(1));

        sink.steps.remove(1);
        settleRefresh();
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.panel.rows()).containsExactly("1  ●  click(#a)", "2  ●  click(#c)");
            assertThat(recording.panel.selected()).isEqualTo(-1);
            // Delete with nothing selected deletes nothing
            recording.panel.press("✕ Delete");
        });
        settle();
        assertThat(sink.log).isEmpty();
    }

    @Test
    void redoFromARowCutsAtThatStepsId() throws Exception {
        sink.person("click(#a)", "click(#b)", "click(#c)");
        int b = sink.steps.get(1).id;
        withSteps();
        sink.insert(0, "click(#x)");

        SwingUtilities.invokeAndWait(() -> {
            recording.panel.speed(0);
            recording.panel.select(1);
            recording.panel.press("↺ Redo from selected");
        });
        // the steps before the cut are replayed from a reset - x and a, as the recording now is - and the person records on
        eventually(() -> sink.log.contains("resume") ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("cut " + b, "reset", "replay 0", "replay 1", "resume");
        assertThat(sink.lines()).containsExactly("click(#x)", "click(#a)");
    }

    @Test
    void replayAllResetsFirstLikeTheTestWill() throws Exception {
        sink.person("click(#a)", "click(#b)");
        withSteps();
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.panel.replayAllTip()).isEqualTo("from a reset, as the exported test runs");
            recording.panel.speed(0);
            recording.panel.press("▶ Replay all");
        });
        eventually(() -> !recording.replaying && sink.log.size() == 3 ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("reset", "replay 0", "replay 1");
        SwingUtilities.invokeAndWait(() -> assertThat(recording.bar.text()).isEqualTo("replayed steps 1-2: all passed"));
    }

    @Test
    void replayAllShowsTheResetOnTheBarAndStepByStepWaitsAfterIt() throws Exception {
        sink.person("click(#a)", "click(#b)");
        withSteps();
        CountDownLatch inReset = new CountDownLatch(1);
        CountDownLatch goOn = new CountDownLatch(1);
        sink.onReset = () -> {
            inReset.countDown();
            try { goOn.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.speed(3);
            recording.panel.press("▶ Replay all");
        });
        assertThat(inReset.await(5, TimeUnit.SECONDS)).isTrue();
        Bar replayBar = eventually(() -> scene.inspector.overlay(frame.getRootPane()).bar() != recording.bar
                ? scene.inspector.overlay(frame.getRootPane()).bar() : null);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(replayBar.title()).isEqualTo("Replaying");
            assertThat(replayBar.text()).isEqualTo("resetting the app …");
            // the reset is no step: no row is marked for it
            assertThat(recording.panel.rows()).containsExactly("1  ●  click(#a)", "2  ●  click(#b)");
        });
        goOn.countDown();
        // step by step: the reset ran, the first step waits for Next
        eventually(() -> replayBar.text().startsWith("1/2") ? Boolean.TRUE : null);
        Thread.sleep(200);
        assertThat(sink.log).containsExactly("reset");
        recording.next.release();
        eventually(() -> sink.log.contains("replay 0") ? Boolean.TRUE : null);
        recording.next.release();
        eventually(() -> !recording.replaying ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("reset", "replay 0", "replay 1");
    }

    @Test
    void fromSelectedOnALaterStepDoesNotReset() throws Exception {
        sink.person("click(#a)", "click(#b)", "click(#c)");
        withSteps();
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.speed(0);
            recording.panel.select(1);
            recording.panel.press("▶ From selected");
        });
        eventually(() -> !recording.replaying && sink.log.size() == 2 ? Boolean.TRUE : null);
        Thread.sleep(100);
        assertThat(sink.log).containsExactly("replay 1", "replay 2");
    }

    @Test
    void fromSelectedOnTheFirstStepResetsToo() throws Exception {
        sink.person("click(#a)", "click(#b)");
        withSteps();
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.speed(0);
            recording.panel.select(0);
            recording.panel.press("▶ From selected");
        });
        eventually(() -> !recording.replaying && sink.log.size() == 3 ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("reset", "replay 0", "replay 1");
    }

    /** A recording of {@code lines} the person ended with Done; the sink's log starts empty. */
    private void doneWith(String... lines) throws Exception {
        sink.person(lines);
        withSteps();
        SwingUtilities.invokeAndWait(recording::done);
        recording.done.get(5, TimeUnit.SECONDS);
        settle();
        sink.log.clear();
    }

    @Test
    void redoResetsReplaysUpToTheStepAndRecordsOn() throws Exception {
        doneWith("click(#a)", "click(#b)", "click(#c)");
        int c = sink.steps.get(2).id;
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.state).isEqualTo(Recording.State.STOPPED);
            recording.panel.speed(0);
            recording.panel.select(2);
            recording.panel.press("↺ Redo from selected");
        });
        eventually(() -> sink.log.contains("resume") ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("cut " + c, "reset", "replay 0", "replay 1", "resume");
        assertThat(sink.lines()).containsExactly("click(#a)", "click(#b)");
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.state).isEqualTo(Recording.State.RECORDING);
            assertThat(recording.bar.text()).isEqualTo("Back at step 2: record the rest.");
        });
    }

    @Test
    void redoFromTheFirstStepResetsAndRecordsFromThere() throws Exception {
        doneWith("click(#a)", "click(#b)");
        int a = sink.steps.get(0).id;
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.speed(0);
            recording.panel.select(0);
            recording.panel.press("↺ Redo from selected");
        });
        eventually(() -> sink.log.contains("resume") ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("cut " + a, "reset", "resume");
        assertThat(sink.lines()).isEmpty();
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.state).isEqualTo(Recording.State.RECORDING);
            assertThat(recording.bar.text()).isEqualTo("Recording cleared: record from here.");
        });
    }

    @Test
    void redoOnALoadedRecordedTestResetsToo() throws Exception {
        Recording r = loadAddToCart();
        int second = sink.steps.get(1).id;
        SwingUtilities.invokeAndWait(() -> {
            r.panel.speed(0);
            r.panel.select(1);
            r.panel.press("↺ Redo from selected");
        });
        eventually(() -> sink.log.contains("resume") ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("cut " + second, "reset", "replay 0", "resume");
        assertThat(sink.lines()).containsExactly("click(button 'Add to cart')");
        SwingUtilities.invokeAndWait(() -> assertThat(r.state).isEqualTo(Recording.State.RECORDING));
    }

    /** A live recording of {@code lines}, its steps panel open; the sink's log starts empty. */
    private void recordingWith(String... lines) throws Exception {
        sink.person(lines);
        withSteps();
        sink.log.clear();
    }

    /** Presses ↺ Redo from selected on {@code row} at Fast. */
    private void redo(int row) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.speed(0);
            recording.panel.select(row);
            recording.panel.press("↺ Redo from selected");
        });
    }

    @Test
    void aFailedResetDuringARedoOfALiveRecordingStopsIt() throws Exception {
        recordingWith("click(#a)", "click(#b)", "click(#c)");
        int b = sink.steps.get(1).id;
        sink.reset = new Inspector.Result(false, "this session has no reset");
        SwingUtilities.invokeAndWait(() -> assertThat(recording.state).isEqualTo(Recording.State.RECORDING));
        redo(1);
        eventually(() -> !recording.replaying && sink.log.contains("end") ? Boolean.TRUE : null);
        settle();
        Thread.sleep(100);
        // the cut stays; the person's clicks on a screen nobody reset do not join the recording after it
        assertThat(sink.log).containsExactly("cut " + b, "reset", "end");
        assertThat(sink.lines()).containsExactly("click(#a)");
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.state).isEqualTo(Recording.State.STOPPED);
            assertThat(recording.bar.text()).isEqualTo("✗ reset failed: this session has no reset - recording stopped");
            assertThat(scene.inspector.overlay(frame.getRootPane()).bar()).isSameAs(recording.bar);
            assertThat(scene.inspector.engaged()).isNull();
        });
    }

    @Test
    void aFailedStepDuringARedoOfALiveRecordingStopsIt() throws Exception {
        recordingWith("click(#a)", "click(#b)", "click(#c)");
        int c = sink.steps.get(2).id;
        sink.failStep = 1;
        redo(2);
        eventually(() -> !recording.replaying && sink.log.contains("end") ? Boolean.TRUE : null);
        settle();
        Thread.sleep(100);
        assertThat(sink.log).containsExactly("cut " + c, "reset", "replay 0", "replay 1", "end");
        assertThat(sink.lines()).containsExactly("click(#a)", "click(#b)");
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.state).isEqualTo(Recording.State.STOPPED);
            assertThat(recording.bar.text()).isEqualTo("✗ step 2 failed: no button 'b' - recording stopped");
        });
    }

    @Test
    void theStopDuringTheResetOfARedoFromTheFirstStepStopsTheRecording() throws Exception {
        recordingWith("click(#a)", "click(#b)");
        int a = sink.steps.get(0).id;
        CountDownLatch inReset = new CountDownLatch(1);
        CountDownLatch goOn = new CountDownLatch(1);
        sink.onReset = () -> {
            inReset.countDown();
            try { goOn.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        redo(0);
        assertThat(inReset.await(5, TimeUnit.SECONDS)).isTrue();
        Bar replayBar = eventually(() -> scene.inspector.overlay(frame.getRootPane()).bar() != recording.bar
                ? scene.inspector.overlay(frame.getRootPane()).bar() : null);
        SwingUtilities.invokeAndWait(() -> replayBar.press("Stop"));
        goOn.countDown();
        eventually(() -> !recording.replaying && sink.log.contains("end") ? Boolean.TRUE : null);
        settle();
        Thread.sleep(100);
        // nothing was replayed, yet the person's Stop counts: the cut stays and nothing records on
        assertThat(sink.log).containsExactly("cut " + a, "reset", "end");
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.state).isEqualTo(Recording.State.STOPPED);
            assertThat(recording.bar.text()).isEqualTo("■ stopped after the reset - recording stopped");
        });
    }

    @Test
    void aPassingRedoOfALiveRecordingRecordsOn() throws Exception {
        recordingWith("click(#a)", "click(#b)");
        int b = sink.steps.get(1).id;
        redo(1);
        eventually(() -> sink.log.contains("resume") ? Boolean.TRUE : null);
        settle();
        assertThat(sink.log).containsExactly("cut " + b, "reset", "replay 0", "resume");
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.state).isEqualTo(Recording.State.RECORDING);
            assertThat(recording.bar.text()).isEqualTo("Back at step 1: record the rest.");
        });
    }

    @Test
    void aFailedResetBeforeARedoKeepsTheCutAndRecordsNothing() throws Exception {
        doneWith("click(#a)", "click(#b)", "click(#c)");
        int b = sink.steps.get(1).id;
        sink.reset = new Inspector.Result(false, "this session has no reset");
        sink.undoLabel = "cut from step 2 (redo) by the person";
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.speed(0);
            recording.panel.select(1);
            recording.panel.press("↺ Redo from selected");
        });
        eventually(() -> !recording.replaying && sink.log.contains("reset") ? Boolean.TRUE : null);
        settle();
        Thread.sleep(100);
        assertThat(sink.log).containsExactly("cut " + b, "reset");
        // the cut stays, and Undo is there to bring it back
        assertThat(sink.lines()).containsExactly("click(#a)");
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.state).isEqualTo(Recording.State.STOPPED);
            assertThat(recording.bar.text()).isEqualTo("✗ reset failed: this session has no reset");
            assertThat(recording.panel.undoEnabled()).isTrue();
            assertThat(scene.inspector.driving()).isNull();
            assertThat(scene.inspector.engaged()).isNull();
        });
    }

    @Test
    void undoInThePanelUndoesAndSaysWhat() throws Exception {
        sink.person("click(#a)", "click(#c)");
        sink.undoLabel = "delete of step 2 by the person";
        withSteps();
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.panel.undoEnabled()).isTrue();
            assertThat(recording.panel.undoTip()).isEqualTo("delete of step 2 by the person");
            recording.panel.press("↶ Undo");
        });
        eventually(() -> !recording.panel.undoEnabled() ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("undo");
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.bar.text()).isEqualTo("undid: delete of step 2 by the person");
            // nothing left to undo
            assertThat(recording.panel.undoEnabled()).isFalse();
        });

        // a refused undo says why on the bar
        sink.undoLabel = "cut from step 2 (redo) by the person";
        sink.refuseUndo = "the agent is replaying the recording";
        settleRefresh();
        SwingUtilities.invokeAndWait(() -> recording.panel.press("↶ Undo"));
        eventually(() -> recording.bar.text().contains("replaying") ? Boolean.TRUE : null);
        SwingUtilities.invokeAndWait(() ->
                assertThat(recording.bar.text()).isEqualTo("✗ not undone: the agent is replaying the recording"));
    }

    /** The panel re-reads the recording now, as the live timer would. */
    private void settleRefresh() throws Exception {
        SwingUtilities.invokeAndWait(() -> recording.changed(null));
        settle();
    }

    @Test
    void anAgentChangeShowsAtOnceWithItsNotice() throws Exception {
        Inspector before = Inspector.swap(scene.inspector);
        try {
            recordInFrame();
            sink.person("click(#a)", "click(#b)", "click(#c)");
            SwingUtilities.invokeAndWait(() -> {
                recording.toggleSteps();
                recording.live.stop();
            });
            eventually(() -> recording.panel.rows().size() == 3 ? Boolean.TRUE : null);

            // the agent deletes step 2: the session tells the palette, which re-reads at once (no timer runs)
            sink.steps.remove(1);
            Inspector.recordingChanged("the agent deleted step 2");
            eventually(() -> recording.panel.rows().size() == 2 ? Boolean.TRUE : null);
            SwingUtilities.invokeAndWait(() -> {
                assertThat(recording.panel.rows()).containsExactly("1  ●  click(#a)", "2  ●  click(#c)");
                assertThat(recording.bar.text()).isEqualTo("the agent deleted step 2");
            });
        } finally {
            Inspector.swap(before);
        }
    }

    @Test
    void anAgentsNoticeGivesTheBarBackAfterFourSeconds() throws Exception {
        withSteps();
        SwingUtilities.invokeAndWait(() -> {
            recording.bar.text("Use the app: every step is recorded.");
            recording.changed("the agent deleted step 2");
            assertThat(recording.bar.text()).isEqualTo("the agent deleted step 2");
            // a second notice while the first shows: the text before both comes back
            recording.changed("the agent inserted at step 1");
            assertThat(recording.noteTimer.getInitialDelay()).isEqualTo(Recording.NOTE_MS).isEqualTo(4000);
            recording.noteTimer.getActionListeners()[0].actionPerformed(null);
            assertThat(recording.bar.text()).isEqualTo("Use the app: every step is recorded.");
        });
    }

    // ---- the palette follows what the agent does --------------------------------------------------------------

    /** Runs {@code body} with this scene's inspector as the JVM's one: the agent's calls reach it. */
    private void asTheInspector(Body body) throws Exception {
        Inspector before = Inspector.swap(scene.inspector);
        try {
            body.run();
        } finally {
            Inspector.swap(before);
        }
    }

    private interface Body {
        void run() throws Exception;
    }

    /** The agent started a recording: the palette's recording, once it shows it. */
    private Recording agentStarts() throws Exception {
        Recording old = scene.inspector.recording();
        Inspector.agentStarted();
        recording = eventually(() -> scene.inspector.recording() != old ? scene.inspector.recording() : null);
        return recording;
    }

    @Test
    void whenTheAgentStartsThePaletteRecordsAndSaysWho() throws Exception {
        asTheInspector(() -> {
            // Load recorded test… is open: the agent's start closes it
            openLoadPanel();
            agentStarts();
            SwingUtilities.invokeAndWait(() -> {
                assertThat(scene.inspector.testsPanel).isNull();
                assertThat(recording.state).isEqualTo(Recording.State.RECORDING);
                assertThat(recording.bar.title()).isEqualTo("Recording");
                assertThat(recording.bar.text()).isEqualTo("The agent started this recording: use the app, your steps join it.");
                assertThat(recording.bar.labels()).containsExactly("Record", "Check", "Steps", "Done");
                assertThat(scene.inspector.overlay(frame.getRootPane()).bar()).isSameAs(recording.bar);
                assertThat(recording.listening()).isTrue();
                assertThat(scene.inspector.engaged()).isEqualTo(
                        "a recording is running in the app (the agent started it): Done in its palette or the agent ends it");
            });
            settle();
            assertThat(sink.log).doesNotContain("begin");
        });
    }

    @Test
    void whenTheAgentStopsThePaletteShowsItStopped() throws Exception {
        asTheInspector(() -> {
            SwingUtilities.invokeAndWait(this::paletteInFrame);
            agentStarts();
            sink.agent("click(#new)", "click(#save)");
            Inspector.agentStopped();
            // a test run need not wait for the palette to hear it: the session stopped already
            assertThat(scene.inspector.engaged()).isNull();
            recording.done.get(5, TimeUnit.SECONDS);
            String text = eventually(() -> recording.bar.text().startsWith("The agent") ? recording.bar.text() : null);
            assertThat(text).isEqualTo("The agent ended the recording - 2 steps");
            SwingUtilities.invokeAndWait(() -> {
                assertThat(recording.state).isEqualTo(Recording.State.STOPPED);
                assertThat(recording.bar.labels()).containsExactly("● Record", "Load…", "Steps", "Export…");
                assertThat(scene.inspector.overlay(frame.getRootPane()).bar()).isSameAs(recording.bar);
                assertThat(recording.listening()).isFalse();
                assertThat(scene.inspector.engaged()).isNull();
            });
            assertThat(sink.log).doesNotContain("end");
        });
    }

    @Test
    void theAgentsLoadShowsTheRecordedTestReadyToRun() throws Exception {
        asTheInspector(() -> {
            Recording person = recordInFrame();
            SwingUtilities.invokeAndWait(person::done);
            person.done.get(5, TimeUnit.SECONDS);
            twoRecordedTests();
            sink.loadRecordedTest(sink.recordedFolder.resolve("add-to-cart.json"));
            Inspector.agentLoaded("add-to-cart", 2);
            recording = eventually(() -> scene.inspector.recording() != person && scene.inspector.recording().panel != null
                    && scene.inspector.recording().panel.rows().size() == 2 ? scene.inspector.recording() : null);
            SwingUtilities.invokeAndWait(() -> {
                assertThat(recording.state).isEqualTo(Recording.State.STOPPED);
                assertThat(recording.bar.title()).isEqualTo("Loaded");
                assertThat(recording.bar.text()).isEqualTo("add-to-cart - 2 steps");
                assertThat(recording.panel.replayAllLabel()).isEqualTo("▶ Run");
                assertThat(scene.inspector.overlay(frame.getRootPane()).bar()).isSameAs(recording.bar);
                assertThat(person.panel).isNull();
            });
            run(recording, 0);
            eventually(() -> !recording.replaying && sink.log.contains("replay 1") ? Boolean.TRUE : null);
            assertThat(sink.log).containsSubsequence("reset", "replay 0", "replay 1");
        });
    }

    @Test
    void aResetTakesThePaletteBackToIdle() throws Exception {
        asTheInspector(() -> {
            Recording person = recordInFrame();
            SwingUtilities.invokeAndWait(() -> {
                person.done();
                person.toggleSteps();
            });
            person.done.get(5, TimeUnit.SECONDS);
            StepsPanel panel = person.panel;
            Inspector.recordingReplaced();
            eventually(() -> scene.inspector.recording() == null ? Boolean.TRUE : null);
            recording = null;
            SwingUtilities.invokeAndWait(() -> {
                Overlay o = scene.inspector.overlay(frame.getRootPane());
                assertThat(o.bar().title()).isEqualTo("swing-ui-agent");
                assertThat(o.bar().labels()).containsExactly("● Record", "Load recorded test…");
                assertThat(o.holds(panel)).isFalse();
                assertThat(scene.inspector.engaged()).isNull();
            });
        });
    }

    @Test
    void whileTheAgentReplaysThePersonsInputIsNotRecordedAndTheButtonsAreAway() throws Exception {
        asTheInspector(() -> {
            recordInFrame();
            sink.person("click(#a)", "click(#b)");
            int a = sink.steps.get(0).id;
            int b = sink.steps.get(1).id;
            SwingUtilities.invokeAndWait(() -> {
                recording.toggleSteps();
                recording.live.stop();
            });
            eventually(() -> recording.panel.rows().size() == 2 ? Boolean.TRUE : null);
            Bar own = recording.bar;

            Inspector.agentReplaying("resetting the app, then replaying steps 1-2");
            Overlay o = scene.inspector.overlay(frame.getRootPane());
            Bar agent = eventually(() -> "Agent".equals(o.bar().title()) ? o.bar() : null);
            SwingUtilities.invokeAndWait(() -> {
                assertThat(agent.text()).isEqualTo("resetting the app, then replaying steps 1-2");
                assertThat(agent.labels()).isEmpty();
                assertThat(o.controlsAreAway()).isTrue();
                assertThat(recording.listening()).isFalse();
            });
            // the agent's progress shows on the same bar
            Inspector.agentReplaying("replaying 1/2  click(#a)");
            eventually(() -> agent.text().equals("replaying 1/2  click(#a)") ? Boolean.TRUE : null);
            Inspector.agentReplayed(a, true);
            Inspector.agentReplayed(b, false);
            Inspector.agentReplaying(null);
            eventually(() -> o.bar() == own ? Boolean.TRUE : null);
            eventually(() -> recording.panel.rows().get(1).startsWith("2  ✗") ? Boolean.TRUE : null);
            SwingUtilities.invokeAndWait(() -> {
                assertThat(recording.panel.rows()).containsExactly("1  ✓  click(#a)", "2  ✗  click(#b)");
                assertThat(o.controlsAreAway()).isFalse();
                assertThat(recording.listening()).isTrue();
                assertThat(recording.state).isEqualTo(Recording.State.RECORDING);
            });
        });
    }

    @Test
    void aRecordPressedWhileTheAgentRecordsShowsTheAgentsRecording() throws Exception {
        asTheInspector(() -> {
            sink.agent("click(#new)");
            sink.refuseBegin = Inspector.AGENTS_RECORDING_RUNS;
            recordInFrame();
            SwingUtilities.invokeAndWait(() -> {
                assertThat(recording.state).isEqualTo(Recording.State.RECORDING);
                assertThat(recording.bar.text()).isEqualTo("The agent started this recording: use the app, your steps join it.");
                assertThat(scene.inspector.engaged()).contains("the agent started it");
            });
            assertThat(sink.log).containsExactly("begin");
            assertThat(sink.lines()).containsExactly("click(#new)");
        });
    }

    // ---- the steps list in steps and checks: numbers, sentences, groups, the row menu, Describe… ---------------------

    @Test
    void rowsShowStepAndCheckNumbers() throws Exception {
        sink.add("1.1", "expect(#total text '0.00')");
        sink.add("2", "click(#new)");
        sink.add("2.1", "expect(#status text 'new')");
        sink.add("3", "click(#save)");
        withSteps();
        // a check-only step 1 has no action row: only its checks
        assertThat(rows()).containsExactly(
                "      └ 1.1  ●  check: #total text '0.00'",
                "2  ●  click(#new)",
                "      └ 2.1  ●  check: #status text 'new'",
                "3  ●  click(#save)");
        SwingUtilities.invokeAndWait(() -> assertThat(recording.panel.title()).isEqualTo("Steps (3)"));
    }

    @Test
    void aStepWithASentenceShowsItAndTheLineOnHover() throws Exception {
        sink.add("person", "1", "click(#new)", "Open a new order", 0, null, null);
        sink.add("agent", "2", "click(#save)", "Save it", 0, null, null);
        withSteps();
        assertThat(rows()).containsExactly("1  ●  Open a new order", "2  ●  agent: Save it");
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.panel.tooltipAt(0)).isEqualTo("click(#new)");
            assertThat(recording.panel.tooltipAt(1)).isEqualTo("click(#save)");
        });
    }

    @Test
    void aCheckWithASentenceShowsIt() throws Exception {
        sink.add("1", "click(#new)");
        sink.add("person", "1.1", "expect(#status text 'new')", "The order is new", 0, null, null);
        withSteps();
        assertThat(rows()).containsExactly("1  ●  click(#new)", "      └ 1.1  ●  The order is new");
        SwingUtilities.invokeAndWait(() -> assertThat(recording.panel.tooltipAt(1)).isEqualTo("expect(#status text 'new')"));
    }

    /** Step 1, then steps 2 (with check 2.1) and 3 in the group "Save the order", then step 4. */
    private void aGroupOfTwo() {
        sink.add("1", "click(#new)");
        sink.add("person", "2", "click(#save)", null, 7, "Save the order", null);
        sink.add("person", "2.1", "expect(#status text 'saved')", null, 7, "Save the order", null);
        sink.add("person", "3", "click(#close)", null, 7, "Save the order", null);
        sink.add("4", "click(#print)");
    }

    @Test
    void aGroupShowsAHeaderAboveItsIndentedSteps() throws Exception {
        aGroupOfTwo();
        withSteps();
        assertThat(rows()).containsExactly(
                "1  ●  click(#new)",
                "▾ Save the order",
                "  2  ●  click(#save)",
                "        └ 2.1  ●  check: #status text 'saved'",
                "  3  ●  click(#close)",
                "4  ●  click(#print)");
    }

    @Test
    void headerRowsHaveNoIdAndSelectTheirSteps() throws Exception {
        aGroupOfTwo();
        withSteps();
        SwingUtilities.invokeAndWait(() -> {
            StepsPanel p = recording.panel;
            assertThat(p.idAt(1)).isEqualTo(-1);
            assertThat(p.recordAt(1)).isEqualTo(-1);
            assertThat(p.idAt(2)).isEqualTo(2);
            assertThat(p.recordAt(2)).isEqualTo(1);
            assertThat(p.recordAt(5)).isEqualTo(4);
            // a click on the header selects its steps, with their checks
            p.select(1);
            assertThat(p.selectedIds()).containsExactly(2, 3, 4);
        });
    }

    /** Opens the row menu on {@code row}, as a right click there does; returns the menu. */
    private StepMenu menuOn(int row) throws Exception {
        java.util.concurrent.atomic.AtomicReference<StepMenu> menu = new java.util.concurrent.atomic.AtomicReference<StepMenu>();
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.openMenu(row);
            menu.set(recording.panel.menu());
        });
        return menu.get();
    }

    /** Chooses {@code label} in the row menu on {@code row}; returns the ask panel that opened, when one did. */
    private AskPanel choose(int row, String label) throws Exception {
        StepMenu menu = menuOn(row);
        SwingUtilities.invokeAndWait(() -> menu.choose(label));
        return recording.askPanel;
    }

    /** Types {@code say} into the ask panel and presses OK. */
    private void answer(AskPanel ask, String say) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ask.type(say);
            ask.ok();
        });
    }

    @Test
    void describeOneStepSetsItsSentence() throws Exception {
        sink.add("1", "click(#new)");
        sink.add("2", "click(#customer)");
        sink.add("3", "click(#save)");
        withSteps();
        assertThat(menuOn(2).labels()).contains("Describe this step…").doesNotContain("Describe this check…");
        AskPanel ask = choose(2, "Describe this step…");
        Overlay o = scene.inspector.overlay(frame.getRootPane());
        SwingUtilities.invokeAndWait(() -> {
            // the ask panel takes the steps panel's place
            assertThat(ask).isNotNull();
            assertThat(ask.title()).isEqualTo("Describe step 3");
            assertThat(o.holds(ask)).isTrue();
            assertThat(o.holds(recording.panel)).isFalse();
        });
        answer(ask, "Save the order");
        eventually(() -> recording.askPanel == null ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("describe 3-3 Save the order");
        SwingUtilities.invokeAndWait(() -> {
            assertThat(o.holds(recording.panel)).isTrue();
            assertThat(recording.bar.text()).isEqualTo("described");
        });
    }

    @Test
    void describeOneCheckSetsItsSentence() throws Exception {
        sink.add("1.1", "expect(#total text '0.00')");
        sink.add("1.2", "expect(#status text 'empty')");
        sink.add("2", "click(#new)");
        sink.add("person", "2.1", "expect(#status text 'new')", "The order is new", 0, null, null);
        withSteps();
        assertThat(menuOn(3).labels()).contains("Describe this check…").doesNotContain("Describe this step…");
        AskPanel ask = choose(3, "Describe this check…");
        SwingUtilities.invokeAndWait(() -> {
            assertThat(ask.title()).isEqualTo("Describe check 2.1");
            // it starts from the sentence the check has
            assertThat(ask.text()).isEqualTo("The order is new");
        });
        answer(ask, "The status says new");
        eventually(() -> recording.askPanel == null ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("describe 4-4 The status says new");

        // a check-only step has no sentence of its own: its checks may each have one
        assertThat(menuOn(0).labels()).contains("Describe this check…").doesNotContain("Describe this step…");
        SwingUtilities.invokeAndWait(() -> recording.panel.selectRange(0, 1));
        assertThat(menuOn(0).labels()).doesNotContain("Describe this step…", "Describe this check…", "Describe these steps…");
    }

    @Test
    void describeRowsOfTwoStepsGroupsThem() throws Exception {
        sink.add("1", "click(#new)");
        sink.add("2", "click(#save)");
        sink.add("2.1", "expect(#status text 'saved')");
        sink.add("3", "click(#close)");
        withSteps();
        SwingUtilities.invokeAndWait(() -> recording.panel.selectRange(1, 3));
        StepMenu menu = menuOn(1);
        assertThat(menu.labels()).contains("Describe these steps…").doesNotContain("Describe this step…");
        AskPanel ask = choose(1, "Describe these steps…");
        SwingUtilities.invokeAndWait(() -> assertThat(ask.title()).isEqualTo("Describe steps 2-3"));
        answer(ask, "Save and close");
        eventually(() -> recording.askPanel == null ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("describe 2-4 Save and close");
    }

    @Test
    void aGroupsHeaderRenamesTheGroup() throws Exception {
        aGroupOfTwo();
        withSteps();
        AskPanel ask = choose(1, "Describe these steps…");
        SwingUtilities.invokeAndWait(() -> {
            assertThat(ask.title()).isEqualTo("Describe steps 2-3");
            assertThat(ask.text()).isEqualTo("Save the order");
        });
        answer(ask, "Save and close the order");
        eventually(() -> recording.askPanel == null ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("describe 2-4 Save and close the order");
    }

    @Test
    void ungroupFromTheMenu() throws Exception {
        aGroupOfTwo();
        withSteps();
        StepMenu outside = menuOn(0);
        assertThat(outside.labels()).contains("Ungroup");
        assertThat(outside.enabled("Ungroup")).isFalse();
        StepMenu inside = menuOn(4);
        assertThat(inside.enabled("Ungroup")).isTrue();
        SwingUtilities.invokeAndWait(() -> inside.choose("Ungroup"));
        eventually(() -> "ungrouped".equals(recording.bar.text()) ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("ungroup 4");
    }

    @Test
    void theRowMenuOffersValueAndPosition() throws Exception {
        sink.add("person", "1", "click(table 'Orders' · Order=ORD-1641 · Customer)", null, 0, null,
                new Inspector.RowLine("value", Collections.<String, Object>singletonMap("Order", "ORD-1641"), 1));
        sink.add("person", "2", "click(table 'Orders' · Order=ORD-1642 · Customer)", null, 0, null,
                new Inspector.RowLine("value", Collections.<String, Object>singletonMap("Order", "ORD-1642"), null));
        sink.add("3", "click(#save)");
        withSteps();
        StepMenu menu = menuOn(0);
        assertThat(menu.labels()).contains("Row: this value (Order=ORD-1641)", "Row: by position (row 1)");
        assertThat(menu.selected("Row: this value (Order=ORD-1641)")).isTrue();
        assertThat(menu.enabled("Row: by position (row 1)")).isTrue();
        SwingUtilities.invokeAndWait(() -> menu.choose("Row: by position (row 1)"));
        eventually(() -> "row by position".equals(recording.bar.text()) ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("row 1 position");

        // a step whose position is not known yet: that way is greyed until a replay learns it
        StepMenu unknown = menuOn(1);
        assertThat(unknown.labels()).contains("Row: by position (replay it once)");
        assertThat(unknown.enabled("Row: by position (replay it once)")).isFalse();
        assertThat(unknown.enabled("Row: this value (Order=ORD-1642)")).isTrue();
        // a step without a row offers no row
        assertThat(menuOn(2).labels()).noneMatch(l -> l.startsWith("Row:"));
    }

    @Test
    void aRefusedDescribeKeepsTheAskPanelOpenWithWhy() throws Exception {
        sink.add("1", "click(#new)");
        sink.refuseDescribe = "a sentence is one line";
        withSteps();
        AskPanel ask = choose(0, "Describe this step…");
        answer(ask, "Open it");
        eventually(() -> ask.message().contains("one line") ? Boolean.TRUE : null);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.askPanel).isSameAs(ask);
            assertThat(scene.inspector.overlay(frame.getRootPane()).holds(ask)).isTrue();
            assertThat(ask.message()).isEqualTo("Not changed: a sentence is one line");
            assertThat(recording.bar.text()).isEqualTo("✗ not described: a sentence is one line");
        });
        // Cancel puts the steps back
        SwingUtilities.invokeAndWait(ask::cancel);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.askPanel).isNull();
            assertThat(scene.inspector.overlay(frame.getRootPane()).holds(recording.panel)).isTrue();
        });
    }

    @Test
    void deleteOnACheckRowDeletesTheCheckOnAnActionRowTheStep() throws Exception {
        sink.add("1", "click(#new)");
        int check = sink.add("1.1", "expect(#status text 'new')");
        int action = sink.add("2", "click(#save)");
        withSteps();
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.select(1);
            recording.panel.press("✕ Delete");
        });
        eventually(() -> sink.log.size() == 1 ? Boolean.TRUE : null);
        settle();
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.select(1);
            recording.panel.press("✕ Delete");
        });
        eventually(() -> sink.log.size() == 2 ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("delete " + check, "delete " + action);
    }

    @Test
    void fromSelectedAndRedoOnACheckRowUseItsStep() throws Exception {
        sink.add("1", "click(#new)");
        sink.add("2", "click(#save)");
        sink.add("2.1", "expect(#status text 'saved')");
        sink.add("3", "click(#close)");
        withSteps();
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.speed(0);
            recording.panel.select(2);
            recording.panel.press("▶ From selected");
        });
        eventually(() -> !recording.replaying && sink.log.size() == 3 ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("replay 1", "replay 2", "replay 3");
        sink.log.clear();
        settle();
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.select(2);
            recording.panel.press("↺ Redo from selected");
        });
        eventually(() -> sink.log.contains("resume") ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("cut 2", "reset", "replay 0", "resume");
    }

    @Test
    void fromSelectedOnACheckOnlyStepResetsFirst() throws Exception {
        sink.add("1.1", "expect(#total text '0.00')");
        sink.add("1.2", "expect(#status text 'empty')");
        sink.add("2", "click(#new)");
        withSteps();
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.speed(0);
            recording.panel.select(1);
            recording.panel.press("▶ From selected");
        });
        eventually(() -> !recording.replaying && sink.log.size() == 4 ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("reset", "replay 0", "replay 1", "replay 2");
    }

    @Test
    void withAGroupShownTheCallbacksGetRecordIndexesNotRows() throws Exception {
        aGroupOfTwo();
        withSteps();
        // row 4 is step 3, the record at 3
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.speed(0);
            recording.panel.select(4);
            recording.panel.press("▶ From selected");
        });
        eventually(() -> !recording.replaying && sink.log.size() == 2 ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("replay 3", "replay 4");
        sink.log.clear();
        settle();
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.select(5);
            recording.panel.press("✕ Delete");
        });
        eventually(() -> sink.log.size() == 1 ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("delete 5");
        settle();
        sink.log.clear();
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.select(3);
            recording.panel.press("↺ Redo from selected");
        });
        eventually(() -> sink.log.contains("resume") ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("cut 2", "reset", "replay 0", "resume");
    }

    @Test
    void aRefreshWhileTheAskPanelIsOpenKeepsItsTargets() throws Exception {
        sink.add("1", "click(#new)");
        sink.add("2", "click(#save)");
        withSteps();
        AskPanel ask = choose(1, "Describe this step…");
        // the agent puts a step in at the top while the person types
        sink.insert(0, "click(#x)");
        settleRefresh();
        SwingUtilities.invokeAndWait(() -> assertThat(recording.askPanel).isSameAs(ask));
        answer(ask, "Save it");
        eventually(() -> recording.askPanel == null ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("describe 2-2 Save it");
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
}
