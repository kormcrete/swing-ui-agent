package io.github.kormcrete.swingui.driver.inspect;

import org.junit.jupiter.api.Test;

import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
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
    void aBarWithoutASpotTakesTheWindowsAndOneWithASpotKeepsIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JRootPane root = new JRootPane();
            root.setSize(800, 600);
            root.doLayout();
            Overlay o = new Overlay(root);
            o.spotFor = b -> new Point(40, 50);
            Bar fresh = new Bar("Recording", "").button("Done", Overlay.HOVER, () -> { });
            o.bar(fresh);
            assertThat(fresh.getLocation()).isEqualTo(new Point(40, 50));

            Bar dragged = new Bar("Recorded", "").button("Steps", Overlay.HOVER, () -> { });
            dragged.moved = new Point(300, 200);
            o.bar(dragged);
            assertThat(dragged.getLocation()).isEqualTo(new Point(300, 200));
            o.dispose();
        });
    }
}
