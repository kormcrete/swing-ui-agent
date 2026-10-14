package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.Shot;
import io.github.kormcrete.swingui.driver.widgets.Screenshots;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** A capture of the app's window leaves the person's palette out; a plain paint of it does not. */
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
                ExportPanel panel = new ExportPanel("RecordedUiIT", "com.example", (c, p) -> { }, () -> { });
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
}
