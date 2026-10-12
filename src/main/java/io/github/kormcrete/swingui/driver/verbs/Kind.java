package io.github.kormcrete.swingui.driver.verbs;

/** What a verb requires of its target before acting. */
enum Kind {
    /** Showing, enabled, the point visible, and a posted press there reaches it. */
    POINTER,
    /** Showing and enabled; focus is emulated, so what covers it does not matter. */
    FOCUS,
    /** Showing with the point visible; a drop target need not be enabled. */
    VISIBLE
}
