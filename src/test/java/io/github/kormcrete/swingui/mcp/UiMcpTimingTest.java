package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.EdtClock;
import io.github.kormcrete.swingui.driver.Reason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.kormcrete.swingui.mcp.rpc.McpToolDefinition;

import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The per-request timing line: one parseable line, target last, and what feeds its numbers. */
class UiMcpTimingTest {

    /** How a line parses: key=value pairs, then target= to the end of the line. */
    private static final Pattern LINE = Pattern.compile("UIMCP-TIMING ((?:\\w+=\\S+ )+)target=(.*)");

    @TempDir
    Path dir;

    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void clear() {
        System.clearProperty(UiMcpTiming.FILE_PROP);
    }

    private static Map<String, String> parse(String line) {
        Matcher m = LINE.matcher(line);
        assertThat(m.matches()).as(line).isTrue();
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String kv : m.group(1).trim().split(" ")) out.put(kv.substring(0, kv.indexOf('=')), kv.substring(kv.indexOf('=') + 1));
        out.put("target", m.group(2));
        return out;
    }

    @Test
    void theTimingLineIsFine() {
        java.util.logging.Logger log = java.util.logging.Logger.getLogger("UIMCP");
        final java.util.List<java.util.logging.Level> levels = new ArrayList<java.util.logging.Level>();
        java.util.logging.Handler h = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord r) { levels.add(r.getLevel()); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        java.util.logging.Level old = log.getLevel();
        log.setLevel(java.util.logging.Level.ALL);
        h.setLevel(java.util.logging.Level.ALL);
        log.addHandler(h);
        try {
            UiMcpTiming.log("UIMCP-TIMING tool=click status=ok");
        } finally {
            log.removeHandler(h);
            log.setLevel(old);
        }
        assertThat(levels).containsExactly(java.util.logging.Level.FINE);
    }

    @Test
    void theLineHasAFixedPrefixNumbersAndTheTargetLast() {
        EdtClock.Reading edt = new EdtClock.Reading(40_000_000L, 2_000_000L, 7);
        String line = UiMcpTiming.line("click", "ok", 153, 150, edt, 1, 842, "button:Save in header");
        assertThat(line).isEqualTo("UIMCP-TIMING tool=click status=ok ms=153 invokeMs=150 edtMs=40 edtWaitMs=2 "
                + "edtCalls=7 offEdtMs=108 emitMs=1 bytes=842 target=button:Save in header");
        Map<String, String> f = parse(line);
        assertThat(f.get("target")).isEqualTo("button:Save in header");
        assertThat(f.get("bytes")).isEqualTo("842");
    }

    @Test
    void aStepLineNamesItsPlaceInTheBatch() {
        String line = UiMcpTiming.stepLine("batch.click", "2/5", "failed", 30, new EdtClock.Reading(0, 0, 0), "e3");
        assertThat(line).isEqualTo("UIMCP-STEP tool=batch.click step=2/5 status=failed ms=30 invokeMs=30 edtMs=0 "
                + "edtWaitMs=0 edtCalls=0 offEdtMs=30 target=e3");
    }

    @Test
    void aTargetNeverBreaksTheLineOrRunsOn() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 50; i++) longName.append("abcd");
        String line = UiMcpTiming.line("type", "ok", 1, 1, new EdtClock.Reading(0, 0, 0), 0, 2, "a\nb\t" + longName);
        assertThat(line).doesNotContain("\n").doesNotContain("\t");
        assertThat(parse(line).get("target")).hasSize(120).endsWith("...");
    }

    @Test
    void statusSeparatesAFailedStepFromAHarnessError() {
        assertThat(UiMcpTiming.status("{\"passed\":true}", false, null)).isEqualTo("ok");
        assertThat(UiMcpTiming.status("{\"passed\":false,\"action\":\"click\"}", false, null)).isEqualTo("failed");
        assertThat(UiMcpTiming.status("{\"value\":\"\\\"passed\\\":false\"}", false, null)).isEqualTo("ok");
        assertThat(UiMcpTiming.status("{}", true, "CAPABILITY_GAP")).isEqualTo("error:CAPABILITY_GAP");
    }

    @Test
    void theTargetSummaryIsShort() throws Exception {
        assertThat(UiMcpTiming.target("click", mapper.readTree(
                "{\"target\":{\"window\":\"dialog\",\"scope\":[\"tab:Customer\"],\"role\":\"button\",\"name\":\"OK\"}}")))
                .isEqualTo("dialog/tab:Customer/button:OK");
        assertThat(UiMcpTiming.target("click", mapper.readTree("{\"target\":{\"ref\":\"e12\"}}"))).isEqualTo("e12");
        assertThat(UiMcpTiming.target("menu", mapper.readTree("{\"path\":[\"File\",\"Exit\"]}"))).isEqualTo("path=File>Exit");
        assertThat(UiMcpTiming.target("do_action", mapper.readTree("{\"action\":{\"action\":\"navigate\"}}"))).isEqualTo("action=navigate");
        assertThat(UiMcpTiming.target("batch", mapper.readTree("{\"steps\":[{},{}]}"))).isEqualTo("steps=2");
        assertThat(UiMcpTiming.target("describe_ui", mapper.readTree("{}"))).isEqualTo("-");
        assertThat(UiMcpTiming.target("expect", mapper.readTree(
                "{\"target\":{\"role\":\"table\",\"row\":{\"index\":2},\"column\":\"Status\"}}")))
                .isEqualTo("table row={index:2} col=Status");
    }

    @Test
    void theTimingFileIsOptional() {
        System.setProperty(UiMcpTiming.FILE_PROP, "off");
        assertThat(UiMcpTiming.file()).isNull();
        System.setProperty(UiMcpTiming.FILE_PROP, dir.resolve("t.log").toString());
        assertThat(UiMcpTiming.file()).isEqualTo(dir.resolve("t.log"));
    }

    @Test
    void theEdtClockCountsRunAndWaitOnTheCallingThreadOnly() throws Exception {
        EdtClock.reset();
        io.github.kormcrete.swingui.driver.Edt.run(() -> sleep(30));
        EdtClock.Reading r = EdtClock.read();
        assertThat(r.calls).isEqualTo(1);
        assertThat(r.runMs()).isGreaterThanOrEqualTo(25);
        SwingUtilities.invokeAndWait(() -> {
            // on the EDT itself a task runs inline, uncounted on the request thread's clock
            io.github.kormcrete.swingui.driver.Edt.run(() -> { });
        });
        assertThat(EdtClock.read().calls).isEqualTo(1);
        EdtClock.reset();
        assertThat(EdtClock.read().calls).isZero();
    }

    private static McpToolDefinition tool(final String name, final Callable<String> result) {
        return new McpToolDefinition() {
            @Override public String name() { return name; }
            @Override public ObjectNode descriptor(ObjectMapper m) { return m.createObjectNode().put("name", name); }
            @Override public String invoke(JsonNode args) throws Exception { return result.call(); }
        };
    }

    @Test
    void everyRequestLogsOneLineWithItsSizeAndOutcome() throws Exception {
        Path file = dir.resolve("timing.log");
        System.setProperty(UiMcpTiming.FILE_PROP, file.toString());
        List<McpToolDefinition> tools = new ArrayList<McpToolDefinition>();
        tools.add(tool("click", () -> {
            io.github.kormcrete.swingui.driver.Edt.run(() -> sleep(20));
            return "{\"passed\":false,\"message\":\"covered\"}";
        }));
        tools.add(tool("select", () -> { throw new DriverException(Reason.CAPABILITY_GAP, "no popup"); }));
        UiMcpDispatcher d = new UiMcpDispatcher(mapper, tools);
        List<JsonNode> emitted = new ArrayList<JsonNode>();
        d.dispatch("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"click\","
                + "\"arguments\":{\"target\":{\"role\":\"button\",\"name\":\"OK\"}}}}", emitted::add);
        d.dispatch("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"select\","
                + "\"arguments\":{}}}", emitted::add);
        d.dispatch("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}", emitted::add);

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(2);
        Map<String, String> click = parse(lines.get(0));
        assertThat(click.get("tool")).isEqualTo("click");
        assertThat(click.get("status")).isEqualTo("failed");
        assertThat(Long.parseLong(click.get("edtMs"))).isGreaterThanOrEqualTo(15);
        assertThat(Long.parseLong(click.get("ms"))).isGreaterThanOrEqualTo(Long.parseLong(click.get("invokeMs")));
        assertThat(click.get("edtCalls")).isEqualTo("1");
        assertThat(click.get("bytes")).isEqualTo(String.valueOf("{\"passed\":false,\"message\":\"covered\"}".length()));
        assertThat(click.get("target")).isEqualTo("button:OK");
        Map<String, String> select = parse(lines.get(1));
        assertThat(select.get("status")).isEqualTo("error:CAPABILITY_GAP");
        assertThat(select.get("edtCalls")).isEqualTo("0");
        assertThat(emitted).hasSize(3);
    }

    @Test
    void aBatchLogsOneStepLinePerStep() throws Exception {
        Path file = dir.resolve("steps.log");
        System.setProperty(UiMcpTiming.FILE_PROP, file.toString());
        UiMcpSession session = new UiMcpSession(new UiMcpReadToolsTest.DriverHost(), new UiMcpToolsTest.FakeInventory(), mapper);
        session.batch(mapper.readTree("{\"steps\":[{\"verb\":\"press\",\"keys\":\"Escape\"},"
                + "{\"verb\":\"click\",\"target\":{\"ref\":\"e3\"}}]}"));
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).startsWith("UIMCP-STEP tool=batch.press step=1/2 status=ok ").endsWith("target=keys=Escape");
        assertThat(lines.get(1)).startsWith("UIMCP-STEP tool=batch.click step=2/2 status=ok ").endsWith("target=e3");
        assertThat(Collections.frequency(lines, null)).isZero();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
