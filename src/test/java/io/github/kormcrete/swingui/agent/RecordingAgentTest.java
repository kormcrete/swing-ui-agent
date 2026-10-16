package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.client.AgentClient;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.testing.RecordingVerbs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The recording tool through the real endpoint, lease gate and session tool. */
class RecordingAgentTest {

    private final RecordingVerbs verbs = new RecordingVerbs();
    private InJvmAgent agent;
    private AgentClient client;

    @BeforeEach
    void start() {
        agent = InJvmAgent.start(verbs, null);
        client = new AgentClient("127.0.0.1", agent.port());
    }

    @AfterEach
    void stop() {
        agent.stop();
    }

    private JsonNode recording(String op) throws Exception {
        ObjectNode a = client.args();
        a.put("op", op);
        return client.callJson("recording", a);
    }

    private ObjectNode lease() {
        ObjectNode a = client.args();
        a.put("op", "lease").put("owner", "suite");
        return a;
    }

    @Test
    void noTestRunLeasesTheAgentWhileARecordingRuns() throws Exception {
        assertThat(recording("start").path("state").asText()).isEqualTo("recording");
        assertThatThrownBy(() -> client.callJson("session", lease()))
                .isInstanceOf(DriverException.class)
                .hasMessageContaining("recording {\"op\":\"stop\"}")
                .satisfies(e -> assertThat(((DriverException) e).reason()).isEqualTo(Reason.BUSY));
        assertThat(recording("stop").path("state").asText()).isEqualTo("stopped");
        assertThat(client.callJson("session", lease()).path("token").asText()).isNotEmpty();
    }

    @Test
    void listPassesWhileATestHoldsTheLease() throws Exception {
        String token = client.callJson("session", lease()).path("token").asText();
        // another caller, no token: a read op still answers; start is the lease holder's
        assertThat(recording("list").path("state").asText()).isEqualTo("idle");
        assertThatThrownBy(() -> recording("start")).hasMessageContaining("BUSY: leased by suite");
        ObjectNode start = client.args();
        start.put("op", "start");
        assertThat(client.withLease(token).callJson("recording", start).path("state").asText()).isEqualTo("recording");
    }
}
