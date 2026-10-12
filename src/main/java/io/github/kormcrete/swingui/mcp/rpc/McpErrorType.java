package io.github.kormcrete.swingui.mcp.rpc;

/**
 * The MCP tool-error taxonomy: the server cannot do this ({@code CAPABILITY_GAP}), the request was malformed
 * ({@code INPUT_ERROR}), or what it named is not there ({@code NOT_FOUND}).
 */
public enum McpErrorType {
    CAPABILITY_GAP, INPUT_ERROR, NOT_FOUND
}
