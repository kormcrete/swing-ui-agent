package io.github.kormcrete.swingui.driver.window;

import java.awt.Component;

/** One top-level thing a snapshot or a locator starts from: a window, a dialog, or a showing popup. */
public final class WindowRoot {

    public final Component content;
    /** {@code window}, {@code dialog} or {@code menu} (a popup). */
    public final String role;
    public final String title;
    public final boolean modal;
    /** Title of the dialog that blocks this root, or null. */
    public final String blockedBy;
    public final boolean focused;

    public WindowRoot(Component content, String role, String title, boolean modal, String blockedBy, boolean focused) {
        this.content = content;
        this.role = role;
        this.title = title == null ? "" : title;
        this.modal = modal;
        this.blockedBy = blockedBy;
        this.focused = focused;
    }
}
