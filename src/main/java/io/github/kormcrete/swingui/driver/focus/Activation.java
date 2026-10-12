package io.github.kormcrete.swingui.driver.focus;

import io.github.kormcrete.swingui.driver.Edt;
import io.github.kormcrete.swingui.driver.platform.Seam;

import java.awt.Component;
import java.awt.FocusTraversalPolicy;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.util.List;

/**
 * What a real activation of a window does, emulated. Queued focus requests for the window
 * are met first: a modal dialog queues one, and AWT holds every posted key until it is met. Then
 * focus goes to the window's most recent focus owner, else its initial component.
 */
public final class Activation {

    private static final int MAX_PENDING = 10;

    private final Seam seam;
    private final FocusEmulator focus;

    public Activation(Seam seam, FocusEmulator focus) {
        this.seam = seam;
        this.focus = focus;
    }

    public void activate(final Window window) {
        Edt.run(() -> {
            resolvePending(window);
            initialFocus(window);
        });
    }

    /**
     * After a window closed: unless a showing component already has focus, focus goes back to {@code owner}'s
     * most recent focus owner, else {@code owner} is pinned as the focused window.
     */
    public void restoreAfterClose(final Window owner) {
        Edt.run(() -> {
            Component current = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (current != null && current.isShowing()) return;
            Component last = owner.getMostRecentFocusOwner();
            if (last != null && last.isShowing()) {
                focus.focusUnchecked(last);
            } else {
                seam.pinFocusedWindow(owner);
            }
        });
    }

    private void resolvePending(Window window) {
        Component previous = null;
        for (int i = 0; i < MAX_PENDING; i++) {
            List<Component> pending = seam.pendingFocusRequests();
            if (pending.isEmpty()) return;
            Component first = pending.get(0);
            if (FocusEmulator.windowOf(first) != window) return;
            if (first == previous || !first.isShowing()) {
                // Met once and still queued, or no longer showing: cancel it so the held keys go out.
                seam.cancelPendingFocus(first);
            } else {
                focus.focusUnchecked(first);
            }
            previous = first;
        }
    }

    private void initialFocus(Window window) {
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (owner != null && owner != window && FocusEmulator.windowOf(owner) == window) {
            seam.pinFocusedWindow(window);
            return;
        }
        Component target = window.getMostRecentFocusOwner();
        if (target == null || target == window || !target.isShowing()) {
            FocusTraversalPolicy policy = window.getFocusTraversalPolicy();
            target = policy == null ? null : policy.getInitialComponent(window);
        }
        if (target != null && target != window) {
            focus.focusUnchecked(target);
        } else {
            seam.pinFocusedWindow(window);
        }
    }
}
