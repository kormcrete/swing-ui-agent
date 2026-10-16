package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.input.HitCheck;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDesktopPane;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JInternalFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Boxes snap to what a locator can address, and a table cell is addressed by its row's key. */
class InspectorTest {

    private final Scene scene = new Scene();

    @Test
    void aButtonSnapsToTheLocatorTheRecorderWrites() {
        Target t = scene.inspector.snap(scene.save, new Point(5, 5));
        assertThat(t.locator.role).isEqualTo("button");
        assertThat(t.locator.name).isEqualTo("Save");
        assertThat(t.element).contains("button 'Save'");
        assertThat(t.fragile).isFalse();
    }

    // ---- what the person reads: what they see, never the locator ---------------------------------------------------

    @Test
    void thePersonReadsWhatTheySeeNotTheLocator() throws Exception {
        onEdt(() -> {
            JTextField customer = labelled("Customer", 400);
            // a second Save makes the recorder fall back to nth: the agent's line says so, the person's does not
            JButton other = new JButton("Save");
            other.setBounds(10, 200, 80, 24);
            scene.content.add(other);

            assertThat(scene.inspector.cell(scene.orders, 1, 2).shown).isEqualTo("ORD-1641 · Status");
            assertThat(scene.inspector.column(scene.orders, 1).shown).isEqualTo("Customer column");
            Target save = scene.inspector.component(scene.save);
            assertThat(save.shown).isEqualTo("Save");
            assertThat(save.element).contains("nth").contains("(fragile)");
            Target field = scene.inspector.component(customer);
            assertThat(field.shown).isEqualTo("Customer");
            assertThat(field.element).contains("#customerName");
            assertThat(scene.inspector.component(scene.orders).shown).isEqualTo("table");
        });
    }

    @Test
    void aLongLabelEndsInAnEllipsisAndTheColumnStays() throws Exception {
        onEdt(() -> {
            scene.orders.setValueAt("ORD-1641 for the very long customer name of a wholesale stationery shop", 1, 0);
            String shown = scene.inspector.cell(scene.orders, 1, 2).shown;
            assertThat(shown).hasSizeLessThanOrEqualTo(Target.SHOWN_MAX).startsWith("ORD-1641 for").endsWith("… · Status");
        });
    }

    @Test
    void theHoverBoxAndTheAgentsBoxSayWhatThePersonSees() throws Exception {
        Overlay[] o = new Overlay[1];
        onEdt(() -> {
            JRootPane app = new JRootPane();
            app.setBounds(0, 0, 400, 200);
            app.getContentPane().add(scene.content);
            app.doLayout();
            app.getLayeredPane().setBounds(0, 0, 400, 200);
            layOut(app);
            o[0] = scene.inspector.overlay(app);
            Point p = inside(scene.save);
            scene.inspector.hoverIn(o[0], new java.awt.event.MouseEvent(scene.save, java.awt.event.MouseEvent.MOUSE_MOVED,
                    0, 0, p.x, p.y, 0, false));
            assertThat(o[0].hoverLabel()).isEqualTo("Save");
        });
        List<Target> one = scene.inspector.targets(Collections.singletonList(
                scene.resolver.resolve(io.github.kormcrete.swingui.driver.model.Locator.of("button", "Save"))));
        scene.inspector.highlight(one, null, 0);
        onEdt(() -> {
            // the question swept the hover's overlay away: the box went up in the window's new one
            Overlay boxes = scene.inspector.overlayFor(scene.save);
            assertThat(boxes.markLabels()).containsExactly("Save");
            boxes.dispose();
        });
    }

    /** A text field with an id in code and {@code caption} beside it on screen, at {@code y}. EDT. */
    private JTextField labelled(String caption, int y) {
        JLabel label = new JLabel(caption);
        JTextField field = new JTextField();
        field.setName("customerName");
        label.setLabelFor(field);
        label.setBounds(10, y, 80, 24);
        field.setBounds(100, y, 120, 24);
        scene.content.add(label);
        scene.content.add(field);
        return field;
    }

    @Test
    void aFieldWithANameSnapsToItsId() {
        Target t = scene.inspector.component(scene.customer);
        assertThat(t.locator.id).isEqualTo("customer");
    }

