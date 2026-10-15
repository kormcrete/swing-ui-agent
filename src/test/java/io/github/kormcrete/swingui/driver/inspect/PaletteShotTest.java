package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.Shot;
import io.github.kormcrete.swingui.driver.widgets.Screenshots;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRootPane;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A capture of the app's window leaves the person's palette out; a plain paint of it does not. With {@code palette}
 * on, a capture shows the window as the person sees it: the palette, and the menus open over the window.
 */
class PaletteShotTest {

    private interface Body { void run(JRootPane root, Bar bar, ExportPanel panel) throws Exception; }

    private static void withPalette(Body body) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JRootPane root = new JRootPane();
            JPanel content = new JPanel(null);
            content.setOpaque(true);
            content.setBackground(Color.RED);
            root.setContentPane(content);
            root.setSize(600, 400);
            root.doLayout();
            Overlay overlay = new Overlay(root);
            try {
                Bar bar = new Bar("swing-ui-agent", "").button("● Record", Overlay.NO, () -> { });
                overlay.bar(bar);
                ExportPanel panel = new ExportPanel("RecordedUiIT", "com.example", Paths.get("uitest"), null, ExportPanel.JAVA, (c, p) -> { }, (n, f) -> { }, () -> { });
                overlay.panel(panel);
                body.run(root, bar, panel);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                overlay.dispose();
            }
        });
    }

    private static int centre(BufferedImage image, Component c) {
        return image.getRGB(c.getX() + c.getWidth() / 2, c.getY() + c.getHeight() / 2) & 0xFFFFFF;
    }

    private static BufferedImage decode(Shot shot) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(shot.png));
    }

    private static int at(BufferedImage image, Point p) {
        return image.getRGB(p.x, p.y) & 0xFFFFFF;
    }

    /** The centre of {@code c} in {@code window}'s coordinates, from where both are on screen. */
    private static Point onScreenCentre(Component c, Component window) {
        Point p = c.getLocationOnScreen();
        Point w = window.getLocationOnScreen();
        return new Point(p.x - w.x + c.getWidth() / 2, p.y - w.y + c.getHeight() / 2);
    }

    private static JPanel block(Color color) {
        JPanel block = new JPanel();
        block.setOpaque(true);
        block.setBackground(color);
        block.setPreferredSize(new Dimension(120, 80));
        return block;
    }

    @Test
    void aPlainPaintShowsTheBar() throws Exception {
        withPalette((root, bar, panel) -> {
            BufferedImage image = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
            root.paint(image.createGraphics());
            assertThat(bar.getWidth()).isPositive();
            assertThat(centre(image, bar)).isNotEqualTo(0xFF0000);
        });
    }

    @Test
    void aCaptureOfTheWindowLeavesThePaletteOut() throws Exception {
        withPalette((root, bar, panel) -> {
            Shot shot = Screenshots.of(root, null, null, null);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(shot.png));
            assertThat(bar.getWidth()).isPositive();
            assertThat(panel.getWidth()).isPositive();
            assertThat(centre(image, bar)).isEqualTo(0xFF0000);
            assertThat(centre(image, panel)).isEqualTo(0xFF0000);
        });
    }

    @Test
    void aCaptureLeavesTheRecordedTestsPanelOutToo() throws Exception {
        withPalette((root, bar, panel) -> {
            RecordedTestsPanel testsDir = new RecordedTestsPanel(Paths.get("uitest"), java.util.Collections.<Inspector.RecordedTestEntry>emptyList(),
                    0, f -> { }, f -> { }, () -> { });
            // where the panel slot is: over the app, under the bar
            testsDir.setBounds(10, 10, 300, 200);
            testsDir.validate();
            root.getLayeredPane().add(testsDir, javax.swing.JLayeredPane.PALETTE_LAYER);
            Shot shot = Screenshots.of(root, null, null, null);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(shot.png));
            assertThat(centre(image, testsDir)).isEqualTo(0xFF0000);
        });
    }

    @Test
    void aCaptureLeavesASentenceTypedInTheStepsListOut() throws Exception {
        withPalette((root, bar, panel) -> {
            StepsPanel steps = new StepsPanel("▶ Replay all", () -> { }, i -> { }, i -> { }, i -> { }, () -> { }, () -> { });
            steps.setBounds(10, 10, 460, 260);
            root.getLayeredPane().add(steps, javax.swing.JLayeredPane.PALETTE_LAYER);
            steps.show(java.util.Collections.singletonList(new Inspector.StepLine(1, "person", "click(#save)", false)),
                    new java.util.HashMap<Integer, String>(), 0, null, null);
            layOut(steps);
            steps.describeSelection();
            javax.swing.JTextField field = steps.nameField();
            field.setText("Save the order");
            java.awt.Rectangle at = SwingUtilities.convertRectangle(field.getParent(), field.getBounds(), root);
            int x = at.x + at.width / 2;
            int y = at.y + at.height / 2;
            BufferedImage plain = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
            root.paint(plain.createGraphics());
            assertThat(plain.getRGB(x, y) & 0xFFFFFF).isNotEqualTo(0xFF0000);
            Shot shot = Screenshots.of(root, null, null, null);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(shot.png));
            assertThat(image.getRGB(x, y) & 0xFFFFFF).isEqualTo(0xFF0000);
        });
    }

    @Test
    void withThePaletteACaptureShowsTheBarAndItsPanel() throws Exception {
        withPalette((root, bar, panel) -> {
            BufferedImage image = decode(Screenshots.of(root, null, null, null, true));
            assertThat(centre(image, bar)).isNotEqualTo(0xFF0000);
            assertThat(centre(image, panel)).isNotEqualTo(0xFF0000);
        });
    }

    @Test
    void withThePaletteACaptureOfAnElementShowsThePaletteOverIt() throws Exception {
        withPalette((root, bar, panel) -> {
            Component content = root.getContentPane();
            Point barCentre = SwingUtilities.convertPoint(bar.getParent(),
                    new Point(bar.getX() + bar.getWidth() / 2, bar.getY() + bar.getHeight() / 2), content);
            assertThat(at(decode(Screenshots.of(content, null, null, null)), barCentre)).isEqualTo(0xFF0000);
            assertThat(at(decode(Screenshots.of(content, null, null, null, true)), barCentre)).isNotEqualTo(0xFF0000);
        });
    }

    // ---- popups need a window on screen ----------------------------------------------------------------------------

    private interface Shown { void run(JFrame frame, JPanel content) throws Exception; }

    /** {@code body} on the EDT with an undecorated red 500 x 360 frame on screen; anything left open is closed after. */
    private static void inFrameOnScreen(Shown body) throws Exception {
        JFrame frame = new JFrame();
        try {
            SwingUtilities.invokeAndWait(() -> {
                JPanel content = new JPanel(null);
                content.setOpaque(true);
                content.setBackground(Color.RED);
                frame.setUndecorated(true);
                frame.setContentPane(content);
                frame.setBounds(120, 120, 500, 360);
                frame.setVisible(true);
            });
            SwingUtilities.invokeAndWait(() -> {
                try {
                    body.run(frame, (JPanel) frame.getContentPane());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
                frame.dispose();
            });
        }
    }

    @Test
    void withThePaletteMenusInWindowsOfTheirOwnArePaintedWhereTheyShow() throws Exception {
        JPanel blue = block(Color.BLUE);
        JPanel green = block(Color.GREEN);
        inFrameOnScreen((frame, content) -> {
            // a menu in a window of its own (a heavyweight popup), as a combo's list or a context menu may be
            JPopupMenu menu = new JPopupMenu();
            menu.setLightWeightPopupEnabled(false);
            menu.add(blue);
            menu.show(content, 40, 40);
            // any other popup the app opens: a window or a panel over the window, whatever the look and feel picks
            Point s = content.getLocationOnScreen();
            Popup popup = PopupFactory.getSharedInstance().getPopup(content, green, s.x + 300, s.y + 200);
            popup.show();
            try {
                assertThat(SwingUtilities.getWindowAncestor(menu)).isNotSameAs(frame);
                Point b = onScreenCentre(blue, frame);
                Point g = onScreenCentre(green, frame);
                BufferedImage without = decode(Screenshots.of(frame, null, null, null));
                assertThat(at(without, b)).isEqualTo(0xFF0000);
                assertThat(at(without, g)).isEqualTo(0xFF0000);
                BufferedImage with = decode(Screenshots.of(frame, null, null, null, true));
                assertThat(at(with, b)).isEqualTo(0x0000FF);
                assertThat(at(with, g)).isEqualTo(0x00FF00);
                // at 2x the popups land at the scaled spot
                BufferedImage twice = decode(Screenshots.of(frame, null, null, 2.0, true));
                assertThat(at(twice, new Point(b.x * 2, b.y * 2))).isEqualTo(0x0000FF);
                // a capture of an element shows the part of the menu over it
                BufferedImage part = decode(Screenshots.of(content, null, null, null, true));
                assertThat(at(part, SwingUtilities.convertPoint(blue, blue.getWidth() / 2, blue.getHeight() / 2, content)))
                        .isEqualTo(0x0000FF);
            } finally {
                menu.setVisible(false);
                popup.hide();
            }
        });
    }

    @Test
    void withThePaletteTheCheckMenuIsInThePicture() throws Exception {
        JPanel magenta = block(Color.MAGENTA);
        inFrameOnScreen((frame, content) -> {
            // lightweight (in the popup layer) where the look and feel allows, else a window of its own
            CheckMenu check = new CheckMenu();
            check.add(new JMenuItem("Check the total"));
            check.add(magenta);
            check.show(content, 300, 30);
            try {
                Point m = onScreenCentre(magenta, frame);
                assertThat(at(decode(Screenshots.of(frame, null, null, null)), m)).isEqualTo(0xFF0000);
                assertThat(at(decode(Screenshots.of(frame, null, null, null, true)), m)).isEqualTo(0xFF00FF);
            } finally {
                check.setVisible(false);
            }
        });
    }

    private static void layOut(java.awt.Container c) {
        c.doLayout();
        for (Component k : c.getComponents()) if (k instanceof java.awt.Container) layOut((java.awt.Container) k);
    }
}
