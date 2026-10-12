package io.github.kormcrete.swingui.mcp.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON-RPC 2.0 dispatcher for the MCP protocol. Callers supply an {@link Emitter} that receives each outbound
 * JSON frame; the dispatcher never touches I/O directly.
 *
 * <p>{@code domainName} is the {@code serverInfo.name} of the {@code initialize} response; null or empty
 * falls back to "mcp-server".
 */
public class McpDispatcher {

    public interface Emitter {
        void emit(JsonNode node) throws Exception;
    }

    private static final String PROTOCOL_VERSION = "2025-03-26";

    private final ObjectMapper mapper;
    private final String domainName;
    private final Map<String, McpToolDefinition> tools;
    private final List<McpResourceDefinition> resources;
    private final Map<String, McpPromptDefinition> prompts;
    private final McpErrorClassifier classifier = new McpErrorClassifier();

    public McpDispatcher(ObjectMapper mapper,
                         String domainName,
                         List<McpToolDefinition> tools,
                         List<McpResourceDefinition> resources,
                         List<McpPromptDefinition> prompts) {
        this.mapper = mapper;
        this.domainName = domainName != null && !domainName.isEmpty() ? domainName : "mcp-server";
        this.tools = new LinkedHashMap<String, McpToolDefinition>();
        for (McpToolDefinition t : tools) {
            this.tools.put(t.name(), t);
        }
        this.resources = new ArrayList<McpResourceDefinition>(resources);
        this.prompts = new LinkedHashMap<String, McpPromptDefinition>();
        for (McpPromptDefinition p : prompts) {
            this.prompts.put(p.name(), p);
        }
    }

    /**
     * Dispatches one JSON-RPC message; all responses are sent via {@code emitter}. A body that is not JSON
     * is answered with a parse error (-32700); an exception from the emitter is swallowed, so the transport
     * must handle connection-level errors itself.
     */
    public void dispatch(String json, Emitter emitter) {
        JsonNode msg;
        try {
            msg = mapper.readTree(json);
        } catch (Exception e) {
            parseError(e, emitter);
            return;
        }
        try {
            JsonNode id = msg.get("id");
            String method = msg.path("method").asText();

            if ("initialize".equals(method)) {
                sendResult(id, buildInitializeResult(), emitter);

            } else if ("notifications/initialized".equals(method)
                    || "initialized".equals(method)) {
                // notification — no response

            } else if ("tools/list".equals(method)) {
                sendResult(id, buildToolsList(), emitter);

            } else if ("tools/call".equals(method)) {
                JsonNode params = msg.path("params");
                String name = params.path("name").asText();
                JsonNode args = params.path("arguments");
                McpToolDefinition tool = tools.get(name);
                if (tool == null) {
                    sendError(id, -32601, "Unknown tool: " + name, emitter);
                } else {
                    String toolResult;
                    boolean isError = false;
                    try {
                        toolResult = tool.invoke(args);
                    } catch (Exception e) {
                        McpError err = classifier.classify(e);
                        ObjectNode body = mapper.createObjectNode();
                        body.put("errorType", err.type().name());
                        body.put("message", err.message());
                        toolResult = body.toString();
                        isError = true;
                    }
                    sendResult(id, buildToolResult(toolResult, isError), emitter);
                }

            } else if ("resources/list".equals(method)) {
                sendResult(id, buildResourcesList(), emitter);

            } else if ("resources/read".equals(method)) {
                String uri = msg.path("params").path("uri").asText();
                if (uri.isEmpty()) {
                    sendError(id, -32602, "Missing required param: uri", emitter);
                } else {
                    handleResourceRead(id, uri, emitter);
                }

            } else if ("prompts/list".equals(method)) {
                sendResult(id, buildPromptsList(), emitter);

            } else if ("prompts/get".equals(method)) {
                JsonNode params = msg.path("params");
                String name = params.path("name").asText();
                JsonNode promptArgs = params.path("arguments");
                if (name.isEmpty()) {
                    sendError(id, -32602, "Missing required param: name", emitter);
                } else {
                    McpPromptDefinition prompt = prompts.get(name);
                    if (prompt == null) {
                        sendError(id, -32602, "Unknown prompt: " + name, emitter);
                    } else {
                        sendResult(id, prompt.get(promptArgs, mapper), emitter);
                    }
                }

            } else if ("ping".equals(method)) {
                sendResult(id, mapper.createObjectNode(), emitter);
            }
            // unknown methods get no answer

        } catch (Exception ignored) {
            // an emitter or transport error
        }
    }

