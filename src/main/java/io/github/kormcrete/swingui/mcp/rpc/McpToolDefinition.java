package io.github.kormcrete.swingui.mcp.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.Set;

/**
 * Single MCP tool: metadata + invocation.
 */
public interface McpToolDefinition {

    String name();

    /** JSON Schema descriptor sent in tools/list responses. */
    ObjectNode descriptor(ObjectMapper mapper);

    /** Invoked on tools/call. Returns the text content for the MCP response. */
    String invoke(JsonNode args) throws Exception;

    /**
     * Values of the {@code op} argument that only read: they run for anyone, like a read-only tool, also while a person
     * replays in the palette. Every other op of the tool is gated.
     */
    default Set<String> readOnlyOps() {
        return Collections.emptySet();
    }
}
