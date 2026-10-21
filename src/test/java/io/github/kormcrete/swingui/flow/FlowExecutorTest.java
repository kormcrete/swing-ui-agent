package io.github.kormcrete.swingui.flow;

import io.github.kormcrete.swingui.driver.DomainStepFailed;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.PendingDialog;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.StepCall;
import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.outcome.UiReading;
import io.github.kormcrete.swingui.flow.refs.RunValues;
import io.github.kormcrete.swingui.mcp.FakeVerbs;
import io.github.kormcrete.swingui.spi.StepHost;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.entry;

/** The step model: the verbs through the driver, and navigate / close / loginAs through the {@link StepHost}. */
class FlowExecutorTest {

    private final RecordingStepHost host = new RecordingStepHost(new FakeVerbs());

    private FlowResult run(String... steps) {
        return new FlowExecutor().run(FlowJson.parse("{\"name\":\"t\",\"steps\":[" + String.join(",", steps) + "]}"), host);
    }

    private static PendingDialog deleteRefused() {
        return new PendingDialog("Delete line item", "Only line items without a quantity can be deleted.",
                Collections.singletonList("OK"), Collections.<String>emptyList());
    }

    private static final String NAVIGATE = "{\"action\":\"navigate\",\"target\":\"Orders\"}";
    private static final String CLOSE = "{\"action\":\"close\"}";
    private static final String LOGIN = "{\"action\":\"loginAs\",\"as\":\"customer\"}";
    private static final String ANSWER = "{\"action\":\"answer\",\"button\":\"OK\"}";

    // ---- an unknown step type --------------------------------------------------------------

    @Test
    void anUnknownStepTypeIsBadInput() {
        DriverException e = catchThrowableOfType(
                () -> run("{\"action\":\"countRows\",\"op\":\"gte\",\"value\":1}"), DriverException.class);

        assertThat(e).isNotNull();
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("'countRows'");
        assertThat(e.getMessage()).contains("click", "type", "press", "check", "select", "pick", "menu", "answer",
                "chooseFile", "drag", "drop", "expect", "scroll", "setClipboard", "screenshot", "contextMenu",
                "navigate", "close", "loginAs");
        assertThat(FlowJson.stepTypes()).hasSize(26).contains("do");
    }

    // ---- the app steps behind a waiting dialog ---------------------------------------------

    @Test
    void anAppStepBehindAWaitingDialogIsRefused() {
        host.waiting = deleteRefused();

        for (String appStep : Arrays.asList(NAVIGATE, CLOSE, LOGIN)) {
            StepResult step = run(appStep).steps.get(0);
            assertThat(step.passed).as(appStep).isFalse();
            assertThat(step.message).as(appStep).contains("a dialog is waiting for an answer")
                    .contains("Only line items without a quantity can be deleted.")
                    .contains("'answer'");
        }
        assertThat(host.calls).isEmpty();

        StepResult answer = run(ANSWER).steps.get(0);
        assertThat(answer.passed).isTrue();
        assertThat(host.verbs.calls).hasSize(1);
        assertThat(host.verbs.calls.get(0)).startsWith("answer");
    }

    @Test
    void theThreeAppStepsReachTheHost() {
        FlowResult result = run(NAVIGATE, CLOSE, LOGIN);

        assertThat(result.passed()).isTrue();
        assertThat(result.steps).hasSize(3);
        assertThat(host.calls).containsExactly("navigate Orders", "close", "loginAs customer");
        assertThat(host.verbs.calls).isEmpty();
    }

    @Test
    void aClosedTabIsCheckedAbsentAndAShownOneIsCheckedNotHidden() {
        FakeVerbs verbs = new FakeVerbs();
        FlowResult result = new FlowExecutor().run(FlowJson.parse("{\"name\":\"t\",\"steps\":["
                + "{\"action\":\"expect\",\"target\":{\"role\":\"tab\",\"name\":\"ORD-1640\"},\"absent\":true},"
                + "{\"action\":\"expect\",\"target\":{\"role\":\"tab\",\"name\":\"Orders\"},\"shown\":true}]}"),
                new RecordingStepHost(verbs));

        assertThat(result.passed()).isTrue();
        assertThat(verbs.expectations).hasSize(2);
        assertThat(verbs.expectations.get(0).absent).isTrue();
        assertThat(verbs.expectations.get(1).state).containsExactly("!hidden");
    }

