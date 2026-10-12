package io.github.kormcrete.swingui.driver.focus;

import io.github.kormcrete.swingui.driver.Edt;
import io.github.kormcrete.swingui.driver.platform.Seam;

import javax.swing.InputVerifier;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.FocusEvent;

/**
 * Moves keyboard focus without the OS: asks the current owner's InputVerifier, pins the
 * toolkit's focused window to the target's, then dispatches a synthetic FOCUS_GAINED through the
 * stock focus manager. The pin follows every move, so the app's own requestFocus calls land in Java.
 */
public final class FocusEmulator {

    private final Seam seam;

    public FocusEmulator(Seam seam) {
        this.seam = seam;
    }

    /** Returns false, and leaves focus where it was, when the current owner's verifier refuses. */
    public boolean focus(final Component target) {
        return Edt.call(() -> {
            if (!verifierYields(owner(), target)) {
                return false;
            }
            focusUnchecked(target);
            return true;
        });
    }

    /** A move that was already accepted: resolving a queued request, or restoring a window's focus. EDT. */
    void focusUnchecked(Component target) {
        seam.pinFocusedWindow(windowOf(target));
        Component old = owner();
        if (old != target) {
            target.dispatchEvent(new FocusEvent(target, FocusEvent.FOCUS_GAINED, false, old));
        }
    }

    public Component owner() {
        return Edt.call(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
    }

    public static Window windowOf(Component c) {
        return c instanceof Window ? (Window) c : SwingUtilities.getWindowAncestor(c);
    }

    /** JComponent's request-focus rule: the owner's verifier decides, unless the target opts out. */
    static boolean verifierYields(Component from, Component to) {
        if (from == to) return true;
        if (!(from instanceof JComponent) || !(to instanceof JComponent)) return true;
        if (!((JComponent) to).getVerifyInputWhenFocusTarget()) return true;
        InputVerifier verifier = ((JComponent) from).getInputVerifier();
        return verifier == null || verifier.shouldYieldFocus((JComponent) from);
    }
}
