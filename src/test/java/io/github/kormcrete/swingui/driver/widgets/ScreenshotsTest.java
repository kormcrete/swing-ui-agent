package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.Shot;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The capture paints the component itself, so none of this needs a window on screen - which is also
 * why the verb works while the app sits in the background.
 */
class ScreenshotsTest {

    /** Left half red, right half blue, so a crop can be told from the whole. */
    private static JPanel twoTone(int width, int height) {
        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Color.RED);
                g.fillRect(0, 0, getWidth() / 2, getHeight());
                g.setColor(Color.BLUE);
                g.fillRect(getWidth() / 2, 0, getWidth() - getWidth() / 2, getHeight());
            }
        };
        panel.setSize(new Dimension(width, height));
        return panel;
    }

    private static BufferedImage decode(Shot shot) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(shot.png));
        assertThat(image).as("the bytes are a readable PNG").isNotNull();
        return image;
    }

    @Test
    void capturesTheComponentAtItsOwnSize() throws IOException {
        Shot shot = Screenshots.of(twoTone(200, 100), null, null);

        assertThat(shot.width).isEqualTo(200);
        assertThat(shot.height).isEqualTo(100);
        assertThat(shot.sourceWidth).isEqualTo(200);
        assertThat(shot.sourceHeight).isEqualTo(100);
        assertThat(shot.scaled).isFalse();

        BufferedImage image = decode(shot);
        assertThat(image.getWidth()).isEqualTo(200);
        assertThat(image.getHeight()).isEqualTo(100);
        assertThat(new Color(image.getRGB(10, 50))).isEqualTo(Color.RED);
        assertThat(new Color(image.getRGB(190, 50))).isEqualTo(Color.BLUE);
    }

    @Test
    void aRegionCropsToThatPartOfTheComponent() throws IOException {
        // the right half: all blue, and half as wide
        Shot shot = Screenshots.of(twoTone(200, 100), new double[] {0.5, 0.0, 0.5, 1.0}, null);

        assertThat(shot.width).isEqualTo(100);
        assertThat(shot.height).isEqualTo(100);
        assertThat(shot.sourceWidth).as("the source size is still reported whole").isEqualTo(200);

        BufferedImage image = decode(shot);
        assertThat(new Color(image.getRGB(5, 50))).isEqualTo(Color.BLUE);
        assertThat(new Color(image.getRGB(95, 50))).isEqualTo(Color.BLUE);
    }

    @Test
    void anOversizedCaptureIsScaledDownAndSaysSo() throws IOException {
        Shot shot = Screenshots.of(twoTone(800, 400), null, 100);

        assertThat(shot.scaled).isTrue();
        assertThat(Math.max(shot.width, shot.height)).isEqualTo(100);
        assertThat(shot.width).as("the aspect ratio is kept").isEqualTo(100);
        assertThat(shot.height).isEqualTo(50);
        assertThat(decode(shot).getWidth()).isEqualTo(100);
    }

    @Test
    void aCaptureInsideTheBudgetIsNotTouched() {
        Shot shot = Screenshots.of(twoTone(200, 100), null, 1000);

        assertThat(shot.scaled).isFalse();
        assertThat(shot.width).isEqualTo(200);
    }

    @Test
    void aComponentBiggerThanWhatIsOnScreenIsCapturedAsItAppears() {
        // Only the part the parent shows is captured, not the component's whole (possibly huge) size.
        JPanel clipped = twoTone(200, 2000);
        JPanel parent = new JPanel(null);
        parent.setSize(100, 50);
        parent.add(clipped);
        clipped.setBounds(0, 0, 200, 2000);

        Shot shot = Screenshots.of(clipped, null, null);

        assertThat(shot.sourceWidth).isEqualTo(100);
        assertThat(shot.sourceHeight).isEqualTo(50);
        assertThat(shot.width).isEqualTo(100);
        assertThat(shot.height).isEqualTo(50);
        assertThat(shot.scaled).isFalse();
    }

    @Test
    void aComponentWithNoSizeIsRefusedRatherThanCaptured() {
        DriverException e = catchThrowableOfType(
                () -> Screenshots.of(twoTone(0, 0), null, null), DriverException.class);

        assertThat(e.reason()).isEqualTo(Reason.NOT_ACTIONABLE);
        assertThat(e.getMessage()).contains("no size on screen").contains("0x0");
    }

    @Test
    void aRegionOutsideTheComponentIsRefused() {
        DriverException e = catchThrowableOfType(
                () -> Screenshots.of(twoTone(200, 100), new double[] {2.0, 2.0, 0.5, 0.5}, null),
                DriverException.class);

        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("region");
    }

    @Test
    void aMaxSizeBelowOneIsRefused() {
        DriverException e = catchThrowableOfType(
                () -> Screenshots.of(twoTone(200, 100), null, 0), DriverException.class);

        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("maxSize");
    }
}
