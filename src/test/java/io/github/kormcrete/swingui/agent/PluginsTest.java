package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.driver.DomainStepFailed;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.StepCall;
import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.flow.UiUsers;
import io.github.kormcrete.swingui.mcp.FakeVerbs;
import io.github.kormcrete.swingui.spi.DomainStep;
import io.github.kormcrete.swingui.spi.ParamType;
import io.github.kormcrete.swingui.spi.PluginParts;
import io.github.kormcrete.swingui.spi.StepHost;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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

    @Test
    void theMergeNamesStepsFromOneSnapshotPerPlugin() {
        // vocabulary() reads each ready slot once and merges those snapshots: a slot turning ready in between cannot
        // make the counted ids and the named steps differ (it used to read every slot twice)
        List<Plugins.VocabularyStep> merged = Plugins.merge(Arrays.asList("order-desk", "extras", "late"),
                Arrays.asList(Arrays.asList(PluginSlotTest.addToCart(), step("placeOrder", "Place the order")),
                        Arrays.asList(step("addToCart", "Add the first product to the cart")),
                        java.util.Collections.<DomainStep>emptyList()));
        assertThat(merged.stream().map(v -> v.name + " from " + v.plugin).collect(Collectors.toList())).containsExactly(
                "order-desk:addToCart from order-desk", "placeOrder from order-desk", "extras:addToCart from extras");
    }

    // ---- the do step: runStep runs a domain step's body on an in-JVM Client --------------------

    private final FakeVerbs verbs = new FakeVerbs();

    private static DomainStep addToCart() {
        return DomainStep.act("addToCart", "Add {product} to the cart")
                .param("product", ParamType.TEXT)
                .code((ui, p) -> BodyTraceTest.addToCart(ui, p.text("product")))
                .build();
    }

    private static DomainStep addQtyToCart() {
        return DomainStep.act("addQtyToCart", "Add {qty} × {product} to the cart")
                .param("product", ParamType.TEXT)
                .param("qty", ParamType.INT).optional("1")
                .code((ui, p) -> BodyTraceTest.setQuantity(ui, p.text("product"), p.integer("qty")))
                .build();
    }

    private static Map<String, String> values(String... nameValue) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i < nameValue.length; i += 2) m.put(nameValue[i], nameValue[i + 1]);
        return m;
    }

    private static List<String> calls(VerbOutcome out) {
        return out.calls.stream().map(c -> c.json + " " + c.passed).collect(Collectors.toList());
    }

    @Test
    void runStepRunsTheBodyOnAnInJvmClient() {
        Plugins plugins = new Plugins(verbs);
        AtomicReference<Thread> ranOn = new AtomicReference<Thread>();
        readyWith(plugins, "order-desk", DomainStep.act("addToCart", "Add {product} to the cart")
                .param("product", ParamType.TEXT)
                .code((ui, p) -> {
                    ranOn.set(Thread.currentThread());
                    BodyTraceTest.addToCart(ui, p.text("product"));
                })
                .build());

        VerbOutcome out = plugins.runStep("addToCart", values("product", "Gel pen, black"));

        assertThat(out.sentence).isEqualTo("Add Gel pen, black to the cart");
        assertThat(verbs.calls).hasSize(2); // the body reached the app's verbs
        assertThat(calls(out)).containsExactly(
                "{\"action\":\"click\",\"target\":{\"scope\":[\"tab:New order*\"],\"role\":\"table\","
                        + "\"row\":{\"Product\":\"Gel pen, black\"},\"column\":\"Product\"}} true",
                "{\"action\":\"click\",\"target\":{\"scope\":[\"tab:New order*\"],\"role\":\"button\","
                        + "\"name\":\"Add to cart\"}} true");
        assertThat(out.calls.get(1).action).startsWith("click(");
        assertThat(out.calls.get(1).outcome).isSameAs(verbs.outcome);
        assertThat(ranOn.get()).isSameAs(Thread.currentThread()); // the request thread, not the EDT
    }

    @Test
    void anOptionalParamLeftOutHoldsItsDefaultInTheBodyAndTheSentence() {
        Plugins plugins = new Plugins(verbs);
        readyWith(plugins, "order-desk", addQtyToCart());

        VerbOutcome out = plugins.runStep("addQtyToCart", values("product", "A4 paper, 500 sheets"));

        assertThat(out.sentence).isEqualTo("Add 1 × A4 paper, 500 sheets to the cart");
        assertThat(out.calls.get(0).json).contains("\"text\":\"1\"");
    }

    @Test
    void anUnknownStepIdIsBadInput() {
        Plugins plugins = new Plugins(verbs);
        readyWith(plugins, "order-desk", addToCart(), step("placeOrder", "Place the order"));

        assertThatThrownBy(() -> plugins.runStep("addToBasket", values("product", "Gel pen, black")))
                .isInstanceOf(DriverException.class)
                .hasMessage("BAD_INPUT: no domain step 'addToBasket'; the steps are addToCart, placeOrder");
        assertThatThrownBy(() -> plugins.runStep("extras:addToCart", values("product", "Gel pen, black")))
                .hasMessage("BAD_INPUT: no domain step 'extras:addToCart'; the steps are addToCart, placeOrder");
        assertThat(verbs.calls).isEmpty();
    }

    @Test
    void aMissingParamIsBadInput() {
        Plugins plugins = new Plugins(verbs);
        readyWith(plugins, "order-desk", addToCart(), addQtyToCart());

        assertThatThrownBy(() -> plugins.runStep("addToCart", values()))
                .isInstanceOf(DriverException.class)
                .hasMessage("BAD_INPUT: addToCart needs param 'product'");
        assertThatThrownBy(() -> plugins.runStep("addToCart", null))
                .hasMessage("BAD_INPUT: addToCart needs param 'product'");
        assertThatThrownBy(() -> plugins.runStep("addToCart", values("product", "Gel pen, black", "qty", "2")))
                .hasMessage("BAD_INPUT: addToCart has no param 'qty' (its params: product)");
        assertThatThrownBy(() -> plugins.runStep("addToCart", values("product", null)))
                .hasMessage("BAD_INPUT: param 'product' of addToCart has no value");
        for (String notAnInt : Arrays.asList("three", "-1", "2.5", "99999999999")) {
            assertThatThrownBy(() -> plugins.runStep("addQtyToCart", values("product", "Gel pen, black", "qty", notAnInt)))
                    .hasMessage("BAD_INPUT: param 'qty' of addQtyToCart is INT: a whole number in int range, not '"
                            + notAnInt + "'");
        }
        assertThat(verbs.calls).isEmpty(); // checked before the body runs
    }

    @Test
    void anIdTwoPluginsOfferNeedsThePluginsName() {
        Plugins plugins = new Plugins(verbs);
        readyWith(plugins, "order-desk", addToCart());
        readyWith(plugins, "extras", DomainStep.act("addToCart", "Add the first product to the cart")
                .code((ui, p) -> ui.button("Add to cart").click()).build());

        assertThatThrownBy(() -> plugins.runStep("addToCart", values()))
                .hasMessage("BAD_INPUT: 'addToCart' is offered by more than one plugin; name one: order-desk:addToCart, "
                        + "extras:addToCart");
        assertThat(plugins.runStep("extras:addToCart", values()).sentence).isEqualTo("Add the first product to the cart");
        assertThat(plugins.runStep("order-desk:addToCart", values("product", "Gel pen, black")).calls).hasSize(2);
    }

    @Test
    void aQualifiedIdWorksWhenTheIdIsUnique() {
        Plugins plugins = new Plugins(verbs);
        readyWith(plugins, "order-desk", addToCart());

        assertThat(plugins.runStep("order-desk:addToCart", values("product", "Gel pen, black")).sentence)
                .isEqualTo("Add Gel pen, black to the cart");
    }

    @Test
    void aFailingCallFailsTheStepWithTheCallsUpToIt() {
        Plugins plugins = new Plugins(verbs);
        readyWith(plugins, "order-desk", addToCart());
        verbs.during = () -> verbs.failure = verbs.calls.size() == 2
                ? new DriverException(Reason.NOT_FOUND, "nothing matches button 'Add to cart'") : null;

        DomainStepFailed e = (DomainStepFailed) org.assertj.core.api.Assertions.catchThrowable(
                () -> plugins.runStep("addToCart", values("product", "Gel pen, black")));

        assertThat(e.reason()).isEqualTo(Reason.NOT_FOUND);
        assertThat(e.getMessage()).isEqualTo("NOT_FOUND: Add Gel pen, black to the cart: step 2 ("
                + e.outcome().calls.get(1).action + ") failed: nothing matches button 'Add to cart'");
        List<StepCall> calls = e.outcome().calls;
        assertThat(calls).extracting(c -> c.passed).containsExactly(true, false);
        assertThat(calls.get(1).reason).isEqualTo(Reason.NOT_FOUND);
        assertThat(calls.get(1).message).isEqualTo("NOT_FOUND: nothing matches button 'Add to cart'");
    }

    @Test
    void aBodyThatGetsPastAFailedCallAndThenFailsIsTheFailureNotTheCall() {
        Plugins plugins = new Plugins(verbs);
        readyWith(plugins, "order-desk", DomainStep.act("addToCart", "Add {product} to the cart")
                .param("product", ParamType.TEXT)
                .code((ui, p) -> {
                    try {
                        ui.button("Add to cart").click();
                    } catch (RuntimeException tried) {
                        // the body goes on without it
                    }
                    throw new IllegalStateException("the cart has no line for " + p.text("product"));
                })
                .build());
        verbs.failure = new DriverException(Reason.NOT_FOUND, "nothing matches button 'Add to cart'");

        DomainStepFailed e = (DomainStepFailed) org.assertj.core.api.Assertions.catchThrowable(
                () -> plugins.runStep("addToCart", values("product", "Gel pen, black")));

        assertThat(e.reason()).isEqualTo(Reason.APP_ERROR);
        assertThat(e.getMessage()).isEqualTo("APP_ERROR: Add Gel pen, black to the cart: the step's body failed: "
                + "the cart has no line for Gel pen, black");
        assertThat(e.outcome().calls).extracting(c -> c.passed).containsExactly(false);
    }

    @Test
    void aBodysAppStepsWaitForAnAnsweredDialog() {
        StepHost host = mock(StepHost.class);
        org.mockito.Mockito.when(host.openDialog()).thenReturn(new io.github.kormcrete.swingui.driver.PendingDialog(
                "Discard order?", "The cart is not empty.", Arrays.asList("Discard", "Keep"), Collections.<String>emptyList()));
        Plugins plugins = new Plugins(verbs);
        plugins.add("order-desk", Paths.get("order-desk")).ready(new PluginParts(host, null, null, null, null, null,
                () -> Arrays.asList(
                        DomainStep.act("openOrders", "Open the orders").code((ui, p) -> ui.navigate("Orders")).build(),
                        DomainStep.act("closeTab", "Close the tab").code((ui, p) -> ui.closeActiveTab()).build(),
                        DomainStep.act("logIn", "Log in as the clerk").code((ui, p) -> ui.loginAs("clerk")).build(),
                        DomainStep.act("discard", "Discard the order").code((ui, p) -> ui.answer("Discard")).build())), "ok");
        String waiting = "a dialog is waiting for an answer: " + host.openDialog().summary()
                + " - answer it with the 'answer' verb before the next step";

        for (String id : Arrays.asList("openOrders", "closeTab", "logIn")) {
            DomainStepFailed e = (DomainStepFailed) org.assertj.core.api.Assertions.catchThrowable(
                    () -> plugins.runStep(id, values()));
            assertThat(e).as(id).isNotNull();
            assertThat(e.getMessage()).as(id).endsWith("failed: " + waiting);
        }
        org.mockito.Mockito.verify(host, org.mockito.Mockito.never()).navigate(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(host, org.mockito.Mockito.never()).closeActiveTab();
        org.mockito.Mockito.verify(host, org.mockito.Mockito.never()).loginAs(org.mockito.ArgumentMatchers.anyString());
        // the step itself is not refused: a body may answer the dialog
        assertThat(calls(plugins.runStep("discard", values()))).containsExactly("{\"action\":\"answer\",\"button\":\"Discard\"} true");
    }

    @Test
    void aBodysOwnFailureMasksTheSecretsItRead() {
        System.setProperty(UiUsers.FILE_PROPERTY, writeUsers("clerk.password=s3cret-Pw"));
        try {
            Plugins plugins = new Plugins(verbs);
            readyWith(plugins, "order-desk",
                    DomainStep.act("logIn", "Log in as the clerk")
                            .code((ui, p) -> {
                                throw new IllegalStateException("the password " + ui.secret("clerk", "password") + " expired");
                            }).build(),
                    DomainStep.act("logInAgain", "Log in again as the clerk")
                            .code((ui, p) -> {
                                String pw = ui.secret("clerk", "password");
                                throw new DriverException(Reason.EXPECTATION, "refused " + pw, Arrays.asList("tried " + pw));
                            }).build());

            DomainStepFailed plain = (DomainStepFailed) org.assertj.core.api.Assertions.catchThrowable(
                    () -> plugins.runStep("logIn", values()));
            DomainStepFailed driver = (DomainStepFailed) org.assertj.core.api.Assertions.catchThrowable(
                    () -> plugins.runStep("logInAgain", values()));

            assertThat(plain.getMessage()).isEqualTo("APP_ERROR: Log in as the clerk: the step's body failed: "
                    + "the password *** expired");
            assertThat(driver.getMessage()).isEqualTo("EXPECTATION: Log in again as the clerk: the step's body failed: refused ***");
            assertThat(driver.details()).containsExactly("tried ***");
        } finally {
            System.clearProperty(UiUsers.FILE_PROPERTY);
        }
    }

    @Test
    void aDeclarativeStepRunsItsStepsWithTheValues() {
        Plugins plugins = new Plugins(verbs);
        readyWith(plugins, "order-desk", DomainStep.act("openRow", "Open order row {row}")
                .param("row", ParamType.INT)
                .steps("{\"action\":\"click\",\"target\":{\"role\":\"table\",\"name\":\"Orders\","
                        + "\"row\":{\"index\":\"{row}\"}},\"count\":2}")
                .build());

        VerbOutcome out = plugins.runStep("openRow", values("row", "3"));

        assertThat(out.sentence).isEqualTo("Open order row 3");
        assertThat(calls(out)).containsExactly(
                "{\"action\":\"click\",\"target\":{\"role\":\"table\",\"name\":\"Orders\",\"row\":{\"index\":3}},"
                        + "\"count\":2} true");
        assertThat(verbs.calls).hasSize(1);
    }

    @Test
    void aSecretTheBodyReadsIsMaskedInItsSteps() {
        System.setProperty(UiUsers.FILE_PROPERTY, writeUsers("clerk.password=s3cret-Pw"));
        try {
            Plugins plugins = new Plugins(verbs);
            readyWith(plugins, "order-desk", DomainStep.act("logIn", "Log in as the clerk")
                    .code((ui, p) -> ui.password("Password").fill(ui.secret("clerk", "password")))
                    .build());

            VerbOutcome out = plugins.runStep("logIn", values());

            assertThat(verbs.calls).anyMatch(c -> c.contains("s3cret-Pw")); // the app got it
            assertThat(out.calls).allMatch(c -> !c.json.contains("s3cret-Pw") && !c.action.contains("s3cret-Pw"));
            assertThat(out.calls.get(0).json).contains("\"text\":\"***\"");
        } finally {
            System.clearProperty(UiUsers.FILE_PROPERTY);
        }
    }

    private static String writeUsers(String lines) {
        try {
            java.nio.file.Path f = java.nio.file.Files.createTempFile("ui-users", ".properties");
            f.toFile().deleteOnExit();
            java.nio.file.Files.write(f, lines.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return f.toString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void withoutAPluginADoStepIsACapabilityGap() {
        assertThatThrownBy(() -> new Plugins(verbs).runStep("addToCart", values()))
                .isInstanceOf(DriverException.class)
                .hasMessage("CAPABILITY_GAP: the do step 'addToCart' needs a plugin with a vocabulary; none configured "
                        + "(agent argument plugins=…)");
        Plugins noVocabulary = new Plugins(verbs);
        noVocabulary.add("demo", Paths.get("demo")).ready(new PluginParts(null, null), "OrderDeskPlugin");
        noVocabulary.add("late", Paths.get("late")).waiting("waiting for x.Y to load");
        assertThatThrownBy(() -> noVocabulary.runStep("addToCart", values()))
                .hasMessage("CAPABILITY_GAP: the do step 'addToCart' needs a plugin with a vocabulary; none offers one: "
                        + "demo is ready (OrderDeskPlugin), late is waiting (waiting for x.Y to load)");
    }

    @Test
    void withoutADriverADoStepIsACapabilityGap() {
        Plugins plugins = new Plugins(null);
        readyWith(plugins, "order-desk", addToCart());

        assertThatThrownBy(() -> plugins.runStep("addToCart", values("product", "Gel pen, black")))
                .hasMessage("CAPABILITY_GAP: no UI driver is installed for the do step 'addToCart'");
    }

    /** Hooks a step's body asks its Client for. */
    public interface CartHooks {
        int cartLines();
    }

    @Test
    void theBodyReachesAPluginsHooks() {
        Plugins plugins = new Plugins(verbs);
        CartHooks hooks = () -> 2;
        AtomicReference<Integer> seen = new AtomicReference<Integer>();
        plugins.add("order-desk", Paths.get("order-desk")).ready(new PluginParts(null, null, null, CartHooks.class, hooks,
                null, () -> Collections.singletonList(DomainStep.act("countLines", "Count the cart lines")
                        .code((ui, p) -> seen.set(ui.hooks(CartHooks.class).cartLines())).build())), "ok");

        plugins.runStep("countLines", values());

        assertThat(seen.get()).isEqualTo(2);
    }
}
