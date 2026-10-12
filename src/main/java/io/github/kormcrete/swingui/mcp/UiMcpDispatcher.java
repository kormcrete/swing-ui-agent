package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.EdtClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kormcrete.swingui.mcp.rpc.McpDispatcher;
import io.github.kormcrete.swingui.mcp.rpc.McpToolDefinition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The MCP dispatcher with two parts of its own: {@code tools/call} maps a driver exception by its reason
 * (the base classifier matches message text), catches Errors too and logs a {@link UiMcpTiming} line; and
 * {@code initialize} carries this server's instructions instead of the generic text. Everything else goes to the
 * base dispatcher unchanged.
 */
public final class UiMcpDispatcher extends McpDispatcher {

    static final String INSTRUCTIONS = "This server drives the live Swing application the way a user would, "
            + "without taking the user's mouse, keyboard or focus. Read the screen with 'snapshot' (refs e1, e2, ... "
            + "stay valid until the next snapshot) and 'read'. Act with the verb tools (click, type, press, check, "
            + "select, pick, menu, context_menu, answer, choose_file, drag, drop, scroll, expect, set_clipboard; paste "
            + "with press \"Mod+V\", read a copy back with read_clipboard; a file chooser's folder with read_file_chooser); a 'target' is {\"ref\":...} "
            + "or a locator {window, scope, role, name, id, row, column, item, point}. The app steps navigate, close "
            + "and loginAs run through do_action / do_actions (they need the app plugin). Call session first: app steps (navigate, close, loginAs, describe_ui) need the app plugin 'ready'. Run several known steps in ONE 'batch' call, and keep reads small: scope a "
            + "snapshot with 'target' and 'depth' (compact:true drops rows), or check one element with 'expect' / "
            + "'read'. Every successful step with a Java form is recorded; "
            + "export_test writes a JUnit UI test. "
            + "A result with passed:false means the app did not do what the step expected: report it. A result "
            + "that isError with an errorType body means the harness cannot do this (CAPABILITY_GAP) or the "
            + "request was malformed (INPUT_ERROR): report it to the user instead of working around it.";

    private final ObjectMapper mapper;
    private final Map<String, McpToolDefinition> tools = new LinkedHashMap<String, McpToolDefinition>();

    public UiMcpDispatcher(ObjectMapper mapper, List<McpToolDefinition> tools) {
        super(mapper, "swing-ui-agent", tools, Collections.emptyList(), Collections.emptyList());
        this.mapper = mapper;
        for (McpToolDefinition t : tools) this.tools.put(t.name(), t);
    }

    @Override
    public void dispatch(String json, Emitter emitter) {
        long received = System.nanoTime();
        JsonNode message;
        try {
            message = mapper.readTree(json);
        } catch (Exception e) {
            parseError(e, emitter);
            return;
        }
        String method = message.path("method").asText();
        String tool = message.path("params").path("name").asText();
        if ("tools/call".equals(method) && tools.containsKey(tool)) {
            callTool(message, tools.get(tool), emitter, received);
        } else if ("initialize".equals(method)) {
            super.dispatch(json, node -> {
                if (node.path("result").isObject()) ((ObjectNode) node.get("result")).put("instructions", INSTRUCTIONS);
                emitter.emit(node);
            });
        } else {
            super.dispatch(json, emitter);
        }
    }

    /**
     * A tool normally answers with text. One that has to answer with something else - {@code screenshot}
     * returns an image - wraps its blocks in a top-level {@code _mcpContent} array, which is emitted
     * verbatim. The marker is deliberately not "content": no tool's own payload can collide with it.
     */
    private void setContent(ObjectNode result, String text, boolean isError) {
        if (!isError && text != null && text.startsWith("{")) {
            try {
                JsonNode body = mapper.readTree(text);
                JsonNode blocks = body.get("_mcpContent");
                if (blocks != null && blocks.isArray() && blocks.size() > 0) {
                    result.set("content", blocks);
                    return;
                }
            } catch (Exception ignored) {
                // not JSON, or not ours: fall through and send it as text
            }
        }
        result.putArray("content").addObject().put("type", "text").put("text", text == null ? "" : text);
    }

    /**
     * Runs the tool and emits its answer, then logs the request's {@link UiMcpTiming} line: the whole
     * request from {@code received}, the tool's own time split by {@link EdtClock}, the emit, and the
     * answer's size.
     */
    private void callTool(JsonNode message, McpToolDefinition tool, Emitter emitter, long received) {
        JsonNode args = message.path("params").path("arguments");
        String text;
        boolean isError = false;
        String errorType = null;
        EdtClock.reset();
        long invoked = System.nanoTime();
        try {
            text = tool.invoke(args);
        } catch (Throwable e) {
            // an Error too (a LinkageError from a stale plugin, a StackOverflowError): it must never reach the app's
            // uncaught-exception handler, and the caller must get an answer
            ObjectNode body = ToolErrors.body(mapper, e);
            errorType = body.path("errorType").asText(null);
            text = body.toString();
            isError = true;
        }
        long returned = System.nanoTime();
        EdtClock.Reading edt = EdtClock.read();
        ObjectNode result = mapper.createObjectNode();
        result.put("isError", isError);
        setContent(result, text, isError);
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        JsonNode id = message.get("id");
        if (id != null && !id.isNull()) response.set("id", id);
        response.set("result", result);
        try {
            emitter.emit(response);
        } catch (Exception ignored) {
            // the caller hung up; the transport handles that
        }
        long emitted = System.nanoTime();
        try {
            UiMcpTiming.log(UiMcpTiming.line(tool.name(), UiMcpTiming.status(text, isError, errorType),
                    millis(emitted - received), millis(returned - invoked), edt, millis(emitted - returned),
                    UiMcpTiming.bytes(text), UiMcpTiming.target(tool.name(), args)));
        } catch (RuntimeException ignored) {
            // measuring must never cost the caller its answer
        }
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000L;
    }
}
