package io.github.kormcrete.swingui.mcp.rpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Single MCP resource: metadata, URI matching, and content read.
 */
public interface McpResourceDefinition {

    /** Canonical URI, or a single-placeholder template, e.g. "app://domain" or "app://resource/{name}/schema". */
    String uri();

    /** Entry for resources/list responses: {uri, name, description, mimeType}. */
    ObjectNode descriptor(ObjectMapper mapper);

    /** True if this definition serves the requested URI (exact match or template match). */
    boolean matches(String requestedUri);

    /** MIME type of the read result, e.g. "application/json" or "text/plain". */
    String mimeType();

    /** Invoked on resources/read. Returns the text content for the MCP response. */
    String read(String requestedUri) throws Exception;
}
