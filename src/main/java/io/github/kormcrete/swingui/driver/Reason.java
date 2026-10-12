package io.github.kormcrete.swingui.driver;

/** Why a driver call failed. */
public enum Reason {
    NOT_FOUND, AMBIGUOUS, NOT_ACTIONABLE, TIMEOUT, EXPECTATION, APP_ERROR, CAPABILITY_GAP, BAD_INPUT,
    /** Another owner holds the agent's lease. */
    BUSY;

    /**
     * "The harness can't do this", as opposed to "the app didn't do what the flow expected". The MCP
     * layer turns these into tool errors; every other reason is a failed step.
     */
    public boolean isToolError() {
        return this == CAPABILITY_GAP || this == BAD_INPUT || this == BUSY;
    }
}
