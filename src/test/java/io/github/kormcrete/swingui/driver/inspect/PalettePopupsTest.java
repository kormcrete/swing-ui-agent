package io.github.kormcrete.swingui.driver.inspect;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JToolTip;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.ComboPopup;
import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The palette's own popups - the row menu, a tooltip - show above the palette in a window on screen, where Swing would
 * put them under it; the replay speed's list is a window of its own (on top anyway, and never painted with the window);
 * the app's own popups stay where Swing puts them. Every click goes to the window, which hands it to whatever lies on
 * top at that point, as a click of the person's. Swing's own popup factory throughout: it puts a popup that fits in the
 * window into the window, as Metal, Windows and FlatLaf do (Aqua's makes every popup a window of its own, on top anyway).
 */
class PalettePopupsTest {

    private final Scene scene = new Scene();
    private final FakeSink sink = new FakeSink();
    private final ExecutorService sinkThread = Executors.newSingleThreadExecutor();
    private final JFrame frame = new JFrame();
    private Recording recording;
    private PopupFactory before;

    @BeforeEach
    void show() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            before = PopupFactory.getSharedInstance();
            PopupFactory.setSharedInstance(new PopupFactory());
            frame.setUndecorated(true);
            frame.setContentPane(new JPanel(null));
            frame.setBounds(60, 60, 900, 700);
            frame.setVisible(true);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (recording != null) recording.dispose();
            frame.dispose();
            PopupFactory.setSharedInstance(before);
        });
        sinkThread.shutdownNow();
    }

    @Test
    void aRightClickOnAStepOpensItsMenuAboveTheStepsListAndItsItemsTakeTheClick() throws Exception {
        StepsPanel panel = stepsShown();
        SwingUtilities.invokeAndWait(() -> {
            JList<String> list = panel.stepsList();
            Rectangle cell = list.getCellBounds(1, 1);
            rightClick(list, new Point(cell.x + 30, cell.y + cell.height / 2));
        });
        StepMenu menu = eventually(() -> panel.menu() != null && panel.menu().isShowing() ? panel.menu() : null);
        SwingUtilities.invokeAndWait(() -> {
            JMenuItem first = (JMenuItem) menu.getComponent(0);
            Point at = center(first);
            assertThat(topAt(at)).isSameAs(first);
            click(first, new Point(first.getWidth() / 2, first.getHeight() / 2));
        });
        // the first item describes the step: the field to type its sentence in opens
        eventually(() -> panel.nameField());
        SwingUtilities.invokeAndWait(() -> assertThat(menu.isVisible()).isFalse());
    }

    @Test
    void aClickOnAnotherStepClosesTheMenuAndSelectsThatStep() throws Exception {
        StepsPanel panel = stepsShown();
        JList<String> list = panel.stepsList();
        SwingUtilities.invokeAndWait(() -> {
            Rectangle cell = list.getCellBounds(0, 0);
            rightClick(list, new Point(cell.x + 30, cell.y + cell.height / 2));
        });
        StepMenu menu = eventually(() -> panel.menu() != null && panel.menu().isShowing() ? panel.menu() : null);
        SwingUtilities.invokeAndWait(() -> {
            Rectangle cell = list.getCellBounds(2, 2);
            click(list, new Point(cell.x + cell.width - 20, cell.y + cell.height / 2));
        });
        eventually(() -> !menu.isVisible() ? Boolean.TRUE : null);
        assertThat(onEdt(list::getSelectedIndex)).isEqualTo(2);
    }

    @Test
    void theReplaySpeedOpensAboveTheStepsListAndEachChoiceCanBePicked() throws Exception {
        StepsPanel panel = stepsShown();
        String[] speeds = {"Fast", "Normal", "Slow", "Step by step"};
        for (int pick : new int[] {2, 3, 0, 1}) {
            JComboBox<String> speed = panel.speedChoice();
            SwingUtilities.invokeAndWait(() -> click(speed, new Point(speed.getWidth() / 2, speed.getHeight() / 2)));
            JList<?> choices = eventually(() -> {
                ComboPopup popup = (ComboPopup) speed.getUI().getAccessibleChild(speed, 0);
                return popup.isVisible() && popup.getList().isShowing() ? popup.getList() : null;
            });
            SwingUtilities.invokeAndWait(() -> {
                Rectangle cell = choices.getCellBounds(pick, pick);
                Point at = new Point(cell.x + cell.width / 2, cell.y + cell.height / 2);
                // a window of its own, on top of the window and the palette, never in a capture of the window
                assertThat(SwingUtilities.getWindowAncestor(choices)).isNotSameAs(frame);
                click(choices, at);
            });
            eventually(() -> panel.speed() == pick ? Boolean.TRUE : null);
            assertThat(onEdt(() -> (String) speed.getSelectedItem())).isEqualTo(speeds[pick]);
        }
    }

    @Test
    void shiftF10AndTheContextMenuKeyOpenTheSelectedStepsMenuAndNoMoreButtonIsLeft() throws Exception {
        StepsPanel panel = stepsShown();
        for (KeyEvent key : new KeyEvent[] {
                new KeyEvent(panel.stepsList(), KeyEvent.KEY_PRESSED, 0, InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_F10, KeyEvent.CHAR_UNDEFINED),
                new KeyEvent(panel.stepsList(), KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_CONTEXT_MENU, KeyEvent.CHAR_UNDEFINED)}) {
            SwingUtilities.invokeAndWait(() -> {
                panel.select(2);
                panel.stepsList().dispatchEvent(key);
            });
            StepMenu menu = eventually(() -> panel.menu() != null && panel.menu().isShowing() ? panel.menu() : null);
            SwingUtilities.invokeAndWait(() -> {
                assertThat(topAt(center((JComponent) menu.getComponent(0)))).isSameAs(menu.getComponent(0));
                menu.setVisible(false);
            });
        }
        SwingUtilities.invokeAndWait(() -> {
            for (Component c : buttonsOf(panel)) assertThat(((JButton) c).getText()).isNotEqualTo("⋯");
        });
    }

    @Test
    void aPalettePartsTooltipShowsAboveThePaletteAndTheAppsOwnPopupsStayOnThePopupLayer() throws Exception {
        StepsPanel panel = stepsShown();
        SwingUtilities.invokeAndWait(() -> {
            JLayeredPane layers = frame.getLayeredPane();
            // a tooltip over the steps list, as the list's own hover line opens it
            JList<String> list = panel.stepsList();
            JToolTip tip = list.createToolTip();
            tip.setTipText("the technical line");
            Point on = list.getLocationOnScreen();
            Popup shown = PopupFactory.getSharedInstance().getPopup(list, tip, on.x + 20, on.y + 20);
            shown.show();
            assertThat(layers.getLayer(holder(tip))).isGreaterThan(layers.getLayer(panel));
            assertThat(topAt(center(tip))).isSameAs(tip);
            shown.hide();

            // the app's own menu over the same spot stays under the palette, where Swing puts it
            JButton app = new JButton("Archive");
            app.setBounds(10, 10, 120, 30);
            frame.getContentPane().add(app);
            JPopupMenu appMenu = new JPopupMenu();
            appMenu.add(new JMenuItem("Archive all"));
            Point listAt = SwingUtilities.convertPoint(list, 20, 20, app);
            appMenu.show(app, listAt.x, listAt.y);
            assertThat(layers.getLayer(holder(appMenu))).isEqualTo(JLayeredPane.POPUP_LAYER);
            appMenu.setVisible(false);
        });
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /** A recording of three steps with its Steps list open in the frame on screen. */
    private StepsPanel stepsShown() throws Exception {
        sink.person("click(#a)", "click(#b)", "click(#c)");
        SwingUtilities.invokeAndWait(() -> {
            recording = new Recording(scene.inspector, sink, sinkThread, scene.inspector.overlay(frame.getRootPane()), () -> { });
            recording.start();
            recording.toggleSteps();
            recording.live.stop();
        });
        sinkThread.submit(() -> { }).get(5, TimeUnit.SECONDS);
        return eventually(() -> {
            StepsPanel p = recording.panel;
            if (p == null || !p.isShowing() || p.rows().size() != 3) return null;
            p.validate();
            Rectangle cell = p.stepsList().getCellBounds(2, 2);
            return cell != null && cell.height > 0 && p.speedChoice().getWidth() > 0 ? p : null;
        });
    }

    /** The component directly under the frame's layered pane that holds {@code c}. EDT. */
    private Component holder(Component c) {
        Component k = c;
        while (k.getParent() != frame.getLayeredPane()) k = k.getParent();
        return k;
    }

    /** What lies on top at {@code p} (layered-pane coordinates), as a click there finds it. EDT. */
    private Component topAt(Point p) {
        return SwingUtilities.getDeepestComponentAt(frame.getLayeredPane(), p.x, p.y);
    }

    /** {@code c}'s center in layered-pane coordinates. EDT. */
    private Point center(JComponent c) {
        return SwingUtilities.convertPoint(c, c.getWidth() / 2, c.getHeight() / 2, frame.getLayeredPane());
    }

    private void click(Component on, Point at) {
        mouse(on, at, MouseEvent.BUTTON1, InputEvent.BUTTON1_DOWN_MASK);
    }

    private void rightClick(Component on, Point at) {
        mouse(on, at, MouseEvent.BUTTON3, InputEvent.BUTTON3_DOWN_MASK);
    }

    /**
     * A press, release and click at {@code at} on {@code on}, sent to its window (the frame, or a popup's own), which
     * finds what lies on top there.
     */
    private void mouse(Component on, Point at, int button, int down) {
        java.awt.Window w = SwingUtilities.getWindowAncestor(on);
        Point p = SwingUtilities.convertPoint(on, at, w);
        boolean popup = button == MouseEvent.BUTTON3;
        long now = System.currentTimeMillis();
        w.dispatchEvent(new MouseEvent(w, MouseEvent.MOUSE_PRESSED, now, down, p.x, p.y, 1, popup, button));
        w.dispatchEvent(new MouseEvent(w, MouseEvent.MOUSE_RELEASED, now, 0, p.x, p.y, 1, false, button));
        w.dispatchEvent(new MouseEvent(w, MouseEvent.MOUSE_CLICKED, now, 0, p.x, p.y, 1, false, button));
    }

    private static java.util.List<Component> buttonsOf(java.awt.Container c) {
        java.util.List<Component> out = new java.util.ArrayList<Component>();
        for (Component k : c.getComponents()) {
            if (k instanceof JButton) out.add(k);
            else if (k instanceof java.awt.Container) out.addAll(buttonsOf((java.awt.Container) k));
        }
        return out;
    }

    private static <T> T onEdt(Callable<T> read) throws Exception {
        Object[] got = new Object[1];
        SwingUtilities.invokeAndWait(() -> {
            try { got[0] = read.call(); } catch (Exception e) { throw new RuntimeException(e); }
        });
        @SuppressWarnings("unchecked") T t = (T) got[0];
        return t;
    }

    private static <T> T eventually(Callable<T> read) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            T got = onEdt(read);
            if (got != null) return got;
            Thread.sleep(20);
        }
        throw new AssertionError("nothing within 5 s");
    }
}
