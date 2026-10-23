package io.github.kormcrete.swingui.driver.platform;

/** What the driver needs from JDK internals. A missing required one stops the launch. */
public enum Capability {
    PIN_FOCUSED_WINDOW(true, "pin the toolkit's focused window"),
    TYPE_AHEAD(true, "read and cancel queued focus requests"),
    OS_INPUT(false, "tell OS input from posted input"),
    DROP_PEER(false, "attach a drop peer"),
    DRAG_SOURCE(false, "stand in for a drag source"),
    SWING_DRAG(false, "stand in for a Swing drag"),
    APP_ACTIVE(false, "read whether the app is active");

    public final boolean required;
    public final String what;

    Capability(boolean required, String what) {
        this.required = required;
        this.what = what;
    }
}
