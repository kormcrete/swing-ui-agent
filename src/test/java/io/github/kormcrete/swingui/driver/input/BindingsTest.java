package io.github.kormcrete.swingui.driver.input;

import org.junit.jupiter.api.Test;

import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.event.KeyEvent;

import static org.assertj.core.api.Assertions.assertThat;

class BindingsTest {

    @Test
    void findsTheKeyBoundToAnActionIncludingParentMaps() {
        JPanel p = new JPanel();
        InputMap parent = new InputMap();
        parent.put(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "startEditing");
        p.getInputMap(JComponent.WHEN_FOCUSED).setParent(parent);
        assertThat(Bindings.find(p, JComponent.WHEN_FOCUSED, "startEditing"))
                .isEqualTo(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0));
        assertThat(Bindings.find(p, JComponent.WHEN_FOCUSED, "nothing")).isNull();
    }

    @Test
    void ignoresReleaseAndTypedStrokes() {
        JPanel p = new JPanel();
        p.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_A, 0, true), "act");
        assertThat(Bindings.find(p, JComponent.WHEN_FOCUSED, "act")).isNull();
    }
}
