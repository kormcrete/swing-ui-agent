package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kormcrete.swingui.mcp.rpc.McpError;
import io.github.kormcrete.swingui.mcp.rpc.McpErrorClassifier;
import io.github.kormcrete.swingui.mcp.rpc.McpErrorType;

/** Maps a tool's exception to the {@code errorType} body, by type rather than by message. */
final class ToolErrors {

    private static final McpErrorClassifier FALLBACK = new McpErrorClassifier();

    private ToolErrors() {}

    static ObjectNode body(ObjectMapper m, Throwable t) {
        ObjectNode body = m.createObjectNode();
        if (t instanceof DriverException) {
            DriverException e = (DriverException) t;
            body.put("errorType", typeOf(e.reason()).name());
            body.put("reason", e.reason().name());
            body.put("message", e.getMessage());
            if (!e.details().isEmpty()) {
                ArrayNode details = body.putArray("details");
                for (String d : e.details()) details.add(d);
            }
            return body;
        }
        if (t instanceof JsonProcessingException) {
            body.put("errorType", McpErrorType.INPUT_ERROR.name());
            body.put("message", "malformed arguments: " + ((JsonProcessingException) t).getOriginalMessage());
            return body;
        }
        McpError error = FALLBACK.classify(t);
        body.put("errorType", error.type().name());
        // an Error is named by its class, so a NoSuchMethodError reads as a plugin/app version mismatch
        body.put("message", t instanceof Exception ? error.message() : t.toString());
        return body;
    }

    static McpErrorType typeOf(Reason reason) {
        switch (reason) {
            case CAPABILITY_GAP: return McpErrorType.CAPABILITY_GAP;
            case NOT_FOUND:
            case AMBIGUOUS: return McpErrorType.NOT_FOUND;
            default: return McpErrorType.INPUT_ERROR;
        }
    }
}
