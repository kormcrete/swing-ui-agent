package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.spi.DomainStep;
import io.github.kormcrete.swingui.spi.ParamType;
import io.github.kormcrete.swingui.spi.PluginParts;
import io.github.kormcrete.swingui.spi.StepHost;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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

    private static void readyWith(Plugins plugins, String name, DomainStep... steps) {
        plugins.add(name, Paths.get(name)).ready(new PluginParts(() -> Arrays.asList(steps)), "ok");
    }

    private static DomainStep step(String id, String sentence) {
        return DomainStep.act(id, sentence).code(PluginSlotTest.NOTHING).build();
    }

    private static List<String> names(Plugins plugins) {
        return plugins.vocabulary().stream().map(v -> v.name + " from " + v.plugin + ": " + v.step.sentence())
                .collect(Collectors.toList());
    }

    @Test
    void vocabulariesOfTwoPluginsMerge() {
        Plugins plugins = new Plugins(null);
        readyWith(plugins, "order-desk", PluginSlotTest.addToCart(), step("bad", "Do {x}"));
        plugins.add("loading", Paths.get("loading")).waiting("waiting for x.Y to load");
        readyWith(plugins, "checks", DomainStep.check("cartTotalIs", "The cart total is {total}")
                .param("total", ParamType.TEXT).code(PluginSlotTest.NOTHING).build(), step("worse", "{y}"));
        assertThat(names(plugins)).containsExactly(
                "addToCart from order-desk: Add {product} to the cart",
                "cartTotalIs from checks: The cart total is {total}");
        assertThat(plugins.vocabularyProblems()).containsExactly(
                "order-desk: step 'bad' dropped: the sentence names {x}, which is not a declared param",
                "checks: step 'worse' dropped: the sentence names {y}, which is not a declared param");
    }

    @Test
    void anIdInTwoPluginsIsQualified() {
        Plugins plugins = new Plugins(null);
        readyWith(plugins, "order-desk", PluginSlotTest.addToCart(), step("placeOrder", "Place the order"));
        readyWith(plugins, "extras", step("addToCart", "Add the first product to the cart"));
        assertThat(names(plugins)).containsExactly(
                "order-desk:addToCart from order-desk: Add {product} to the cart",
                "placeOrder from order-desk: Place the order",
                "extras:addToCart from extras: Add the first product to the cart");
        assertThat(plugins.vocabularyProblems()).isEmpty();
    }

    @Test
    void withoutAPluginTheVocabularyIsEmpty() {
        Plugins plugins = new Plugins(null);
        assertThat(plugins.vocabulary()).isEmpty();
        plugins.add("demo", Paths.get("demo")).ready(new PluginParts(null, null), "ok");
        assertThat(plugins.vocabulary()).isEmpty();
        assertThat(plugins.vocabularyProblems()).isEmpty();
    }
}