    @Test
    void aPointInATableSnapsToThatCellKeyedByItsFirstUniqueColumn() {
        Rectangle cell = scene.orders.getCellRect(1, 2, true);
        Target t = scene.inspector.snap(scene.orders, new Point(cell.x + 2, cell.y + 2));
        assertThat(t.role).isEqualTo("cell");
        assertThat(t.locator.row).isEqualTo(Collections.<String, Object>singletonMap("Order", "ORD-1641"));
        assertThat(t.locator.column).isEqualTo("Status");
        assertThat(t.row).isEqualTo(1);
        assertThat(t.column).isEqualTo(2);
    }

    @Test
    void theHeaderSnapsToTheWholeColumn() {
        Rectangle h = scene.header.getHeaderRect(1);
        Target t = scene.inspector.snap(scene.header, new Point(h.x + 3, 3));
        assertThat(t.role).isEqualTo("column");
        assertThat(t.locator.column).isEqualTo("Customer");
        assertThat(t.locator.row).isNull();
        assertThat(t.wholeColumn()).isTrue();
    }

    @Test
    void aPointInASpinnerCellEditorSnapsToTheCellBeingEdited() throws Exception {
        onEdt(() -> {
            JTable cart = cart(new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(1, 1, 99, 1)));
            cart.doLayout();
            cart.editCellAt(1, 1);
            javax.swing.JSpinner spinner = (javax.swing.JSpinner) cart.getEditorComponent();
            layOut(spinner);
            JTextField text = ((javax.swing.JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
            // what the palette finds under the pointer: the spinner's own text field, not the editor component
            Point inText = SwingUtilities.convertPoint(text, inside(text), cart);
            assertThat(SwingUtilities.getDeepestComponentAt(cart, inText.x, inText.y)).isSameAs(text);

            for (Component under : Arrays.asList(text, arrowOf(spinner))) {
                Target t = scene.inspector.snapNow(under, inside(under));
                assertThat(t.role).isEqualTo("cell");
                assertThat(t.component).isSameAs(cart);
                assertThat(t.locator.row).isEqualTo(Collections.<String, Object>singletonMap("Product", "Gel pen"));
                assertThat(t.locator.column).isEqualTo("Qty");
                assertThat(t.row).isEqualTo(1);
                assertThat(t.column).isEqualTo(1);
                assertThat(CheckMenu.choices(t)).extracting(c -> c.label).contains("Qty is “2” in this row");
            }
        });
    }

    @Test
    void aPointInATextFieldCellEditorSnapsToTheCellBeingEdited() throws Exception {
        onEdt(() -> {
            JTable cart = cart(new JTextField());
            cart.editCellAt(1, 1);
            Component editor = cart.getEditorComponent();
            Target t = scene.inspector.snap(editor, inside(editor));
            assertThat(t.role).isEqualTo("cell");
            assertThat(t.locator.row).isEqualTo(Collections.<String, Object>singletonMap("Product", "Gel pen"));
            assertThat(t.locator.column).isEqualTo("Qty");
        });
    }

    /** A two-row cart whose Qty column edits in {@code editor} (a spinner or a text field). */
    private JTable cart(javax.swing.JComponent editor) {
        JTable cart = new JTable(new javax.swing.table.DefaultTableModel(
                new Object[][] {{"A4 paper", 1}, {"Gel pen", 2}}, new Object[] {"Product", "Qty"}));
        javax.swing.table.TableCellEditor cells = editor instanceof JTextField
                ? new javax.swing.DefaultCellEditor((JTextField) editor) : new SpinnerEditor((javax.swing.JSpinner) editor);
        cart.getColumnModel().getColumn(1).setCellEditor(cells);
        cart.setBounds(10, 130, 300, 40);
        scene.content.add(cart);
        return cart;
    }

    private static final class SpinnerEditor extends javax.swing.AbstractCellEditor implements javax.swing.table.TableCellEditor {
        private final javax.swing.JSpinner spinner;

        SpinnerEditor(javax.swing.JSpinner spinner) {
            this.spinner = spinner;
        }

        @Override public Object getCellEditorValue() { return spinner.getValue(); }
        @Override public Component getTableCellEditorComponent(JTable t, Object value, boolean selected, int row, int column) {
            spinner.setValue(value);
            return spinner;
        }
    }

    private static Component arrowOf(javax.swing.JSpinner spinner) {
        for (Component c : spinner.getComponents()) if (c instanceof JButton) return c;
        throw new AssertionError("no arrow button in the spinner");
    }

    @Test
    void anActionAndTheChecksAfterItAreOneStep() {
        List<Inspector.StepLine> steps = new java.util.ArrayList<Inspector.StepLine>();
        for (String l : Arrays.asList("click(#save)", "expect(#status text 'saved')", "expect(#save !disabled)",
                "click(#new)", "type(#customer 'Acme')")) {
            steps.add(new Inspector.StepLine(steps.size() + 1, "person", l, false));
        }
        assertThat(Inspector.checksAfter(steps, 0, steps.size())).isEqualTo(2);
        assertThat(Inspector.checksAfter(steps, 3, steps.size())).isEqualTo(0);
        assertThat(Inspector.checksAfter(steps, 0, 2)).isEqualTo(1);
    }

    @Test
    void noticesNeverWaitForTheEdt() throws Exception {
        Inspector before = Inspector.swap(scene.inspector);
        java.util.concurrent.CountDownLatch blocked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            blocked.countDown();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertThat(blocked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // the session tells the palette under its own lock: that call must return though the EDT is busy
            java.util.concurrent.CompletableFuture.runAsync(() -> Inspector.recordingChanged("the agent deleted step 2"))
                    .get(1, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            Inspector.swap(before);
        }
        SwingUtilities.invokeAndWait(() -> { });
    }

    // ---- the agent acts on what the palette covers -----------------------------------------------------------------

    private final JButton archive = new JButton("Archive");

    /** An unshown {@code w} x {@code h} window with an empty app content pane. */
    private static JRootPane window(int w, int h) {
        JRootPane root = new JRootPane();
        root.setContentPane(new JPanel(null));
        root.setSize(w, h);
        root.doLayout();
        return root;
    }

    private static Bar idleBar() {
        return new Bar("swing-ui-agent", "").button("● Record", Overlay.NO, () -> { });
    }

    private static Point centre(Component c) {
        return new Point(c.getX() + c.getWidth() / 2, c.getY() + c.getHeight() / 2);
    }

    private static Point inside(Component c) {
        return new Point(c.getWidth() / 2, c.getHeight() / 2);
    }

    /** An unshown container lays nothing out on validate: lay it out by hand, all the way down. */
    private static void layOut(Container c) {
        c.doLayout();
        for (Component k : c.getComponents()) if (k instanceof Container) layOut((Container) k);
    }

    private static JTextField firstField(Container c) {
        for (Component k : c.getComponents()) {
            if (k instanceof JTextField) return (JTextField) k;
            JTextField inside = k instanceof Container ? firstField((Container) k) : null;
            if (inside != null) return inside;
        }
        return null;
    }

    private void onEdt(Runnable body) throws Exception {
        SwingUtilities.invokeAndWait(body);
    }

    @Test
    void thePaletteStepsOffAButtonTheAgentClicks() throws Exception {
        onEdt(() -> {
            JRootPane root = window(800, 600);
            Overlay o = scene.inspector.overlay(root);
            Bar bar = idleBar();
            o.bar(bar);
            archive.setBounds(bar.getBounds());
            root.getContentPane().add(archive);
            JLayeredPane layers = root.getLayeredPane();
            Point c = centre(archive);
            assertThat(HitCheck.mouseTarget(layers, c.x, c.y)).isSameAs(bar);

            assertThat(scene.inspector.stepOff(bar, archive, inside(archive))).isTrue();

            assertThat(HitCheck.mouseTarget(layers, c.x, c.y)).isSameAs(archive);
            assertThat(bar.getBounds().intersects(archive.getBounds())).isFalse();
            assertThat(bar.getParent()).isSameAs(layers);
            o.dispose();
        });
    }

    @Test
    void thePaletteStepsOffAButtonInAnInternalFrame() throws Exception {
        onEdt(() -> {
            JRootPane root = window(800, 600);
            Overlay o = scene.inspector.overlay(root);
            Bar bar = idleBar();
            o.bar(bar);
            JDesktopPane desktop = new JDesktopPane();
            desktop.setBounds(0, 0, 800, 600);
            root.getContentPane().add(desktop);
            JInternalFrame order = new JInternalFrame("Order");
            // over the bottom right corner, where the bar sits
            order.setBounds(200, 200, 640, 440);
            order.getContentPane().setLayout(null);
            order.setVisible(true);
            desktop.add(order);
            JButton archiveOrder = new JButton("Archive");
            order.getContentPane().add(archiveOrder);
            layOut(root);
            JLayeredPane layers = root.getLayeredPane();
            archiveOrder.setBounds(SwingUtilities.convertRectangle(layers, bar.getBounds(), order.getContentPane()));
            Point c = SwingUtilities.convertPoint(archiveOrder, inside(archiveOrder), layers);
            assertThat(HitCheck.mouseTarget(layers, c.x, c.y)).isSameAs(bar);

            assertThat(scene.inspector.stepOff(bar, archiveOrder, inside(archiveOrder))).isTrue();

            assertThat(HitCheck.mouseTarget(layers, c.x, c.y)).isSameAs(archiveOrder);
            assertThat(bar.getParent()).isSameAs(layers);
            o.dispose();
        });
    }

    @Test
    void withItsPanelOpenBothLeaveTheTarget() throws Exception {
        onEdt(() -> {
            JRootPane root = window(800, 600);
            Overlay o = scene.inspector.overlay(root);
            Bar bar = idleBar();
            o.bar(bar);
            ExportPanel panel = new ExportPanel("PlaceOrderUiIT", "com.example", Paths.get("uitest"), null, ExportPanel.JAVA, (k, p) -> { }, (n, f) -> { }, () -> { });
            o.panel(panel);
            layOut(panel);
            JLayeredPane layers = root.getLayeredPane();
            JTextField className = firstField(panel);
            archive.setBounds(SwingUtilities.convertRectangle(className.getParent(), className.getBounds(), layers));
            root.getContentPane().add(archive);
            Point c = centre(archive);
            assertThat(HitCheck.mouseTarget(layers, c.x, c.y)).isSameAs(className);
            Component cover = className;

            assertThat(scene.inspector.stepOff(cover, archive, inside(archive))).isTrue();

            assertThat(HitCheck.mouseTarget(layers, c.x, c.y)).isSameAs(archive);
            assertThat(bar.getBounds().intersects(archive.getBounds())).isFalse();
            assertThat(panel.getBounds().intersects(archive.getBounds())).isFalse();
            assertThat(panel.getParent()).isSameAs(layers);
            o.dispose();
        });
    }

    @Test
    void inAWindowTooSmallItLeavesUntilTheAgentsStepEnds() throws Exception {
        onEdt(() -> {
            JRootPane root = window(160, 60);
            Overlay o = scene.inspector.overlay(root);
            Bar bar = idleBar();
            o.bar(bar);
            archive.setBounds(0, 0, 160, 60);
            root.getContentPane().add(archive);

            assertThat(scene.inspector.stepOff(bar, archive, inside(archive))).isTrue();
            assertThat(bar.getParent()).isNull();
            Point c = centre(archive);
            assertThat(HitCheck.mouseTarget(root.getLayeredPane(), c.x, c.y)).isSameAs(archive);

            scene.inspector.agentDone();
            assertThat(bar.getParent()).isSameAs(root.getLayeredPane());
            o.dispose();
        });
    }

    @Test
    void theCatcherIsNeverMovedForTheAgent() throws Exception {
        onEdt(() -> {
            JRootPane root = window(800, 600);
            Overlay o = scene.inspector.overlay(root);
            Bar bar = idleBar();
            o.bar(bar);
            archive.setBounds(bar.getBounds());
            root.getContentPane().add(archive);
            o.catcher(e -> { }, e -> { });
            Component catcher = root.getLayeredPane().getComponentsInLayer(JLayeredPane.POPUP_LAYER - 1)[0];

            assertThat(scene.inspector.stepOff(catcher, archive, inside(archive))).isFalse();
            assertThat(bar.getBounds().intersects(archive.getBounds())).isTrue();
            o.dispose();
        });
    }

    @Test
    void aDragByThePersonWinsAgain() throws Exception {
        onEdt(() -> {
            JRootPane root = window(800, 600);
            Overlay o = scene.inspector.overlay(root);
            Bar bar = idleBar();
            o.bar(bar);
            archive.setBounds(bar.getBounds());
            root.getContentPane().add(archive);
            assertThat(scene.inspector.stepOff(bar, archive, inside(archive))).isTrue();
            assertThat(bar.getBounds().intersects(archive.getBounds())).isFalse();

            bar.dragTo(archive.getLocation());
            o.relayout();

            assertThat(bar.getLocation()).isEqualTo(archive.getLocation());
            o.dispose();
        });
    }

    @Test
    void afterTheAgentsStepTheBarIsBackAtItsSpot() throws Exception {
        onEdt(() -> {
            JRootPane root = window(800, 600);
            Overlay o = scene.inspector.overlay(root);
            Bar bar = idleBar();
            o.bar(bar);
            Rectangle home = bar.getBounds();
            archive.setBounds(home);
            root.getContentPane().add(archive);

            assertThat(scene.inspector.stepOff(bar, archive, inside(archive))).isTrue();
            assertThat(bar.getBounds().intersects(archive.getBounds())).isFalse();

            // the step ended: back without waiting for the agent's next step or a drag
            scene.inspector.agentDone();
            assertThat(bar.getBounds()).isEqualTo(home);
            o.relayout();
            assertThat(bar.getBounds()).isEqualTo(home);
            o.dispose();
        });
    }

    @Test
    void aDraggedSpotSurvivesAnAgentStepThatPushedItAside() throws Exception {
        onEdt(() -> {
            JRootPane root = window(800, 600);
            Overlay o = scene.inspector.overlay(root);
            Bar bar = idleBar();
            o.bar(bar);
            Point theirs = new Point(200, 150);
            bar.dragTo(theirs);
            o.relayout();
            archive.setBounds(bar.getBounds());
            root.getContentPane().add(archive);

            assertThat(scene.inspector.stepOff(bar, archive, inside(archive))).isTrue();
            assertThat(bar.getBounds().intersects(archive.getBounds())).isFalse();

            scene.inspector.agentDone();
            assertThat(bar.getLocation()).isEqualTo(theirs);
            o.dispose();
        });
    }

    @Test
    void afterHighlightBoxesUnderItAreClearedItIsBack() throws Exception {
        onEdt(() -> {
            // on screen: a box shows only on a showing component
            frame.setUndecorated(true);
            frame.setContentPane(scene.content);
            Bar bar = paletteInFrame();
            frame.setVisible(true);
            Rectangle home = bar.getBounds();
            scene.save.setBounds(SwingUtilities.convertRectangle(frame.getLayeredPane(), home, scene.content));

            scene.inspector.highlight(Collections.singletonList(scene.inspector.component(scene.save)), null, 0);
            assertThat(bar.getBounds().intersects(inLayers(scene.save))).isFalse();

            scene.inspector.clear();
            assertThat(bar.getBounds()).isEqualTo(home);
        });
    }

    @Test
    void aDragsTwoEndsStayClearTogetherAndTheNextStepStartsAfresh() throws Exception {
        onEdt(() -> {
            JRootPane root = window(800, 600);
            Overlay o = scene.inspector.overlay(root);
            Bar bar = idleBar();
            o.bar(bar);
            Rectangle home = bar.getBounds();
            // drop areas, not buttons: the bar's default spot may cover them, so only the agent's step moves it
            JPanel from = new JPanel();
            JPanel to = new JPanel();
            JPanel next = new JPanel();
            from.setBounds(home);
            to.setBounds(8, home.y, home.width, home.height);
            next.setBounds(home.x, 8, home.width, home.height);
            root.getContentPane().add(from);
            root.getContentPane().add(to);
            root.getContentPane().add(next);

            // one drag: both ends are checked before either is pressed
            scene.inspector.stepOff(bar, from, inside(from));
            assertThat(bar.getBounds().intersects(to.getBounds())).isTrue();
            scene.inspector.stepOff(bar, to, inside(to));
            assertThat(bar.getBounds().intersects(from.getBounds())).isFalse();
            assertThat(bar.getBounds().intersects(to.getBounds())).isFalse();
            assertThat(bar.getBounds()).isEqualTo(next.getBounds());

            // the step ended: back on its spot, over both ends again
            scene.inspector.agentDone();
            assertThat(bar.getBounds()).isEqualTo(home);

            // the next step avoids only what it acts on
            scene.inspector.stepOff(bar, next, inside(next));
            assertThat(bar.getBounds()).isEqualTo(home);
            o.dispose();
        });
    }

    // ---- the palette follows the open modal dialog ----------------------------------------------------------------

    private final FakeSink sink = new FakeSink();
    private final JFrame frame = new JFrame();
    private final JDialog dialog = new JDialog(frame, true);

    @AfterEach
    void closeWindows() {
        dialog.dispose();
        frame.dispose();
    }

    /** The palette (idle) in the unshown 800 x 600 frame; the dialog is 500 x 300. EDT. */
    private Bar paletteInFrame() {
        Scene.size(frame, 800, 600);
        Scene.size(dialog, 500, 300);
        scene.inspector.install(sink);
        return showIn(frame).bar();
    }

    /** The palette's window is now {@code w}, as when it opened or closed. EDT. */
    private Overlay showIn(Window w) {
        JRootPane root = ((RootPaneContainer) w).getRootPane();
        scene.inspector.paletteRoot = () -> root;
        scene.inspector.attachPalette();
        return scene.inspector.overlay(root);
    }

    @Test
    void thePaletteMovesIntoTheOpenModalDialogAndBackWhenItCloses() throws Exception {
        onEdt(() -> {
            Bar bar = paletteInFrame();
            Overlay inFrame = scene.inspector.overlay(frame.getRootPane());
            assertThat(bar.getParent()).isSameAs(frame.getLayeredPane());

            Overlay inDialog = showIn(dialog);
            assertThat(inDialog.bar()).isSameAs(bar);
            assertThat(bar.getParent()).isSameAs(dialog.getLayeredPane());
            assertThat(inFrame.bar()).isNull();

            showIn(frame);
            assertThat(bar.getParent()).isSameAs(frame.getLayeredPane());
            assertThat(inDialog.bar()).isNull();
        });
    }

    @Test
    void aDialogTooNarrowForItsButtonsKeepsThePaletteWhereItWas() throws Exception {
        onEdt(() -> {
            Bar bar = paletteInFrame();
            Scene.size(dialog, 120, 80);

            showIn(dialog);

            assertThat(bar.getParent()).isSameAs(frame.getLayeredPane());
        });
    }

    @Test
    void inADialogThePaletteStartsOffTheDialogsButtons() throws Exception {
        onEdt(() -> {
            Bar bar = paletteInFrame();
            JLayeredPane layers = dialog.getLayeredPane();
            Container content = dialog.getContentPane();
            content.setLayout(null);
            Dimension size = bar.getPreferredSize();
            // where the bar would go by default: the bottom right corner
            Rectangle corner = Overlay.placeBar(size.width, size.height, layers.getWidth(), layers.getHeight(), null,
                    Collections.<Rectangle>emptyList());
            JButton next = new JButton("Next");
            next.setBounds(SwingUtilities.convertRectangle(layers, corner, content));
            content.add(next);

            showIn(dialog);

            assertThat(bar.getParent()).isSameAs(layers);
            Rectangle nextInLayers = SwingUtilities.convertRectangle(content, next.getBounds(), layers);
            assertThat(bar.getBounds().intersects(nextInLayers)).isFalse();
        });
    }

    @Test
    void thePersonsSpotIsRememberedPerWindow() throws Exception {
        onEdt(() -> {
            Bar bar = paletteInFrame();
            Point inFrame = new Point(100, 120);
            bar.dragTo(inFrame);
            scene.inspector.overlay(frame.getRootPane()).relayout();

            Overlay o = showIn(dialog);
            Point inDialog = new Point(40, 50);
            bar.dragTo(inDialog);
            o.relayout();

            showIn(frame);
            assertThat(bar.getLocation()).isEqualTo(inFrame);
            showIn(dialog);
            assertThat(bar.getLocation()).isEqualTo(inDialog);
        });
    }

    @Test
    void aDragInTheMainWindowNeverFollowsTheBarIntoADialog() throws Exception {
        onEdt(() -> {
            paletteInFrame();
            scene.inspector.agentStartedNow();
            Bar recording = scene.inspector.overlay(frame.getRootPane()).bar();
            recording.dragTo(new Point(100, 120));
            scene.inspector.overlay(frame.getRootPane()).relayout();
            // kept inside the window: the agent's long bar text makes it wide
            Point inFrame = recording.getLocation();

            // the agent replays; checkout's dialog opens meanwhile, and the replay ends in it
            scene.inspector.agentReplayingNow("replaying 3/5  click(#checkout)");
            showIn(dialog);
            scene.inspector.agentReplayingNow(null);

            JLayeredPane layers = dialog.getLayeredPane();
            Dimension size = recording.getPreferredSize();
            Rectangle byDefault = Overlay.placeBar(size.width, size.height, layers.getWidth(), layers.getHeight(), null,
                    Collections.<Rectangle>emptyList());
            assertThat(recording.getParent()).isSameAs(layers);
            assertThat(recording.getLocation()).isEqualTo(byDefault.getLocation());

            // the main window's drag was not saved as the dialog's
            showIn(frame);
            assertThat(recording.getLocation()).isEqualTo(inFrame);
            showIn(dialog);
            assertThat(recording.getLocation()).isEqualTo(byDefault.getLocation());
        });
    }

    @Test
    void theLatestDragInAWindowWinsForEveryBarShownThere() throws Exception {
        onEdt(() -> {
            Bar idle = paletteInFrame();
            Overlay o = scene.inspector.overlay(frame.getRootPane());
            Point first = new Point(100, 120);
            idle.dragTo(first);
            o.relayout();

            scene.inspector.agentStartedNow();
            Bar recording = o.bar();
            assertThat(recording.getLocation()).isEqualTo(first);
            Point second = new Point(200, 300);
            recording.dragTo(second);
            o.relayout();

            scene.inspector.agentStoppedNow();
            assertThat(o.bar().getLocation()).isEqualTo(second);
            scene.inspector.recordingReplacedNow();
            assertThat(o.bar()).isSameAs(idle);
            assertThat(idle.getLocation()).isEqualTo(second);
        });
    }

    // ---- its default spot covers no small component ----------------------------------------------------------------

    /** The palette (idle) in the unshown 800 x 600 frame showing {@code content}, laid out all the way down. EDT. */
    private Bar paletteOver(Container content) {
        frame.setContentPane(content);
        Scene.size(frame, 800, 600);
        Scene.size(dialog, 500, 300);
        layOut(frame.getRootPane());
        scene.inspector.install(sink);
        return showIn(frame).bar();
    }

    /** {@code c}'s bounds in its window's layered pane. */
    private static Rectangle inLayers(Component c) {
        return SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), SwingUtilities.getRootPane(c).getLayeredPane());
    }

