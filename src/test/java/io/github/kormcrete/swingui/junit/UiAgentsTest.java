package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.agent.InJvmAgent;
import io.github.kormcrete.swingui.client.AgentUnreachable;
import io.github.kormcrete.swingui.testing.RecordingVerbs;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

class UiAgentsTest {

    @Test
    void noAgentFailsFastNamingTheConfig() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        UiAgents agents = new UiAgents("127.0.0.1", port);
        Throwable first = catchThrowable(agents);
        assertThat(first).isInstanceOf(AgentUnreachable.class).hasMessageContaining("-javaagent");
        // the second call fails at once with the very same failure, without connecting again
        assertThat(catchThrowable(agents)).isSameAs(first);
    }

    private static Throwable catchThrowable(UiAgents agents) {
        return org.assertj.core.api.Assertions.catchThrowable(agents::shared);
    }

    @Test
    void anAgentWithoutAnyPluginIsUsable() {
        InJvmAgent agent = InJvmAgent.start(new RecordingVerbs(), null);
        try {
            assertThat(new UiAgents("127.0.0.1", agent.port()).shared()).isNotNull();
        } finally {
            agent.stop();
        }
    }

    @Test
    void aGuardFailureNamesTheAgentsAddress() {
        // no guard is registered in this module's tests: UiAgents.prefix is the seam
        IllegalStateException e = UiAgents.prefix("127.0.0.1:8766", new IllegalStateException("the client started before the last build"));
        assertThat(e).hasMessage("127.0.0.1:8766: the client started before the last build");
    }
}
