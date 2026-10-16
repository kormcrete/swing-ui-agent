package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.PendingDialog;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.spi.DomainStep;
import io.github.kormcrete.swingui.spi.ParamType;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    static final DomainStep.Body NOTHING = (ui, p) -> { };

    /** A ready slot whose plugin offers only these steps. */
    static PluginSlot vocabularySlot(String name, DomainStep... steps) {
        PluginSlot slot = new PluginSlot(name, Paths.get(name));
        slot.ready(new PluginParts(() -> Arrays.asList(steps)), "ok");
        return slot;
    }

    static List<String> ids(List<DomainStep> steps) {
        return steps.stream().map(DomainStep::id).collect(Collectors.toList());
    }

    static DomainStep addToCart() {
        return DomainStep.act("addToCart", "Add {product} to the cart").param("product", ParamType.TEXT).code(NOTHING)
                .build();
    }

    @Test
    void aStepWhoseSentenceNamesAnUndeclaredParamIsDroppedAndListed() {
        PluginSlot slot = vocabularySlot("order-desk", addToCart(),
                DomainStep.act("setQuantity", "Set the quantity of {product} to {qty}").param("product", ParamType.TEXT)
                        .code(NOTHING).build());
        assertThat(slot.state()).isEqualTo(PluginSlot.State.READY);
        assertThat(ids(slot.vocabulary())).containsExactly("addToCart");
        assertThat(slot.vocabularyProblems()).containsExactly("order-desk: step 'setQuantity' dropped: the sentence names "
                + "{qty}, which is not a declared param");
    }

    @Test
    void duplicateIdsInOnePluginAreAProblem() {
        PluginSlot slot = vocabularySlot("order-desk", addToCart(),
                DomainStep.act("addToCart", "Add a {product}").param("product", ParamType.TEXT).code(NOTHING).build());
        assertThat(slot.vocabulary()).hasSize(1);
        assertThat(slot.vocabulary().get(0).sentence()).isEqualTo("Add {product} to the cart");
        assertThat(slot.vocabularyProblems()).containsExactly("order-desk: step 'addToCart' dropped: the id is already used "
                + "by an earlier step of this plugin");
    }

    @Test
    void aPluginWithOnlyAVocabularyIsReady() {
        Plugins plugins = new Plugins(verbs);
        PluginSlot slot = plugins.add("order-desk", Paths.get("order-desk-plugin/target/classes"));
        slot.ready(new PluginParts(() -> Collections.singletonList(addToCart())), "OrderDeskPlugin");
        assertThat(slot.state()).isEqualTo(PluginSlot.State.READY);
        assertThat(ids(slot.vocabulary())).containsExactly("addToCart");
        assertThat(slot.vocabularyProblems()).isEmpty();
        // a vocabulary is no app plugin: app steps still say none is there
        assertThat(plugins.app()).isNull();
        assertThatThrownBy(() -> plugins.navigate("Orders")).hasMessageContaining("CAPABILITY_GAP");
    }

    @Test
    void aJavaCallOrDeclarativeBodyNamingAnUndeclaredParamIsAProblem() {
        PluginSlot slot = vocabularySlot("order-desk",
                DomainStep.act("addQtyToCart", "Add {qty} × {product} to the cart").param("product", ParamType.TEXT)
                        .param("qty", ParamType.INT).code(NOTHING)
                        .java("io.github.kormcrete.orderdesk.uitest.OrderDeskSteps", "addQtyToCart(ui, {product}, {count})")
                        .build(),
                DomainStep.act("openOrder", "Open order {order}").param("order", ParamType.TEXT)
                        .steps("{\"action\":\"click\",\"target\":{\"role\":\"tab\",\"name\":\"Orders\"}}",
                                "{\"action\":\"click\",\"target\":{\"role\":\"row\",\"name\":\"{number}\"}}")
                        .build());
        assertThat(slot.vocabulary()).isEmpty();
        assertThat(slot.vocabularyProblems()).containsExactly(
                "order-desk: step 'addQtyToCart' dropped: the java call names {count}, which is not a declared param",
                "order-desk: step 'openOrder' dropped: steps entry 2 names {number}, which is not a declared param");
    }

    @Test
    void aMatchEntryThatIsNoJsonObjectIsAProblem() {
        PluginSlot slot = vocabularySlot("order-desk",
                DomainStep.act("placeOrder", "Place the order").code(NOTHING)
                        .match("{\"action\":\"click\",\"target\":{\"name\":\"Place order\"}}", "[1]")
                        .matchAlso("{\"action\":\"answer\"").matchAlso("{\"a\":1} x").build());
        assertThat(slot.vocabulary()).isEmpty();
        assertThat(slot.vocabularyProblems()).hasSize(1);
        assertThat(slot.vocabularyProblems().get(0))
                .startsWith("order-desk: step 'placeOrder' dropped: match entry 2 is not a JSON object; "
                        + "matchAlso 1 entry 1 is not valid JSON (")
                .contains("; matchAlso 2 entry 1 is not valid JSON (");
    }

    @Test
    void aPatternThatDoesNotParseIsAProblem() {
        PluginSlot slot = vocabularySlot("order-desk", addToCart(),
                DomainStep.act("addQtyToCart", "Add {qty} × {product} to the cart").param("product", ParamType.TEXT)
                        .param("qty", ParamType.INT).code(NOTHING)
                        .match("{\"action\":\"click\",\"target\":{\"row\":{\"Product\":\"{product}\"}}}",
                                "{\"action\":\"type\",\"text\":\"{count}\"}").build(),
                DomainStep.act("placeOrder", "Place the order").code(NOTHING)
                        .match("{\"target\":{\"name\":\"Place order\"}}").build());
        assertThat(ids(slot.vocabulary())).containsExactly("addToCart");
        assertThat(slot.patterns()).hasSize(1);
        assertThat(slot.patterns().get(0).step()).isSameAs(slot.vocabulary().get(0));
        assertThat(slot.vocabularyProblems()).containsExactly(
                "order-desk: step 'addQtyToCart' dropped: match entry 2 names {count}, which is not a declared param",
                "order-desk: step 'placeOrder' dropped: match entry 1 has no action");
    }

    @Test
    void aStepWhosePatternCannotBeDerivedStaysWithAWhy() {
        PluginSlot slot = vocabularySlot("order-desk", addToCart(),
                DomainStep.act("placeOrder", "Place the order").code((ui, p) -> ui.button("Place order").click()).build());
        assertThat(ids(slot.vocabulary())).containsExactly("addToCart", "placeOrder");
        assertThat(slot.vocabularyProblems()).isEmpty();
        assertThat(slot.patterns().get(0).why()).isEqualTo("the body makes no steps");
        assertThat(slot.patterns().get(1).why()).isNull();
        assertThat(slot.patterns().get(1).patterns()).hasSize(1);
    }

    @Test
    void optionalAndChoicesBeforeAnyParamAreRefusedAtOnce() {
        assertThatThrownBy(() -> DomainStep.act("placeOrder", "Place the order").optional("1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("optional(...) applies to a param: declare one first");
        assertThatThrownBy(() -> DomainStep.act("placeOrder", "Place the order").choices(Collections::emptyList))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("choices(...) applies to a param: declare one first");
    }

    @Test
    void anIntDefaultMustFitAnIntAndBracesAroundANumberAreNoParam() {
        PluginSlot slot = vocabularySlot("order-desk",
                DomainStep.act("setQuantity", "Set {qty}").param("qty", ParamType.INT).optional("99999999999")
                        .code(NOTHING).build(),
                DomainStep.act("pickFirst", "Pick option {1}").code(NOTHING).build());
        assertThat(ids(slot.vocabulary())).containsExactly("pickFirst");
        assertThat(slot.vocabularyProblems()).containsExactly("order-desk: step 'setQuantity' dropped: the default of "
                + "INT param 'qty' is not a whole number in int range: '99999999999'");
    }

    @Test
    void aStepNeedsExactlyOneBody() {
        PluginSlot slot = vocabularySlot("order-desk",
                DomainStep.act("placeOrder", "Place the order").build(),
                DomainStep.act("checkout", "Check out").code(NOTHING).steps("{\"action\":\"press\",\"keys\":\"Enter\"}")
                        .build());
        assertThat(slot.vocabularyProblems()).containsExactly(
                "order-desk: step 'placeOrder' dropped: no body: give code(...) or steps(...)",
                "order-desk: step 'checkout' dropped: two bodies: give code(...) or steps(...), not both");
    }

    @Test
    void idsAndParamsMustBeUsableNames() {
        PluginSlot slot = vocabularySlot("order-desk",
                DomainStep.act("add to cart", "Add {my product}").param("my product", ParamType.TEXT).code(NOTHING).build(),
                DomainStep.act("setQuantity", "Set {qty}").param("qty", ParamType.INT).optional("one")
                        .param("qty", ParamType.TEXT).code(NOTHING).java("Order Desk Steps", "setQuantity(ui, {qty})")
                        .build());
        assertThat(slot.vocabularyProblems()).containsExactly(
                "order-desk: step 'add to cart' dropped: the id must start with a letter and hold only letters, digits, "
                        + "_ and -; param 'my product': a name must start with a letter and hold only letters, digits and _",
                "order-desk: step 'setQuantity' dropped: the default of INT param 'qty' is not a whole number in int range: 'one'; "
                        + "param 'qty' is declared twice; the java class 'Order Desk Steps' is not a class name");
    }

    @Test
    void aVocabularyThatThrowsLeavesThePluginReadyAndSaysWhy() {
        PluginSlot slot = new PluginSlot("order-desk", Paths.get("p"));
        slot.ready(new PluginParts(() -> {
            throw new IllegalStateException("no products yet");
        }), "ok");
        assertThat(slot.state()).isEqualTo(PluginSlot.State.READY);
        assertThat(slot.vocabulary()).isEmpty();
        assertThat(slot.vocabularyProblems())
                .containsExactly("order-desk: the vocabulary failed: java.lang.IllegalStateException: no products yet");
    }

    @Test
    void aFailureAfterReadyDropsTheVocabulary() {
        PluginSlot slot = vocabularySlot("order-desk", addToCart(),
                DomainStep.act("bad", "{x}").code(NOTHING).build());
        slot.failed("app context closed");
        assertThat(slot.vocabulary()).isEmpty();
        assertThat(slot.vocabularyProblems()).isEmpty();
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
