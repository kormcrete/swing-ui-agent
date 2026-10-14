package io.github.kormcrete.swingui.driver.input;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** AWT's receiver rule: the topmost visible component under the point that takes mouse input. */
class HitCheckTest {

    private final JPanel root = new JPanel(null);
    private final JButton button = new JButton("Save");

    HitCheckTest() {
        root.setBounds(0, 0, 200, 100);
        button.setBounds(10, 10, 60, 20);
    }

    @AfterEach
    void noCover() {
        HitCheck.cover(null);
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
    void aLabelWithOnlyItsOwnPopupMenuTakesTheClick() {
        JLabel attachments = new JLabel("[attachments]");
        attachments.setBounds(10, 10, 60, 20);
        attachments.setComponentPopupMenu(new JPopupMenu());
        root.add(attachments);
        assertThat(HitCheck.mouseTarget(root, 20, 15)).isSameAs(attachments);
    }

    @Test
    void anInheritedPopupMenuDoesNotMakeAComponentTakeTheClick() {
        root.setComponentPopupMenu(new JPopupMenu());
        JLabel label = new JLabel("idle");
        label.setBounds(10, 10, 60, 20);
        label.setInheritsPopupMenu(true);
        root.add(label);
        assertThat(HitCheck.mouseTarget(root, 20, 15)).isSameAs(root);
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

    @Test
    void aCoverThatStepsOffLetsTheClickThrough() {
        JPanel palette = overlay(true);
        root.add(palette);
        root.add(button);
        HitCheck.cover((cover, target, p) -> {
            root.remove(cover);
            return true;
        });
        assertThat(HitCheck.receiver(root, new Point(20, 15), button, new Point(10, 5))).isSameAs(button);
    }

    @Test
    void aCoverThatCannotStepOffStillCovers() {
        JPanel blocker = overlay(true);
        root.add(blocker);
        root.add(button);
        HitCheck.cover((cover, target, p) -> false);
        assertThat(HitCheck.receiver(root, new Point(20, 15), button, new Point(10, 5))).isSameAs(blocker);
    }

    @Test
    void nothingCoveringAsksNoCover() {
        root.add(button);
        List<Component> asked = new ArrayList<Component>();
        HitCheck.cover((cover, target, p) -> asked.add(cover));
        assertThat(HitCheck.receiver(root, new Point(20, 15), button, new Point(10, 5))).isSameAs(button);
        assertThat(asked).isEmpty();
    }
}
