package io.github.kormcrete.swingui.mcp.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Single MCP tool: metadata + invocation.
 */
public interface McpToolDefinition {

    String name();

    /** JSON Schema descriptor sent in tools/list responses. */
    ObjectNode descriptor(ObjectMapper mapper);

    /** Invoked on tools/call. Returns the text content for the MCP response. */
    String invoke(JsonNode args) throws Exception;
}
