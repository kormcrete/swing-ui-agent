package io.github.kormcrete.swingui.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/** One JSON-RPC POST per call to a swing-ui-agent endpoint. Stateless; no MCP session to open first. */
public final class AgentConnection {

    /** A JSON-RPC error reply (not a tool error, which is a normal result with isError). */
    public static final class RpcError extends IOException {
        public RpcError(String message) { super(message); }
    }

    /** The agent answered, but not with 200: it is running and failed (or refused the request). */
    public static final class HttpError extends IOException {
        public final int status;
        public final String body;

        public HttpError(int status, String body, URL url) {
            super("HTTP " + status + " from " + url + (body.isEmpty() ? "" : ": " + body));
            this.status = status;
            this.body = body;
        }
    }

    private static final int CONNECT_MS = 2_000;
    /** Steps wait for the UI themselves; this only stops a hung agent from hanging the caller forever. */
    private static final int READ_MS = 600_000;

    private final URL url;
    private final ObjectMapper mapper;
    private final AtomicLong ids = new AtomicLong();

    public AgentConnection(String host, int port, ObjectMapper mapper) throws IOException {
        this.url = new URL("http", host, port, "/mcp");
        this.mapper = mapper;
    }

    public URL url() { return url; }

    public JsonNode request(String method, JsonNode params) throws IOException {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", ids.incrementAndGet());
        req.put("method", method);
        req.set("params", params);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(CONNECT_MS);
        c.setReadTimeout(READ_MS);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json, text/event-stream");
        try {
            c.connect();
        } catch (SocketTimeoutException e) {
            // Windows does not refuse a closed local port at once: it retries for about 2 s, so a missing agent shows
            // as a connect timeout there. A listening agent is accepted by the OS at once, however busy the app is.
            ConnectException refused = new ConnectException("no answer to connect within " + CONNECT_MS + " ms");
            refused.initCause(e);
            throw refused;
        }
        try (OutputStream out = c.getOutputStream()) {
            mapper.writeValue(out, req);
        }
        int code = c.getResponseCode();
        if (code != 200) throw new HttpError(code, errorBody(c), url);
        JsonNode reply;
        try (InputStream in = c.getInputStream()) {
            reply = mapper.readTree(in);
        }
        if (reply.has("error")) throw new RpcError(reply.path("error").path("message").asText("JSON-RPC error"));
        return reply.path("result");
    }

    private static String errorBody(HttpURLConnection c) {
        try (InputStream in = c.getErrorStream()) {
            if (in == null) return "";
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    public JsonNode callTool(String name, JsonNode arguments) throws IOException {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments);
        return request("tools/call", params);
    }
}
