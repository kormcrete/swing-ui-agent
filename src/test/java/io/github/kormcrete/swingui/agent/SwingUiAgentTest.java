package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.mcp.HttpMcpEndpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SwingUiAgentTest {

    @Test
    void badArgumentsLeaveTheAppRunning() {
        String err = captureErr(() -> assertThat(SwingUiAgent.start("plugin=/x.jar", mock(Instrumentation.class))).isNull());
        assertThat(err).contains("swing-ui-agent").contains("plugin").contains("not started");
    }

    @Test
    void busyPortLeavesTheAppRunning(@TempDir Path dir) throws Exception {
        Path plugin = pluginDir(dir);
        Instrumentation inst = mock(Instrumentation.class);
        try (ServerSocket busy = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            String err = captureErr(() -> assertThat(SwingUiAgent.start(
                    "mode=background,port=" + busy.getLocalPort() + ",plugins=" + plugin, inst)).isNull());
            assertThat(err).contains(String.valueOf(busy.getLocalPort()));
        }
        // An agent that did not start must not load its plugin into the app.
        verify(inst, never()).addTransformer(any());
    }

    @Test
    void theListeningLineIsInTheLog(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("Kormcrete Order Desk").resolve("agent.log");
        HttpMcpEndpoint endpoint = SwingUiAgent.start("mode=background,port=0,log=" + log, mock(Instrumentation.class));
        try {
            assertThat(endpoint).isNotNull();
            assertThat(new String(Files.readAllBytes(log), StandardCharsets.UTF_8))
                    .contains("swing-ui-agent listening on http://127.0.0.1:" + endpoint.port() + "/mcp");
        } finally {
            if (endpoint != null) endpoint.stop();
        }
    }

    @Test
    void aBusyPortIsInTheLog(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("agent.log");
        try (ServerSocket busy = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            captureErr(() -> assertThat(SwingUiAgent.start(
                    "mode=background,port=" + busy.getLocalPort() + ",log=" + log, mock(Instrumentation.class))).isNull());
            assertThat(new String(Files.readAllBytes(log), StandardCharsets.UTF_8))
                    .contains("swing-ui-agent: cannot listen on 127.0.0.1:" + busy.getLocalPort());
        }
    }

    @Test
    void aNonLoopbackBindLeavesTheAppRunning(@TempDir Path dir) throws Exception {
        Path plugin = pluginDir(dir);
        Instrumentation inst = mock(Instrumentation.class);
        String err = captureErr(() -> assertThat(SwingUiAgent.start(
                "mode=background,port=0,bind=0.0.0.0,plugins=" + plugin, inst)).isNull());
        assertThat(err.trim()).as("one stderr line, no stack trace").doesNotContain("\n").doesNotContain("\tat ");
        assertThat(err).contains("swing-ui-agent").contains("bind").contains("0.0.0.0").contains("not started");
        verify(inst, never()).addTransformer(any());
    }

    @Test
    void aMissingPluginIsReportedAndTheAgentStillStarts() {
        String[] err = new String[1];
        HttpMcpEndpoint[] endpoint = new HttpMcpEndpoint[1];
        err[0] = captureErr(() -> endpoint[0] = SwingUiAgent.start("mode=background,port=0,plugins=/no/such.jar",
                mock(Instrumentation.class)));
        try {
            assertThat(err[0]).contains("no plugin at");
            assertThat(endpoint[0]).isNotNull();
        } finally {
            if (endpoint[0] != null) endpoint[0].stop();
        }
    }

    @Test
    void anUnreadableDescriptorIsReportedAndTheAgentStillStarts(@TempDir Path dir) throws Exception {
        Path props = dir.resolve(PluginDescriptor.RESOURCE);
        Files.createDirectories(props.getParent());
        // A malformed unicode escape makes Properties.load throw IllegalArgumentException, not IOException.
        Files.write(props, "app-class=\\uZZZZ".getBytes(StandardCharsets.ISO_8859_1));
        HttpMcpEndpoint[] endpoint = new HttpMcpEndpoint[1];
        String err = captureErr(() -> endpoint[0] = SwingUiAgent.start("mode=background,port=0,plugins=" + dir,
                mock(Instrumentation.class)));
        try {
            assertThat(err).contains("swing-ui-agent").contains(dir.toString());
            assertThat(endpoint[0]).isNotNull();
            JsonNode info = sessionInfo(endpoint[0]);
            assertThat(info.path("plugin").path("state").asText()).isEqualTo("failed");
        } finally {
            if (endpoint[0] != null) endpoint[0].stop();
        }
    }

    @Test
    void anInstrumentationFailureNeverEscapes(@TempDir Path dir) throws Exception {
        Path plugin = pluginDir(dir);
        Instrumentation inst = mock(Instrumentation.class);
        doThrow(new IllegalStateException("boom")).when(inst).addTransformer(any());
        when(inst.getAllLoadedClasses()).thenReturn(new Class<?>[0]);
        HttpMcpEndpoint[] endpoint = new HttpMcpEndpoint[1];
        String err = captureErr(() -> endpoint[0] = SwingUiAgent.start("mode=background,port=0,plugins=" + plugin, inst));
        try {
            assertThat(err).contains("cannot watch class loading").contains("boom");
            assertThat(endpoint[0]).isNotNull();
        } finally {
            if (endpoint[0] != null) endpoint[0].stop();
        }
    }

    @Test
    void startsTheEndpointOnTheGivenPort() {
        HttpMcpEndpoint endpoint = SwingUiAgent.start("mode=background,port=0", mock(Instrumentation.class));
        try {
            assertThat(endpoint).isNotNull();
            assertThat(endpoint.port()).isPositive();
        } finally {
            if (endpoint != null) endpoint.stop();
        }
    }

    @Test
    void sessionInfoReportsTheBoundPort() throws Exception {
        HttpMcpEndpoint endpoint = SwingUiAgent.start("mode=background,port=0", mock(Instrumentation.class));
        try {
            assertThat(endpoint).isNotNull();
            JsonNode info = sessionInfo(endpoint);
            assertThat(info.path("endpoint").path("port").asInt()).isEqualTo(endpoint.port());
            assertThat(info.path("plugin").path("state").asText()).isEqualTo("none");
        } finally {
            if (endpoint != null) endpoint.stop();
        }
    }

    private static Path pluginDir(Path dir) throws Exception {
        Path props = dir.resolve(PluginDescriptor.RESOURCE);
        Files.createDirectories(props.getParent());
        Files.write(props, "app-class=com.example.orders.AppContext".getBytes(StandardCharsets.ISO_8859_1));
        return dir;
    }

    private static JsonNode sessionInfo(HttpMcpEndpoint endpoint) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + endpoint.port() + "/mcp").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = c.getOutputStream()) {
            out.write(("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"session\",\"arguments\":{\"op\":\"info\"}}}").getBytes(StandardCharsets.UTF_8));
        }
        assertThat(c.getResponseCode()).isEqualTo(200);
        JsonNode reply = mapper.readTree(read(c.getInputStream()));
        return mapper.readTree(reply.path("result").path("content").get(0).path("text").asText());
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int r;
        while ((r = in.read(chunk)) != -1) buf.write(chunk, 0, r);
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    static String captureErr(Runnable r) {
        PrintStream saved = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true));
        try {
            r.run();
        } finally {
            System.setErr(saved);
        }
        return buf.toString();
    }
}
