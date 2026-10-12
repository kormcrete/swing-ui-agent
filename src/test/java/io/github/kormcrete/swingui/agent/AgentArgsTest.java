package io.github.kormcrete.swingui.agent;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentArgsTest {

    @Test
    void defaults() {
        AgentArgs a = AgentArgs.parse(null);
        assertThat(a.port).isEqualTo(8765);
        assertThat(a.bind).isEqualTo("127.0.0.1");
        assertThat(a.mode).isEqualTo("normal");
        assertThat(a.plugins).isEmpty();
    }

    @Test
    void parsesEveryKey() {
        AgentArgs a = AgentArgs.parse("port=8766, mode=background,plugins=/a/p.jar" + File.pathSeparator + "/b/classes,bind=localhost");
        assertThat(a.port).isEqualTo(8766);
        assertThat(a.mode).isEqualTo("background");
        assertThat(a.bind).isEqualTo("localhost");
        assertThat(a.plugins).containsExactly(Paths.get("/a/p.jar"), Paths.get("/b/classes"));
    }

    @Test
    void anUnknownKeyNamesTheKnownOnes() {
        assertThatThrownBy(() -> AgentArgs.parse("plugin=/a.jar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plugin")
                .hasMessageContaining("port, bind, mode, plugins");
    }

    @Test
    void aBadModeIsRefused() {
        assertThatThrownBy(() -> AgentArgs.parse("mode=hidden"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("hidden");
    }

    @Test
    void aBadPortIsRefused() {
        assertThatThrownBy(() -> AgentArgs.parse("port=abc")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("abc");
        assertThatThrownBy(() -> AgentArgs.parse("port=70000")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLoopbackBindIsAccepted() {
        assertThat(AgentArgs.parse("bind=127.0.0.1").bind).isEqualTo("127.0.0.1");
        assertThat(AgentArgs.parse("bind=127.0.0.2").bind).isEqualTo("127.0.0.2");
        assertThat(AgentArgs.parse("bind=::1").bind).isEqualTo("::1");
        assertThat(AgentArgs.parse("bind=LOCALHOST").bind).isEqualTo("LOCALHOST");
    }

    @Test
    void aNonLoopbackBindIsRefused() {
        assertThatThrownBy(() -> AgentArgs.parse("bind=0.0.0.0"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bind").hasMessageContaining("0.0.0.0");
        assertThatThrownBy(() -> AgentArgs.parse("bind=192.168.1.10"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bind");
        assertThatThrownBy(() -> AgentArgs.parse("bind=999.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bind");
    }

    @Test
    void aHostNameBindIsRefusedWithoutALookup() {
        // only IP literals and localhost: premain must never wait on DNS
        assertThatThrownBy(() -> AgentArgs.parse("bind=my-host.example"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bind").hasMessageContaining("my-host.example");
    }

    @Test
    void aPairWithoutEqualsIsRefused() {
        assertThatThrownBy(() -> AgentArgs.parse("8765")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("key=value");
    }
}