    @Test
    void shownWithAnotherKindIsRefusedNamingTheKinds() {
        FlowResult result = run("{\"action\":\"expect\",\"target\":{\"role\":\"tab\",\"name\":\"Orders\"},"
                + "\"shown\":true,\"text\":\"Orders\"}");
        assertThat(result.passed()).isFalse();
        assertThat(result.steps.get(0).message).contains("exactly one of").contains("shown, absent").contains("2 given");
    }

    // ---- the executor's own rules ----------------------------------------------------------

    @Test
    void executionStopsAtFirstFailure() {
        host.failNavigateTo = "Orders";

        FlowResult result = run(NAVIGATE, CLOSE);

        assertThat(result.passed()).isFalse();
        assertThat(result.steps).hasSize(1);
        assertThat(result.steps.get(0).reason).isEqualTo("TIMEOUT");
        assertThat(host.calls).containsExactly("navigate Orders");
    }

    @Test
    void anAppStepRunsOnceTheDialogIsAnswered() {
        host.waiting = deleteRefused();
        host.verbs.during = () -> host.waiting = null; // the answer closes it

        FlowResult result = run(ANSWER, NAVIGATE);

        assertThat(result.passed()).isTrue();
        assertThat(host.calls).containsExactly("navigate Orders");
    }

    @Test
    void failWithNullMessageBecomesEmptyString() {
        StepResult r = StepResult.fail(0, "someAction", null);
        assertThat(r.passed).isFalse();
        assertThat(r.message).isEqualTo("");
    }

    // ---- the do step: a plugin's domain step, through the host -----------------------------

    private static final String DO = "{\"action\":\"do\",\"step\":\"addToCart\",\"params\":{\"product\":\"Gel pen, black\"}}";
    private static final String ROW_CLICK = "{\"action\":\"click\",\"target\":{\"scope\":[\"tab:New order*\"],"
            + "\"role\":\"table\",\"row\":{\"Product\":\"Gel pen, black\"},\"column\":\"Product\"}}";
    private static final String ADD_CLICK = "{\"action\":\"click\",\"target\":{\"scope\":[\"tab:New order*\"],"
            + "\"role\":\"button\",\"name\":\"Add to cart\"}}";

    private static StepCall call(String json, String action, boolean passed) {
        StepCall c = new StepCall(json, action);
        c.passed = passed;
        c.ms = 7;
        if (passed) c.outcome = new VerbOutcome();
        return c;
    }

    private static VerbOutcome addToCart(StepCall... calls) {
        VerbOutcome out = new VerbOutcome();
        out.sentence = "Add Gel pen, black to the cart";
        out.calls = Arrays.asList(calls);
        return out;
    }

    @Test
    void aDoStepRunsThroughTheHost() {
        host.stepOutcome = addToCart(call(ROW_CLICK, "click(row)", true), call(ADD_CLICK, "click(Add to cart)", true));

        FlowResult result = run(DO);

        assertThat(result.passed()).isTrue();
        assertThat(host.calls).containsExactly("do addToCart {product=Gel pen, black}");
        StepResult step = result.steps.get(0);
        assertThat(step.action).isEqualTo("Add Gel pen, black to the cart");
        assertThat(step.steps).extracting(c -> c.index + " " + c.action + " " + c.passed + " " + c.ms)
                .containsExactly("0 click(row) true 7", "1 click(Add to cart) true 7");
        assertThat(step.steps.get(1).json).isEqualTo(ADD_CLICK);
        assertThat(host.verbs.calls).isEmpty(); // the host ran the body, not the executor
    }

    @Test
    void aDoStepWithoutAStepIdIsBadInput() {
        host.stepOutcome = addToCart();

        StepResult step = run("{\"action\":\"do\"}").steps.get(0);

        assertThat(host.calls).isEmpty();
        assertThat(step.passed).isFalse();
        assertThat(step.reason).isEqualTo("BAD_INPUT");
        assertThat(step.message).isEqualTo("BAD_INPUT: a do step needs 'step': the id of a plugin's domain step");
    }

