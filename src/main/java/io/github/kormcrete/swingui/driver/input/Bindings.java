package io.github.kormcrete.swingui.driver.input;

import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import java.awt.event.KeyEvent;

/** The key a component's look and feel binds to an action, so the driver can press it instead of calling it. */
public final class Bindings {

    private Bindings() {}

    /** A key-pressed stroke bound to {@code actionName} under {@code condition}, parents included; null for none. */
    public static KeyStroke find(JComponent component, int condition, Object actionName) {
        InputMap map = component.getInputMap(condition);
        KeyStroke[] keys = map == null ? null : map.allKeys();
        if (keys == null) return null;
        for (KeyStroke key : keys) {
            if (key.getKeyEventType() == KeyEvent.KEY_PRESSED && !key.isOnKeyRelease()
                    && actionName.equals(map.get(key))) {
                return key;
            }
        }
        return null;
    }
}
