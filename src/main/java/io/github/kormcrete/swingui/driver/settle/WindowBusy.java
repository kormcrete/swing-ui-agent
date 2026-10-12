package io.github.kormcrete.swingui.driver.settle;

import java.util.function.Predicate;

/**
 * The rule a window-bound {@link BusySignal} (a spinner, a glass pane) answers by. Generic over the
 * window type so it is testable without windows; a live signal runs it on the EDT, in one call.
 */
public final class WindowBusy {

    private WindowBusy() {}

    /** Whether any showing window is busy: the global answer. */
    public static <W> boolean anywhere(Iterable<W> windows, Predicate<W> showing, Predicate<W> busy) {
        for (W w : windows) {
            if (showing.test(w) && busy.test(w)) return true;
        }
        return false;
    }

    /**
     * Busy as seen from {@code scope}, the window a step acted in: its own answer while it shows. Once it
     * has closed (the step closed it, e.g. a dialog's OK), the app went on somewhere else, so the answer
     * is the global one.
     */
    public static <W> boolean scoped(W scope, Iterable<W> windows, Predicate<W> showing, Predicate<W> busy) {
        return showing.test(scope) ? busy.test(scope) : anywhere(windows, showing, busy);
    }
}