    @Test
    void theMainWindowsDefaultSpotAvoidsAButtonRowBottomRight() throws Exception {
        onEdt(() -> {
            JPanel content = new JPanel(new BorderLayout());
            content.add(new JScrollPane(new JTable(30, 3)), BorderLayout.CENTER);
            JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton[] buttons = {new JButton("Archive"), new JButton("Delete"), new JButton("Checkout…")};
            buttons[2].setEnabled(false);
            for (JButton b : buttons) row.add(b);
            content.add(row, BorderLayout.SOUTH);

            Bar bar = paletteOver(content);

            assertThat(bar.getParent()).isSameAs(frame.getLayeredPane());
            for (JButton b : buttons) assertThat(bar.getBounds().intersects(inLayers(b))).as(b.getText()).isFalse();
        });
    }

    @Test
    void aDialogsSpotAvoidsItsButtonsAndCombo() throws Exception {
        onEdt(() -> {
            Bar bar = paletteInFrame();
            Container content = dialog.getContentPane();
            content.setLayout(null);
            JComboBox<String> customer = new JComboBox<String>(new String[] {"Ada Shop Ltd"});
            customer.setBounds(20, 10, 150, 24);
            JButton back = new JButton("Back");
            back.setBounds(10, 260, 90, 28);
            JButton next = new JButton("Next");
            next.setBounds(300, 260, 90, 28);
            JButton cancel = new JButton("Cancel");
            cancel.setBounds(400, 260, 90, 28);
            for (Component c : new Component[] {customer, back, next, cancel}) content.add(c);

            showIn(dialog);

            assertThat(bar.getParent()).isSameAs(dialog.getLayeredPane());
            for (Component c : new Component[] {customer, back, next, cancel}) {
                assertThat(bar.getBounds().intersects(inLayers(c))).as(c.toString()).isFalse();
            }
        });
    }

