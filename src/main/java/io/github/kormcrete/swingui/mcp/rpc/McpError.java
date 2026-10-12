package io.github.kormcrete.swingui.mcp.rpc;

/**
 * A classified tool error: a {@link McpErrorType} plus the human-readable message.
 */
public final class McpError {

    private final McpErrorType type;
    private final String message;

    public McpError(McpErrorType type, String message) {
        this.type = type;
        this.message = message;
    }

    public McpErrorType type() {
        return type;
    }

    public String message() {
        return message;
    }
}