    /** Answers a body that is not JSON: error -32700 with a null id, as JSON-RPC 2.0 requires. */
    protected final void parseError(Exception e, Emitter emitter) {
        try {
            ObjectNode resp = mapper.createObjectNode();
            resp.put("jsonrpc", "2.0");
            resp.putNull("id");
            ObjectNode err = resp.putObject("error");
            err.put("code", -32700);
            err.put("message", "parse error: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            emitter.emit(resp);
        } catch (Exception ignored) {
            // the transport handles connection-level errors
        }
    }

    /** Builds a {@code notifications/resources/list_changed} notification frame (no id). */
    public JsonNode buildResourcesListChangedNotification() {
        ObjectNode n = mapper.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.put("method", "notifications/resources/list_changed");
        return n;
    }

    // --- response builders ---

    private JsonNode buildInitializeResult() {
        ObjectNode r = mapper.createObjectNode();
        r.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode caps = r.putObject("capabilities");
        caps.putObject("tools");
        if (!resources.isEmpty()) {
            caps.putObject("resources").put("listChanged", true);
        }
        if (!prompts.isEmpty()) {
            caps.putObject("prompts");
        }
        ObjectNode info = r.putObject("serverInfo");
        info.put("name", domainName);
        info.put("version", "1.0");
        r.put("instructions", buildInstructions());
        return r;
    }

    private String buildInstructions() {
        return "This server is '" + domainName + "'. A tool result that isError with a JSON "
                + "{ \"errorType\": ... } body means the server cannot do this (CAPABILITY_GAP), could not find "
                + "what was named (NOT_FOUND) or got a malformed request (INPUT_ERROR): report it to the user "
                + "instead of working around it.";
    }

    private JsonNode buildToolsList() {
        ObjectNode r = mapper.createObjectNode();
        ArrayNode arr = r.putArray("tools");
        for (McpToolDefinition tool : tools.values()) {
            arr.add(tool.descriptor(mapper));
        }
        return r;
    }

    private JsonNode buildToolResult(String text, boolean isError) {
        ObjectNode r = mapper.createObjectNode();
        r.put("isError", isError);
        ArrayNode content = r.putArray("content");
        content.addObject().put("type", "text").put("text", text != null ? text : "");
        return r;
    }

    private JsonNode buildResourcesList() {
        ObjectNode r = mapper.createObjectNode();
        ArrayNode arr = r.putArray("resources");
        for (McpResourceDefinition res : resources) {
            arr.add(res.descriptor(mapper));
        }
        return r;
    }

    private void handleResourceRead(JsonNode id, String uri, Emitter emitter) throws Exception {
        for (McpResourceDefinition res : resources) {
            if (res.matches(uri)) {
                String text;
                try {
                    text = res.read(uri);
                } catch (Exception e) {
                    String m = e.getMessage() != null ? e.getMessage() : e.toString();
                    sendError(id, -32603, "Resource read failed: " + m, emitter);
                    return;
                }
                ObjectNode result = mapper.createObjectNode();
                ArrayNode contents = result.putArray("contents");
                ObjectNode c = contents.addObject();
                c.put("uri", uri);
                c.put("mimeType", res.mimeType());
                c.put("text", text != null ? text : "");
                sendResult(id, result, emitter);
                return;
            }
        }
        sendError(id, -32602, "Unknown resource: " + uri, emitter);
    }

    private JsonNode buildPromptsList() {
        ObjectNode r = mapper.createObjectNode();
        ArrayNode arr = r.putArray("prompts");
        for (McpPromptDefinition p : prompts.values()) {
            arr.add(p.descriptor(mapper));
        }
        return r;
    }

    // --- wire helpers ---

    private void sendResult(JsonNode id, JsonNode result, Emitter emitter) throws Exception {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        if (id != null && !id.isNull()) resp.set("id", id);
        resp.set("result", result);
        emitter.emit(resp);
    }

    private void sendError(JsonNode id, int code, String message, Emitter emitter) throws Exception {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        if (id != null && !id.isNull()) resp.set("id", id);
        ObjectNode err = resp.putObject("error");
        err.put("code", code);
        err.put("message", message);
        emitter.emit(resp);
    }
}
