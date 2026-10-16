package io.github.kormcrete.swingui.flow;

import io.github.kormcrete.swingui.driver.DomainStepFailed;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.PendingDialog;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.StepCall;
import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.mcp.FakeVerbs;
import io.github.kormcrete.swingui.spi.StepHost;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

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
        assertThat(FlowJson.stepTypes()).hasSize(25).contains("do");
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
}
