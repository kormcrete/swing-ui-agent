package io.github.kormcrete.swingui.driver.settle;

import io.github.kormcrete.swingui.driver.Edt;

import javax.swing.RootPaneContainer;
import java.awt.Component;
import java.awt.Window;
import java.util.Arrays;

/** A visible glass pane that takes mouse input blocks the window (e.g. a "please wait" blocker). */
public final class GlassPaneBusy implements BusySignal {

    @Override
    public String name() {
        return "glass pane blocker";
    }

    @Override
    public boolean isBusy() {
        return Edt.call(() -> WindowBusy.anywhere(Arrays.asList(Window.getWindows()), Window::isShowing,
                GlassPaneBusy::blocks));
    }

    /** The acting window's own answer; the global one once that window has closed ({@link WindowBusy#scoped}). */
    @Override
    public boolean isBusyIn(Object window) {
        if (!(window instanceof Window)) return isBusy();
        final Window w = (Window) window;
        return Edt.call(() -> WindowBusy.scoped(w, Arrays.asList(Window.getWindows()), Window::isShowing,
                GlassPaneBusy::blocks));
    }

    private static boolean blocks(Window w) {
        if (!(w instanceof RootPaneContainer)) return false;
        Component glass = ((RootPaneContainer) w).getGlassPane();
        return glass != null && glass.isVisible() && glass.getMouseListeners().length > 0;
    }
}
