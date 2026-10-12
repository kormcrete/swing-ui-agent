package io.github.kormcrete.swingui.mcp.rpc;

/**
 * Maps an exception thrown by a tool into a typed {@link McpError} by its message: one that says "not found" is
 * {@code NOT_FOUND}, anything else {@code INPUT_ERROR}.
 */
public final class McpErrorClassifier {

    public McpError classify(Throwable t) {
        String msg = t.getMessage() != null ? t.getMessage() : t.toString();
        if (msg.toLowerCase().contains("not found")) {
            return new McpError(McpErrorType.NOT_FOUND, msg);
        }
        // arity, parse, validation, and any unexpected error
        return new McpError(McpErrorType.INPUT_ERROR, msg);
    }
}
