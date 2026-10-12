package io.github.kormcrete.swingui.driver.input;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.event.MouseAdapter;

import static org.assertj.core.api.Assertions.assertThat;

/** AWT's receiver rule: the topmost visible component under the point that takes mouse input. */
class HitCheckTest {

    private final JPanel root = new JPanel(null);
    private final JButton button = new JButton("Save");

    HitCheckTest() {
        root.setBounds(0, 0, 200, 100);
        button.setBounds(10, 10, 60, 20);
    }

    private JPanel overlay(boolean listens) {
        JPanel o = new JPanel(null);
        o.setBounds(0, 0, 200, 100);
        if (listens) o.addMouseListener(new MouseAdapter() {});
        return o;
    }

    @Test
    void theButtonUnderThePointIsHit() {
        root.add(button);
        assertThat(HitCheck.mouseTarget(root, 20, 15)).isSameAs(button);
    }

    @Test
    void anOverlayWithoutListenersLetsTheClickThrough() {
        root.add(overlay(false));
        root.add(button);
        assertThat(HitCheck.mouseTarget(root, 20, 15)).isSameAs(button);
    }

    @Test
    void aListeningOverlayCatchesTheClick() {
        JPanel blocker = overlay(true);
        root.add(blocker);
        root.add(button);
        assertThat(HitCheck.mouseTarget(root, 20, 15)).isSameAs(blocker);
    }

    @Test
    void aHiddenOverlayIsIgnored() {
        JPanel blocker = overlay(true);
        blocker.setVisible(false);
        root.add(blocker);
        root.add(button);
        assertThat(HitCheck.mouseTarget(root, 20, 15)).isSameAs(button);
    }

    @Test
    void nothingThatTakesMouseInputIsNull() {
        root.add(button);
        assertThat(HitCheck.mouseTarget(root, 150, 80)).isNull();
    }
}
