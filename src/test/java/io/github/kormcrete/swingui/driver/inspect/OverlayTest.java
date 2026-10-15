package io.github.kormcrete.swingui.driver.inspect;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The bar stays inside the window and off every box, so it never hides what it asks about or replays. */
class OverlayTest {

    private static final List<Rectangle> NONE = Collections.emptyList();

    @Test
    void itStartsBottomRight() {
        assertThat(Overlay.placeBar(200, 30, 800, 600, null, NONE)).isEqualTo(new Rectangle(592, 562, 200, 30));
    }

    @Test
    void aBoxInThatCornerSendsItToTheNextFreeOne() {
        List<Rectangle> box = Collections.singletonList(new Rectangle(500, 540, 280, 50));
        assertThat(Overlay.placeBar(200, 30, 800, 600, null, box)).isEqualTo(new Rectangle(8, 562, 200, 30));
    }

    @Test
    void whereThePersonDraggedItWinsUnlessItCoversABox() {
        assertThat(Overlay.placeBar(200, 30, 800, 600, new Point(100, 100), NONE)).isEqualTo(new Rectangle(100, 100, 200, 30));
        List<Rectangle> box = Collections.singletonList(new Rectangle(90, 90, 50, 50));
        assertThat(Overlay.placeBar(200, 30, 800, 600, new Point(100, 100), box)).isEqualTo(new Rectangle(592, 562, 200, 30));
    }

    @Test
    void itNeverLeavesTheWindow() {
        Rectangle r = Overlay.placeBar(900, 30, 400, 300, new Point(1000, 1000), NONE);
        assertThat(r.x).isGreaterThanOrEqualTo(0);
        assertThat(r.x + r.width).isLessThanOrEqualTo(400);
        assertThat(r.y + r.height).isLessThanOrEqualTo(300);
    }

    @Test
    void aSpotIsFreeOnlyWhenTheBarAndItsPanelAreClear() {
        List<Rectangle> box = Collections.singletonList(new Rectangle(600, 400, 50, 50));
        assertThat(Overlay.placeBar(200, 30, 800, 600, null, box)).isEqualTo(new Rectangle(592, 562, 200, 30));
        assertThat(Overlay.placeBar(200, 30, 800, 600, null, box, new Dimension(300, 200)))
                .isEqualTo(new Rectangle(8, 562, 200, 30));
    }

    @Test
    void thePanelSitsWhereItDidBefore() {
        Dimension steps = new Dimension(300, 200);
        assertThat(Overlay.panelBeside(new Rectangle(592, 562, 200, 30), steps, 800, 600))
                .isEqualTo(new Rectangle(492, 356, 300, 200));
        assertThat(Overlay.panelBeside(new Rectangle(592, 8, 200, 30), steps, 800, 600))
                .isEqualTo(new Rectangle(492, 44, 300, 200));
        assertThat(Overlay.panelBeside(new Rectangle(592, 562, 200, 30), new Dimension(1000, 1000), 800, 600))
                .isEqualTo(new Rectangle(8, 16, 784, 540));
    }

    @Test
    void everyBarTakesTheWindowsSpotNeverOneItBringsAlong() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JRootPane root = new JRootPane();
            root.setSize(800, 600);
            root.doLayout();
            Overlay o = new Overlay(root);
            o.spotFor = b -> new Point(40, 50);
            Bar fresh = new Bar("Recording", "").button("Done", Overlay.HOVER, () -> { });
            o.bar(fresh);
            assertThat(fresh.getLocation()).isEqualTo(new Point(40, 50));

            // dragged in another window: that spot stays there
            Bar elsewhere = new Bar("Recorded", "").button("Steps", Overlay.HOVER, () -> { });
            elsewhere.moved = new Point(300, 200);
            o.bar(elsewhere);
            assertThat(elsewhere.getLocation()).isEqualTo(new Point(40, 50));

