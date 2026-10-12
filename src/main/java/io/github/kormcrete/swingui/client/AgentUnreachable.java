package io.github.kormcrete.swingui.client;

/** Nothing answers on the agent's port: the app is not running with the agent, or on another port. */
public final class AgentUnreachable extends RuntimeException {
    public AgentUnreachable(String where, Throwable cause) {
        super("no swing-ui-agent on " + where + " — start the app with -javaagent:swing-ui-agent.jar=port=N and retry",
                cause);
    }
}
