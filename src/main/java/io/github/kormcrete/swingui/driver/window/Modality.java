package io.github.kormcrete.swingui.driver.window;

import java.awt.Dialog;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Which modal dialog blocks a window, computed from public state. EDT. */
public final class Modality {

    private Modality() {}

    public static List<Dialog> showingModals() {
        List<Dialog> out = new ArrayList<Dialog>();
        for (Window w : Window.getWindows()) {
            if (w instanceof Dialog && w.isShowing() && ((Dialog) w).isModal()) out.add((Dialog) w);
        }
        return out;
    }

    /** The top modal dialog blocking {@code window}, or null when it takes input. */
    public static Dialog blocker(Window window) {
        List<Window> windows = allWindows();
        List<Dialog> blocking = new ArrayList<Dialog>();
        for (Dialog d : showingModals()) {
            if (isBlockedBy(window, d, windows)) blocking.add(d);
        }
        for (Dialog d : blocking) {
            boolean blockedItself = false;
            for (Dialog other : blocking) {
                if (other != d && isBlockedBy(d, other, windows)) blockedItself = true;
            }
            if (!blockedItself) return d;
        }
        return blocking.isEmpty() ? null : blocking.get(blocking.size() - 1);
    }

    /** The showing modal dialog nothing blocks: the one a user answers next. Null when none shows. */
    public static Dialog top() {
        List<Dialog> modals = showingModals();
        for (int i = modals.size() - 1; i >= 0; i--) {
            if (blocker(modals.get(i)) == null) return modals.get(i);
        }
        return null;
    }

    /** Whether {@code candidate} blocks {@code window}, resolving a mutual block by creation order. */
    private static boolean isBlockedBy(Window window, Dialog candidate, List<Window> windows) {
        if (candidate == window) return false;
        boolean candidateBlocksWindow = blocks(candidate, window);
        boolean windowBlocksCandidate = window instanceof Dialog && blocks((Dialog) window, candidate);
        return blocksInOrder(candidateBlocksWindow, windowBlocksCandidate,
                orderOf(windows, candidate), orderOf(windows, window));
    }

    /**
     * Decides a block when both directions could hold: two showing modal dialogs of the same modality
     * type, neither owning the other (e.g. a {@code JOptionPane} opened from inside a modal dialog and
     * parented to the same frame). AWT's {@code Dialog.modalShow} breaks
     * the tie by creation order — the later dialog is not blocked by the earlier one and blocks it
     * instead — so this mirrors that: {@code dialogOrder}/{@code windowOrder} are indices into
     * {@link Window#getWindows()}. Known limit: a dialog re-shown after a newer dialog was created keeps
     * its original (now stale) index, so the tie still resolves by creation order, not by which of the
     * two was actually shown most recently.
     */
    static boolean blocksInOrder(boolean dialogBlocksWindow, boolean windowBlocksDialog, int dialogOrder, int windowOrder) {
        return dialogBlocksWindow && (!windowBlocksDialog || dialogOrder > windowOrder);
    }

    private static List<Window> allWindows() {
        return Arrays.asList(Window.getWindows());
    }

    private static int orderOf(List<Window> windows, Window w) {
        return windows.indexOf(w);
    }

    static boolean blocks(Dialog dialog, Window window) {
        if (ownedBy(window, dialog)) return false;
        switch (dialog.getModalityType()) {
            case MODELESS:
                return false;
            case DOCUMENT_MODAL:
                return root(window) == root(dialog);
            case APPLICATION_MODAL:
                return window.getModalExclusionType() == Dialog.ModalExclusionType.NO_EXCLUDE;
            case TOOLKIT_MODAL:
                return window.getModalExclusionType() != Dialog.ModalExclusionType.TOOLKIT_EXCLUDE;
            default:
                return false;
        }
    }

    private static boolean ownedBy(Window window, Window owner) {
        for (Window w = window; w != null; w = w.getOwner()) {
            if (w == owner) return true;
        }
        return false;
    }

    private static Window root(Window window) {
        Window w = window;
        while (w.getOwner() != null) w = w.getOwner();
        return w;
    }
}