    @Test
    void aLabelIsNeverCovered() throws Exception {
        onEdt(() -> {
            JPanel content = new JPanel(null);
            JLabel total = new JLabel("Total 42.00");
            Dimension size = total.getPreferredSize();
            // where the bar would go by default: the bottom right corner
            total.setBounds(800 - 20 - size.width, 600 - 24 - size.height, size.width, size.height);
            content.add(total);

            Bar bar = paletteOver(content);

            assertThat(bar.getBounds().intersects(inLayers(total))).isFalse();
        });
    }

    @Test
    void aTableUnderTheCornerIsFine() throws Exception {
        onEdt(() -> {
            JPanel content = new JPanel(new BorderLayout());
            content.add(new JScrollPane(new JTable(30, 3)), BorderLayout.CENTER);

            Bar bar = paletteOver(content);

            JLayeredPane layers = frame.getLayeredPane();
            Dimension size = bar.getPreferredSize();
            assertThat(bar.getBounds()).isEqualTo(Overlay.placeBar(size.width, size.height, layers.getWidth(),
                    layers.getHeight(), null, Collections.<Rectangle>emptyList()));
        });
    }

    @Test
    void aDialogPageChangeLeavesTheBarOnItsSpot() throws Exception {
        onEdt(() -> {
            Bar bar = paletteInFrame();
            Container content = dialog.getContentPane();
            content.setLayout(null);
            JButton first = new JButton("Next");
            first.setBounds(10, 10, 90, 28);
            content.add(first);
            showIn(dialog);
            Rectangle home = bar.getBounds();
            assertThat(home.intersects(inLayers(first))).isFalse();

            // checkout's next page: a Place order button where the bar sits; the palette's timer looks again
            content.remove(first);
            JButton place = new JButton("Place order");
            place.setBounds(SwingUtilities.convertRectangle(dialog.getLayeredPane(), home, content));
            content.add(place);
            scene.inspector.attachPalette();
            scene.inspector.overlay(dialog.getRootPane()).relayout();
            assertThat(bar.getBounds()).isEqualTo(home);
        });
    }

