package io.github.kormcrete.swingui.client;

import io.github.kormcrete.swingui.agent.InJvmAgent;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.testing.RecordingVerbs;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentClientTest {

    @Test
    void nothingListeningIsAgentUnreachableWithAHint() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        AgentClient c = new AgentClient("127.0.0.1", port);
        assertThatThrownBy(() -> c.callJson("session", c.args()))
                .isInstanceOf(AgentUnreachable.class)
                .hasMessageStartingWith("no swing-ui-agent on 127.0.0.1:" + port + " — start the app with "
                        + "-javaagent:swing-ui-agent.jar=port=N and retry");
    }

    @Test
    void aToolErrorBecomesTheDriverExceptionTheAgentThrew() {
        InJvmAgent agent = InJvmAgent.start(new RecordingVerbs(), null);
        try {
            AgentClient c = new AgentClient("127.0.0.1", agent.port());
            assertThatThrownBy(() -> c.callJson("read", c.args()))
                    .isInstanceOf(DriverException.class)
                    .hasMessage("BAD_INPUT: read needs a 'target' object");
        } finally {
            agent.stop();
        }
    }

    @Test
    void aForeignLeaseIsBusyAndTheHolderPasses() {
        InJvmAgent agent = InJvmAgent.start(new RecordingVerbs(), null);
        try {
            AgentClient c = new AgentClient("127.0.0.1", agent.port());
            String token = c.callJson("session", c.args().put("op", "lease").put("owner", "suite")).get("token").asText();
            assertThatThrownBy(() -> new RemoteVerbs(c).press("ENTER", null, null))
                    .isInstanceOf(DriverException.class)
                    .extracting(e -> ((DriverException) e).reason()).isEqualTo(Reason.BUSY);
            new RemoteVerbs(c.withLease(token)).press("ENTER", null, null);
        } finally {
            agent.stop();
        }
    }

    @Test
    void anUnknownReasonFromANewerAgentIsAnAppErrorThatSaysSo() {
        assertThat(AgentClient.reasonOf("NOT_FOUND")).isEqualTo(Reason.NOT_FOUND);
        assertThat(AgentClient.reasonOf("SOMETHING_NEW")).isEqualTo(Reason.APP_ERROR);
        AgentClient c = new AgentClient("127.0.0.1", 1); // no connection is made until a call
        DriverException e = c.toolError("{\"reason\":\"SOMETHING_NEW\",\"message\":\"boom\"}");
        assertThat(e.reason()).isEqualTo(Reason.APP_ERROR);
        assertThat(e.getMessage()).contains("boom").contains("SOMETHING_NEW")
                .contains("older than the client's agent");
    }
}
