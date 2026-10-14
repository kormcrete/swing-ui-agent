package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.input.HitCheck;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Rectangle;
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
    void withItsPanelOpenBothLeaveTheTarget() throws Exception {
        onEdt(() -> {
            JRootPane root = window(800, 600);
            Overlay o = scene.inspector.overlay(root);
            Bar bar = idleBar();
            o.bar(bar);
            ExportPanel panel = new ExportPanel("PlaceOrderUiIT", "com.example", (k, p) -> { }, () -> { });
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
}
