package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.client.AgentClient;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.flow.Flow;
import io.github.kormcrete.swingui.flow.FlowFiles;
import io.github.kormcrete.swingui.flow.verb.ClickVerb;
import io.github.kormcrete.swingui.testing.RecordingVerbs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** run_flow through the real endpoint, lease gate and session reset. */
class RunFlowAgentTest {

    @TempDir Path evidence;
    private final RecordingVerbs verbs = new RecordingVerbs();
    private InJvmAgent agent;
    private AgentClient client;
    private String flowJson;

    @BeforeEach
    void start() {
        agent = InJvmAgent.start(verbs, null);
        client = new AgentClient("127.0.0.1", agent.port());
        Flow f = new Flow();
        f.version = FlowFiles.VERSION;
        f.name = "add-to-cart";
        f.start = FlowFiles.START_RESET;
        ClickVerb click = new ClickVerb();
        click.target = Locator.of("button", "Add to cart");
        f.steps.add(click);
        flowJson = FlowFiles.write(f);
    }

    @AfterEach
    void stop() {
        agent.stop();
    }

    private ObjectNode args() throws Exception {
        ObjectNode a = client.args();
        a.set("flow", client.json().readTree(flowJson));
        a.put("evidence", evidence.toString());
        return a;
    }

    @Test
    void runFlowIsGatedByTheLease() throws Exception {
        ObjectNode lease = client.args();
        lease.put("op", "lease").put("owner", "suite");
        String token = client.callJson("session", lease).path("token").asText();
        assertThat(token).isNotEmpty();
        assertThatThrownBy(() -> client.call("run_flow", args()))
                .isInstanceOf(DriverException.class)
                .satisfies(e -> assertThat(((DriverException) e).reason()).isEqualTo(Reason.BUSY));
        JsonNode out = client.withLease(token).callJson("run_flow", args());
        assertThat(out.path("passed").asBoolean()).isTrue();
    }

    @Test
    void itResetsTheAppFirst() throws Exception {
        assertThat(client.callJson("run_flow", args()).path("passed").asBoolean()).isTrue();
        int reset = verbs.calls.indexOf("resetPointer");
        int click = -1;
        for (int i = 0; i < verbs.calls.size(); i++) if (verbs.calls.get(i).startsWith("click")) click = i;
        assertThat(reset).isGreaterThanOrEqualTo(0);
        assertThat(click).isGreaterThan(reset);
    }
}
