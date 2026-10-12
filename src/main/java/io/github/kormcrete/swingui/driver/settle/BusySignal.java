package io.github.kormcrete.swingui.driver.settle;

/** Something that makes the app not ready for the next step. Called off the EDT. */
public interface BusySignal {

    String name();

    boolean isBusy();

    /**
     * Busy as seen from {@code window} — the window the step acted in. A signal tied to no window
     * (background jobs) ignores it; one that lives in a window (a spinner, a glass pane) answers for
     * that window only, so a modal is not held by the busy spinner of the window it blocks.
     */
    default boolean isBusyIn(Object window) {
        return isBusy();
    }
}
