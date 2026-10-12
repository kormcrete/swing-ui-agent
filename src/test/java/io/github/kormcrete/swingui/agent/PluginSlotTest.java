package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.PendingDialog;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.spi.StepHost;
import io.github.kormcrete.swingui.spi.UiInventory;
import io.github.kormcrete.swingui.spi.PluginParts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PluginSlotTest {

    private final Verbs verbs = mock(Verbs.class);

    @Test
    void aNewSlotHasNoStateYet() {
        PluginSlot slot = new PluginSlot("demo", Paths.get("plugin.jar"));
        assertThat(slot.name()).isEqualTo("demo");
        assertThat(slot.state()).isEqualTo(PluginSlot.State.NONE);
        assertThat(slot.parts()).isNull();
    }

    @Test
    void aFailedPluginSaysWhy() {
        PluginSlot slot = new PluginSlot("demo", Paths.get("plugin.jar"));
        slot.failed("demo.jar declares no io.github.kormcrete.swingui.spi.UiPlugin");
        assertThat(slot.state()).isEqualTo(PluginSlot.State.FAILED);
        assertThatThrownBy(() -> slot.readyParts("loginAs")).hasMessageContaining("declares no");
    }

    @Test
    void oncePluginIsReadyThroughPluginsCallsGoThrough() {
        List<String> calls = new ArrayList<String>();
        Plugins plugins = new Plugins(verbs);
        PluginSlot slot = plugins.add("demo", Paths.get("demo.jar"));
        slot.ready(new PluginParts(recordingHost(calls), inventory()), "DemoUiPlugin from demo.jar");
        plugins.navigate("Orders");
        plugins.loginAs("alice");
        plugins.closeActiveTab();
        assertThat(calls).containsExactly("navigate Orders", "loginAs alice", "close");
        assertThat(plugins.activeTabTitle()).isEqualTo("Tab");
        assertThat(slot.state()).isEqualTo(PluginSlot.State.READY);
        assertThat(slot.detail()).isEqualTo("DemoUiPlugin from demo.jar");
        assertThat(plugins.verbs()).isSameAs(verbs);
    }

    @Test
    void failedAfterReadyDropsThePartsAndSaysSo() {
        PluginSlot slot = new PluginSlot("demo", Paths.get("plugin.jar"));
        slot.ready(new PluginParts(null, null), "DemoUiPlugin from plugin.jar");
        slot.failed("app context closed");
        assertThat(slot.state()).isEqualTo(PluginSlot.State.FAILED);
        assertThat(slot.parts()).isNull();
        assertThat(slot.detail()).isEqualTo("failed after ready: app context closed");
    }

    @Test
    void readyRecordsTheLocationTime(@TempDir Path dir) throws Exception {
        Path jar = Files.write(dir.resolve("p.jar"), new byte[] {1});
        Files.setLastModifiedTime(jar, FileTime.fromMillis(1_700_000_000_000L));
        PluginSlot slot = new PluginSlot("p", jar);
        slot.ready(new PluginParts(null, null), "ok");
        assertThat(slot.locationTime()).isEqualTo(1_700_000_000_000L);
    }

    @Test
    void readyPartsBeforeReadyNamesTheState() {
        PluginSlot slot = new PluginSlot("demo", Paths.get("x"));
        slot.waiting("waiting for a.B to load");
        assertThatThrownBy(() -> slot.readyParts("plugin_call"))
                .isInstanceOf(DriverException.class)
                .hasMessage("CAPABILITY_GAP: plugin_call needs plugin 'demo', which is waiting: waiting for a.B to load");
    }

    private StepHost recordingHost(List<String> calls) {
        return new StepHost() {
            @Override public Verbs verbs() { return verbs; }
            @Override public void navigate(String target) { calls.add("navigate " + target); }
            @Override public void closeActiveTab() { calls.add("close"); }
            @Override public void loginAs(String alias) { calls.add("loginAs " + alias); }
            @Override public PendingDialog openDialog() { return null; }
        };
    }

    @Test
    void anInventoryWithoutFriendlyNavigateNamesHasNone() {
        assertThat(inventory().navTargets()).isEmpty();
    }

    private UiInventory inventory() {
        return new UiInventory() {
            @Override public String activeTabTitle() { return "Tab"; }
            @Override public List<String> entryPointIds() { return Collections.emptyList(); }
            @Override public List<Map<String, Object>> toolbarActions() { return Collections.emptyList(); }
            @Override public List<String> menuPaths() { return Collections.emptyList(); }
            @Override public Map<String, List<String>> navEntryPoints() { return Collections.emptyMap(); }
            @Override public Map<String, List<String>> viewTabs() { return Collections.emptyMap(); }
            @Override public List<Map<String, Object>> viewSections() { return Collections.emptyList(); }
            @Override public List<String> viewSectionProblems() { return Collections.emptyList(); }
        };
    }
}