            // dragged here: the next bar shown here goes there too
            elsewhere.dragTo(new Point(120, 140));
            o.bar(fresh);
            assertThat(fresh.getLocation()).isEqualTo(new Point(120, 140));
            o.dispose();
        });
    }

    // ---- the default spot covers no small component ---------------------------------------------------------------

    @Test
    void theEdgeScanFindsAGapBetweenSmallComponents() {
        // a button row on both ends of the bottom edge and a toolbar along the top: every corner and middle is covered
        List<Rectangle> small = Arrays.asList(new Rectangle(0, 550, 300, 50), new Rectangle(520, 550, 280, 50),
                new Rectangle(0, 0, 800, 50));
        assertThat(Overlay.placeBar(200, 30, 800, 600, null, NONE, null, small, null))
                .isEqualTo(new Rectangle(304, 562, 200, 30));
    }

    @Test
    void withNoFreeSpotItCoversTheFewestThenTheLeastArea() {
        // a label over the whole window: every spot covers one; a second one bottom right makes that corner cover two
        List<Rectangle> two = Arrays.asList(new Rectangle(0, 0, 400, 200), new Rectangle(300, 170, 20, 20));
        assertThat(Overlay.placeBar(200, 30, 400, 200, null, NONE, null, two, null))
                .isEqualTo(new Rectangle(8, 162, 200, 30));
        // one each: the spot that covers the least of them wins (on the right edge, just under the top one)
        List<Rectangle> rows = Arrays.asList(new Rectangle(0, 0, 400, 140), new Rectangle(0, 180, 400, 20));
        assertThat(Overlay.placeBar(200, 30, 400, 200, null, NONE, null, rows, null))
                .isEqualTo(new Rectangle(192, 138, 200, 30));
    }

    @Test
    void theAgentsAreasStillComeFirstAndThenTheSmallComponents() {
        // what the agent acts on is never covered; among the spots clear of it, one free of small components wins
        List<Rectangle> agent = Collections.singletonList(new Rectangle(592, 562, 200, 30));
        List<Rectangle> small = Collections.singletonList(new Rectangle(8, 562, 200, 30));
        assertThat(Overlay.placeBar(200, 30, 800, 600, null, agent, null, small, null))
                .isEqualTo(new Rectangle(592, 8, 200, 30));
    }

    @Test
    void aPanelBesideTheBarNeverMovesItOntoASmallComponent() {
        // bottom right: the bar is free, its panel above covers a label; every other spot puts the bar on one
        List<Rectangle> small = Arrays.asList(new Rectangle(600, 400, 50, 20), new Rectangle(0, 0, 800, 550),
                new Rectangle(0, 550, 580, 50));
        assertThat(Overlay.placeBar(200, 30, 800, 600, null, NONE, new Dimension(300, 200), small, null))
                .isEqualTo(new Rectangle(592, 562, 200, 30));
        // with a free spot for both, the panel's is taken
        List<Rectangle> label = Collections.singletonList(new Rectangle(600, 400, 50, 20));
        assertThat(Overlay.placeBar(200, 30, 800, 600, null, NONE, new Dimension(300, 200), label, null))
                .isEqualTo(new Rectangle(8, 562, 200, 30));
    }

    @Test
    void theDraggedSpotWinsOverSmallComponents() {
        List<Rectangle> button = Collections.singletonList(new Rectangle(90, 90, 80, 30));
        assertThat(Overlay.placeBar(200, 30, 800, 600, new Point(100, 100), NONE, null, button, null))
                .isEqualTo(new Rectangle(100, 100, 200, 30));
    }

    /** {@code content} in an unshown 800 x 600 root pane, laid out all the way down. EDT. */
    private static JRootPane rootWith(Container content) {
        JRootPane root = new JRootPane();
        root.setContentPane(content);
        root.setSize(800, 600);
        layOut(root);
        return root;
    }

    private static void layOut(Container c) {
        c.doLayout();
        for (Component k : c.getComponents()) if (k instanceof Container) layOut((Container) k);
    }

    private static Rectangle in(Component c, JRootPane root) {
        return SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), root.getLayeredPane());
    }

    @Test
    void controlsAndLabelsAreSmallAndViewsAndContainersAreNot() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel form = new JPanel(null);
            JButton next = new JButton("Next");
            next.setEnabled(false);
            JCheckBox rush = new JCheckBox("Rush");
            JComboBox<String> customer = new JComboBox<String>(new String[] {"Ada Shop Ltd"});
            JTextField po = new JTextField();
            JSpinner qty = new JSpinner();
            JSlider discount = new JSlider();
            JLabel total = new JLabel("Total 42.00");
            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Orders", new JPanel());
            tabs.addTab("Customers", new JPanel());
            JScrollPane cart = new JScrollPane(new JTable(new Object[][] {{"A4 paper, 500 sheets", 2}}, new Object[] {"Product", "Qty"}));
            JScrollPane products = new JScrollPane(new JList<String>(new String[] {"Gel pen, black"}));
            JScrollPane categories = new JScrollPane(new JTree());
            JScrollPane notes = new JScrollPane(new JTextArea("Deliver before noon"));
            Component[] small = {next, rush, customer, po, qty, discount, total};
            Component[] large = {tabs, cart, products, categories, notes};
            int y = 0;
            for (Component c : small) {
                c.setBounds(10, y, 120, 24);
                form.add(c);
                y += 30;
            }
            tabs.setBounds(200, 10, 400, 150);
            form.add(tabs);
            int x = 200;
            for (Component c : new Component[] {cart, products, categories, notes}) {
                c.setBounds(x, 170, 140, 150);
                form.add(c);
                x += 150;
            }
            JRootPane root = rootWith(form);
            JMenuBar menu = new JMenuBar();
            menu.add(new JMenu("File"));
            root.setJMenuBar(menu);
            layOut(root);

            List<Rectangle> found = Overlay.smallIn(root.getLayeredPane());

            for (Component c : new Component[] {next, rush, customer, po, qty, discount}) assertThat(found).contains(in(c, root));
            assertThat(found).contains(in(menu, root));
            Rectangle tab = SwingUtilities.convertRectangle(tabs, tabs.getBoundsAt(1), root.getLayeredPane());
            assertThat(found).contains(tab);
            // a label counts where its text is, not its whole width
            Rectangle label = in(total, root);
            assertThat(found).anySatisfy(r -> assertThat(label.contains(r) && r.width < label.width).isTrue());
            // nothing inside the views or the tab's page counts
            for (Rectangle r : found) {
                for (Component c : large) {
                    Rectangle view = c == tabs ? in(tabs.getSelectedComponent(), root) : in(c, root);
                    assertThat(r.intersects(view)).as("%s inside %s", r, c.getClass().getSimpleName()).isFalse();
                }
            }
        });
    }

    @Test
    void aLargeViewUnderTheCornerDoesNotMoveTheBar() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel content = new JPanel(new BorderLayout());
            content.add(new JScrollPane(new JTable(40, 4)), BorderLayout.CENTER);
            Overlay o = new Overlay(rootWith(content));
            Bar bar = new Bar("swing-ui-agent", "").button("● Record", Overlay.NO, () -> { });
            o.bar(bar);
            Dimension size = bar.getPreferredSize();
            assertThat(bar.getBounds()).isEqualTo(Overlay.placeBar(size.width, size.height, 800, 600, null, NONE));
            o.dispose();
        });
    }

    @Test
    void aRecheckMovesADefaultBarOnlyWhenItNowCoversASmallComponentAndRepaintsNothingOtherwise() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel content = new JPanel(null);
            JRootPane root = rootWith(content);
            Overlay o = new Overlay(root);
            Bar bar = new Bar("swing-ui-agent", "").button("● Record", Overlay.NO, () -> { });
            o.bar(bar);
            Rectangle home = bar.getBounds();

            // nothing changed: a tick looks again and repaints nothing
            assertThat(repaintedDuring(o::recheck)).isEmpty();
            assertThat(bar.getBounds()).isEqualTo(home);

            // a label appears elsewhere: the bar stays
            JLabel far = new JLabel("Ada Shop Ltd");
            far.setBounds(10, 10, 100, 20);
            content.add(far);
            o.recheck();
            assertThat(bar.getBounds()).isEqualTo(home);

            // a button appears under it: it moves off
            JButton next = new JButton("Next");
            next.setBounds(SwingUtilities.convertRectangle(root.getLayeredPane(), home, content));
            content.add(next);
            o.recheck();
            assertThat(bar.getBounds().intersects(in(next, root))).isFalse();

            // the button goes again: the bar keeps its new spot
            Rectangle moved = bar.getBounds();
            content.remove(next);
            o.recheck();
            assertThat(bar.getBounds()).isEqualTo(moved);
            o.dispose();
        });
    }

    @Test
    void aNewBarKeepsThePersonsSpotInThisWindow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel content = new JPanel(null);
            JButton next = new JButton("Next");
            next.setBounds(100, 100, 80, 30);
            content.add(next);
            Overlay o = new Overlay(rootWith(content));
            Bar idle = new Bar("swing-ui-agent", "").button("● Record", Overlay.NO, () -> { });
            o.bar(idle);
            idle.dragTo(new Point(100, 100));
            o.relayout();
            o.recheck();
            assertThat(idle.getLocation()).isEqualTo(new Point(100, 100));

            Bar recording = new Bar("Recording", "").button("Done", Overlay.HOVER, () -> { });
            o.bar(recording);
            assertThat(recording.getLocation()).isEqualTo(new Point(100, 100));
            o.dispose();
        });
    }

    /** What asked to be repainted while {@code body} ran (the components' own dirty regions, not their parents'). EDT. */
    private static List<Component> repaintedDuring(Runnable body) {
        List<Component> dirty = new ArrayList<Component>();
        RepaintManager before = RepaintManager.currentManager((JComponent) null);
        RepaintManager.setCurrentManager(new RepaintManager() {
            @Override
            public void addDirtyRegion(JComponent c, int x, int y, int w, int h) {
                dirty.add(c);
            }
        });
        try {
            body.run();
        } finally {
            RepaintManager.setCurrentManager(before);
        }
        return dirty;
    }

    @Test
    void anIdlePaletteRepaintsNothingWhileItFollowsTheWindow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JRootPane root = new JRootPane();
            root.setSize(800, 600);
            root.doLayout();
            Overlay o = new Overlay(root);
            Bar bar = new Bar("swing-ui-agent", "").button("● Record", Overlay.NO, () -> { });
            o.bar(bar);
            Rectangle at = bar.getBounds();

            // a tick of the follow timer: nothing it draws changed
            assertThat(repaintedDuring(o::relayout)).isEmpty();

            // the window grows: the bar follows it to the new corner
            root.setSize(900, 700);
            root.doLayout();
            o.relayout();
            assertThat(bar.getLocation()).isEqualTo(new Point(at.x + 100, at.y + 100));

            // a box is drawn by the overlay itself: adding it repaints, the next tick does not
            JButton save = new JButton("Save");
            root.getContentPane().add(save);
            assertThat(repaintedDuring(() -> o.mark(new Overlay.Mark(save, null, -1, -1, "Save", Overlay.ASK, false), null)))
                    .contains(o);
            assertThat(repaintedDuring(o::relayout)).doesNotContain(o);
            o.dispose();
        });
    }

    @Test
    void theFollowTimerRunsOnlyWhileSomethingShows() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JRootPane root = new JRootPane();
            root.setSize(800, 600);
            root.doLayout();
            Overlay o = new Overlay(root);
            assertThat(o.following()).isFalse();

            Overlay.Mark box = new Overlay.Mark(root.getContentPane(), null, -1, -1, "Order", Overlay.ASK, false);
            o.mark(box, null);
            assertThat(o.following()).isTrue();
            o.clearMarks();
            assertThat(o.following()).isFalse();

            o.hover(box);
            assertThat(o.following()).isTrue();
            o.hover(null);
            assertThat(o.following()).isFalse();

            o.bar(new Bar("swing-ui-agent", "").button("● Record", Overlay.NO, () -> { }));
            assertThat(o.following()).isTrue();
            o.dispose();
            assertThat(o.following()).isFalse();
        });
    }
}
