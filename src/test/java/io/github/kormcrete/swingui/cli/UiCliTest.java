package io.github.kormcrete.swingui.cli;

import io.github.kormcrete.swingui.mcp.HttpMcpEndpoint;
import io.github.kormcrete.swingui.mcp.UiMcpDispatcher;
import io.github.kormcrete.swingui.mcp.rpc.McpDispatcher;
import io.github.kormcrete.swingui.mcp.rpc.McpToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class UiCliTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private HttpMcpEndpoint endpoint;
    @TempDir Path dir;

    private int start() throws Exception {
        McpToolDefinition echo = tool("echo", "Echoes 'text'.", a -> a.path("text").asText());
        McpToolDefinition fail = tool("fail", "Always fails.", a -> { throw new IllegalArgumentException("nope"); });
        McpToolDefinition linkage = tool("stale", "A stale plugin class.", a -> { throw new NoSuchMethodError("com.example.orders.App.open()V"); });
        // the real shape: UiMcpSession.screenshot answers {"_mcpContent":[image block, text block]}
        McpToolDefinition shot = tool("screenshot", "An image.", a -> {
            ObjectNode r = mapper.createObjectNode();
            ArrayNode content = r.putArray("_mcpContent");
            content.addObject().put("type", "image").put("mimeType", "image/png")
                    .put("data", Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}));
            content.addObject().put("type", "text").put("text", "{\"width\":1,\"height\":1}");
            return r.toString();
        });
        McpToolDefinition doAction = tool("do_action", "Echoes its arguments.", a -> a.toString());
        // run_flow answers like the real tool: a failed flow is a result, not an error; the name picks the outcome
        McpToolDefinition runFlow = tool("run_flow", "A flow.", a -> a.path("name").asText().equals("good")
                ? "{\"passed\":true,\"report\":\"Flow 'good' passed:\"}"
                : "{\"passed\":false,\"report\":\"Flow 'x' failed: [0] FAIL click\"}");
        McpToolDefinition click = tool("click", "A click.", a -> "{\"passed\":false}");
        UiMcpDispatcher d = new UiMcpDispatcher(mapper, Arrays.asList(echo, fail, linkage, shot, doAction, runFlow, click));
        endpoint = new HttpMcpEndpoint(d, mapper, "127.0.0.1", "/mcp", 0);
        endpoint.start();
        return endpoint.port();
    }

    @AfterEach
    void stop() {
        if (endpoint != null) endpoint.stop();
    }

    private int cli(String... args) {
        return UiCli.run(args, new PrintStream(out, true), new PrintStream(err, true));
    }

    @Test
    void callsAToolAndPrintsItsText() throws Exception {
        int port = start();
        assertThat(cli("--port", String.valueOf(port), "echo", "{\"text\":\"hello\"}")).isEqualTo(0);
        assertThat(out.toString().trim()).isEqualTo("hello");
    }

    @Test
    void aFailedFlowExitsFourWithItsReportOnStderr() throws Exception {
        int port = start();
        assertThat(cli("--port", String.valueOf(port), "run_flow", "{\"name\":\"bad\"}")).isEqualTo(4);
        assertThat(mapper.readTree(out.toString()).path("passed").asBoolean()).isFalse();
        assertThat(err.toString()).contains("Flow 'x' failed: [0] FAIL click");
    }

    @Test
    void aPassedFlowExitsZero() throws Exception {
        int port = start();
        assertThat(cli("--port", String.valueOf(port), "run_flow", "{\"name\":\"good\"}")).isEqualTo(0);
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void aFailedClickStillExitsZero() throws Exception {
        int port = start();
        assertThat(cli("--port", String.valueOf(port), "click", "{}")).isEqualTo(0);
    }

    @Test
    void listsToolsByNameAndShowsOneInFull() throws Exception {
        int port = start();
        assertThat(cli("--port", String.valueOf(port), "tools")).isEqualTo(0);
        assertThat(out.toString()).contains("echo").contains("Echoes 'text'.").contains("screenshot");
        out.reset();
        assertThat(cli("--port", String.valueOf(port), "tools", "echo")).isEqualTo(0);
        assertThat(mapper.readTree(out.toString()).path("name").asText()).isEqualTo("echo");
    }

    @Test
    void leaseFlagPutsTheTokenIntoTheArguments() throws Exception {
        int port = start();
        assertThat(cli("--port", String.valueOf(port), "--lease", "T", "do_action", "{\"action\":{\"action\":\"close\"}}")).isEqualTo(0);
        assertThat(out.toString().trim()).isEqualTo("{\"action\":{\"action\":\"close\"},\"_lease\":\"T\"}");
    }

    @Test
    void aToolErrorExitsOneOnStderr() throws Exception {
        int port = start();
        assertThat(cli("--port", String.valueOf(port), "fail")).isEqualTo(1);
        assertThat(err.toString()).contains("nope");
    }

    @Test
    void anErrorInAToolExitsOneNotThree() throws Exception {
        int port = start();
        assertThat(cli("--port", String.valueOf(port), "stale")).isEqualTo(1);
        assertThat(err.toString()).contains("NoSuchMethodError").doesNotContain("no swing-ui-agent");
    }

    @Test
    void anAgentFailureExitsOneWithTheStatusAndBody() throws Exception {
        McpDispatcher broken = new McpDispatcher(mapper, "test", Collections.<McpToolDefinition>emptyList(),
                Collections.emptyList(), Collections.emptyList()) {
            @Override public void dispatch(String json, Emitter emitter) {
                throw new StackOverflowError("deep snapshot");
            }
        };
        endpoint = new HttpMcpEndpoint(broken, mapper, "127.0.0.1", "/mcp", 0);
        endpoint.start();
        assertThat(cli("--port", String.valueOf(endpoint.port()), "session")).isEqualTo(1);
        assertThat(err.toString()).contains("HTTP 500").contains("deep snapshot").doesNotContain("no swing-ui-agent");
    }

    @Test
    void noAgentExitsThreeWithAHint() throws Exception {
        int free;
        try (ServerSocket s = new ServerSocket(0)) {
            free = s.getLocalPort();
        }
        assertThat(cli("--port", String.valueOf(free), "session")).isEqualTo(3);
        assertThat(err.toString()).contains("no swing-ui-agent on 127.0.0.1:" + free).contains("-javaagent");
    }

    @Test
    void outWritesTheImageAndPrintsItsPath() throws Exception {
        int port = start();
        Path png = dir.resolve("deeper/s.png");
        assertThat(cli("--port", String.valueOf(port), "--out", png.toString(), "screenshot")).isEqualTo(0);
        assertThat(Files.readAllBytes(png)).containsExactly(1, 2, 3);
        assertThat(out.toString()).doesNotContain("AQID").contains(png.toAbsolutePath().toString()).contains("\"width\":1");
    }

    @Test
    void anImageWithoutOutIsDescribedNotDumped() throws Exception {
        int port = start();
        assertThat(cli("--port", String.valueOf(port), "screenshot")).isEqualTo(0);
        assertThat(out.toString()).contains("[image image/png, 3 bytes; use --out FILE]").doesNotContain("AQID");
    }

    @Test
    void anUnwritableOutExitsOne() throws Exception {
        int port = start();
        Path blocker = dir.resolve("file");
        Files.write(blocker, new byte[] {0});
        assertThat(cli("--port", String.valueOf(port), "--out", blocker.resolve("s.png").toString(), "screenshot")).isEqualTo(1);
        assertThat(err.toString()).contains("--out");
    }

    @Test
    void badJsonIsUsage() throws Exception {
        int port = start();
        assertThat(cli("--port", String.valueOf(port), "echo", "{nope")).isEqualTo(2);
        assertThat(err.toString()).contains("not JSON");
    }

    @Test
    void noToolIsUsage() {
        assertThat(cli()).isEqualTo(2);
        assertThat(err.toString()).contains("usage");
    }

    interface Body { String run(JsonNode args) throws Exception; }

    private McpToolDefinition tool(String name, String description, Body body) {
        return new McpToolDefinition() {
            @Override public String name() { return name; }
            @Override public ObjectNode descriptor(ObjectMapper m) {
                ObjectNode d = m.createObjectNode();
                d.put("name", name);
                d.put("description", description);
                d.putObject("inputSchema").put("type", "object");
                return d;
            }
            @Override public String invoke(JsonNode args) throws Exception { return body.run(args); }
        };
    }
}
