package io.github.kormcrete.swingui.driver.window;

import io.github.kormcrete.swingui.driver.model.Roles;

import javax.swing.AbstractButton;
import javax.swing.JPopupMenu;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/** The live {@link WindowSource}: every showing window of this process but the palette's own, plus showing popups. */
public final class AppWindows implements WindowSource {

    private volatile Supplier<Window> main = AppWindows::firstShowingFrame;

    /** Names the app's main window; the default is the first showing frame. */
    public void setMain(Supplier<Window> main) {
        this.main = main;
    }

    @Override
    public Component main() {
        return main.get();
    }

    @Override
    public List<WindowRoot> roots() {
        Window focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
        List<Window> windows = new ArrayList<Window>(Arrays.asList(Window.getWindows()));
        if (focused != null && windows.remove(focused)) windows.add(0, focused);
        List<WindowRoot> out = new ArrayList<WindowRoot>();
        for (Window w : windows) {
            // the palette's own windows (its folder dialog) are never the app's
            if (!w.isShowing() || isPopupWindow(w) || Roles.agentOwn(w)) continue;
            Dialog blocker = Modality.blocker(w);
            boolean modal = w instanceof Dialog && ((Dialog) w).isModal();
            out.add(new WindowRoot(w, w instanceof Dialog ? "dialog" : "window", titleOf(w), modal,
                    blocker == null ? null : titleOf(blocker), w == focused));
        }
        for (JPopupMenu popup : popups()) {
            out.add(new WindowRoot(popup, "menu", popupTitle(popup), false, null, false));
        }
        return out;
    }

    /** Showing popups; those on the menu selection path come last, in path order, so the innermost is last. */
    public List<JPopupMenu> popups() {
        List<JPopupMenu> found = new ArrayList<JPopupMenu>();
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) collectPopups(w, found);
        }
        List<JPopupMenu> onPath = new ArrayList<JPopupMenu>();
        for (MenuElement e : MenuSelectionManager.defaultManager().getSelectedPath()) {
            if (e instanceof JPopupMenu && found.contains(e)) onPath.add((JPopupMenu) e);
        }
        List<JPopupMenu> out = new ArrayList<JPopupMenu>();
        for (JPopupMenu p : found) {
            if (!onPath.contains(p)) out.add(p);
        }
        out.addAll(onPath);
        return out;
    }

    public static String titleOf(Window w) {
        String title = null;
        if (w instanceof Frame) title = ((Frame) w).getTitle();
        if (w instanceof Dialog) title = ((Dialog) w).getTitle();
        return title == null ? "" : title;
    }

    static boolean isPopupWindow(Window w) {
        return w.getType() == Window.Type.POPUP || w.getClass().getName().startsWith("javax.swing.Popup$");
    }

    private static void collectPopups(Component c, List<JPopupMenu> out) {
        if (Roles.agentOwn(c)) return;
        if (c instanceof JPopupMenu) {
            if (c.isShowing() && !out.contains(c)) out.add((JPopupMenu) c);
            return;
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) collectPopups(child, out);
        }
    }

    private static String popupTitle(JPopupMenu popup) {
        Component invoker = popup.getInvoker();
        if (invoker instanceof AbstractButton && ((AbstractButton) invoker).getText() != null) {
            return ((AbstractButton) invoker).getText();
        }
        return popup.getLabel() == null ? "" : popup.getLabel();
    }

    private static Window firstShowingFrame() {
        for (Window w : Window.getWindows()) {
            if (w instanceof Frame && w.isShowing()) return w;
        }
        return null;
    }
}
