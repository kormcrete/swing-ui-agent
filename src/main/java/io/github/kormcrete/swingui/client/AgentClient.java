package io.github.kormcrete.swingui.client;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.flow.FlowJson;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;

/**
 * One agent as a test JVM sees it: tool calls over {@link AgentConnection}, the lease token added to each, and a
 * tool error turned back into the {@link DriverException} the agent threw (same reason, same message).
 * Immutable: {@link #withLease} returns a client sharing the connection.
 */
public final class AgentClient {

    private final AgentConnection connection;
    private final ObjectMapper json;
    private final String where;
    private final String lease;

    public AgentClient(String host, int port) {
        this(connect(host, port), host + ":" + port, null);
    }

    private AgentClient(AgentConnection connection, String where, String lease) {
        this.connection = connection;
        this.json = mapper();
        this.where = where;
        this.lease = lease;
    }

    private static AgentConnection connect(String host, int port) {
        try {
            return new AgentConnection(host, port, mapper());
        } catch (IOException e) {
            throw new AgentUnreachable(host + ":" + port, e);
        }
    }

    /** Driver types as the agent writes them; tolerant of fields a newer agent adds. */
    public static ObjectMapper mapper() {
        ObjectMapper m = FlowJson.registerDriverTypes(new ObjectMapper());
        m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return m;
    }

    public AgentClient withLease(String token) {
        return new AgentClient(connection, where, token);
    }

    public String lease() { return lease; }
    /** {@code host:port} of this agent. */
    public String where() { return where; }
    public ObjectMapper json() { return json; }
    public ObjectNode args() { return json.createObjectNode(); }

    /** The tools/call result as sent (its content blocks). */
    public JsonNode call(String tool, ObjectNode args) {
        if (lease != null) args.put("_lease", lease);
        JsonNode result;
        try {
            result = connection.callTool(tool, args);
        } catch (ConnectException e) {
            throw new AgentUnreachable(where, e);
        } catch (IOException e) {
            throw new DriverException(Reason.APP_ERROR, tool + " on " + where + ": " + e.getMessage());
        }
        if (result.path("isError").asBoolean(false)) throw toolError(firstText(result));
        return result;
    }

    /** The tool's answer: its first text block, parsed as JSON. */
    public JsonNode callJson(String tool, ObjectNode args) {
        return parse(firstText(call(tool, args)));
    }

    JsonNode parse(String text) {
        try {
            return json.readTree(text);
        } catch (IOException e) {
            throw new DriverException(Reason.APP_ERROR, "the agent answered no JSON: " + text);
        }
    }

    DriverException toolError(String text) {
        JsonNode body = parse(text);
        String type = body.path("errorType").asText("");
        String name = body.hasNonNull("reason") ? body.get("reason").asText() : null;
        Reason reason = name != null ? reasonOf(name)
                : "CAPABILITY_GAP".equals(type) ? Reason.CAPABILITY_GAP : Reason.BAD_INPUT;
        String message = DriverException.stripReason(body.path("message").asText(text), reason);
        if (name != null && !reason.name().equals(name)) {
            message = "(reason " + name + " is unknown here: this JVM's swing-ui-agent classes are older than the "
                    + "client's agent - rebuild, or restart the client) " + message;
        }
        return new DriverException(reason, message, strings(body.get("details")));
    }

    /** The reason by name; one this JVM's classes do not know (version skew) is an APP_ERROR. */
    static Reason reasonOf(String name) {
        try {
            return Reason.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Reason.APP_ERROR;
        }
    }

    static String firstText(JsonNode result) {
        for (JsonNode c : result.path("content")) {
            if ("text".equals(c.path("type").asText())) return c.path("text").asText();
        }
        throw new DriverException(Reason.APP_ERROR, "the agent's reply has no text block: " + result);
    }

    static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<String>();
        if (array != null) for (JsonNode n : array) out.add(n.asText());
        return out;
    }
}
