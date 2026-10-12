package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.agent.InJvmAgent;
import io.github.kormcrete.swingui.client.AgentClient;
import io.github.kormcrete.swingui.client.AgentUnreachable;
import io.github.kormcrete.swingui.testing.RecordingVerbs;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UiPoolTest {

    private static List<String> where(List<UiAgents> agents) {
        List<String> out = new ArrayList<String>();
        for (UiAgents a : agents) out.add(a.where());
        return out;
    }

    @Test
    void parseSortsByPortAndTrims() {
        assertThat(where(UiPool.parse(" 127.0.0.1:8767 ,127.0.0.1:8765,127.0.0.1:8766 ")))
                .containsExactly("127.0.0.1:8765", "127.0.0.1:8766", "127.0.0.1:8767");
    }

    @Test
    void parseRejectsATrailingCommaAMissingPortADuplicateAndNothing() {
        assertThatThrownBy(() -> UiPool.parse("127.0.0.1:8765,")).hasMessageContaining("swingui.agents")
                .hasMessageContaining("empty entry");
        assertThatThrownBy(() -> UiPool.parse("127.0.0.1")).hasMessageContaining("'127.0.0.1' is not host:port");
        assertThatThrownBy(() -> UiPool.parse("127.0.0.1:87x5")).hasMessageContaining("'127.0.0.1:87x5' is not host:port");
        assertThatThrownBy(() -> UiPool.parse("127.0.0.1:87655")).hasMessageContaining("'127.0.0.1:87655' is not host:port");
        assertThatThrownBy(() -> UiPool.parse("127.0.0.1:0")).hasMessageContaining("'127.0.0.1:0' is not host:port");
        assertThatThrownBy(() -> UiPool.parse("127.0.0.1:-1")).hasMessageContaining("'127.0.0.1:-1' is not host:port");
        assertThatThrownBy(() -> UiPool.parse("127.0.0.1:+8765")).hasMessageContaining("'127.0.0.1:+8765' is not host:port");
        assertThatThrownBy(() -> UiPool.parse("127.0.0.1:8765,127.0.0.1:8765")).hasMessageContaining("twice");
        assertThatThrownBy(() -> UiPool.parse("  ")).hasMessageContaining("names no agent");
    }

    @Test
    void theDefaultIsTheOneAgentOnSwinguiPort() {
        String before = System.getProperty(UiAgents.PORT_PROPERTY);
        try {
            System.clearProperty(UiAgents.PORT_PROPERTY);
            assertThat(UiPool.defaultSpec()).isEqualTo("127.0.0.1:8765");
            System.setProperty(UiAgents.PORT_PROPERTY, "8799");
            assertThat(UiPool.defaultSpec()).isEqualTo("127.0.0.1:8799");
        } finally {
            if (before == null) System.clearProperty(UiAgents.PORT_PROPERTY); else System.setProperty(UiAgents.PORT_PROPERTY, before);
        }
    }

    @Test
    void forSpecIsOnePoolPerSpec() {
        assertThat(UiPool.forSpec("127.0.0.1:9001, 127.0.0.1:9002")).isSameAs(UiPool.forSpec("127.0.0.1:9002,127.0.0.1:9001"));
    }

    @Test
    void needingMoreThanThePoolHasFailsAtOnce() {
        UiPool pool = new UiPool(UiPool.parse("127.0.0.1:8765"), 60_000, "127.0.0.1:8765");
        long t0 = System.currentTimeMillis();
        assertThatThrownBy(() -> pool.reserve(2, "T#m"))
                .hasMessage("pool exhausted: needs 2, has 1 (swingui.agents=127.0.0.1:8765)");
        assertThat(System.currentTimeMillis() - t0).isLessThan(1_000);
    }

    @Test
    void reserveTakesTheLowestFreePortsAndReleaseGivesThemBack() {
        UiPool pool = new UiPool(UiPool.parse("127.0.0.1:8767,127.0.0.1:8765,127.0.0.1:8766"), 0, "x");
        List<UiAgents> one = pool.reserve(1, "A");
        assertThat(where(one)).containsExactly("127.0.0.1:8765");
        List<UiAgents> two = pool.reserve(2, "B");
        assertThat(where(two)).containsExactly("127.0.0.1:8766", "127.0.0.1:8767");
        pool.release(one);
        assertThat(where(pool.reserve(1, "C"))).containsExactly("127.0.0.1:8765");
    }

    @Test
    void aBusyPoolWaitsForARelease() throws Exception {
        final UiPool pool = new UiPool(UiPool.parse("127.0.0.1:8765"), 10_000, "x");
        final List<UiAgents> held = pool.reserve(1, "A");
        final CountDownLatch got = new CountDownLatch(1);
        Thread waiter = new Thread(() -> { pool.reserve(1, "B"); got.countDown(); });
        waiter.start();
        assertThat(got.await(200, TimeUnit.MILLISECONDS)).isFalse();
        pool.release(held);
        assertThat(got.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void aBusyPoolFailsAfterItsWaitNamingTheHolders() {
        UiPool pool = new UiPool(UiPool.parse("127.0.0.1:8765"), 150, "127.0.0.1:8765");
        pool.reserve(1, "ConcurrentOrderEditUiIT#x");
        assertThatThrownBy(() -> pool.reserve(1, "Other#y"))
                .hasMessageContaining("pool busy: needs 1 free of 1")
                .hasMessageContaining("ConcurrentOrderEditUiIT#x");
    }

    @Test
    void aDeadMemberFailsNamingItsAddress() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        UiPool pool = UiPool.forSpec("127.0.0.1:" + port);
        UiAgents member = pool.reserve(1, "T").get(0);
        assertThatThrownBy(member::shared).isInstanceOf(AgentUnreachable.class).hasMessageContaining("127.0.0.1:" + port);
    }

    @Test
    void ofWrapsReadyAgentsUnchecked() {
        InJvmAgent a = InJvmAgent.start(new RecordingVerbs(), null); // no plugin: a checked agent would refuse it
        try {
            AgentClient c = new AgentClient("127.0.0.1", a.port());
            assertThat(UiPool.of(c).reserve(1, "T").get(0).shared()).isSameAs(c);
        } finally {
            a.stop();
        }
    }
}
