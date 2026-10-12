package io.github.kormcrete.swingui.flow;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.PendingDialog;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.mcp.FakeVerbs;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class FlowExecutorVerbTest {

    /** A host whose loginAs is refused, as a host that cannot switch users would. */
    private final RecordingStepHost backend = new RecordingStepHost(new FakeVerbs()) {
        @Override public void loginAs(String alias) {
            throw new UnsupportedOperationException("this host does not switch users");
        }
    };

    private FlowResult run(String... steps) {
        return new FlowExecutor().run(FlowJson.parse("{\"name\":\"t\",\"steps\":[" + String.join(",", steps) + "]}"), backend);
    }

    private static final String CLICK = "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"OK\"}}";

    @Test
    void aVerbRunsThroughTheDriverAndCarriesItsOutcome() {
        backend.verbs.outcome.via = "api";
        backend.verbs.outcome.pending = new PendingDialog("Delete", "Really?", Arrays.asList("Yes", "No"),
                Collections.<String>emptyList());
        StepResult step = run(CLICK).steps.get(0);
        assertThat(step.passed).isTrue();
        assertThat(step.via).isEqualTo("api");
        assertThat(step.pending.title).isEqualTo("Delete");
        assertThat(step.outcome).isSameAs(backend.verbs.outcome);
        assertThat(backend.verbs.calls.get(0)).startsWith("click button 'OK'");
    }

    @Test
    void aDriverFailureKeepsItsReasonAndStopsTheFlow() {
        backend.verbs.failure = new DriverException(Reason.NOT_FOUND, "nothing matches",
                Collections.singletonList("button 'Cancel' in main"));
        FlowResult result = run(CLICK, CLICK);
        assertThat(result.steps).hasSize(1);
        assertThat(result.steps.get(0).reason).isEqualTo("NOT_FOUND");
        assertThat(result.steps.get(0).details).containsExactly("button 'Cancel' in main");
    }

    @Test
    void noDriverIsACapabilityGap() {
        backend.verbs = null;
        StepResult step = run(CLICK).steps.get(0);
        assertThat(step.passed).isFalse();
        assertThat(step.reason).isEqualTo("CAPABILITY_GAP");
    }

    @Test
    void anAppStepTheHostCannotRunIsACapabilityGap() {
        StepResult step = run("{\"action\":\"loginAs\",\"as\":\"customer\"}").steps.get(0);
        assertThat(step.reason).isEqualTo("CAPABILITY_GAP");
    }

    @Test
    void realInputIsReportedPerStep() {
        backend.verbs.during = () -> {
            backend.verbs.seam.systemGenerated = true;
            backend.verbs.watch.onEvent(new KeyEvent(new JPanel(), KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_A, 'a'));
            backend.verbs.during = () -> { };
        };
        FlowResult result = run(CLICK, CLICK);
        assertThat(result.steps.get(0).realInput).hasSize(1);
        assertThat(result.steps.get(1).realInput).isNull();
        assertThat(result.steps.get(0).activations).isNull();
    }

    @Test
    void theFlowResultCarriesTheCapabilityReport() {
        assertThat(run(CLICK).capabilities).contains("seam: FakeSeam");
    }

    @Test
    void aWaitingOptionPaneHoldsBackAppStepsButNotVerbs() {
        backend.waiting = new PendingDialog("Question", "Continue?", Arrays.asList("Yes", "No"),
                Collections.<String>emptyList());
        assertThat(run("{\"action\":\"answer\",\"button\":\"Yes\"}").steps.get(0).passed).isTrue();
        StepResult appStep = run("{\"action\":\"navigate\",\"target\":\"Orders\"}").steps.get(0);
        assertThat(appStep.passed).isFalse();
        assertThat(appStep.message).contains("a dialog is waiting");
    }

    @Test
    void aContextMenuRunsAsAVerb() {
        StepResult step = run("{\"action\":\"contextMenu\",\"target\":{\"role\":\"table\",\"name\":\"Orders\"},"
                + "\"path\":[\"Delete\"]}").steps.get(0);
        assertThat(step.passed).isTrue();
        assertThat(backend.verbs.calls).hasSize(1);
        assertThat(backend.verbs.calls.get(0)).startsWith("contextMenu").contains("Delete");
        assertThat(backend.calls).isEmpty();
    }

    @Test
    void reasonsComeFromTheExceptionType() {
        assertThat(FlowExecutor.reasonOf(new DriverException(Reason.BAD_INPUT, "x"))).isEqualTo("BAD_INPUT");
        assertThat(FlowExecutor.reasonOf(new UnsupportedOperationException("x"))).isEqualTo("CAPABILITY_GAP");
        assertThat(FlowExecutor.reasonOf(new AssertionError("x"))).isNull();
    }
}
