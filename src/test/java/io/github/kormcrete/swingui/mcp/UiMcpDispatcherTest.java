package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import io.github.kormcrete.swingui.mcp.rpc.McpToolDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/** "The harness can't do this" is a typed tool error; the instructions describe this server. */
class UiMcpDispatcherTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> emitted = new ArrayList<JsonNode>();

    private McpToolDefinition tool(final String name, final Callable<String> result) {
        return new McpToolDefinition() {
            @Override public String name() { return name; }
            @Override public ObjectNode descriptor(ObjectMapper m) { return m.createObjectNode().put("name", name); }
            @Override public String invoke(JsonNode args) throws Exception { return result.call(); }
        };
    }

    private JsonNode call(McpToolDefinition tool) throws Exception {
        UiMcpDispatcher d = new UiMcpDispatcher(mapper, Collections.singletonList(tool));
        d.dispatch("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool.name() + "\",\"arguments\":{}}}", emitted::add);
        assertThat(emitted).hasSize(1);
        return emitted.get(0);
    }

    private JsonNode body(JsonNode response) throws Exception {
        return mapper.readTree(response.path("result").path("content").get(0).path("text").asText());
    }

    @Test
    void aResultIsPlainText() throws Exception {
        JsonNode r = call(tool("snapshot", () -> "{\"ok\":true}"));
        assertThat(r.path("id").asInt()).isEqualTo(7);
        assertThat(r.path("result").path("isError").asBoolean()).isFalse();
        assertThat(body(r).path("ok").asBoolean()).isTrue();
    }

    @Test
    void aCapabilityGapIsATypedToolError() throws Exception {
        JsonNode r = call(tool("click", () -> { throw new DriverException(Reason.CAPABILITY_GAP, "no popup"); }));
        assertThat(r.path("result").path("isError").asBoolean()).isTrue();
        assertThat(body(r).path("errorType").asText()).isEqualTo("CAPABILITY_GAP");
        assertThat(body(r).path("reason").asText()).isEqualTo("CAPABILITY_GAP");
        assertThat(body(r).path("message").asText()).contains("no popup");
    }

    @Test
    void badInputIsAnInputErrorWithDetails() throws Exception {
        JsonNode r = call(tool("read", () -> {
            throw new DriverException(Reason.BAD_INPUT, "stale ref e3", Collections.singletonList("take a new snapshot"));
        }));
        assertThat(body(r).path("errorType").asText()).isEqualTo("INPUT_ERROR");
        assertThat(body(r).path("details").get(0).asText()).isEqualTo("take a new snapshot");
    }

    @Test
    void otherExceptionsFallBackToTheBaseClassifier() throws Exception {
        JsonNode r = call(tool("do_action", () -> { throw new IllegalArgumentException("Missing required 'action' object"); }));
        assertThat(r.path("result").path("isError").asBoolean()).isTrue();
        assertThat(body(r).path("errorType").asText()).isEqualTo("INPUT_ERROR");
    }

    @Test
    void anErrorFromAToolIsAToolErrorNamingItsClass() throws Exception {
        // a stale plugin class folder: the Error must never reach the app's uncaught-exception handler
        JsonNode r = call(tool("do_action", () -> { throw new NoSuchMethodError("com.example.orders.App.open()V"); }));
        assertThat(r.path("id").asInt()).isEqualTo(7);
        assertThat(r.path("result").path("isError").asBoolean()).isTrue();
        assertThat(body(r).path("message").asText()).contains("NoSuchMethodError").contains("com.example.orders.App.open()V");
    }

    @Test
    void anUnknownToolIsAJsonRpcError() {
        UiMcpDispatcher d = new UiMcpDispatcher(mapper, Collections.<McpToolDefinition>emptyList());
        d.dispatch("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"nope\"}}", emitted::add);
        assertThat(emitted.get(0).path("error").path("code").asInt()).isEqualTo(-32601);
    }

    @Test
    void initializeDescribesTheUiHarness() {
        UiMcpDispatcher d = new UiMcpDispatcher(mapper, Collections.<McpToolDefinition>emptyList());
        d.dispatch("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", emitted::add);
        JsonNode result = emitted.get(0).path("result");
        assertThat(result.path("serverInfo").path("name").asText()).isEqualTo("swing-ui-agent");
        assertThat(result.path("instructions").asText())
                .contains("Swing application").contains("snapshot").contains("CAPABILITY_GAP").doesNotContain("This server is '");
    }

    @Test
    void toolsListIsPassedThrough() {
        UiMcpDispatcher d = new UiMcpDispatcher(mapper, Collections.singletonList(tool("snapshot", () -> "")));
        d.dispatch("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", emitted::add);
        assertThat(emitted.get(0).path("result").path("tools").get(0).path("name").asText()).isEqualTo("snapshot");
    }
}
