package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.model.Locator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.AWTEventListenerProxy;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.lang.reflect.Method;
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
        SwingUtilities.invokeAndWait(() -> panel.kind(ExportPanel.JAVA));
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
    void exportOffersARecordedTestFirstAndOpensOnIt() throws Exception {
        ExportPanel panel = exportPanelAfterDone();
        assertThat(panel.kindLabels()).containsExactly("Recorded test", "Java test");
        assertThat(panel.kind()).isEqualTo(ExportPanel.RECORDED);
        assertThat(panel.recordedFields()).containsExactly("recorded-test", sink.recordedFolder.toString());
        assertThat(panel.actionLabel()).isEqualTo("Export");

        SwingUtilities.invokeAndWait(panel::submit);
        eventually(() -> recording.exportPanel == null ? Boolean.TRUE : null);
        assertThat(sink.recordedExports).containsExactly(sink.recordedFolder.resolve("recorded-test.json").toString());
        assertThat(sink.exports).isEmpty();
        assertThat(recording.bar.text()).contains("exported").contains("recorded-test.json");
    }

    @Test
    void exportOffersAJavaTestTooAndOpensOnTheKindUsedLast() throws Exception {
        ExportPanel panel = exportPanelAfterDone();
        SwingUtilities.invokeAndWait(() -> panel.kind(ExportPanel.JAVA));
        assertThat(panel.kind()).isEqualTo(ExportPanel.JAVA);
        assertThat(panel.names()).containsExactly("RecordedUiIT", "com.example");
        assertThat(panel.actionLabel()).isEqualTo("Export");

        SwingUtilities.invokeAndWait(panel::submit);
        eventually(() -> recording.exportPanel == null ? Boolean.TRUE : null);
        assertThat(sink.exports).containsExactly("com.example.RecordedUiIT");

        // the kind used last is where the panel opens next time
        SwingUtilities.invokeAndWait(recording::toggleExport);
        ExportPanel again = eventually(() -> recording.exportPanel);
        assertThat(again.kind()).isEqualTo(ExportPanel.JAVA);
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
            recording.replaying = true;

            recording.rehome(inDialog);

            assertThat(recording.home).isSameAs(inDialog);
            assertThat(inDialog.bar()).isSameAs(replayBar);
            assertThat(replayBar.getParent()).isSameAs(dialog.getLayeredPane());
            assertThat(inFrame.bar()).isNull();
            // the open steps list goes along
            assertThat(inDialog.holds(recording.panel)).isTrue();
            assertThat(recording.panel.getParent()).isSameAs(dialog.getLayeredPane());
            recording.replaying = false;
        });
    }

    /** A "Next" button where the bar goes by default in the dialog: its bottom middle. EDT. */
    private Rectangle nextInDialog(Bar bar) {
        JLayeredPane layers = dialog.getLayeredPane();
        Container content = dialog.getContentPane();
        content.setLayout(null);
        Dimension size = bar.getPreferredSize();
        Rectangle corner = Overlay.paletteSpot(size.width, size.height, layers.getWidth(), layers.getHeight(),
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
            // as a replay starts: its own bar, the open steps list stays
            Bar replayBar = new Bar("Replaying", "").button("Stop", Overlay.NO, () -> { });
            inFrame.bar(replayBar);
            recording.replaying = true;
            // a box of the replay keeps the frame's overlay alive after the palette leaves
            inFrame.mark(new Overlay.Mark(frame.getContentPane(), null, -1, -1, "1/2", Overlay.HOVER, false), null);

            Overlay inDialog = showIn(dialog.getRootPane());

            assertThat(recording.home).isSameAs(inDialog);
            assertThat(inDialog.bar()).isSameAs(replayBar);
            assertThat(replayBar.getParent()).isSameAs(dialog.getLayeredPane());
            assertThat(inDialog.holds(recording.panel)).isTrue();
            assertThat(recording.panel.getParent()).isSameAs(dialog.getLayeredPane());
            assertThat(inFrame.bar()).isNull();
            assertThat(inFrame.holds(recording.panel)).isFalse();
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
    void stepByStepASmallConfirmationTheRunOpensGetsTheBarAndStepsBesideItAndNextAnswersIt() throws Exception {
        Recording r = loadAddToCart();
        // as small as "Discard this new order?" with Discard and Cancel
        JDialog confirm = new JDialog(frame, true);
        try {
            run(r, 3);
            Overlay inFrame = scene.inspector.overlay(frame.getRootPane());
            Bar replayBar = eventually(() -> inFrame.bar() != r.bar ? inFrame.bar() : null);
            eventually(() -> replayBar.text().startsWith("1/2") ? Boolean.TRUE : null);
            r.next.release();
            eventually(() -> replayBar.text().startsWith("2/2") ? Boolean.TRUE : null);
            // step 1 opened the confirmation: the window events (or the end of the step) move the palette
            SwingUtilities.invokeAndWait(() -> {
                Scene.size(confirm, 286, 173);
                scene.inspector.paletteRoot = confirm::getRootPane;
                scene.inspector.attachPalette();

                java.awt.Window beside = SwingUtilities.getWindowAncestor(replayBar);
                assertThat(beside).isInstanceOf(PaletteWindow.class);
                assertThat(beside.getOwner()).isSameAs(confirm);
                assertThat(replayBar.text()).endsWith("paused: Next runs it");
                // the open steps list goes along, above the bar
                assertThat(SwingUtilities.getWindowAncestor(r.panel)).isSameAs(beside);
                assertThat(r.panel.getY() + r.panel.getHeight()).isLessThanOrEqualTo(replayBar.getY());
                assertThat(beside.getHeight()).isGreaterThanOrEqualTo(r.panel.getPreferredSize().height + replayBar.getHeight());
                assertThat(inFrame.bar()).isNull();
            });
            // Next, pressed beside the dialog, runs the answer
            SwingUtilities.invokeAndWait(() -> replayBar.press("Next"));
            eventually(() -> !r.replaying ? Boolean.TRUE : null);
            assertThat(sink.log).containsExactly("reset", "replay 0", "replay 1");
            // the confirmation closed: everything is back in the frame
            SwingUtilities.invokeAndWait(() -> {
                java.awt.Window beside = SwingUtilities.getWindowAncestor(r.bar);
                scene.inspector.paletteRoot = frame::getRootPane;
                scene.inspector.attachPalette();
                assertThat(r.bar.getParent()).isSameAs(frame.getLayeredPane());
                assertThat(r.panel.getParent()).isSameAs(frame.getLayeredPane());
                assertThat(((PaletteWindow) beside).gone()).isTrue();
            });
        } finally {
            confirm.dispose();
        }
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
    void aReplayIsOneRunSoTheBarStaysAsideUntilItEnds() throws Exception {
        sink.person("click(#a)", "click(#b)");
        withSteps();
        List<Boolean> running = new java.util.concurrent.CopyOnWriteArrayList<Boolean>();
        sink.onReplay = () -> running.add(Inspector.agentIsRunning());
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.speed(0);
            recording.panel.press("▶ Replay all");
        });
        eventually(() -> !recording.replaying && sink.log.size() == 3 ? Boolean.TRUE : null);
        assertThat(running).containsExactly(true, true);
        assertThat(Inspector.agentIsRunning()).isFalse();

        // a replay that breaks ends the run too
        sink.replayThrows = new IllegalStateException("the session went away");
        sink.log.clear();
        SwingUtilities.invokeAndWait(() -> recording.panel.press("▶ Replay all"));
        eventually(() -> !recording.replaying && sink.log.contains("replay 0") ? Boolean.TRUE : null);
        assertThat(Inspector.agentIsRunning()).isFalse();
    }

    /** A replay whose work never reaches the sink thread ends at once and leaves no run counted. */
    @Test
    void aReplayThatCannotStartLeavesNoRunCounted() throws Exception {
        sink.person("click(#a)", "click(#b)");
        withSteps();
        sinkThread.shutdownNow();
        java.io.PrintStream err = System.err;
        System.setErr(new java.io.PrintStream(new java.io.ByteArrayOutputStream(), true));
        try {
            SwingUtilities.invokeAndWait(() -> {
                recording.panel.speed(0);
                recording.panel.press("▶ Replay all");
            });
            eventually(() -> !recording.replaying ? Boolean.TRUE : null);
        } finally {
            System.setErr(err);
        }
        assertThat(Inspector.agentIsRunning()).isFalse();
        SwingUtilities.invokeAndWait(() -> assertThat(recording.bar.text()).startsWith("✗ replay failed"));
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

    @Test
    void theStepsListStaysOpenThroughAStepByStepReplayAndShowsWhereItIs() throws Exception {
        sink.person("click(#a)", "click(#b)", "click(#c)");
        withSteps();
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.speed(3);
            recording.panel.select(1);
            recording.panel.press("▶ From selected");
        });
        Overlay o = scene.inspector.overlay(frame.getRootPane());
        Bar replayBar = eventually(() -> o.bar() != recording.bar ? o.bar() : null);
        eventually(() -> replayBar.text().startsWith("2/3") ? Boolean.TRUE : null);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.panel.getParent()).isSameAs(frame.getLayeredPane());
            assertThat(recording.panel.rows()).containsExactly("1  ●  click(#a)", "2  ▶  click(#b)", "3  ●  click(#c)");
            // nothing in it changes the recording or starts another replay while the steps run
            for (String b : Arrays.asList("▶ Replay all", "▶ From selected", "↺ Redo from selected", "✕ Delete", "↶ Undo", "⋯")) {
                assertThat(recording.panel.enabled(b)).as(b).isFalse();
            }
        });
        recording.next.release();
        eventually(() -> recording.panel.rows().equals(Arrays.asList("1  ●  click(#a)", "2  ✓  click(#b)", "3  ▶  click(#c)"))
                ? Boolean.TRUE : null);
        recording.next.release();
        eventually(() -> !recording.replaying ? Boolean.TRUE : null);
        settle();
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.panel.getParent()).isSameAs(frame.getLayeredPane());
            assertThat(recording.panel.rows()).containsExactly("1  ●  click(#a)", "2  ✓  click(#b)", "3  ✓  click(#c)");
            assertThat(recording.panel.enabled("▶ Replay all")).isTrue();
            assertThat(recording.panel.enabled("✕ Delete")).isTrue();
        });
        assertThat(sink.log).containsExactly("replay 1", "replay 2");
    }

    @Test
    void theReplayBarsStepsButtonClosesAndOpensTheListWhileItRuns() throws Exception {
        sink.person("click(#a)", "click(#b)", "click(#c)");
        withSteps();
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.speed(3);
            recording.panel.select(1);
            recording.panel.press("▶ From selected");
        });
        Overlay o = scene.inspector.overlay(frame.getRootPane());
        Bar replayBar = eventually(() -> o.bar() != recording.bar ? o.bar() : null);
        eventually(() -> replayBar.text().startsWith("2/3") ? Boolean.TRUE : null);
        SwingUtilities.invokeAndWait(() -> {
            replayBar.press("Steps");
            assertThat(recording.panel).isNull();
            replayBar.press("Steps");
            // back at once, as far as the replay got (the sink thread is busy replaying)
            assertThat(recording.panel.getParent()).isSameAs(frame.getLayeredPane());
            assertThat(recording.panel.rows()).containsExactly("1  ●  click(#a)", "2  ▶  click(#b)", "3  ●  click(#c)");
            assertThat(recording.panel.enabled("✕ Delete")).isFalse();
            // the row menu changes nothing either
            recording.panel.select(0);
            recording.panel.press("⋯");
            assertThat(recording.panel.menu()).isNull();
        });
        recording.next.release();
        recording.next.release();
        eventually(() -> !recording.replaying ? Boolean.TRUE : null);
        settle();
        SwingUtilities.invokeAndWait(() ->
                assertThat(recording.panel.rows()).containsExactly("1  ●  click(#a)", "2  ✓  click(#b)", "3  ✓  click(#c)"));
        assertThat(sink.log).containsExactly("replay 1", "replay 2");
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
            assertThat(recording.bar.text()).isEqualTo("✗ 2 click #b: no button 'b' - recording stopped");
        });
    }

    @Test
    void aFailedStepSaysOnTheBarWhatThePersonReadsInTheStepsListAndWhyInPlainWords() throws Exception {
        sink.add("person", "1", "menu(File > Close tab)", "Close the order tab", 0, null, null);
        sink.add("person", "2", "answer(Discard)", "Discard the order", 0, null, null);
        sink.failStep = 1;
        sink.failWith = Inspector.Result.failedStep("answer(Discard) - NOT_FOUND: no modal dialog is showing",
                "NOT_FOUND", "NOT_FOUND: no modal dialog is showing");
        withSteps();
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.speed(0);
            recording.panel.press("▶ Replay all");
        });
        eventually(() -> !recording.replaying ? Boolean.TRUE : null);
        settle();
        // the sentence and a reason a person reads; the technical line stays the agent's
        SwingUtilities.invokeAndWait(() -> assertThat(recording.bar.text()).startsWith("✗ 2 Discard the order: no dialog is open"));
    }

    @Test
    void aFailedCheckSaysWhatItReadsInstead() throws Exception {
        sink.add("person", "1", "click(button 'Add to cart')", "Add the A4 paper to the cart", 0, null, null);
        sink.add("person", "1.1", "expect(#total text '€7.32')", "The total is €7.32", 0, null, null);
        sink.failStep = 1;
        sink.failWith = Inspector.Result.failedStep(
                "expect(#total text '€7.32') - EXPECTATION: expected text '€7.32' but was '€7.30'",
                "EXPECTATION", "EXPECTATION: expected text '€7.32' but was '€7.30'");
        withSteps();
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.speed(0);
            recording.panel.press("▶ Replay all");
        });
        eventually(() -> !recording.replaying ? Boolean.TRUE : null);
        settle();
        SwingUtilities.invokeAndWait(() -> assertThat(recording.bar.text())
                .startsWith("✗ 1.1 The total is €7.32: it reads “€7.30”, not “€7.32”"));
    }

    @Test
    void plainReasonsForThePersonsBar() {
        assertThat(Recording.plain("NOT_FOUND", "NOT_FOUND: no modal dialog is showing")).isEqualTo("no dialog is open");
        assertThat(Recording.plain("NOT_FOUND", "no popup is showing")).isEqualTo("no popup is open");
        assertThat(Recording.plain("NOT_FOUND", "no file chooser is showing")).isEqualTo("no file chooser is open");
        assertThat(Recording.plain("NOT_FOUND", "nothing matches button 'Save'")).isEqualTo("it is not on the screen");
        assertThat(Recording.plain("AMBIGUOUS", "2 elements match button 'Save'")).isEqualTo("more than one element fits it");
        assertThat(Recording.plain("NOT_ACTIONABLE", "disabled: menu item 'Close tab'")).isEqualTo("it is disabled");
        assertThat(Recording.plain("NOT_ACTIONABLE", "covered by a popup")).isEqualTo("it cannot be used now");
        assertThat(Recording.plain("TIMEOUT", "still busy after 10000 ms: SwingWorker")).isEqualTo("the app stayed busy");
        assertThat(Recording.plain("APP_ERROR", "uncaught exception on the event dispatch thread: NPE"))
                .isEqualTo("the app reported an error");
        assertThat(Recording.plain("EXPECTATION", "expected cell [Total] '€7.32' but was '€7.30'"))
                .isEqualTo("it reads “€7.30”, not “€7.32”");
        // any other wording is shown as the driver says it, without its code
        assertThat(Recording.plain("EXPECTATION", "EXPECTATION: expected rows == 2 but there are 3"))
                .isEqualTo("expected rows == 2 but there are 3");
        assertThat(Recording.plain(null, "no step 12")).isEqualTo("no step 12");
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
    void whileTheAgentReplaysThePersonsInputIsNotRecordedAndTheListsButtonsRest() throws Exception {
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
                assertThat(recording.panel.getParent()).isSameAs(frame.getLayeredPane());
                assertThat(recording.panel.enabled("▶ Replay all")).isFalse();
                assertThat(recording.panel.enabled("✕ Delete")).isFalse();
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
                assertThat(recording.panel.enabled("▶ Replay all")).isTrue();
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

    /** Chooses {@code label} in the row menu on {@code row}; returns the field that opened in place, when one did. */
    private JTextField choose(int row, String label) throws Exception {
        StepMenu menu = menuOn(row);
        SwingUtilities.invokeAndWait(() -> menu.choose(label));
        return onEdt(() -> recording.panel.nameField());
    }

    /** Double-clicks row {@code row}, as the person would: two presses, releases and clicks on it. Returns the field. */
    private JTextField doubleClick(int row) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.JList<String> list = recording.panel.stepsList();
            layOut(recording.panel, 460, 300);
            Rectangle cell = list.getCellBounds(row, row);
            int x = cell.x + 8;
            int y = cell.y + cell.height / 2;
            for (int count = 1; count <= 2; count++) {
                long now = System.currentTimeMillis();
                list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_PRESSED, now, InputEvent.BUTTON1_DOWN_MASK, x, y, count,
                        false, MouseEvent.BUTTON1));
                list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_RELEASED, now, 0, x, y, count, false, MouseEvent.BUTTON1));
                list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_CLICKED, now, 0, x, y, count, false, MouseEvent.BUTTON1));
            }
        });
        return onEdt(() -> recording.panel.nameField());
    }

    /** Sizes the panel and lays it out, as showing it would. EDT. */
    private static void layOut(Container c, int width, int height) {
        c.setSize(width, height);
        layOut(c);
    }

    private static void layOut(Container c) {
        c.doLayout();
        for (Component k : c.getComponents()) if (k instanceof Container) layOut((Container) k);
    }

    /**
     * Presses {@code code} on {@code on}, as the keyboard delivers it there: the event goes out (the recorder hears what
     * any listener of the app's would) and, the window not showing, the binding the component has for it while it has
     * the keyboard runs, as Swing would run it before the window's own. EDT.
     */
    private static void key(JComponent on, int code) {
        char c = code == KeyEvent.VK_ENTER ? '\n' : code == KeyEvent.VK_ESCAPE ? '\u001b' : KeyEvent.CHAR_UNDEFINED;
        KeyEvent pressed = new KeyEvent(on, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, code, c);
        on.dispatchEvent(pressed);
        if (!on.isShowing()) {
            KeyStroke stroke = KeyStroke.getKeyStroke(code, 0);
            Object name = on.getInputMap(JComponent.WHEN_FOCUSED).get(stroke);
            Action action = name == null ? null : on.getActionMap().get(name);
            if (action != null) SwingUtilities.notifyAction(action, stroke, pressed, on, 0);
        }
        on.dispatchEvent(new KeyEvent(on, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, code, c));
    }

    /** Types {@code say} over what the field holds and presses Enter. */
    private void enter(JTextField field, String say) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            field.setText(say);
            key(field, KeyEvent.VK_ENTER);
        });
    }

    /** The keyboard leaves the field for {@code to}, as a click there takes it. EDT. */
    private static void focusLeaves(JTextField field, Component to) {
        FocusEvent lost = new FocusEvent(field, FocusEvent.FOCUS_LOST, false, to);
        for (FocusListener l : field.getFocusListeners()) {
            // the palette's own listener; the look and feel's caret has no say in it
            if (l.getClass().getName().startsWith("io.github.kormcrete.swingui")) l.focusLost(lost);
        }
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> read) throws Exception {
        java.util.concurrent.atomic.AtomicReference<T> got = new java.util.concurrent.atomic.AtomicReference<T>();
        SwingUtilities.invokeAndWait(() -> {
            try { got.set(read.call()); } catch (Exception e) { throw new RuntimeException(e); }
        });
        return got.get();
    }

    /** Waits until the field in place is closed. */
    private void closed() throws Exception {
        eventually(() -> recording.panel.naming() ? null : Boolean.TRUE);
    }

    @Test
    void aDoubleClickOnAStepNamesItInPlace() throws Exception {
        sink.add("1", "click(#new)");
        sink.add("2", "click(#customer)");
        sink.add("3", "click(#save)");
        withSteps();
        JTextField field = doubleClick(2);
        SwingUtilities.invokeAndWait(() -> {
            // the field covers the row's text, in the list: number and mark stay in view, the rest of the list too
            assertThat(field).isNotNull();
            assertThat(field.getParent()).isSameAs(recording.panel.stepsList());
            assertThat(recording.panel.namingRow()).isEqualTo(2);
            Rectangle cell = recording.panel.stepsList().getCellBounds(2, 2);
            assertThat(field.getY()).isEqualTo(cell.y);
            assertThat(field.getX()).isGreaterThan(cell.x);
            // no sentence yet: empty, the technical line greyed in it
            assertThat(field.getText()).isEmpty();
            assertThat(recording.panel.nameHint()).isEqualTo("click(#save)");
            assertThat(field.getToolTipText()).contains("Enter saves").contains("Empty removes the sentence");
            assertThat(scene.inspector.overlay(frame.getRootPane()).holds(recording.panel)).isTrue();
        });
        enter(field, "  Save the order ");
        closed();
        assertThat(sink.log).containsExactly("describe 3-3 Save the order");
        SwingUtilities.invokeAndWait(() -> {
            assertThat(field.getParent()).isNull();
            assertThat(recording.bar.text()).isEqualTo("described");
        });
    }

    @Test
    void f2AndEnterOnTheSelectedRowNameItToo() throws Exception {
        sink.add("1", "click(#new)");
        sink.add("person", "2", "click(#save)", "Save it", 0, null, null);
        withSteps();
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.select(1);
            key(recording.panel.stepsList(), KeyEvent.VK_F2);
            // it starts from the sentence the step has
            assertThat(recording.panel.nameField().getText()).isEqualTo("Save it");
            assertThat(recording.panel.namingRow()).isEqualTo(1);
            key(recording.panel.nameField(), KeyEvent.VK_ESCAPE);
            assertThat(recording.panel.naming()).isFalse();

            recording.panel.select(0);
            key(recording.panel.stepsList(), KeyEvent.VK_ENTER);
            assertThat(recording.panel.namingRow()).isEqualTo(0);
            assertThat(recording.panel.nameField().getText()).isEmpty();
        });
    }

    @Test
    void escCancelsAndDescribesNothing() throws Exception {
        sink.add("1", "click(#new)");
        withSteps();
        JTextField field = doubleClick(0);
        SwingUtilities.invokeAndWait(() -> {
            field.setText("Open a new order");
            key(field, KeyEvent.VK_ESCAPE);
            assertThat(recording.panel.naming()).isFalse();
        });
        settle();
        assertThat(sink.log).isEmpty();
        assertThat(rows()).containsExactly("1  ●  click(#new)");
    }

    @Test
    void aClickElsewhereSavesAChangeOnly() throws Exception {
        sink.add("1", "click(#new)");
        sink.add("person", "2", "click(#save)", "Save it", 0, null, null);
        withSteps();
        JButton save = appButton();
        List<Component> given = new java.util.concurrent.CopyOnWriteArrayList<Component>();
        scene.inspector.focusOwner = () -> save;
        scene.inspector.giveFocus = given::add;
        // unchanged: the field just closes
        JTextField same = doubleClick(1);
        SwingUtilities.invokeAndWait(() -> {
            focusLeaves(same, save);
            assertThat(recording.panel.naming()).isFalse();
        });
        settle();
        assertThat(sink.log).isEmpty();

        // changed: saved
        JTextField changed = doubleClick(1);
        SwingUtilities.invokeAndWait(() -> {
            changed.setText("Save the order");
            focusLeaves(changed, save);
        });
        closed();
        assertThat(sink.log).containsExactly("describe 2-2 Save the order");
        // the keyboard went where the person clicked: nothing is given back
        assertThat(given).isEmpty();
    }

    @Test
    void aRefusedSentenceKeepsTheFieldOpenWithTheTextAndTheBarSaysWhy() throws Exception {
        sink.add("1", "click(#new)");
        sink.refuseDescribe = "a sentence is one line";
        withSteps();
        JTextField field = doubleClick(0);
        enter(field, "Open it");
        eventually(() -> recording.bar.text().contains("one line") ? Boolean.TRUE : null);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.bar.text()).isEqualTo("✗ not described: a sentence is one line");
            assertThat(recording.panel.nameField()).isSameAs(field);
            assertThat(field.getText()).isEqualTo("Open it");
            assertThat(field.isEditable()).isTrue();
            assertThat(field.getParent()).isSameAs(recording.panel.stepsList());
        });
        // fixed, Enter again saves
        sink.refuseDescribe = null;
        enter(field, "Open a new order");
        closed();
        assertThat(sink.log).containsExactly("describe 1-1 Open it", "describe 1-1 Open a new order");
    }

    @Test
    void aCheckRowNamesTheCheckAndAStepOfChecksOnlyHasNoSentence() throws Exception {
        sink.add("1.1", "expect(#total text '0.00')");
        sink.add("1.2", "expect(#status text 'empty')");
        sink.add("2", "click(#new)");
        sink.add("person", "2.1", "expect(#status text 'new')", "The order is new", 0, null, null);
        withSteps();
        assertThat(menuOn(3).labels()).contains("Describe this check…").doesNotContain("Describe this step…");
        JTextField field = doubleClick(3);
        SwingUtilities.invokeAndWait(() -> assertThat(field.getText()).isEqualTo("The order is new"));
        enter(field, "The status says new");
        closed();
        assertThat(sink.log).containsExactly("describe 4-4 The status says new");

        // a check alone, without a sentence: the check's line greyed
        JTextField one = doubleClick(0);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.panel.nameHint()).isEqualTo("check: #total text '0.00'");
            key(one, KeyEvent.VK_ESCAPE);
        });
        // a check-only step has no sentence of its own: F2 on it opens nothing, and the menu offers nothing
        assertThat(menuOn(0).labels()).contains("Describe this check…").doesNotContain("Describe this step…");
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.selectRange(0, 1);
            key(recording.panel.stepsList(), KeyEvent.VK_F2);
            assertThat(recording.panel.naming()).isFalse();
        });
        assertThat(menuOn(0).labels()).doesNotContain("Describe this step…", "Describe this check…", "Describe these steps…");
    }

    @Test
    void severalStepsGetAHeaderRowOfTheirOwnForTheGroupsSentence() throws Exception {
        sink.add("1", "click(#new)");
        sink.add("2", "click(#save)");
        sink.add("2.1", "expect(#status text 'saved')");
        sink.add("3", "click(#close)");
        withSteps();
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.selectRange(1, 3);
            key(recording.panel.stepsList(), KeyEvent.VK_ENTER);
        });
        // a header opens above the steps, the field on it; the steps stay selected
        assertThat(rows()).containsExactly(
                "1  ●  click(#new)",
                "▾ ",
                "2  ●  click(#save)",
                "      └ 2.1  ●  check: #status text 'saved'",
                "3  ●  click(#close)");
        JTextField field = onEdt(() -> recording.panel.nameField());
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.panel.namingRow()).isEqualTo(1);
            assertThat(recording.panel.nameHint()).isEqualTo("steps 2-3");
            assertThat(field.getToolTipText()).contains("Ungroup").doesNotContain("Empty removes");
            assertThat(recording.panel.selectedIds()).containsExactly(2, 3, 4);
        });
        enter(field, "Save and close");
        closed();
        assertThat(sink.log).containsExactly("describe 2-4 Save and close");
        // the header of its own goes with the field (the session's group shows on the next re-read)
        assertThat(rows()).hasSize(4);
    }

    @Test
    void aDoubleClickInASelectionOfSeveralStepsNamesThemAll() throws Exception {
        sink.add("1", "click(#new)");
        sink.add("2", "click(#save)");
        sink.add("3", "click(#close)");
        withSteps();
        SwingUtilities.invokeAndWait(() -> recording.panel.selectRange(1, 2));
        doubleClick(2);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.panel.rows()).containsExactly("1  ●  click(#new)", "▾ ", "2  ●  click(#save)", "3  ●  click(#close)");
            assertThat(recording.panel.namingRow()).isEqualTo(1);
        });
    }

    @Test
    void aDoubleClickOnAGroupsHeaderRenamesTheGroup() throws Exception {
        aGroupOfTwo();
        withSteps();
        JTextField field = doubleClick(1);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.panel.namingRow()).isEqualTo(1);
            assertThat(field.getText()).isEqualTo("Save the order");
            // no second header: the group's own holds the field
            assertThat(recording.panel.rows()).hasSize(6);
        });
        enter(field, "Save and close the order");
        closed();
        assertThat(sink.log).containsExactly("describe 2-4 Save and close the order");
    }

    @Test
    void theRowMenusDescribeOpensTheSameFieldInPlace() throws Exception {
        aGroupOfTwo();
        withSteps();
        assertThat(menuOn(0).labels()).contains("Describe this step…");
        JTextField step = choose(0, "Describe this step…");
        SwingUtilities.invokeAndWait(() -> {
            assertThat(step.getParent()).isSameAs(recording.panel.stepsList());
            assertThat(recording.panel.namingRow()).isEqualTo(0);
            key(step, KeyEvent.VK_ESCAPE);
        });
        // a right click on the group's header: its steps
        JTextField group = choose(1, "Describe these steps…");
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.panel.namingRow()).isEqualTo(1);
            assertThat(group.getText()).isEqualTo("Save the order");
        });
        enter(group, "Save and close the order");
        closed();
        assertThat(sink.log).containsExactly("describe 2-4 Save and close the order");
    }

    @Test
    void oneFieldAtATimeAnotherRowsEndsTheOpenOneSavingItsChange() throws Exception {
        sink.add("1", "click(#new)");
        sink.add("2", "click(#save)");
        withSteps();
        List<Component> given = new java.util.concurrent.CopyOnWriteArrayList<Component>();
        scene.inspector.focusOwner = () -> new JTextField("Ada");
        scene.inspector.giveFocus = given::add;
        JTextField first = doubleClick(0);
        SwingUtilities.invokeAndWait(() -> {
            // the same row again keeps the field
            recording.panel.select(0);
            key(recording.panel.stepsList(), KeyEvent.VK_F2);
            assertThat(recording.panel.nameField()).isSameAs(first);
            first.setText("Open it");
            recording.panel.select(1);
            key(recording.panel.stepsList(), KeyEvent.VK_F2);
            assertThat(recording.panel.namingRow()).isEqualTo(1);
            assertThat(recording.panel.nameField()).isNotSameAs(first);
            assertThat(first.getParent()).isNull();
        });
        eventually(() -> sink.log.isEmpty() ? null : Boolean.TRUE);
        assertThat(sink.log).containsExactly("describe 1-1 Open it");
        // the keyboard goes from field to field, not back to the app in between
        assertThat(given).isEmpty();
    }

    @Test
    void theRowMenusDescribeWhileAFieldIsOpenEndsItFirst() throws Exception {
        sink.add("1", "click(#new)");
        sink.add("2", "click(#save)");
        withSteps();
        JTextField first = doubleClick(0);
        SwingUtilities.invokeAndWait(() -> first.setText("Open it"));
        StepMenu menu = menuOn(1);
        assertThat(menu.enabled("Describe this step…")).isTrue();
        SwingUtilities.invokeAndWait(() -> menu.choose("Describe this step…"));
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.panel.namingRow()).isEqualTo(1);
            assertThat(first.getParent()).isNull();
        });
        eventually(() -> sink.log.isEmpty() ? null : Boolean.TRUE);
        assertThat(sink.log).containsExactly("describe 1-1 Open it");
    }

    @Test
    void afterSaveOrCancelTheKeyboardGoesBackToTheAppsComponent() throws Exception {
        sink.add("1", "click(#new)");
        withSteps();
        List<Component> given = new java.util.concurrent.CopyOnWriteArrayList<Component>();
        JTextField customer = new JTextField("Ada");
        scene.inspector.focusOwner = () -> customer;
        scene.inspector.giveFocus = given::add;
        JTextField cancelled = doubleClick(0);
        SwingUtilities.invokeAndWait(() -> key(cancelled, KeyEvent.VK_ESCAPE));
        assertThat(given).containsExactly(customer);

        JTextField described = doubleClick(0);
        enter(described, "Open a new order");
        closed();
        assertThat(given).containsExactly(customer, customer);
    }

    @Test
    void theKeyboardGoesBackToTheAppNotToTheStepsListThatTookIt() throws Exception {
        sink.add("1", "click(#new)");
        withSteps();
        List<Component> given = new java.util.concurrent.CopyOnWriteArrayList<Component>();
        JTextField customer = new JTextField("Ada");
        scene.inspector.giveFocus = given::add;
        // the person's click on a row took the keyboard from the app's field: the list has it when naming starts
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.JList<String> list = recording.panel.stepsList();
            FocusEvent gained = new FocusEvent(list, FocusEvent.FOCUS_GAINED, false, customer);
            for (FocusListener l : list.getFocusListeners()) l.focusGained(gained);
        });
        scene.inspector.focusOwner = () -> recording.panel.stepsList();
        JTextField field = doubleClick(0);
        SwingUtilities.invokeAndWait(() -> key(field, KeyEvent.VK_ESCAPE));
        assertThat(given).containsExactly(customer);
    }

    @Test
    void withNoAppComponentKnownNothingIsGivenBack() throws Exception {
        sink.add("1", "click(#new)");
        withSteps();
        List<Component> given = new java.util.concurrent.CopyOnWriteArrayList<Component>();
        scene.inspector.giveFocus = given::add;
        scene.inspector.focusOwner = () -> recording.panel.stepsList();
        JTextField field = doubleClick(0);
        SwingUtilities.invokeAndWait(() -> key(field, KeyEvent.VK_ESCAPE));
        assertThat(given).isEmpty();
    }

    @Test
    void whatIsTypedInTheFieldIsNeitherRecordedNorTheApps() throws Exception {
        Inspector before = Inspector.swap(scene.inspector);
        try {
            recordInFrame();
            SwingUtilities.invokeAndWait(() -> {
                recording.toggleSteps();
                recording.live.stop();
            });
            sink.person("click(#new)");
            settleRefresh();
            JTextField field = doubleClick(0);
            SwingUtilities.invokeAndWait(() -> {
                assertThat(Overlay.ours(field)).isTrue();
                assertThat(recording.listening()).isTrue();
                field.dispatchEvent(new KeyEvent(field, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0,
                        KeyEvent.VK_UNDEFINED, 'O'));
                field.setText("Open a new order");
                // Enter and Esc are the field's own while it has the keyboard: Swing runs them before the window's
                // bindings (its default button, a dialog's Esc), so they never reach the app
                for (int code : new int[] {KeyEvent.VK_ENTER, KeyEvent.VK_ESCAPE}) {
                    assertThat(field.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(code, 0))).isNotNull();
                }
            });
            enter(field, "Open a new order");
            closed();
            settle();
            // the recording got the sentence, no step of the typing or of Enter
            assertThat(sink.lines()).containsExactly("click(#new)");
            assertThat(sink.log).containsExactly("begin", "describe 1-1 Open a new order");
        } finally {
            Inspector.swap(before);
        }
    }

    @Test
    void escInTheFieldWhileCheckingCancelsTheFieldNotCheckMode() throws Exception {
        recordInFrame();
        sink.person("click(#new)");
        SwingUtilities.invokeAndWait(() -> {
            recording.toggleSteps();
            recording.live.stop();
            recording.mode(true);
        });
        settleRefresh();
        JTextField field = doubleClick(0);
        SwingUtilities.invokeAndWait(() -> {
            KeyEvent esc = esc(field, KeyEvent.KEY_PRESSED);
            assertThat(taken(esc)).isFalse();
            assertThat(recording.state).isEqualTo(Recording.State.CHECKING);
            key(field, KeyEvent.VK_ESCAPE);
            assertThat(recording.panel.naming()).isFalse();
        });
    }

    @Test
    void noFieldOpensWhileAReplayRunsAndTheReplaySavesTheOpenOneFirst() throws Exception {
        sink.add("1", "click(#new)");
        sink.add("2", "click(#save)");
        withSteps();
        List<Component> given = new java.util.concurrent.CopyOnWriteArrayList<Component>();
        JTextField customer = new JTextField("Ada");
        scene.inspector.focusOwner = () -> customer;
        scene.inspector.giveFocus = given::add;
        JTextField field = doubleClick(0);
        SwingUtilities.invokeAndWait(() -> field.setText("Open it"));
        CountDownLatch inReplay = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        sink.onReset = () -> {
            inReplay.countDown();
            try { letGo.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.speed(0);
            recording.panel.press("▶ Replay all");
            // the replay closed the field as a click elsewhere would: saved, the keyboard back with the app
            assertThat(recording.panel.naming()).isFalse();
            assertThat(field.getParent()).isNull();
        });
        assertThat(given).containsExactly(customer);
        assertThat(inReplay.await(5, TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.select(1);
            key(recording.panel.stepsList(), KeyEvent.VK_F2);
            assertThat(recording.panel.naming()).isFalse();
        });
        letGo.countDown();
        eventually(() -> !recording.replaying ? Boolean.TRUE : null);
        // the sentence went on ahead of the replay
        assertThat(sink.log).startsWith("describe 1-1 Open it", "reset");
    }

    @Test
    void theAgentsReplayDropsTheSentenceBeingTypedAndGivesTheKeyboardBack() throws Exception {
        sink.add("1", "click(#new)");
        withSteps();
        List<Component> given = new java.util.concurrent.CopyOnWriteArrayList<Component>();
        JTextField customer = new JTextField("Ada");
        scene.inspector.focusOwner = () -> customer;
        scene.inspector.giveFocus = given::add;
        JTextField field = doubleClick(0);
        SwingUtilities.invokeAndWait(() -> {
            field.setText("Open it");
            recording.agentReplay(new Bar("Agent replaying", ""));
            assertThat(recording.panel.naming()).isFalse();
            assertThat(field.getParent()).isNull();
            recording.agentReplayEnded();
        });
        settle();
        assertThat(sink.log).isEmpty();
        assertThat(given).containsExactly(customer);
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
        sink.add("person", "4", "click(table 'Orders' · row 0 · Customer)", null, 0, null,
                new Inspector.RowLine("position", null, 0));
        withSteps();
        StepMenu menu = menuOn(0);
        // the person reads positions from 1: index 1 is the second row
        assertThat(menu.labels()).contains("Row: this value (Order=ORD-1641)", "Row: by position (row 2)");
        assertThat(menu.selected("Row: this value (Order=ORD-1641)")).isTrue();
        assertThat(menu.enabled("Row: by position (row 2)")).isTrue();
        SwingUtilities.invokeAndWait(() -> menu.choose("Row: by position (row 2)"));
        eventually(() -> "row by position".equals(recording.bar.text()) ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("row 1 position");

        // a step whose position is not known yet: that way is greyed until a replay learns it
        StepMenu unknown = menuOn(1);
        assertThat(unknown.labels()).contains("Row: by position (replay it once)");
        assertThat(unknown.enabled("Row: by position (replay it once)")).isFalse();
        assertThat(unknown.enabled("Row: this value (Order=ORD-1642)")).isTrue();
        // a value that is not known: not known yet, or no column tells the rows apart
        StepMenu noValue = menuOn(3);
        assertThat(noValue.labels()).contains("Row: this value (not known)", "Row: by position (row 1)");
        assertThat(noValue.enabled("Row: this value (not known)")).isFalse();
        assertThat(noValue.selected("Row: by position (row 1)")).isTrue();
        // a step without a row offers no row
        assertThat(menuOn(2).labels()).noneMatch(l -> l.startsWith("Row:"));
        // rows of several steps: a row's meaning is one step's, so none is offered
        SwingUtilities.invokeAndWait(() -> recording.panel.selectRange(0, 1));
        assertThat(menuOn(0).labels()).noneMatch(l -> l.startsWith("Row:"));
    }

    @Test
    void deleteIsGreyedForASelectionOfSeveralSteps() throws Exception {
        aGroupOfTwo();
        withSteps();
        SwingUtilities.invokeAndWait(() -> {
            StepsPanel p = recording.panel;
            assertThat(p.deleteEnabled()).isTrue();
            assertThat(p.deleteTip()).isEqualTo("a step with its checks, or one check");
            // a header selects two steps: Delete would take only one of them
            p.select(1);
            assertThat(p.deleteEnabled()).isFalse();
            assertThat(p.deleteTip()).isEqualTo("select one step or check");
            p.press("✕ Delete");
            // a step and its check are one step
            p.selectRange(2, 3);
            assertThat(p.deleteEnabled()).isTrue();
        });
        settle();
        assertThat(sink.log).isEmpty();
    }

    @Test
    void theReplayCounterCountsStepsOnly() throws Exception {
        sink.add("1", "click(#new)");
        sink.add("2", "click(#save)");
        sink.add("2.1", "expect(#status text 'saved')");
        sink.add("3", "click(#close)");
        ids("new", "save", "status", "close");
        withSteps();
        List<String> shown = replayBarTexts();
        assertThat(shown).containsExactly("1/3  click “new”", "2/3  click “save” + 1 check", "2/3  check 2.1 “status”",
                "3/3  click “close”");
    }

    @Test
    void aCheckOnlyStepCountsAsStepOne() throws Exception {
        sink.add("1.1", "expect(#total text '0.00')");
        sink.add("2", "click(#new)");
        ids("total", "new");
        withSteps();
        assertThat(replayBarTexts()).containsExactly("1/2  check 1.1 “total”", "2/2  click “new”");
    }

    /** The records' targets, in order: the components named {@code ids}. */
    private void ids(String... ids) {
        for (int i = 0; i < ids.length; i++) sink.targets.put(i, Locator.ofId(ids[i]));
    }

    @Test
    void theReplayBarNamesWhatEachStepDoesWithoutTheLocator() throws Exception {
        sink.add("1", "click(button 'Add to cart' [New order] in main)");
        sink.add("2", "type(text 'Customer' [New order], Ada)");
        sink.add("2.1", "expect(table 'Cart' row={SKU=PEN-GEL-BLK} column=Qty cell '3')");
        sink.add("3", "menu(File > New order)");
        sink.add("4", "press(Escape)");
        sink.targets.put(0, scoped(Locator.of("button", "Add to cart")));
        sink.targets.put(1, scoped(Locator.of("text", "Customer")));
        Locator cell = Locator.of("table", "Cart");
        cell.row = Collections.<String, Object>singletonMap("SKU", "PEN-GEL-BLK");
        cell.column = "Qty";
        sink.targets.put(2, cell);
        withSteps();
        assertThat(replayBarTexts()).containsExactly("1/4  click “Add to cart”", "2/4  type into “Customer” + 1 check",
                "2/4  check 2.1 “Qty” in “Cart”", "3/4  menu File > New order", "4/4  press Escape");
    }

    @Test
    void aSentenceIsWhatTheReplayBarSays() throws Exception {
        sink.add("person", "1", "click(button 'Add to cart')", "Add the pen to the cart", 0, null, null);
        sink.add("person", "1.1", "expect(label 'Total' text '3.00')", "The total is 3.00", 0, null, null);
        sink.add("2", "click(button 'Checkout…')");
        sink.targets.put(0, Locator.of("button", "Add to cart"));
        sink.targets.put(1, Locator.of("label", "Total"));
        sink.targets.put(2, Locator.of("button", "Checkout…"));
        withSteps();
        assertThat(replayBarTexts()).containsExactly("1/2  Add the pen to the cart + 1 check", "1/2  The total is 3.00",
                "2/2  click “Checkout…”");
    }

    @Test
    void theBoxesBesideTheElementsShowTheNumberAndTheSentenceOnly() throws Exception {
        sink.add("person", "1", "click(button 'Save')", "Save the order", 0, null, null);
        sink.add("person", "1.1", "expect(#customer value 'Ada')", null, 0, null, null);
        sink.add("person", "1.2", "expect(checkbox 'Rush' state checked)", "It is a rush order", 0, null, null);
        sink.add("2", "click(checkbox 'Rush')");
        sink.targets.put(0, Locator.of("button", "Save"));
        sink.targets.put(1, Locator.ofId("customer"));
        sink.targets.put(2, Locator.of("checkbox", "Rush"));
        sink.targets.put(3, Locator.of("checkbox", "Rush"));
        sink.failStep = 2;
        withSteps();
        Overlay o = appOverlay();
        List<List<String>> boxes = Collections.synchronizedList(new java.util.ArrayList<List<String>>());
        sink.onReplay = () -> {
            try {
                SwingUtilities.invokeAndWait(() -> boxes.add(o.markLabels()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.speed(0);
            recording.panel.press("▶ Replay all");
        });
        eventually(() -> !recording.replaying ? Boolean.TRUE : null);
        // as each record runs: its own box, and the results of its step's records before it
        assertThat(boxes).containsExactly(
                Collections.singletonList("1  Save the order"),
                Arrays.asList("1  Save the order", "1.1"),
                Arrays.asList("1  Save the order", "1.1 ✓", "1.2  It is a rush order"));
        for (List<String> shown : boxes) {
            for (String label : shown) assertThat(label).doesNotContain("'", "#", "(", "expect", "click");
        }
    }

    @Test
    void stepByStepTheBoxesKeepTheResultsAndTheNextOneSaysUpNext() throws Exception {
        sink.add("1", "click(button 'Save')");
        sink.add("1.1", "expect(#customer value 'Ada')");
        sink.add("person", "2", "click(checkbox 'Rush')", "Make it a rush order", 0, null, null);
        sink.targets.put(0, Locator.of("button", "Save"));
        sink.targets.put(1, Locator.ofId("customer"));
        sink.targets.put(2, Locator.of("checkbox", "Rush"));
        withSteps();
        Overlay o = scene.inspector.overlay(frame.getRootPane());
        Overlay app = appOverlay();
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.speed(3);
            recording.panel.press("▶ Replay all");
        });
        Bar replayBar = eventually(() -> o.bar() != recording.bar ? o.bar() : null);
        eventually(() -> replayBar.text().startsWith("1/2") ? Boolean.TRUE : null);
        recording.next.release();
        String upNext = eventually(() -> replayBar.text().startsWith("2/2") ? replayBar.text() : null);
        assertThat(upNext).isEqualTo("2/2  Make it a rush order   - paused: Next runs it");
        SwingUtilities.invokeAndWait(() ->
                assertThat(app.markLabels()).containsExactly("1", "1.1 ✓", "2  Make it a rush order   (up next)"));
        recording.next.release();
        eventually(() -> !recording.replaying ? Boolean.TRUE : null);
    }

    /**
     * The scene's app components in a root pane of their own (an unshown window would hide them from the resolver):
     * its overlay, where the replay's boxes go up.
     */
    private Overlay appOverlay() throws Exception {
        Overlay[] o = new Overlay[1];
        SwingUtilities.invokeAndWait(() -> {
            JRootPane app = new JRootPane();
            app.setBounds(0, 0, 400, 200);
            app.getContentPane().add(scene.content);
            app.doLayout();
            app.getLayeredPane().setBounds(0, 0, 400, 200);
            o[0] = scene.inspector.overlay(app);
        });
        return o[0];
    }

    /** {@code l} with a scope and a window, as the recorder writes them: what the replay must not show. */
    private static Locator scoped(Locator l) {
        l.scope = Collections.singletonList("New order");
        l.window = "main";
        return l;
    }

    /** Replays all at Fast; returns what the replay bar said as each record ran. */
    private List<String> replayBarTexts() throws Exception {
        List<String> shown = Collections.synchronizedList(new java.util.ArrayList<String>());
        Overlay o = scene.inspector.overlay(frame.getRootPane());
        sink.onReplay = () -> {
            try {
                SwingUtilities.invokeAndWait(() -> shown.add(o.bar().text()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.speed(0);
            recording.panel.press("▶ Replay all");
        });
        eventually(() -> !recording.replaying ? Boolean.TRUE : null);
        return shown;
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
    void aRefreshWhileTheFieldIsOpenKeepsItOnItsRecord() throws Exception {
        sink.add("1", "click(#new)");
        sink.add("2", "click(#save)");
        withSteps();
        JTextField field = doubleClick(1);
        SwingUtilities.invokeAndWait(() -> field.setText("Save it"));
        // the agent puts a step in at the top while the person types
        sink.insert(0, "click(#x)");
        settleRefresh();
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.panel.nameField()).isSameAs(field);
            assertThat(field.getText()).isEqualTo("Save it");
            // it moved down with its step
            assertThat(recording.panel.namingRow()).isEqualTo(2);
            assertThat(field.getY()).isEqualTo(recording.panel.stepsList().getCellBounds(2, 2).y);
        });
        enter(field, "Save it");
        closed();
        assertThat(sink.log).containsExactly("describe 2-2 Save it");
    }

    @Test
    void aNewGroupsHeaderRowStaysAboveItsStepsThroughARefresh() throws Exception {
        sink.add("1", "click(#new)");
        sink.add("2", "click(#save)");
        sink.add("3", "click(#close)");
        withSteps();
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.selectRange(1, 2);
            key(recording.panel.stepsList(), KeyEvent.VK_F2);
        });
        sink.insert(0, "click(#x)");
        settleRefresh();
        // (the fake session keeps the labels it gave; the field's header goes with the steps, not with row 2)
        assertThat(rows()).containsExactly("1  ✎  agent: click(#x)", "1  ●  click(#new)", "▾ ", "2  ●  click(#save)",
                "3  ●  click(#close)");
        SwingUtilities.invokeAndWait(() -> assertThat(recording.panel.namingRow()).isEqualTo(2));
    }

    @Test
    void whenTheAgentTookTheRecordOutTheFieldClosesNamingNothing() throws Exception {
        sink.add("1", "click(#new)");
        sink.add("2", "click(#save)");
        withSteps();
        List<Component> given = new java.util.concurrent.CopyOnWriteArrayList<Component>();
        JTextField customer = new JTextField("Ada");
        scene.inspector.focusOwner = () -> customer;
        scene.inspector.giveFocus = given::add;
        JTextField field = doubleClick(1);
        SwingUtilities.invokeAndWait(() -> field.setText("Save it"));
        sink.steps.remove(1);
        settleRefresh();
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.panel.naming()).isFalse();
            assertThat(field.getParent()).isNull();
        });
        assertThat(sink.log).isEmpty();
        assertThat(given).containsExactly(customer);
    }

    // ---- a sentence being typed when the panel goes, the keyboard's way back, refusals --------------------------------

    /**
     * Step 1 in the steps panel, "Open a new order" typed in its field and the app's Customer field known as the one
     * the keyboard came from; {@code goes} (EDT) takes the panel away. Returns the components given the keyboard back.
     */
    private List<Component> typingWhenThePanelGoes(Runnable goes) throws Exception {
        sink.add("1", "click(#new)");
        withSteps();
        List<Component> given = new java.util.concurrent.CopyOnWriteArrayList<Component>();
        JTextField customer = new JTextField("Ada");
        scene.inspector.focusOwner = () -> customer;
        JTextField field = doubleClick(0);
        // the keyboard goes back while the field is still in the list: AWT never hands it on from a field it removes
        List<Object> parentAtGiveBack = new java.util.concurrent.CopyOnWriteArrayList<Object>();
        scene.inspector.giveFocus = c -> {
            parentAtGiveBack.add(String.valueOf(field.getParent() != null));
            return given.add(c);
        };
        SwingUtilities.invokeAndWait(() -> {
            field.setText("Open a new order");
            goes.run();
            assertThat(field.getParent()).isNull();
        });
        eventually(() -> sink.log.contains("describe 1-1 Open a new order") ? Boolean.TRUE : null);
        assertThat(given).containsExactly(customer);
        assertThat(parentAtGiveBack).containsExactly("true");
        return given;
    }

    @Test
    void closingTheStepsPanelWhileTypingSavesTheSentence() throws Exception {
        typingWhenThePanelGoes(() -> recording.toggleSteps());
        assertThat(recording.panel).isNull();
    }

    @Test
    void loadRecordedTestTakingThePanelsPlaceSavesTheSentence() throws Exception {
        typingWhenThePanelGoes(() -> recording.closePanels());
    }

    @Test
    void aNewRecordingReplacingThisOneGivesTheKeyboardBackAndSavesNothing() throws Exception {
        sink.add("1", "click(#new)");
        withSteps();
        List<Component> given = new java.util.concurrent.CopyOnWriteArrayList<Component>();
        JTextField customer = new JTextField("Ada");
        scene.inspector.focusOwner = () -> customer;
        scene.inspector.giveFocus = given::add;
        JTextField field = doubleClick(0);
        SwingUtilities.invokeAndWait(() -> {
            field.setText("Open a new order");
            recording.dispose();
            assertThat(field.getParent()).isNull();
        });
        settle();
        // its records went with it
        assertThat(sink.log).isEmpty();
        assertThat(given).containsExactly(customer);
    }

    @Test
    void aModalDialogTakingThePaletteWhileTypingSavesTheSentence() throws Exception {
        typingWhenThePanelGoes(() -> recording.rehome(scene.inspector.overlay(dialog.getRootPane())));
        SwingUtilities.invokeAndWait(() -> {
            assertThat(scene.inspector.overlay(dialog.getRootPane()).holds(recording.panel)).isTrue();
            assertThat(recording.panel.naming()).isFalse();
        });
    }

    @Test
    void exportTakingThePanelsPlaceSavesTheSentence() throws Exception {
        sink.add("1", "click(#new)");
        withSteps();
        SwingUtilities.invokeAndWait(recording::done);
        recording.done.get(5, TimeUnit.SECONDS);
        JTextField field = doubleClick(0);
        SwingUtilities.invokeAndWait(() -> {
            field.setText("Open a new order");
            recording.toggleExport();
        });
        eventually(() -> recording.exportPanel);
        SwingUtilities.invokeAndWait(() -> assertThat(field.getParent()).isNull());
        assertThat(sink.log).contains("describe 1-1 Open a new order");
    }

    @Test
    void escAndEnterGiveTheKeyboardBackBeforeTheFieldLeavesTheList() throws Exception {
        sink.add("1", "click(#new)");
        withSteps();
        JTextField customer = new JTextField("Ada");
        scene.inspector.focusOwner = () -> customer;
        for (int code : new int[] {KeyEvent.VK_ESCAPE, KeyEvent.VK_ENTER}) {
            JTextField field = doubleClick(0);
            List<Boolean> inTheList = new java.util.concurrent.CopyOnWriteArrayList<Boolean>();
            scene.inspector.giveFocus = c -> inTheList.add(field.getParent() != null);
            SwingUtilities.invokeAndWait(() -> {
                field.setText("Open a new order");
                key(field, code);
            });
            closed();
            SwingUtilities.invokeAndWait(() -> assertThat(field.getParent()).isNull());
            assertThat(inTheList).containsExactly(true);
        }
    }

    @Test
    void aClickOnAnotherRowSavesAndGivesTheKeyboardBack() throws Exception {
        sink.add("1", "click(#new)");
        sink.add("2", "click(#save)");
        withSteps();
        List<Component> given = new java.util.concurrent.CopyOnWriteArrayList<Component>();
        JTextField customer = new JTextField("Ada");
        scene.inspector.focusOwner = () -> customer;
        scene.inspector.giveFocus = given::add;
        // unchanged: closes, the keyboard leaves the list for the app
        JTextField same = doubleClick(0);
        SwingUtilities.invokeAndWait(() -> focusLeaves(same, recording.panel.stepsList()));
        assertThat(given).containsExactly(customer);
        // changed: saved, then the same
        JTextField changed = doubleClick(0);
        SwingUtilities.invokeAndWait(() -> {
            changed.setText("Open a new order");
            focusLeaves(changed, recording.panel.stepsList());
        });
        closed();
        assertThat(sink.log).containsExactly("describe 1-1 Open a new order");
        assertThat(given).containsExactly(customer, customer);
    }

    @Test
    void aRefusalAfterAClickElsewherePutsTheKeyboardBackInTheField() throws Exception {
        sink.add("1", "click(#new)");
        sink.add("2", "click(#save)");
        sink.refuseDescribe = "a sentence is one line";
        withSteps();
        JButton save = appButton();
        List<Component> taken = new java.util.concurrent.CopyOnWriteArrayList<Component>();
        JTextField field = doubleClick(0);
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.takeFocus = taken::add;
            field.setText("Open it");
            focusLeaves(field, save);
        });
        eventually(() -> recording.bar.text().contains("one line") ? Boolean.TRUE : null);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.panel.nameField()).isSameAs(field);
            assertThat(field.isEditable()).isTrue();
        });
        assertThat(taken).containsExactly(field);
        // a double click on another row does something: the open field ends (its change handed on again), a new one opens
        JTextField other = doubleClick(1);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(other).isNotSameAs(field);
            assertThat(recording.panel.namingRow()).isEqualTo(1);
        });
        eventually(() -> sink.log.size() == 2 ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("describe 1-1 Open it", "describe 1-1 Open it");
    }

    @Test
    void escOnTheAppWhileAFieldIsOpenStillLeavesCheckMode() throws Exception {
        JButton save = appButton();
        recordInFrame();
        sink.person("click(#new)");
        SwingUtilities.invokeAndWait(() -> {
            recording.toggleSteps();
            recording.live.stop();
            recording.mode(true);
        });
        settleRefresh();
        doubleClick(0);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.panel.naming()).isTrue();
            assertThat(taken(esc(save, KeyEvent.KEY_PRESSED))).isTrue();
            assertThat(recording.state).isEqualTo(Recording.State.RECORDING);
        });
    }

    @Test
    void aDoubleClickWhoseRowsChangedBetweenItsPressesNamesTheRowUnderTheMouse() throws Exception {
        sink.add("1", "click(#1)");
        sink.add("2", "click(#2)");
        sink.add("3", "click(#3)");
        sink.add("4", "click(#4)");
        withSteps();
        // steps 2-3 named as a new group: a header row of its own above them, they stay selected (rows 2-3)
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.selectRange(1, 2);
            key(recording.panel.stepsList(), KeyEvent.VK_F2);
        });
        JTextField field = onEdt(() -> recording.panel.nameField());
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.JList<String> list = recording.panel.stepsList();
            layOut(recording.panel, 460, 300);
            Rectangle cell = list.getCellBounds(3, 3);
            int x = cell.x + 8;
            int y = cell.y + cell.height / 2;
            long now = System.currentTimeMillis();
            // the first press, inside the selection: then the field closes on it and its header row goes
            list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_PRESSED, now, InputEvent.BUTTON1_DOWN_MASK, x, y, 1,
                    false, MouseEvent.BUTTON1));
            list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_RELEASED, now, 0, x, y, 1, false, MouseEvent.BUTTON1));
            list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_CLICKED, now, 0, x, y, 1, false, MouseEvent.BUTTON1));
            focusLeaves(field, list);
            assertThat(recording.panel.rows()).hasSize(4);
            list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_PRESSED, now, InputEvent.BUTTON1_DOWN_MASK, x, y, 2,
                    false, MouseEvent.BUTTON1));
            list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_RELEASED, now, 0, x, y, 2, false, MouseEvent.BUTTON1));
            list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_CLICKED, now, 0, x, y, 2, false, MouseEvent.BUTTON1));
            // row 3 is step 4 now: it alone is named, no group of the rows the first press saw
            assertThat(recording.panel.rows()).hasSize(4);
            assertThat(recording.panel.namingRow()).isEqualTo(3);
            assertThat(recording.panel.nameHint()).isEqualTo("click(#4)");
        });
    }

    @Test
    void someStepsOfAGroupGetAHeaderOfTheirOwnNotTheGroupsName() throws Exception {
        sink.add("1", "click(#new)");
        sink.add("person", "2", "click(#a)", null, 7, "Fill the order", null);
        sink.add("person", "3", "click(#b)", null, 7, "Fill the order", null);
        sink.add("person", "4", "click(#c)", null, 7, "Fill the order", null);
        withSteps();
        // rows: 1, header, 2, 3, 4: steps 2-3 of the group's three
        SwingUtilities.invokeAndWait(() -> {
            recording.panel.selectRange(2, 3);
            key(recording.panel.stepsList(), KeyEvent.VK_F2);
            assertThat(recording.panel.rows()).containsExactly("1  ●  click(#new)", "▾ Fill the order", "▾ ",
                    "  2  ●  click(#a)", "  3  ●  click(#b)", "  4  ●  click(#c)");
            assertThat(recording.panel.namingRow()).isEqualTo(2);
            assertThat(recording.panel.nameField().getText()).isEmpty();
            assertThat(recording.panel.nameHint()).isEqualTo("steps 2-3");
            key(recording.panel.nameField(), KeyEvent.VK_ESCAPE);
            // all three: the group's own header, its sentence to rename
            recording.panel.selectRange(2, 4);
            key(recording.panel.stepsList(), KeyEvent.VK_F2);
            assertThat(recording.panel.namingRow()).isEqualTo(1);
            assertThat(recording.panel.nameField().getText()).isEqualTo("Fill the order");
        });
    }

    @Test
    void aSavedSentenceShowsAsTheFieldClosesWithoutAnotherReRead() throws Exception {
        sink.add("1", "click(#new)");
        withSteps();
        sink.onDescribe = () -> {
            Inspector.StepLine s = sink.steps.get(0);
            sink.steps.set(0, new Inspector.StepLine(s.id, s.author, s.line, false, s.label, "Open a new order", false, 0, null));
        };
        JTextField field = doubleClick(0);
        CountDownLatch hold = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            field.setText("Open a new order");
            key(field, KeyEvent.VK_ENTER);
            // the sink thread is busy after the describe: a re-read would wait
            sinkThread.submit(() -> {
                try { hold.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
        });
        try {
            closed();
            assertThat(rows()).containsExactly("1  ●  Open a new order");
        } finally {
            hold.countDown();
        }
    }

    @Test
    void theKeyboardIsNeverGivenToAnAppComponentThatIsGone() throws Exception {
        int[] asked = {0};
        JTextField gone = new JTextField("Ada") {
            @Override public boolean requestFocusInWindow() {
                asked[0]++;
                return super.requestFocusInWindow();
            }
        };
        // a field of a dialog that closed: not showing
        boolean took = onEdt(() -> scene.inspector.giveFocus.test(gone));
        assertThat(took).isFalse();
        assertThat(asked[0]).isZero();
    }

    @Test
    void enterAndEscInTheFieldNeverReachTheWindowsDefaultButtonOrItsEscAction() throws Exception {
        int[] pressed = {0};
        int[] escaped = {0};
        JTextField app = new JTextField("Ada");
        SwingUtilities.invokeAndWait(() -> {
            JButton ok = new JButton("OK");
            ok.addActionListener(e -> pressed[0]++);
            javax.swing.JPanel content = new javax.swing.JPanel();
            content.add(app);
            content.add(ok);
            frame.setContentPane(content);
            frame.getRootPane().setDefaultButton(ok);
            // the window's Enter and Esc (a default button, a dialog's Esc), also bound for the root pane's descendants:
            // Swing asks those before the window's own bindings, and they work in a window that is not shown
            KeyStroke enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0);
            KeyStroke esc = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
            for (int when : new int[] {JComponent.WHEN_IN_FOCUSED_WINDOW, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT}) {
                frame.getRootPane().getInputMap(when).put(enter, "ok");
                frame.getRootPane().getInputMap(when).put(esc, "close");
            }
            frame.getRootPane().getActionMap().put("ok", new javax.swing.AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) { ok.doClick(); }
            });
            frame.getRootPane().getActionMap().put("close", new javax.swing.AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) { escaped[0]++; }
            });
            // an app field's Enter and Esc reach them
            stroke(app, KeyEvent.VK_ENTER);
            stroke(app, KeyEvent.VK_ESCAPE);
        });
        assertThat(pressed[0]).isEqualTo(1);
        assertThat(escaped[0]).isEqualTo(1);

        sink.add("1", "click(#new)");
        withSteps();
        JTextField field = doubleClick(0);
        SwingUtilities.invokeAndWait(() -> {
            field.setText("Open a new order");
            // taken by the field itself: Swing asks nobody after it
            assertThat(stroke(field, KeyEvent.VK_ENTER)).isTrue();
        });
        closed();
        assertThat(sink.log).containsExactly("describe 1-1 Open a new order");
        JTextField again = doubleClick(0);
        SwingUtilities.invokeAndWait(() -> assertThat(stroke(again, KeyEvent.VK_ESCAPE)).isTrue());
        closed();
        assertThat(pressed[0]).isEqualTo(1);
        assertThat(escaped[0]).isEqualTo(1);
    }

    /**
     * Presses and releases {@code code} on {@code on} as it has the keyboard: Swing's key bindings in their order - the
     * component's own, its ancestors', then the window's (a default button, a dialog's Esc). Whether the press was
     * taken. EDT.
     */
    private static boolean stroke(JComponent on, int code) {
        char c = code == KeyEvent.VK_ENTER ? '\n' : '\u001b';
        boolean taken = SwingUtilities.processKeyBindings(
                new KeyEvent(on, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, code, c));
        SwingUtilities.processKeyBindings(new KeyEvent(on, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, code, c));
        return taken;
    }

    @Test
    void checkModeSaysHowToGetOutAndTheRecordButtonLeadsBack() throws Exception {
        recordInFrame();
        SwingUtilities.invokeAndWait(() -> {
            assertThat(recording.bar.labels()).containsExactly("Record", "Check", "Steps", "Done");
            recording.mode(true);
            assertThat(recording.bar.labels()).containsExactly("◀ Back to recording", "Check", "Steps", "Done");
            assertThat(recording.bar.text()).isEqualTo("Click what to check · Esc or ◀ to go back");
            // a dialog that opens meanwhile keeps the label
            recording.mode(true);
            assertThat(recording.bar.labels()).containsExactly("◀ Back to recording", "Check", "Steps", "Done");

            recording.bar.press("◀ Back to recording");
            assertThat(recording.state).isEqualTo(Recording.State.RECORDING);
            assertThat(recording.bar.labels()).containsExactly("Record", "Check", "Steps", "Done");
            assertThat(recording.bar.text()).isEqualTo("Use the app: every step is recorded.");
        });
    }

    @Test
    void escInCheckModeGoesBackToRecordingAndTheAppNeverSeesIt() throws Exception {
        JButton save = appButton();
        recordInFrame();
        SwingUtilities.invokeAndWait(() -> {
            recording.mode(true);
            assertThat(taken(esc(save, KeyEvent.KEY_PRESSED))).isTrue();
            assertThat(recording.state).isEqualTo(Recording.State.RECORDING);
            assertThat(recording.listening()).isTrue();
            assertThat(recording.bar.labels()).containsExactly("Record", "Check", "Steps", "Done");
            assertThat(scene.inspector.overlay(frame.getRootPane()).catching()).isFalse();
            // the rest of that keystroke goes too
            assertThat(taken(esc(save, KeyEvent.KEY_TYPED))).isTrue();
            assertThat(taken(esc(save, KeyEvent.KEY_RELEASED))).isTrue();
            assertThat(ourDispatchers()).isEmpty();
            // the next Esc is the app's again
            assertThat(taken(esc(save, KeyEvent.KEY_PRESSED))).isFalse();
        });
        settle();
        assertThat(sink.lines()).isEmpty();
    }

    @Test
    void escWhileRecordingIsTheAppsAndRecordedAsBefore() throws Exception {
        JButton save = appButton();
        recordInFrame();
        SwingUtilities.invokeAndWait(() -> {
            KeyEvent press = esc(save, KeyEvent.KEY_PRESSED);
            assertThat(taken(press)).isFalse();
            assertThat(recording.state).isEqualTo(Recording.State.RECORDING);
            // what the recorder hears of it, as the toolkit hands it the event
            for (AWTEventListener l : Toolkit.getDefaultToolkit().getAWTEventListeners()) {
                if (((AWTEventListenerProxy) l).getListener() instanceof HumanSteps) l.eventDispatched(press);
            }
        });
        assertThat(eventually(() -> sink.lines().isEmpty() ? null : sink.lines())).containsExactly("press");
    }

    @Test
    void aClickNoStepCanSayIsShownAsNotRecordedAndListedForTheAgent() throws Exception {
        javax.swing.JList<String> customers = new javax.swing.JList<String>(new String[] {"Ada Shop Ltd", "Ada Shop Ltd"});
        SwingUtilities.invokeAndWait(() -> {
            frame.getContentPane().add(customers);
            customers.setSize(150, 40);
        });
        recordInFrame();
        SwingUtilities.invokeAndWait(() -> {
            java.awt.Rectangle second = customers.getCellBounds(1, 1);
            for (int id : new int[] {MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED}) {
                MouseEvent e = new MouseEvent(customers, id, 1L, id == MouseEvent.MOUSE_PRESSED ? InputEvent.BUTTON1_DOWN_MASK : 0,
                        second.x + 4, second.y + 2, 1, false, MouseEvent.BUTTON1);
                for (AWTEventListener l : Toolkit.getDefaultToolkit().getAWTEventListeners()) {
                    if (((AWTEventListenerProxy) l).getListener() instanceof HumanSteps) l.eventDispatched(e);
                }
            }
        });
        String why = "a click on 'Ada Shop Ltd': more than one entry of the list reads so";
        assertThat(eventually(() -> recording.bar.text().startsWith("✗") ? recording.bar.text() : null))
                .isEqualTo("✗ not recorded: " + why);
        assertThat(sink.log).contains("refused " + why);
        assertThat(sink.lines()).isEmpty();
    }

    @Test
    void escWithTheCheckMenuOpenOnlyClosesTheMenu() throws Exception {
        JButton save = appButton();
        recordInFrame();
        SwingUtilities.invokeAndWait(() -> {
            recording.mode(true);
            // a popup menu showing is on the menu selection path, as the check menu is
            CheckMenu menu = new CheckMenu();
            menu.setInvoker(save);
            MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[] {menu});
            try {
                assertThat(taken(esc(save, KeyEvent.KEY_PRESSED))).isFalse();
                assertThat(recording.state).isEqualTo(Recording.State.CHECKING);
            } finally {
                MenuSelectionManager.defaultManager().clearSelectedPath();
            }
            assertThat(taken(esc(save, KeyEvent.KEY_PRESSED))).isTrue();
            assertThat(recording.state).isEqualTo(Recording.State.RECORDING);
        });
    }

    @Test
    void escDuringAReplayIsNotTheCheckModes() throws Exception {
        JButton save = appButton();
        recordInFrame();
        SwingUtilities.invokeAndWait(() -> {
            recording.mode(true);
            recording.replaying = true;
            try {
                assertThat(taken(esc(save, KeyEvent.KEY_PRESSED))).isFalse();
                assertThat(recording.state).isEqualTo(Recording.State.CHECKING);
            } finally {
                recording.replaying = false;
            }
        });
    }

    @Test
    void escListensOnlyWhileChecking() throws Exception {
        recordInFrame();
        SwingUtilities.invokeAndWait(() -> {
            assertThat(ourDispatchers()).isEmpty();
            recording.mode(true);
            assertThat(ourDispatchers()).hasSize(1);
            recording.mode(true);
            assertThat(ourDispatchers()).hasSize(1);
            recording.mode(false);
            assertThat(ourDispatchers()).isEmpty();

            recording.mode(true);
            recording.done();
            assertThat(ourDispatchers()).isEmpty();
        });
        recording.done.get(5, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(() -> {
            Recording agents = new Recording(scene.inspector, sink, sinkThread, scene.inspector.overlay(frame.getRootPane()), () -> { });
            agents.startedByAgent();
            agents.mode(true);
            agents.stoppedByAgent();
            assertThat(ourDispatchers()).isEmpty();
            agents.dispose();

            Recording replaced = new Recording(scene.inspector, sink, sinkThread, scene.inspector.overlay(frame.getRootPane()), () -> { });
            replaced.start();
            replaced.mode(true);
            replaced.dispose();
            assertThat(ourDispatchers()).isEmpty();
        });
    }

    @Test
    void afterACheckTheBarSaysWhatWasRecordedThenHowToGetOutAgain() throws Exception {
        JButton save = appButton();
        recordInFrame();
        SwingUtilities.invokeAndWait(() -> {
            recording.hintBackMs = 400;
            recording.mode(true);
            java.util.Map<String, Object> step = new java.util.LinkedHashMap<String, Object>();
            step.put("action", "expect");
            recording.check(step, new Target(null, false, "Save", "Save", "button", save, null, -1, -1),
                    scene.inspector.overlay(frame.getRootPane()));
        });
        assertThat(eventually(() -> recording.bar.text().startsWith("recorded: ") ? recording.bar.text() : null))
                .isEqualTo("recorded: ok");
        assertThat(eventually(() -> recording.bar.text().startsWith("Click what") ? recording.bar.text() : null))
                .isEqualTo("Click what to check · Esc or ◀ to go back");
    }

    /** An app button in the frame, where the keyboard is. EDT-safe: called before anything shows. */
    private JButton appButton() throws Exception {
        JButton save = new JButton("Save");
        SwingUtilities.invokeAndWait(() -> frame.getContentPane().add(save));
        return save;
    }

    // ---- folds: a vocabulary's domain step in the place of the steps it stands for

    private static final String PEN_ROW = "click(table 'products' · SKU=PEN-GEL-BLK · Product)";
    private static final String ADD_BUTTON = "click(button 'Add to cart')";
    private static final String ADD_PEN = "Add Gel pen, black to the cart";

    /** The person's two steps that add Gel pen, black, folded into one do step. */
    private int penFold() {
        return sink.fold("person", ADD_PEN, Collections.singletonMap("product", "Gel pen, black"), PEN_ROW, ADD_BUTTON);
    }

    @Test
    void aFoldShowsAsOneRowWithItsRawStepsHidden() throws Exception {
        sink.person(PEN_ROW, ADD_BUTTON);
        withSteps();
        assertThat(rows()).containsExactly("1  ●  " + PEN_ROW, "2  ●  " + ADD_BUTTON);

        // folding recognised the steps after they were recorded: the list follows the recording as it changes
        sink.steps.clear();
        penFold();
        sink.person("click(button 'Checkout…')");
        settleRefresh();
        assertThat(rows()).containsExactly("1  ●  ▸ ◆ " + ADD_PEN, "2  ●  click(button 'Checkout…')");
        assertThat(onEdt(() -> recording.panel.title())).isEqualTo("Steps (2)");
        // its values in bold; on hover what it is and the steps it stands for
        assertThat(onEdt(() -> recording.panel.rendered(0))).contains("Add <b>Gel pen, black</b> to the cart");
        assertThat(onEdt(() -> recording.panel.tooltipAt(0)))
                .isEqualTo("do(order-desk:addToCart) - from 2 steps: " + PEN_ROW + "; " + ADD_BUTTON);

        // ▸ shows the raw steps under it, greyed; they are no steps of their own
        SwingUtilities.invokeAndWait(() -> recording.panel.toggleRaw(0));
        assertThat(rows()).containsExactly("1  ●  ▾ ◆ " + ADD_PEN, "         · " + PEN_ROW, "         · " + ADD_BUTTON,
                "2  ●  click(button 'Checkout…')");
        assertThat(onEdt(() -> recording.panel.greyed(1))).isTrue();
        assertThat(onEdt(() -> recording.panel.greyed(0))).isFalse();
        assertThat(onEdt(() -> recording.panel.idAt(1))).isEqualTo(-1);
        SwingUtilities.invokeAndWait(() -> recording.panel.select(2));
        assertThat(onEdt(() -> recording.panel.selected())).isEqualTo(-1);
        assertThat(onEdt(() -> recording.panel.title())).isEqualTo("Steps (2)");
        // they stay shown when the recording is read again; ▾ hides them
        settleRefresh();
        assertThat(rows()).hasSize(4);
        SwingUtilities.invokeAndWait(() -> recording.panel.toggleRaw(0));
        assertThat(rows()).containsExactly("1  ●  ▸ ◆ " + ADD_PEN, "2  ●  click(button 'Checkout…')");
        // a do step the agent ran has no raw steps: no ▸, and a click on it toggles nothing
        sink.agentDo("Place the order");
        settleRefresh();
        assertThat(rows()).containsExactly("1  ●  ▸ ◆ " + ADD_PEN, "2  ●  click(button 'Checkout…')", "3  ●  agent: ◆ Place the order");
        SwingUtilities.invokeAndWait(() -> recording.panel.toggleRaw(2));
        assertThat(rows()).hasSize(3);
    }

    @Test
    void aFoldsRowMenuRenamesUnfoldsAndDeletes() throws Exception {
        int pen = penFold();
        int paper = sink.fold("person", "Add A4 paper, 500 sheets to the cart",
                Collections.singletonMap("product", "A4 paper, 500 sheets"), "click(table 'products' · SKU=PAP-A4-500 · Product)",
                ADD_BUTTON);
        withSteps();

        StepMenu menu = menuOn(0);
        assertThat(menu.labels()).containsSubsequence("Rename…", "Unfold (show the 2 steps)", "Delete")
                .doesNotContain("Describe this step…");
        // Rename… is the step's sentence, typed in place
        JTextField field = choose(0, "Rename…");
        assertThat(field).isNotNull();
        assertThat(field.getText()).isEqualTo(ADD_PEN);
        SwingUtilities.invokeAndWait(() -> recording.panel.cancelNaming());

        StepMenu second = menuOn(1);
        SwingUtilities.invokeAndWait(() -> second.choose("Delete"));
        eventually(() -> sink.log.contains("delete " + paper) ? Boolean.TRUE : null);

        StepMenu first = menuOn(0);
        SwingUtilities.invokeAndWait(() -> first.choose("Unfold (show the 2 steps)"));
        eventually(() -> "unfolded step 1 into steps 1-2".equals(recording.bar.text()) ? Boolean.TRUE : null);
        assertThat(sink.log).contains("unfold " + pen);
        assertThat(rows()).containsExactly("1  ●  " + PEN_ROW, "2  ●  " + ADD_BUTTON);
        // a raw step has no Unfold; the agent's do step has nothing to unfold either
        assertThat(menuOn(0).labels()).doesNotContain("Unfold (show the 2 steps)", "Rename…").contains("Describe this step…");
        sink.agentDo("Place the order");
        settleRefresh();
        assertThat(menuOn(2).labels()).contains("Rename…", "Delete").noneMatch(l -> l.startsWith("Unfold"));
    }

    @Test
    void theUndoNoteTakesTheFoldBack() throws Exception {
        int pen = penFold();
        withSteps();
        String before = onEdt(() -> recording.bar.text());

        SwingUtilities.invokeAndWait(() -> recording.recognised(pen, ADD_PEN, 2));
        assertThat(onEdt(() -> recording.bar.text())).isEqualTo("recognised: " + ADD_PEN + " (from 2 steps) —");
        assertThat(onEdt(() -> recording.bar.labels())).startsWith("Undo");
        SwingUtilities.invokeAndWait(() -> recording.bar.press("Undo"));
        eventually(() -> recording.bar.text().startsWith("undid: ") ? Boolean.TRUE : null);
        assertThat(sink.log).containsExactly("undoFold " + pen);
        assertThat(onEdt(() -> recording.bar.text()))
                .isEqualTo("undid: fold of steps 1-2 into \"" + ADD_PEN + "\" (recognised in the person's steps)");
        assertThat(onEdt(() -> recording.bar.labels())).doesNotContain("Undo");
        assertThat(rows()).containsExactly("1  ●  " + PEN_ROW, "2  ●  " + ADD_BUTTON);

        // refused (the fold is no longer the last edit): the bar says why
        int again = penFold();
        sink.refuseUndoFold = "the recording changed since: Unfold on its row takes the fold back";
        SwingUtilities.invokeAndWait(() -> recording.recognised(again, ADD_PEN, 2));
        SwingUtilities.invokeAndWait(() -> recording.bar.press("Undo"));
        eventually(() -> recording.bar.text().startsWith("✗ not undone: ") ? Boolean.TRUE : null);
        assertThat(onEdt(() -> recording.bar.text())).endsWith("Unfold on its row takes the fold back");

        // a note nobody answers goes after a while, its Undo with it, and the bar says what it said before
        SwingUtilities.invokeAndWait(() -> {
            recording.bar.text(before);
            recording.recognisedMs = 150;
            recording.recognised(again, ADD_PEN, 2);
        });
        eventually(() -> before.equals(recording.bar.text()) ? Boolean.TRUE : null);
        assertThat(onEdt(() -> recording.bar.labels())).doesNotContain("Undo");
        // a newer line (a step recorded) ends it at once
        SwingUtilities.invokeAndWait(() -> {
            recording.recognisedMs = 60000;
            recording.recognised(again, ADD_PEN, 2);
        });
        SwingUtilities.invokeAndWait(() -> recording.note("the agent deleted step 3"));
        assertThat(onEdt(() -> recording.bar.labels())).doesNotContain("Undo");
    }

    @Test
    void inCheckModeThePluginsChecksComeFirstAndAnOlderClicksAreDropped() throws Exception {
        java.util.Map<String, Object> placed = new java.util.LinkedHashMap<String, Object>();
        placed.put("action", "do");
        placed.put("step", "order-desk:orderIsPlaced");
        sink.domainChecks = Collections.singletonList(new Inspector.DomainCheck("The order is placed", placed));
        List<CheckMenu> shown = Collections.synchronizedList(new java.util.ArrayList<CheckMenu>());
        recordInFrame();
        SwingUtilities.invokeAndWait(() -> {
            recording.showMenu = (m, e) -> shown.add(m);
            recording.mode(true);
        });
        Target save = onEdt(() -> scene.inspector.component(scene.save));
        MouseEvent click = new MouseEvent(scene.save, MouseEvent.MOUSE_PRESSED, 0, 0, 5, 5, 1, false);
        Overlay o = onEdt(() -> scene.inspector.overlay(frame.getRootPane()));

        // each click's menu shows at once, a "…" where the plugin's checks go; only the last click's get them
        SwingUtilities.invokeAndWait(() -> {
            recording.offerChecks(save, click, o);
            recording.offerChecks(save, click, o);
        });
        assertThat(shown).hasSize(2);
        CheckMenu older = shown.get(0);
        CheckMenu menu = shown.get(1);
        eventually(() -> menu.waiting() || older.waiting() ? null : Boolean.TRUE);
        assertThat(menu.labels()).containsSubsequence("Save", "✓ The order is placed", "Reads “Save”")
                .doesNotContain(CheckMenu.WAITING);
        assertThat(older.labels()).doesNotContain(CheckMenu.WAITING).noneMatch(l -> l.startsWith("✓"));
        // the plugin's checks were matched with the element's own checks, as offered
        assertThat(sink.askedChecks.get(0)).anySatisfy(s -> assertThat(s).containsEntry("text", "Save"));
        SwingUtilities.invokeAndWait(() -> menu.choose("✓ The order is placed"));
        eventually(() -> sink.lines().contains("do") ? Boolean.TRUE : null);

        // out of Check mode and back before the answer came: that menu gets none
        shown.clear();
        SwingUtilities.invokeAndWait(() -> {
            recording.offerChecks(save, click, o);
            recording.mode(false);
            recording.mode(true);
        });
        CheckMenu left = shown.get(0);
        eventually(() -> left.waiting() ? null : Boolean.TRUE);
        assertThat(left.labels()).noneMatch(l -> l.startsWith("✓"));
    }

    @Test
    void theCheckMenuOpensAtOnceWhileTheSinkThreadIsBusy() throws Exception {
        sink.domainChecks = Collections.singletonList(new Inspector.DomainCheck("The order is placed",
                Collections.<String, Object>singletonMap("action", "do")));
        List<CheckMenu> shown = Collections.synchronizedList(new java.util.ArrayList<CheckMenu>());
        withSteps();   // the recording on this test's sink thread, which the test holds below
        SwingUtilities.invokeAndWait(() -> {
            recording.showMenu = (m, e) -> shown.add(m);
            recording.mode(true);
        });
        Target save = onEdt(() -> scene.inspector.component(scene.save));
        MouseEvent click = new MouseEvent(scene.save, MouseEvent.MOUSE_PRESSED, 0, 0, 5, 5, 1, false);
        Overlay o = onEdt(() -> scene.inspector.overlay(frame.getRootPane()));
        // the person's step (or a check waiting out its timeout) holds the sink thread
        CountDownLatch busy = new CountDownLatch(1);
        sinkThread.submit(() -> { busy.await(); return null; });
        try {
            SwingUtilities.invokeAndWait(() -> recording.offerChecks(save, click, o));
            assertThat(shown).hasSize(1);
            assertThat(shown.get(0).labels()).contains("Reads “Save”");
            // the plugin's checks do not wait for the sink thread either
            eventually(() -> shown.get(0).waiting() ? null : Boolean.TRUE);
            assertThat(shown.get(0).labels()).contains("✓ The order is placed");
        } finally {
            busy.countDown();
        }
    }

    @Test
    void underTheStepsTheLastOnesSayWhatTheyCouldBecome() throws Exception {
        sink.person(PEN_ROW);
        sink.foldHint = "… could become: " + ADD_PEN + " (1 of 2)";
        withSteps();
        assertThat(onEdt(() -> recording.panel.hint())).isEqualTo("… could become: " + ADD_PEN + " (1 of 2)");

        sink.foldHint = null;
        settleRefresh();
        assertThat(onEdt(() -> recording.panel.hint())).isNull();
    }

    private static KeyEvent esc(Component on, int id) {
        return new KeyEvent(on, id, System.currentTimeMillis(), 0,
                id == KeyEvent.KEY_TYPED ? KeyEvent.VK_UNDEFINED : KeyEvent.VK_ESCAPE, '\u001b');
    }

    /** Whether a key event dispatcher takes {@code e}, as the keyboard focus manager asks them before anyone else. EDT. */
    private static boolean taken(KeyEvent e) {
        for (KeyEventDispatcher d : dispatchers()) if (d.dispatchKeyEvent(e)) return true;
        return false;
    }

    /** The palette's key event dispatchers. EDT. */
    private static List<KeyEventDispatcher> ourDispatchers() {
        List<KeyEventDispatcher> out = new java.util.ArrayList<KeyEventDispatcher>();
        for (KeyEventDispatcher d : dispatchers()) {
            if (d.getClass().getName().startsWith("io.github.kormcrete.swingui")) out.add(d);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<KeyEventDispatcher> dispatchers() {
        try {
            Method m = KeyboardFocusManager.class.getDeclaredMethod("getKeyEventDispatchers");
            m.setAccessible(true);
            List<KeyEventDispatcher> l = (List<KeyEventDispatcher>) m.invoke(KeyboardFocusManager.getCurrentKeyboardFocusManager());
            return l == null ? Collections.<KeyEventDispatcher>emptyList() : l;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
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
