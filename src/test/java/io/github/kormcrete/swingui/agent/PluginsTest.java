package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.spi.PluginParts;
import io.github.kormcrete.swingui.spi.StepHost;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PluginsTest {

    @Test
    void noPluginConfiguredSaysHowToConfigureOne() {
        Plugins plugins = new Plugins(null);
        assertThatThrownBy(() -> plugins.navigate("Orders"))
                .isInstanceOf(DriverException.class)
                .hasMessage("CAPABILITY_GAP: navigate needs the app plugin; no plugin configured (agent argument plugins=…)");
    }

    @Test
    void anAppStepBeforeReadyNamesEverySlot() {
        Plugins plugins = new Plugins(null);
        plugins.add("demo", Paths.get("a")).waiting("waiting for x.Y to load");
        plugins.add("extra", Paths.get("b")).failed("no service file");
        assertThatThrownBy(() -> plugins.navigate("Orders"))
                .hasMessage("CAPABILITY_GAP: navigate needs the app plugin; demo is waiting (waiting for x.Y to load), "
                        + "extra is failed (no service file)");
    }

    @Test
    void theAppIsTheFirstReadySlotWithAStepHost() {
        Plugins plugins = new Plugins(null);
        PluginSlot hooksOnly = plugins.add("hooks", Paths.get("a"));
        PluginSlot app = plugins.add("demo", Paths.get("b"));
        StepHost host = mock(StepHost.class);
        hooksOnly.ready(new PluginParts(null, null), "ok");
        app.ready(new PluginParts(host, null), "ok");
        plugins.navigate("Orders");
        verify(host).navigate("Orders");
        assertThat(plugins.app().host).isSameAs(host);
    }

    @Test
    void aSlotByNameIsRequiredOnceThereAreSeveral() {
        Plugins plugins = new Plugins(null);
        plugins.add("a", Paths.get("a"));
        assertThat(plugins.slot(null).name()).isEqualTo("a");
        plugins.add("b", Paths.get("b"));
        assertThatThrownBy(() -> plugins.slot(null))
                .hasMessage("BAD_INPUT: name the plugin: a, b");
        assertThatThrownBy(() -> plugins.slot("c"))
                .hasMessage("BAD_INPUT: no plugin named 'c' (known: a, b)");
    }

    @Test
    void theAppSlotIsNotReadyWhileTheAppPluginIsStillLoading() {
        Plugins plugins = new Plugins(null);
        plugins.add("hooks", Paths.get("a")).ready(new PluginParts(null, null), "ok");
        PluginSlot demo = plugins.add("demo", Paths.get("b"));
        demo.waiting("waiting for x.Y to load");
        assertThat(plugins.appSlot()).isSameAs(demo);
    }

    @Test
    void theAppSlotIsTheReadyOneWithAHost() {
        Plugins plugins = new Plugins(null);
        plugins.add("hooks", Paths.get("a")).ready(new PluginParts(null, null), "ok");
        PluginSlot demo = plugins.add("demo", Paths.get("b"));
        demo.ready(new PluginParts(mock(StepHost.class), null), "ok");
        assertThat(plugins.appSlot()).isSameAs(demo);
    }

    @Test
    void allReadyWithoutAHostFallsBackToTheFirstSlot() {
        Plugins plugins = new Plugins(null);
        PluginSlot first = plugins.add("a", Paths.get("a"));
        first.ready(new PluginParts(null, null), "ok");
        plugins.add("b", Paths.get("b")).ready(new PluginParts(null, null), "ok");
        assertThat(plugins.appSlot()).isSameAs(first);
        assertThat(new Plugins(null).appSlot()).isNull();
    }
}
