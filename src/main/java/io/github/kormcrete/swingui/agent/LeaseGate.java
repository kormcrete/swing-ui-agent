package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.mcp.rpc.McpToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps every tool that is not read-only so it runs under the {@link Lease}; a read-only tool runs for anyone, and
 * renews the lease when it carries the holder's token. The token travels as the argument {@value #TOKEN_FIELD} and
 * is stripped before the tool sees its arguments.
 */
final class LeaseGate {

    static final String TOKEN_FIELD = "_lease";

    private LeaseGate() {}

    static List<McpToolDefinition> gate(List<McpToolDefinition> tools, Lease lease, ObjectMapper mapper) {
        List<McpToolDefinition> out = new ArrayList<McpToolDefinition>();
        for (McpToolDefinition t : tools) {
            boolean readOnly = t.descriptor(mapper).path("annotations").path("readOnlyHint").asBoolean(false);
            out.add(readOnly ? renewing(t, lease) : gated(t, lease));
        }
        return out;
    }

    static String token(JsonNode args) {
        return args != null && args.hasNonNull(TOKEN_FIELD) ? args.get(TOKEN_FIELD).asText() : null;
    }

    static JsonNode strip(JsonNode args) {
        if (args == null || !args.isObject() || !args.has(TOKEN_FIELD)) return args;
        ObjectNode copy = ((ObjectNode) args).deepCopy();
        copy.remove(TOKEN_FIELD);
        return copy;
    }

    /** A read-only tool runs for anyone; the holder's token, when sent, renews the lease and is stripped. */
    private static McpToolDefinition renewing(final McpToolDefinition tool, final Lease lease) {
        return new McpToolDefinition() {
            @Override public String name() { return tool.name(); }

            @Override public ObjectNode descriptor(ObjectMapper mapper) { return tool.descriptor(mapper); }

            @Override public String invoke(JsonNode args) throws Exception {
                lease.renew(token(args));
                return tool.invoke(strip(args));
            }
        };
    }

    private static McpToolDefinition gated(final McpToolDefinition tool, final Lease lease) {
        return new McpToolDefinition() {
            @Override public String name() { return tool.name(); }

            @Override public ObjectNode descriptor(ObjectMapper mapper) {
                ObjectNode d = tool.descriptor(mapper);
                JsonNode schema = d.get("inputSchema");
                if (schema instanceof ObjectNode) {
                    ObjectNode props = ((ObjectNode) schema).has("properties")
                            ? (ObjectNode) schema.get("properties") : ((ObjectNode) schema).putObject("properties");
                    props.putObject(TOKEN_FIELD).put("type", "string")
                            .put("description", "lease token from session op 'lease'; omit while nobody holds the lease");
                }
                return d;
            }

            @Override public String invoke(JsonNode args) throws Exception {
                String token = token(args);
                lease.enter(token);
                try {
                    return tool.invoke(strip(args));
                } finally {
                    lease.exit(token);
                }
            }
        };
    }
}
