package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.client.AgentClient;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.inspect.PaletteDriving;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.flow.Flow;
import io.github.kormcrete.swingui.flow.RecordedTestFiles;
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

/** run_recorded_test through the real endpoint, lease gate and session reset; the palette's runs keep other calls out. */
class RunRecordedTestAgentTest {

    @TempDir Path evidence;
    private final RecordingVerbs verbs = new RecordingVerbs();
    private InJvmAgent agent;
    private AgentClient client;
    private String testJson;

    @BeforeEach
    void start() {
        agent = InJvmAgent.start(verbs, null);
        client = new AgentClient("127.0.0.1", agent.port());
        Flow f = new Flow();
        f.version = RecordedTestFiles.VERSION;
        f.name = "add-to-cart";
        f.start = RecordedTestFiles.START_RESET;
        ClickVerb click = new ClickVerb();
        click.target = Locator.of("button", "Add to cart");
        f.steps.add(click);
        testJson = RecordedTestFiles.write(f);
    }

    @AfterEach
    void stop() {
        agent.stop();
    }

    private ObjectNode args() throws Exception {
        ObjectNode a = client.args();
        a.set("test", client.json().readTree(testJson));
        a.put("evidence", evidence.toString());
        return a;
    }

    @Test
    void runRecordedTestIsGatedByTheLease() throws Exception {
        ObjectNode lease = client.args();
        lease.put("op", "lease").put("owner", "suite");
        String token = client.callJson("session", lease).path("token").asText();
        assertThat(token).isNotEmpty();
        assertThatThrownBy(() -> client.call("run_recorded_test", args()))
                .isInstanceOf(DriverException.class)
                .satisfies(e -> assertThat(((DriverException) e).reason()).isEqualTo(Reason.BUSY));
        JsonNode out = client.withLease(token).callJson("run_recorded_test", args());
        assertThat(out.path("passed").asBoolean()).isTrue();
    }

    @Test
    void whileAPersonRunsARecordedTestInThePaletteOtherCallsAreBusy() throws Exception {
        ObjectNode click = client.args();
        click.set("target", client.json().readTree("{\"role\":\"button\",\"name\":\"Add to cart\"}"));
        try (PaletteDriving palette = new PaletteDriving("a person is running add-to-cart in the app")) {
            assertThatThrownBy(() -> client.call("click", click))
                    .isInstanceOf(DriverException.class)
                    .hasMessageContaining("BUSY: a person is running add-to-cart in the app")
                    .satisfies(e -> assertThat(((DriverException) e).reason()).isEqualTo(Reason.BUSY));
            assertThat(verbs.calls).noneMatch(c -> c.startsWith("click"));
            // the run ended: the app is everyone's again
            palette.reason(null);
            client.call("click", click);
            assertThat(verbs.calls).anyMatch(c -> c.startsWith("click"));
        }
    }

    @Test
    void itResetsTheAppFirst() throws Exception {
        assertThat(client.callJson("run_recorded_test", args()).path("passed").asBoolean()).isTrue();
        int reset = verbs.calls.indexOf("resetPointer");
        int click = -1;
        for (int i = 0; i < verbs.calls.size(); i++) if (verbs.calls.get(i).startsWith("click")) click = i;
        assertThat(reset).isGreaterThanOrEqualTo(0);
        assertThat(click).isGreaterThan(reset);
    }
}
