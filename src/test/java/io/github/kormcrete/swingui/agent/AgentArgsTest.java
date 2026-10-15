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
        assertThat(a.log).isNull();
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
                .hasMessageContaining("port, bind, mode, palette, recorded, plugins, log");
    }

    @Test
    void recordedNamesTheFolder() {
        assertThat(AgentArgs.parse("recorded=uitest/mine").recorded).isEqualTo("uitest/mine");
        assertThat(AgentArgs.parse("port=1").recorded).isNull();
    }

    @Test
    void aBlankRecordedIsRefused() {
        assertThatThrownBy(() -> AgentArgs.parse("recorded="))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("recorded needs a folder, e.g. recorded=uitest");
    }

    @Test
    void theOldFlowsKeyNamesItsNewName() {
        assertThatThrownBy(() -> AgentArgs.parse("flows=x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'flows' is now 'recorded'");
    }

    @Test
    void theRecordedTestsFolderIsTheOptionElseThePropertyElseUitest(@org.junit.jupiter.api.io.TempDir java.nio.file.Path cwd) {
        assertThat(AgentAssembly.recordedDir(AgentArgs.parse("recorded=mine"), "prop", cwd)).isEqualTo(cwd.resolve("mine"));
        assertThat(AgentAssembly.recordedDir(AgentArgs.parse(null), "prop", cwd)).isEqualTo(cwd.resolve("prop"));
        assertThat(AgentAssembly.recordedDir(AgentArgs.parse(null), null, cwd)).isEqualTo(cwd.resolve("uitest"));
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

    @Test
    void thePaletteIsOnByDefault() {
        assertThat(AgentArgs.parse(null).palette).isTrue();
        assertThat(AgentArgs.parse("port=0").palette).isTrue();
        assertThat(AgentArgs.parse("palette=on").palette).isTrue();
        assertThat(AgentArgs.parse("palette=true").palette).isTrue();
    }

    @Test
    void paletteOffTurnsItOff() {
        assertThat(AgentArgs.parse("palette=off").palette).isFalse();
        assertThat(AgentArgs.parse("palette=false").palette).isFalse();
    }

    @Test
    void aBadPaletteValueIsRefused() {
        assertThatThrownBy(() -> AgentArgs.parse("palette=maybe"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("palette must be on or off");
    }

    @Test
    void backgroundModeDefaultsItOff() {
        assertThat(AgentArgs.parse("mode=background").palette).isFalse();
        assertThat(AgentArgs.parse("mode=background,palette=off").palette).isFalse();
    }

    @Test
    void paletteOnWithBackgroundIsRefusedInEitherOrder() {
        assertThatThrownBy(() -> AgentArgs.parse("mode=background,palette=on"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("palette=on needs a person at the screen; it cannot run with mode=background");
        assertThatThrownBy(() -> AgentArgs.parse("palette=on,mode=background"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("palette=on needs a person at the screen; it cannot run with mode=background");
    }

    @Test
    void theOldInspectorKeyNamesItsNewName() {
        assertThatThrownBy(() -> AgentArgs.parse("inspector=on"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'inspector' is now 'palette' (on by default; palette=off turns it off)");
    }

    @Test
    void withoutAScreenThereIsNoPalette() {
        assertThat(AgentAssembly.paletteOn(AgentArgs.parse(null), true)).isFalse();
        assertThat(AgentAssembly.paletteOn(AgentArgs.parse(null), false)).isTrue();
        assertThat(AgentAssembly.paletteOn(AgentArgs.parse("palette=off"), false)).isFalse();
    }

    @Test
    void theLogFileIsKeptAsGiven() {
        assertThat(AgentArgs.parse("port=8770,log=/tmp/Kormcrete Order Desk/agent.log").log)
                .isEqualTo(Paths.get("/tmp/Kormcrete Order Desk/agent.log"));
    }

    @Test
    void anEmptyLogIsRefused() {
        assertThatThrownBy(() -> AgentArgs.parse("log= "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("log");
    }
}