    @Test
    void aFailingChildFailsTheDoStepAndNamesTheChild() {
        StepCall failed = call(ADD_CLICK, "click(Add to cart)", false);
        failed.reason = Reason.NOT_FOUND;
        failed.message = "NOT_FOUND: nothing matches button 'Add to cart'";
        failed.details = Collections.singletonList("button 'Add to basket' in main [tab:New order · Ada Shop Ltd]");
        host.stepFailure = DomainStepFailed.of(addToCart(call(ROW_CLICK, "click(row)", true), failed),
                new RuntimeException("click failed"), true, UnaryOperator.<String>identity());
        host.verbs.candidates = Collections.singletonList("button 'Add to basket' in main [tab:New order · Ada Shop Ltd]");

        FlowResult result = new FlowExecutor().diagnoseFailures(new FailureDiagnostics(null))
                .run(FlowJson.parse("{\"name\":\"t\",\"steps\":[" + DO + "]}"), host);

        StepResult step = result.steps.get(0);
        assertThat(step.passed).isFalse();
        assertThat(step.action).isEqualTo("Add Gel pen, black to the cart");
        assertThat(step.reason).isEqualTo("NOT_FOUND");
        assertThat(step.message).isEqualTo("NOT_FOUND: Add Gel pen, black to the cart: step 2 (click(Add to cart)) failed: "
                + "nothing matches button 'Add to cart'");
        assertThat(step.details).containsExactly("button 'Add to basket' in main [tab:New order · Ada Shop Ltd]");
        assertThat(step.steps).extracting(c -> c.action + " " + c.passed).containsExactly("click(row) true",
                "click(Add to cart) false");
        StepResult child = step.steps.get(1);
        assertThat(child.reason).isEqualTo("NOT_FOUND");
        assertThat(child.message).isEqualTo("NOT_FOUND: nothing matches button 'Add to cart'");
        // diagnosed where it failed: the child's own target, not the do step (which has none)
        assertThat(step.diagnostics).isNull();
        assertThat(child.diagnostics.matches).hasSize(1);
        assertThat(child.diagnostics.matches.get(0).field).isEqualTo("target");
        assertThat(child.diagnostics.matches.get(0).candidates)
                .containsExactly("button 'Add to basket' in main [tab:New order · Ada Shop Ltd]");
    }

    @Test
    void aBodyThatFailsOnItsOwnNamesNoChild() {
        host.stepFailure = DomainStepFailed.of(addToCart(call(ROW_CLICK, "click(row)", true)),
                new AssertionError("expected 1 line in the cart"), false, UnaryOperator.<String>identity());

        StepResult step = run(DO).steps.get(0);

        assertThat(step.passed).isFalse();
        assertThat(step.reason).isEqualTo("EXPECTATION");
        assertThat(step.message).isEqualTo("EXPECTATION: Add Gel pen, black to the cart: the step's body failed: "
                + "expected 1 line in the cart");
        assertThat(step.steps).extracting(c -> c.action + " " + c.passed).containsExactly("click(row) true");
    }

    @Test
    void withoutAPluginADoStepIsACapabilityGap() {
        StepResult step = run(DO).steps.get(0);

        assertThat(step.passed).isFalse();
        assertThat(step.reason).isEqualTo("CAPABILITY_GAP");
        assertThat(step.message).isEqualTo("CAPABILITY_GAP: the do step 'addToCart' needs a plugin with a vocabulary; "
                + "none configured");
    }
    // ---- values from earlier steps ---------------------------------------------------------

    private static final String COPY = "{\"action\":\"click\",\"id\":\"copy\",\"target\":{\"role\":\"button\",\"name\":\"Copy\"}}";
    private static final String OPEN_COPY = "{\"action\":\"click\",\"target\":{\"role\":\"table\",\"name\":\"Orders\","
            + "\"row\":{\"Number\":\"${copy.rows.Orders.added[0].Number}\"}},\"count\":2}";

    /** The Orders table with one row per number. */
    private static UiReading orders(String... numbers) {
        List<List<String>> rows = new ArrayList<List<String>>();
        for (String n : numbers) rows.add(Arrays.asList(n, "Ada Lovelace"));
        return UiReading.builder().table("Orders", Arrays.asList("Number", "Customer"), rows).build();
    }