    @Test
    void aDialogsSpotAndTheMainWindowsSpotAreIndependent() throws Exception {
        onEdt(() -> {
            Bar bar = paletteInFrame();
            Point inFrame = bar.getLocation();
            JLayeredPane dialogLayers = dialog.getLayeredPane();
            Dimension size = bar.getPreferredSize();
            Point inDialog = Overlay.placeBar(size.width, size.height, dialogLayers.getWidth(), dialogLayers.getHeight(),
                    null, Collections.<Rectangle>emptyList()).getLocation();

            showIn(dialog);
            assertThat(bar.getLocation()).isEqualTo(inDialog);

            // while the palette is in the dialog, the main window's next screen puts a button where the bar was
            Container content = frame.getContentPane();
            content.setLayout(null);
            JButton archiveOrder = new JButton("Archive");
            archiveOrder.setBounds(SwingUtilities.convertRectangle(frame.getLayeredPane(),
                    new Rectangle(inFrame, size), content));
            content.add(archiveOrder);

            // back in the main window: its spot was picked once, it is not picked again
            showIn(frame);
            assertThat(bar.getLocation()).isEqualTo(inFrame);

            // a drag in the main window stays there; the dialog keeps its own spot
            Point dragged = new Point(100, 120);
            bar.dragTo(dragged);
            scene.inspector.overlay(frame.getRootPane()).relayout();
            showIn(dialog);
            assertThat(bar.getLocation()).isEqualTo(inDialog);
            showIn(frame);
            assertThat(bar.getLocation()).isEqualTo(dragged);
        });
    }

