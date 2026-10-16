package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.mcp.rpc.McpToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeaseGateTest {

    private final ObjectMapper m = new ObjectMapper();
    private final Lease lease = new Lease(System::currentTimeMillis);
    private final List<String> seen = new ArrayList<String>();

    private McpToolDefinition tool(final String name, final boolean readOnly) {
        return tool(name, readOnly, java.util.Collections.<String>emptySet());
    }

    private McpToolDefinition tool(final String name, final boolean readOnly, final java.util.Set<String> readOps) {
        return new McpToolDefinition() {
            @Override public java.util.Set<String> readOnlyOps() { return readOps; }
            @Override public String name() { return name; }
            @Override public ObjectNode descriptor(ObjectMapper mapper) {
                ObjectNode d = mapper.createObjectNode();
                d.put("name", name);
                d.putObject("inputSchema").put("type", "object").putObject("properties");
                if (readOnly) d.putObject("annotations").put("readOnlyHint", true);
                return d;
            }
            @Override public String invoke(JsonNode args) {
                seen.add(name + " " + args);
                return "{}";
            }
        };
    }

    @Test
    void readsPassWhileLeasedAndMutatingCallsNeedTheToken() throws Exception {
        List<McpToolDefinition> tools = LeaseGate.gate(Arrays.asList(tool("read", true), tool("do_action", false)), lease, m);
        Lease.Grant g = lease.acquire("suite", null);
        tools.get(0).invoke(m.readTree("{\"target\":{}}"));
        assertThatThrownBy(() -> tools.get(1).invoke(m.readTree("{\"action\":{}}")))
                .hasMessageContaining("BUSY: leased by suite");
        tools.get(1).invoke(m.readTree("{\"action\":{},\"_lease\":\"" + g.token + "\"}"));
        assertThat(seen).containsExactly("read {\"target\":{}}", "do_action {\"action\":{}}");
    }

    @Test
    void aGatedToolAdvertisesTheLeaseArgument() {
        McpToolDefinition gated = LeaseGate.gate(Arrays.asList(tool("do_action", false)), lease, m).get(0);
        assertThat(gated.descriptor(m).path("inputSchema").path("properties").has("_lease")).isTrue();
    }

    @Test
    void describeUiIsReadOnlySoItPassesWhileAnotherOwnerHoldsTheLease() {
        for (McpToolDefinition t : io.github.kormcrete.swingui.mcp.UiMcpTools.build(null, m)) {
            if ("describe_ui".equals(t.name())) {
                assertThat(t.descriptor(m).path("annotations").path("readOnlyHint").asBoolean()).isTrue();
                // a read-only tool keeps its descriptor: it needs no lease and does not advertise one
                assertThat(LeaseGate.gate(Arrays.asList(t), lease, m).get(0).descriptor(m)).isEqualTo(t.descriptor(m));
                return;
            }
        }
        throw new AssertionError("no describe_ui tool");
    }

    @Test
    void vocabularyIsReadOnly() {
        for (McpToolDefinition t : io.github.kormcrete.swingui.mcp.UiMcpTools.build(null, m)) {
            if ("vocabulary".equals(t.name())) {
                assertThat(t.descriptor(m).path("annotations").path("readOnlyHint").asBoolean()).isTrue();
                assertThat(LeaseGate.gate(Arrays.asList(t), lease, m).get(0).descriptor(m)).isEqualTo(t.descriptor(m));
                return;
            }
        }
        throw new AssertionError("no vocabulary tool");
    }

    /** A test that only reads (or polls the DB between reads) is still running: its reads renew the lease. */
    @Test
    void aHolderThatOnlyReadsIsNotTakenOver() throws Exception {
        long[] now = {0L};
        Lease clocked = new Lease(() -> now[0]);
        McpToolDefinition read = LeaseGate.gate(Arrays.asList(tool("read", true)), clocked, m).get(0);
        Lease.Grant g = clocked.acquire("polling test", null);
        now[0] = 10_000L;
        read.invoke(m.readTree("{\"target\":{},\"_lease\":\"" + g.token + "\"}"));
        now[0] = 20_000L;
        assertThatThrownBy(() -> clocked.acquire("rerun", null)).hasMessageContaining("BUSY: leased by polling test");
        // the token is stripped before the tool sees its arguments, as for a mutating tool
        assertThat(seen).containsExactly("read {\"target\":{}}");
    }

    @Test
    void aReadWithoutTheTokenOrWithAForeignOneDoesNotRenew() throws Exception {
        long[] now = {0L};
        Lease clocked = new Lease(() -> now[0]);
        McpToolDefinition read = LeaseGate.gate(Arrays.asList(tool("read", true)), clocked, m).get(0);
        clocked.acquire("stopped test", null);
        now[0] = 10_000L;
        read.invoke(m.readTree("{\"target\":{}}"));
        read.invoke(m.readTree("{\"target\":{},\"_lease\":\"not-the-token\"}"));
        now[0] = 20_000L;
        assertThat(clocked.acquire("rerun", null).owner).isEqualTo("rerun");
        assertThat(seen).containsExactly("read {\"target\":{}}", "read {\"target\":{}}");
    }

    @Test
    void aThrowingToolStillReleasesTheTokenlessCount() {
        McpToolDefinition boom = new McpToolDefinition() {
            @Override public String name() { return "boom"; }
            @Override public ObjectNode descriptor(ObjectMapper mapper) {
                ObjectNode d = mapper.createObjectNode();
                d.put("name", "boom");
                d.putObject("inputSchema").put("type", "object");
                return d;
            }
            @Override public String invoke(JsonNode args) { throw new IllegalStateException("x"); }
        };
        McpToolDefinition gated = LeaseGate.gate(Arrays.asList(boom), lease, m).get(0);
        assertThatThrownBy(() -> gated.invoke(m.createObjectNode())).isInstanceOf(IllegalStateException.class);
        assertThat(lease.acquire("next", null)).isNotNull();
    }

    private McpToolDefinition mixed() {
        return LeaseGate.gate(Arrays.asList(tool("recording", false, java.util.Collections.singleton("list"))), lease, m).get(0);
    }

    @Test
    void aToolsReadOpsRunForAnyoneAndItsOtherOpsNeedTheLease() throws Exception {
        McpToolDefinition recording = mixed();
        Lease.Grant g = lease.acquire("suite", null);
        recording.invoke(m.readTree("{\"op\":\"list\"}"));
        assertThatThrownBy(() -> recording.invoke(m.readTree("{\"op\":\"start\"}")))
                .hasMessageContaining("BUSY: leased by suite");
        recording.invoke(m.readTree("{\"op\":\"start\",\"_lease\":\"" + g.token + "\"}"));
        assertThat(seen).containsExactly("recording {\"op\":\"list\"}", "recording {\"op\":\"start\"}");
    }

    @Test
    void whileAPersonReplaysListPassesAndEditsAreBusy() throws Exception {
        McpToolDefinition recording = mixed();
        lease.drivenBy(() -> "a person is replaying their recording in the app");
        recording.invoke(m.readTree("{\"op\":\"list\"}"));
        assertThatThrownBy(() -> recording.invoke(m.readTree("{\"op\":\"stop\"}")))
                .hasMessageContaining("BUSY: a person is replaying");
        assertThat(seen).containsExactly("recording {\"op\":\"list\"}");
    }

    @Test
    void aMixedToolAdvertisesTheLeaseArgument() {
        assertThat(mixed().descriptor(m).path("inputSchema").path("properties").has("_lease")).isTrue();
        assertThat(mixed().readOnlyOps()).containsExactly("list");
    }

    @Test
    void aReadOpWithTheHoldersTokenRenewsTheLeaseAndIsStripped() throws Exception {
        long[] now = {0L};
        Lease clocked = new Lease(() -> now[0]);
        McpToolDefinition recording = LeaseGate.gate(
                Arrays.asList(tool("recording", false, java.util.Collections.singleton("list"))), clocked, m).get(0);
        Lease.Grant g = clocked.acquire("polling test", null);
        now[0] = 10_000L;
        recording.invoke(m.readTree("{\"op\":\"list\",\"_lease\":\"" + g.token + "\"}"));
        now[0] = 20_000L;
        assertThatThrownBy(() -> clocked.acquire("rerun", null)).hasMessageContaining("BUSY: leased by polling test");
        assertThat(seen).containsExactly("recording {\"op\":\"list\"}");
    }
}
