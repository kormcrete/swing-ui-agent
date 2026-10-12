package io.github.kormcrete.swingui.mcp.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Single MCP prompt: metadata + expansion.
 */
public interface McpPromptDefinition {

    String name();

    /** Entry for prompts/list responses: {name, description, arguments:[{name, description, required}]}. */
    ObjectNode descriptor(ObjectMapper mapper);

    /**
     * Invoked on prompts/get. Returns the full result object:
     * {description, messages:[{role, content:{type:"text", text}}]}.
     */
    ObjectNode get(JsonNode args, ObjectMapper mapper);
}