    private FlowExecutor linked() {
        return new FlowExecutor().outcomes(host.verbs::readUi);
    }

    private FlowResult runLinked(FlowExecutor executor, String... steps) {
        return executor.run(FlowJson.parse("{\"name\":\"t\",\"steps\":[" + String.join(",", steps) + "]}"), host);
    }

    private List<String> clicksOnOrders() {
        List<String> out = new ArrayList<String>();
        for (String c : host.verbs.calls) if (c.startsWith("click table 'Orders'")) out.add(c);
        return out;
    }

    private List<String> typed() {
        List<String> out = new ArrayList<String>();
        for (String c : host.verbs.calls) if (c.startsWith("type ")) out.add(c);
        return out;
    }

    @Test
    void aLinkedStepGetsItsOutcomeAndALaterStepUsesIt() {
        host.verbs.readings.addAll(Arrays.asList(orders("ORD-1642"), orders("ORD-1642", "ORD-1643")));

        FlowResult result = runLinked(linked(), COPY, OPEN_COPY);

        assertThat(result.passed()).isTrue();
        assertThat(clicksOnOrders()).hasSize(1);
        assertThat(clicksOnOrders().get(0)).contains("row={Number=ORD-1643}");
        assertThat(result.steps.get(1).action).contains("ORD-1643");
        assertThat(result.steps.get(1).resolved).containsExactly(
                entry("${copy.rows.Orders.added[0].Number}", "ORD-1643"));
        assertThat(result.steps.get(0).resolved).isNull();
    }

    @Test
    void twoRunsResolveToTheirOwnValues() {
        host.verbs.readings.addAll(Arrays.asList(orders("ORD-1642"), orders("ORD-1642", "ORD-1643"),
                orders("ORD-1642", "ORD-1643"), orders("ORD-1642", "ORD-1643", "ORD-1644")));

        assertThat(runLinked(linked(), COPY, OPEN_COPY).passed()).isTrue();
        assertThat(runLinked(linked(), COPY, OPEN_COPY).passed()).isTrue();

        assertThat(clicksOnOrders()).hasSize(2);
        assertThat(clicksOnOrders().get(0)).contains("row={Number=ORD-1643}");
        assertThat(clicksOnOrders().get(1)).contains("row={Number=ORD-1644}");
    }

    @Test
    void aRowAddedAfterSettleIsWaitedFor() {
        // before, after (the row is not there yet), then the row
        host.verbs.readings.addAll(Arrays.asList(orders("ORD-1642"), orders("ORD-1642"), orders("ORD-1642", "ORD-1643")));

        FlowResult result = runLinked(linked(), COPY, OPEN_COPY);

        assertThat(result.passed()).isTrue();
        assertThat(clicksOnOrders().get(0)).contains("row={Number=ORD-1643}");
        assertThat(host.verbs.readUiCalls).isEqualTo(3);
    }

    @Test
    void aValueThatComesLateIsWaitedForBeforeALaterStepAddsARowToTheSameTable() {
        // Copy's row comes on the third reading; New order, the step after it, puts a row of its own at the top
        host.verbs.reading = () -> {
            List<String> numbers = new ArrayList<String>();
            boolean newOrder = false;
            for (String c : new ArrayList<String>(host.verbs.calls)) newOrder |= c.startsWith("click button 'New order'");
            if (newOrder) numbers.add("ORD-1600");
            numbers.add("ORD-1642");
            if (host.verbs.readUiCalls >= 3) numbers.add("ORD-1643");
            return orders(numbers.toArray(new String[0]));
        };

        FlowResult result = runLinked(linked(), COPY,
                "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"New order\"}}", OPEN_COPY);

        assertThat(result.passed()).as(result.steps.toString()).isTrue();
        assertThat(clicksOnOrders()).hasSize(1);
        assertThat(clicksOnOrders().get(0)).contains("row={Number=ORD-1643}");
        // read again right after Copy, before New order ran: no reading after it
        assertThat(host.verbs.readUiCalls).isEqualTo(3);
    }

