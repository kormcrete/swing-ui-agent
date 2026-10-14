package io.github.kormcrete.swingui.driver.inspect;

/**
 * For tests outside this package: makes an inspector over an unshown scene this JVM's one, its palette driving the app
 * for a reason ({@link Inspector#drivingReason}); closing puts the one before back.
 */
public final class PaletteDriving implements AutoCloseable {

    private final Inspector installed = new Scene().inspector;
    private final Inspector before;

    public PaletteDriving(String reason) {
        installed.driving(reason);
        before = Inspector.swap(installed);
    }

    /** The palette's run or replay goes on for {@code reason}, or has ended (null). */
    public void reason(String reason) {
        installed.driving(reason);
    }

    @Override
    public void close() {
        Inspector.swap(before);
    }
}
