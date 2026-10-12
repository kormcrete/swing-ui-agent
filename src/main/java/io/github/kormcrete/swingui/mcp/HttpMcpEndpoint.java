package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.mcp.rpc.McpDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP over streamable HTTP on the JDK HttpServer: a POST carries one JSON-RPC message and gets its reply in the
 * response body; a notification gets 202; GET is 405 (no server-initiated stream). Stateless, so a one-shot client
 * (the CLI, a JUnit test) needs no session. Binds loopback only, and refuses a foreign {@code Origin} (403) and a
 * body that is not {@code application/json} (415), so a web page in a browser cannot drive the app. Every thread it
 * owns is a daemon, so it never keeps the app's JVM alive.
 */
public final class HttpMcpEndpoint {

    private final McpDispatcher dispatcher;
    private final ObjectMapper mapper;
    private final String bindHost;
    private final String path;
    private final int requestedPort;
    private HttpServer http;

    public HttpMcpEndpoint(McpDispatcher dispatcher, ObjectMapper mapper, String bindHost, String path, int port) {
        if (!isLoopback(bindHost)) {
            throw new IllegalArgumentException("swing-ui-agent binds a loopback address only, not '" + bindHost + "'");
        }
        this.dispatcher = dispatcher;
        this.mapper = mapper;
        this.bindHost = bindHost;
        this.path = path;
        this.requestedPort = port;
    }

    static boolean isLoopback(String host) {
        try {
            return host != null && InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * Starts on a daemon thread: the JDK's dispatcher thread inherits its creator's daemon flag, and a non-daemon
     * dispatcher would keep an app alive that closed its last window without System.exit.
     */
    public void start() throws IOException {
        final IOException[] failure = new IOException[1];
        Thread starter = new Thread(() -> {
            try {
                HttpServer s = HttpServer.create(new InetSocketAddress(bindHost, requestedPort), 0);
                s.createContext(path, this::handle);
                s.setExecutor(Executors.newCachedThreadPool(daemons()));
                s.start();
                http = s;
            } catch (IOException e) {
                failure[0] = e;
            }
        }, "swing-ui-agent-http-start");
        starter.setDaemon(true);
        starter.start();
        try {
            starter.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while starting the endpoint", e);
        }
        if (failure[0] != null) throw failure[0];
    }

    public void stop() {
        if (http != null) http.stop(0);
    }

    /** The bound port: the requested one, or the one picked for port 0. */
    public int port() {
        return http.getAddress().getPort();
    }

    /**
     * Never lets anything escape: a Throwable from the dispatcher would reach the app's uncaught-exception handler,
     * and the caller would see a dropped connection and take the agent for dead. It answers 500 with a JSON-RPC error.
     */
    private void handle(HttpExchange ex) {
        try {
            if (!allowedOrigin(ex.getRequestHeaders().getFirst("Origin"))) {
                // a web page in the developer's browser must not drive the app (MCP streamable HTTP: validate Origin)
                ex.sendResponseHeaders(403, -1);
                return;
            }
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.getResponseHeaders().add("Allow", "POST");
                ex.sendResponseHeaders(405, -1);
                return;
            }
            if (!isJson(ex.getRequestHeaders().getFirst("Content-Type"))) {
                // application/json is no CORS "simple" type: a browser must preflight it, and this server approves none
                ex.sendResponseHeaders(415, -1);
                return;
            }
            String body = readBody(ex);
            final List<JsonNode> replies = new ArrayList<JsonNode>();
            dispatcher.dispatch(body, replies::add);
            if (replies.isEmpty()) {
                ex.sendResponseHeaders(202, -1);
                return;
            }
            JsonNode out;
            if (replies.size() == 1) {
                out = replies.get(0);
            } else {
                ArrayNode batch = mapper.createArrayNode();
                batch.addAll(replies);
                out = batch;
            }
            send(ex, 200, mapper.writeValueAsBytes(out));
        } catch (Throwable t) {
            answerInternalError(ex, t);
        } finally {
            try {
                ex.close();
            } catch (Throwable ignored) {
                // the caller hung up
            }
        }
    }

    /** No Origin (the CLI, an MCP client), or one whose host is localhost, 127.0.0.1 or [::1], any scheme and port. */
    static boolean allowedOrigin(String origin) {
        if (origin == null) return true;
        try {
            String host = new URI(origin.trim()).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** {@code application/json}, parameters such as {@code ; charset=utf-8} allowed. */
    static boolean isJson(String contentType) {
        if (contentType == null) return false;
        int semicolon = contentType.indexOf(';');
        String type = semicolon < 0 ? contentType : contentType.substring(0, semicolon);
        return "application/json".equalsIgnoreCase(type.trim());
    }

    private void answerInternalError(HttpExchange ex, Throwable t) {
        if (ex.getResponseCode() != -1) return;   // the answer has started; the close below ends it
        try {
            ObjectNode reply = mapper.createObjectNode();
            reply.put("jsonrpc", "2.0");
            reply.putNull("id");
            ObjectNode error = reply.putObject("error");
            error.put("code", -32603);
            error.put("message", "swing-ui-agent internal error: " + t);
            send(ex, 500, mapper.writeValueAsBytes(reply));
        } catch (Throwable ignored) {
            // nothing more to tell the caller; the exchange is closed either way
        }
    }

    private static void send(HttpExchange ex, int status, byte[] bytes) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = ex.getRequestBody().read(chunk)) != -1) buf.write(chunk, 0, n);
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static ThreadFactory daemons() {
        final AtomicInteger count = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "swing-ui-agent-http-" + count.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