    @Test
    void aDomainStepsOwnValuesStayWhenItsOutcomeIsReadAgain() {
        // the do step returns the order's customer; the row it adds comes late, and is read again
        host.verbs.readings.addAll(Arrays.asList(orders("ORD-1642"), orders("ORD-1642"), orders("ORD-1642", "ORD-1643")));
        host.stepOutcome = new io.github.kormcrete.swingui.driver.VerbOutcome();
        host.stepOutcome.made = java.util.Collections.<String, Object>singletonMap("customer", "Ada Lovelace");

        FlowResult result = runLinked(linked(),
                "{\"action\":\"do\",\"id\":\"copy\",\"step\":\"order-desk:copyOrder\"}",
                "{\"action\":\"type\",\"target\":{\"id\":\"note\"},"
                        + "\"text\":\"${copy.rows.Orders.added[0].Number} for ${copy.step.customer}\"}");

        assertThat(result.passed()).as(result.steps.toString()).isTrue();
        assertThat(typed()).containsExactly("type #note ORD-1643 for Ada Lovelace null null");
    }

    @Test
    void anUnresolvableLinkFailsInWordsAfterTheWait() {
        host.verbs.readings.add(orders("ORD-1642"));
        long started = System.nanoTime();

        FlowResult result = runLinked(linked().linkWait(300), NAVIGATE, COPY, OPEN_COPY);

        long ms = (System.nanoTime() - started) / 1_000_000L;
        assertThat(result.passed()).isFalse();
        StepResult step = result.steps.get(2);
        assertThat(step.passed).isFalse();
        assertThat(step.reason).isEqualTo("NOT_FOUND");
        assertThat(step.message).isEqualTo("NOT_FOUND: Step 2 (Copy) added no row to Orders");
        assertThat(step.message).doesNotContain("ORD-");
        assertThat(clicksOnOrders()).isEmpty();
        assertThat(ms).isGreaterThanOrEqualTo(300);
        assertThat(host.verbs.readUiCalls).isGreaterThan(2); // it read again while waiting
    }

    @Test
    void unlinkedStepsAreNotRead() {
        host.verbs.readings.addAll(Arrays.asList(orders("ORD-1642"), orders("ORD-1642", "ORD-1643")));

        FlowResult result = runLinked(linked(), NAVIGATE,
                "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"New order\"}}", COPY,
                "{\"action\":\"press\",\"keys\":\"Escape\"}", OPEN_COPY);

        assertThat(result.passed()).isTrue();
        assertThat(host.verbs.readUiCalls).isEqualTo(2); // one linked step: before and after it
    }

    @Test
    void withoutAnOutcomeSourceNothingIsReadOrResolved() {
        // a live single step: its text is the agent's, never escaped, and never a reference
        FlowResult result = run("{\"action\":\"type\",\"target\":{\"role\":\"text\",\"name\":\"Note\"},"
                + "\"text\":\"a ${b}\"}");

        assertThat(result.passed()).isTrue();
        assertThat(typed()).hasSize(1);
        assertThat(typed().get(0)).contains(" a ${b} ");
        assertThat(host.verbs.readUiCalls).isZero();
    }

    @Test
    void aLiteralDollarBraceInAnOlderFileIsTypedAsWritten() {
        Flow flow = RecordedTestFiles.parse("{\"version\":2,\"name\":\"t\",\"start\":\"reset\",\"steps\":["
                + "{\"action\":\"type\",\"target\":{\"role\":\"text\",\"name\":\"Note\"},\"text\":\"a ${b}\"}]}", "t.json");

        FlowResult result = linked().run(flow, host);

        assertThat(result.passed()).isTrue();
        assertThat(typed()).hasSize(1);
        assertThat(typed().get(0)).contains(" a ${b} ");
        assertThat(result.steps.get(0).resolved).isNull();
    }

    private static final String TYPE_NOTE = "{\"action\":\"type\",\"target\":{\"role\":\"text\",\"name\":\"Note\"},"
            + "\"text\":\"Copy ${run.id}\"}";
    private static final String TYPE_SEARCH = "{\"action\":\"type\",\"target\":{\"role\":\"text\",\"name\":\"Search\"},"
            + "\"text\":\"${run.id}\"}";

