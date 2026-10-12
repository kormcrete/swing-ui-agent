package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.mcp.rpc.McpDispatcher;
import io.github.kormcrete.swingui.mcp.rpc.McpToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpMcpEndpointTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpMcpEndpoint endpoint;

    private static final String TOOLS_LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

    private HttpMcpEndpoint start() throws Exception {
        McpToolDefinition echo = new McpToolDefinition() {
            @Override public String name() { return "echo"; }
            @Override public ObjectNode descriptor(ObjectMapper m) {
                ObjectNode d = m.createObjectNode();
                d.put("name", "echo");
                d.put("description", "returns its 'text' argument");
                d.putObject("inputSchema").put("type", "object");
                return d;
            }
            @Override public String invoke(JsonNode args) { return args.path("text").asText(); }
        };
        return start(new McpDispatcher(mapper, "test", Collections.singletonList(echo),
                Collections.emptyList(), Collections.emptyList()));
    }

    private HttpMcpEndpoint start(McpDispatcher dispatcher) throws Exception {
        endpoint = new HttpMcpEndpoint(dispatcher, mapper, "127.0.0.1", "/mcp", 0);
        endpoint.start();
        return endpoint;
    }

    @AfterEach
    void stop() {
        if (endpoint != null) endpoint.stop();
    }

    @Test
    void aToolCallIsAnsweredInTheResponseBody() throws Exception {
        start();
        HttpURLConnection c = post("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"hi\"}}}");
        assertThat(c.getResponseCode()).isEqualTo(200);
        assertThat(c.getContentType()).startsWith("application/json");
        JsonNode reply = mapper.readTree(read(c.getInputStream()));
        assertThat(reply.path("id").asInt()).isEqualTo(7);
        assertThat(reply.path("result").path("content").get(0).path("text").asText()).isEqualTo("hi");
    }

    @Test
    void aNotificationGets202AndNoBody() throws Exception {
        start();
        HttpURLConnection c = post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertThat(c.getResponseCode()).isEqualTo(202);
    }

    @Test
    void getIsRefused() throws Exception {
        start();
        HttpURLConnection c = (HttpURLConnection) url().openConnection();
        assertThat(c.getResponseCode()).isEqualTo(405);
    }

    @Test
    void initializeWorksWithoutASession() throws Exception {
        start();
        HttpURLConnection c = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        assertThat(c.getResponseCode()).isEqualTo(200);
        assertThat(mapper.readTree(read(c.getInputStream())).path("result").has("protocolVersion")).isTrue();
    }

    @Test
    void aMalformedBodyIsAJsonRpcParseError() throws Exception {
        start();
        HttpURLConnection c = post("{nope");
        assertThat(c.getResponseCode()).isEqualTo(200);
        assertThat(read(c.getInputStream())).contains("\"code\":-32700").contains("\"id\":null");
    }

    @Test
    void initializeAdvertisesTheStreamableHttpProtocol() throws Exception {
        start(new UiMcpDispatcher(mapper, Collections.<McpToolDefinition>emptyList()));
        assertThat(read(post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}").getInputStream()))
                .contains("\"protocolVersion\":\"2025-03-26\"").contains("\"name\":\"swing-ui-agent\"");
    }

    @Test
    void aNonLoopbackBindIsRefused() {
        assertThatThrownBy(() -> new HttpMcpEndpoint(null, mapper, "0.0.0.0", "/mcp", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void serverThreadsAreDaemons() throws Exception {
        java.util.Set<Thread> before = Thread.getAllStackTraces().keySet();
        start();
        post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").getResponseCode();
        java.util.Set<Thread> added = new java.util.HashSet<Thread>(Thread.getAllStackTraces().keySet());
        added.removeAll(before);
        assertThat(added).isNotEmpty();   // the JDK's dispatcher thread and at least one handler thread
        assertThat(added).allSatisfy(t -> assertThat(t.isDaemon()).as(t.getName()).isTrue());
    }

    @Test
    void aThrowableFromTheDispatcherIsA500WithAJsonRpcError() throws Exception {
        start(new McpDispatcher(mapper, "test", Collections.<McpToolDefinition>emptyList(),
                Collections.emptyList(), Collections.emptyList()) {
            @Override public void dispatch(String json, Emitter emitter) {
                throw new StackOverflowError("deep snapshot");
            }
        });
        HttpURLConnection c = post(TOOLS_LIST);
        assertThat(c.getResponseCode()).isEqualTo(500);
        assertThat(c.getContentType()).startsWith("application/json");
        JsonNode reply = mapper.readTree(read(c.getErrorStream()));
        assertThat(reply.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(reply.path("error").path("code").asInt()).isEqualTo(-32603);
        assertThat(reply.path("error").path("message").asText()).contains("StackOverflowError").contains("deep snapshot");
        // the endpoint lives on
        assertThat(post(TOOLS_LIST).getResponseCode()).isEqualTo(500);
    }

    @Test
    void aRequestWithoutOriginIsServed() throws Exception {
        start();
        assertThat(rawPost(null, "application/json", TOOLS_LIST)).isEqualTo(200);
    }

    @Test
    void aLoopbackOriginIsServed() throws Exception {
        start();
        assertThat(rawPost("http://localhost:5173", "application/json", TOOLS_LIST)).isEqualTo(200);
        assertThat(rawPost("https://127.0.0.1", "application/json", TOOLS_LIST)).isEqualTo(200);
        assertThat(rawPost("http://[::1]:8080", "application/json", TOOLS_LIST)).isEqualTo(200);
        assertThat(rawPost("HTTP://LocalHost", "application/json", TOOLS_LIST)).isEqualTo(200);
    }

    @Test
    void aForeignOriginIsForbidden() throws Exception {
        start();
        assertThat(rawPost("https://evil.example", "application/json", TOOLS_LIST)).isEqualTo(403);
        assertThat(rawPost("http://localhost.evil.example", "application/json", TOOLS_LIST)).isEqualTo(403);
        assertThat(rawPost("null", "application/json", TOOLS_LIST)).isEqualTo(403);
    }

    @Test
    void aBodyThatIsNotJsonIsRefused() throws Exception {
        start();
        // text/plain is what a cross-origin "simple" fetch may send without a preflight
        assertThat(rawPost(null, "text/plain", TOOLS_LIST)).isEqualTo(415);
        assertThat(rawPost(null, "application/x-www-form-urlencoded", TOOLS_LIST)).isEqualTo(415);
        assertThat(rawPost(null, null, TOOLS_LIST)).isEqualTo(415);
        assertThat(rawPost(null, "application/jsonp", TOOLS_LIST)).isEqualTo(415);
    }

    @Test
    void jsonWithParametersIsServed() throws Exception {
        start();
        assertThat(rawPost(null, "application/json; charset=utf-8", TOOLS_LIST)).isEqualTo(200);
        assertThat(rawPost(null, "Application/JSON", TOOLS_LIST)).isEqualTo(200);
    }

    /** Raw HTTP: HttpURLConnection will not set Origin, and always sends a Content-Type with a body. Returns the status. */
    private int rawPost(String origin, String contentType, String json) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        try (Socket s = new Socket("127.0.0.1", endpoint.port())) {
            s.setSoTimeout(10_000);
            StringBuilder req = new StringBuilder("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n");
            if (origin != null) req.append("Origin: ").append(origin).append("\r\n");
            if (contentType != null) req.append("Content-Type: ").append(contentType).append("\r\n");
            req.append("Content-Length: ").append(body.length).append("\r\n\r\n");
            OutputStream out = s.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            String status = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1)).readLine();
            return Integer.parseInt(status.split(" ")[1]);
        }
    }

    private URL url() throws Exception {
        return new URL("http://127.0.0.1:" + endpoint.port() + "/mcp");
    }

    private HttpURLConnection post(String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) url().openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setReadTimeout(10_000);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = c.getOutputStream()) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return c;
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int r;
        while ((r = in.read(chunk)) != -1) buf.write(chunk, 0, r);
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }
}
