package io.github.kormcrete.swingui.driver;

/** Launch mode, timeouts and snapshot budgets. */
public final class DriverSettings {

    public final LaunchMode mode;
    public final long settleMillis;
    public final long jobActionMillis;
    public final long expectMillis;
    public final int maxNodes;
    public final int maxRows;

    public DriverSettings(LaunchMode mode, long settleMillis, long jobActionMillis, long expectMillis,
                          int maxNodes, int maxRows) {
        this.mode = mode;
        this.settleMillis = settleMillis;
        this.jobActionMillis = jobActionMillis;
        this.expectMillis = expectMillis;
        this.maxNodes = maxNodes;
        this.maxRows = maxRows;
    }

    public static DriverSettings defaults(LaunchMode mode) {
        return new DriverSettings(mode, 30_000, 120_000, 10_000, 400, 20);
    }
}