    @Test
    void runIdIsTheSameWithinARunAndNewNextRun() {
        FlowExecutor executor = linked();
        assertThat(runLinked(executor, TYPE_NOTE, TYPE_SEARCH).passed()).isTrue();
        assertThat(runLinked(executor, TYPE_NOTE, TYPE_SEARCH).passed()).isTrue();

        List<String> typed = typed();
        assertThat(typed).hasSize(4);
        String first = typed.get(1).replaceAll("^type text 'Search' (\\S+) .*$", "$1");
        String second = typed.get(3).replaceAll("^type text 'Search' (\\S+) .*$", "$1");
        assertThat(first).matches("[a-z0-9]{8}");
        assertThat(second).matches("[a-z0-9]{8}").isNotEqualTo(first);
        assertThat(typed.get(0)).contains(" Copy " + first + " ");
        assertThat(typed.get(2)).contains(" Copy " + second + " ");
        assertThat(host.verbs.readUiCalls).isZero(); // run values need no reading
    }

    @Test
    void givenRunValuesAreUsed() {
        RunValues values = new RunValues("a1b2c3d4", java.time.LocalDateTime.of(2026, 10, 21, 9, 30, 5));

        FlowResult result = runLinked(linked().runValues(values), TYPE_NOTE);

        assertThat(typed().get(0)).contains(" Copy a1b2c3d4 ");
        assertThat(result.steps.get(0).resolved).containsExactly(entry("${run.id}", "a1b2c3d4"));
    }

    private static final String REMEMBER_NUMBER = "{\"action\":\"remember\",\"target\":{\"role\":\"label\",\"name\":\"Number\"},"
            + "\"as\":\"orderNumber\"}";

    @Test
    void rememberedValueIsUsedLater() {
        host.verbs.remembered = "ORD-1641";

        FlowResult result = runLinked(linked(), REMEMBER_NUMBER,
                "{\"action\":\"type\",\"target\":{\"role\":\"text\",\"name\":\"Search\"},\"text\":\"${orderNumber}\"}");

        assertThat(result.passed()).isTrue();
        assertThat(typed().get(0)).contains(" ORD-1641 ");
        assertThat(host.verbs.readUiCalls).isZero();
    }

    @Test
    void rememberingAsRunIsRefusedAtRun() {
        StepResult step = runLinked(linked(), REMEMBER_NUMBER.replace("orderNumber", "run")).steps.get(0);

        assertThat(step.passed).isFalse();
        assertThat(step.reason).isEqualTo("BAD_INPUT");
        assertThat(step.message).contains("'run' is reserved");
        assertThat(host.verbs.calls).isEmpty();
    }

    @Test
    void aNameNoEarlierStepHasFailsInWordsAtOnce() {
        StepResult step = runLinked(linked().linkWait(300),
                "{\"action\":\"type\",\"target\":{\"role\":\"text\",\"name\":\"Search\"},\"text\":\"${orderNumber}\"}")
                .steps.get(0);

        assertThat(step.passed).isFalse();
        assertThat(step.message).isEqualTo("NOT_FOUND: no earlier step is named orderNumber");
        // nothing to read again: no wait (counted, not timed: a slow machine must not fail it)
        assertThat(host.verbs.readUiCalls).isZero();
        assertThat(host.verbs.calls).isEmpty();
    }

    @Test
    void anIntParamWithAReferenceIsCheckedAtRun() {
        host.verbs.remembered = "3";
        host.stepOutcome = new VerbOutcome();

        FlowResult result = runLinked(linked(), REMEMBER_NUMBER.replace("orderNumber", "qty"),
                "{\"action\":\"do\",\"step\":\"order-desk:addQtyToCart\",\"params\":{\"product\":\"Gel pen, black\","
                        + "\"qty\":\"${qty}\"}}");

        assertThat(result.passed()).isTrue();
        // the host runs it with the value (its plugin checks the INT there)
        assertThat(host.calls).containsExactly("do order-desk:addQtyToCart {product=Gel pen, black, qty=3}");
        assertThat(result.steps.get(1).resolved).containsExactly(entry("${qty}", "3"));
    }
}
