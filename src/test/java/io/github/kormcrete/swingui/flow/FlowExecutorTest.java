package io.github.kormcrete.swingui.flow;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.PendingDialog;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.mcp.FakeVerbs;
import io.github.kormcrete.swingui.spi.StepHost;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

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
        assertThat(FlowJson.stepTypes()).hasSize(24);
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
}