    @Test
    void aDraggedSpotIsKeptEvenOverAButton() throws Exception {
        onEdt(() -> {
            JPanel content = new JPanel(null);
            JButton save = new JButton("Save");
            save.setBounds(100, 120, 90, 28);
            content.add(save);
            Bar bar = paletteOver(content);
            assertThat(bar.getBounds().intersects(inLayers(save))).isFalse();

            Point over = inLayers(save).getLocation();
            bar.dragTo(over);
            scene.inspector.overlay(frame.getRootPane()).relayout();
            scene.inspector.attachPalette();
            assertThat(bar.getLocation()).isEqualTo(over);

            // into a dialog and back: the person's spot in the main window is still theirs
            showIn(dialog);
            showIn(frame);
            assertThat(bar.getLocation()).isEqualTo(over);
        });
    }

    @Test
    void whileTheAgentActsThePaletteStays() throws Exception {
        onEdt(() -> {
            Bar bar = paletteInFrame();
            Inspector.agentActing(true);
            try {
                showIn(dialog);
                assertThat(bar.getParent()).isSameAs(frame.getLayeredPane());
            } finally {
                Inspector.agentActing(false);
            }

            scene.inspector.agentDone();

            assertThat(bar.getParent()).isSameAs(dialog.getLayeredPane());
        });
    }

    @Test
    void aTestRunTakesItOutAndItMovesHidden() throws Exception {
        onEdt(() -> {
            Bar bar = paletteInFrame();
            scene.inspector.testRunNow(true);
            assertThat(bar.getParent()).isNull();

            Overlay inDialog = showIn(dialog);
            assertThat(inDialog.bar()).isSameAs(bar);
            assertThat(bar.getParent()).isNull();

            scene.inspector.testRunNow(false);
            assertThat(bar.getParent()).isSameAs(dialog.getLayeredPane());
        });
    }
}
