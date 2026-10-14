package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.input.HitCheck;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JDesktopPane;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JInternalFrame;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
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
    void aRepeatedValueIsNoKeyTheNextUniqueColumnIs() {
        JTable byCustomer = new JTable(new Object[][] {{"Acme", "ORD-1"}, {"Acme", "ORD-2"}}, new Object[] {"Customer", "Order"});
        assertThat(Inspector.rowKey(byCustomer, 1)).isEqualTo(Collections.<String, Object>singletonMap("Order", "ORD-2"));
    }

    @Test
    void withoutAUniqueColumnTheRowGoesByIndex() {
        JTable twins = new JTable(new Object[][] {{"Acme", "Open"}, {"Acme", "Open"}}, new Object[] {"Customer", "Status"});
        Map<String, Object> key = Inspector.rowKey(twins, 1);
        assertThat(key).isEqualTo(Collections.<String, Object>singletonMap("index", 1));
    }

    @Test
    void anActionAndTheChecksAfterItAreOneStep() {
        List<String> steps = Arrays.asList("click(#save)", "expect(#status text 'saved')", "expect(#save !disabled)",
                "click(#new)", "type(#customer 'Acme')");
        assertThat(Inspector.checksAfter(steps, 0, steps.size())).isEqualTo(2);
        assertThat(Inspector.checksAfter(steps, 3, steps.size())).isEqualTo(0);
        assertThat(Inspector.checksAfter(steps, 0, 2)).isEqualTo(1);
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

            bar.moved = archive.getLocation();
            o.relayout();

            assertThat(bar.getLocation()).isEqualTo(archive.getLocation());
            o.dispose();
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
            JButton from = archive;
            JButton to = new JButton("Customer");
            JButton next = new JButton("Checkout");
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

            scene.inspector.agentDone();
            o.relayout();
            assertThat(bar.getBounds()).isEqualTo(next.getBounds());

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
            bar.moved = inFrame;
            scene.inspector.overlay(frame.getRootPane()).relayout();

            Overlay o = showIn(dialog);
            Point inDialog = new Point(40, 50);
            bar.moved = inDialog;
            o.relayout();

            showIn(frame);
            assertThat(bar.getLocation()).isEqualTo(inFrame);
            showIn(dialog);
            assertThat(bar.getLocation()).isEqualTo(inDialog);
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
