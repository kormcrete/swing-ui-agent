package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.flow.verb.ClickVerb;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class RecordingModelTest {

    private final RecordingModel model = new RecordingModel();

    private static ClickVerb click(String name) {
        ClickVerb c = new ClickVerb();
        c.target = Locator.of("button", name);
        return c;
    }

    private void three() {
        model.append(click("a"), RecordedStep.Author.AGENT);
        model.append(click("b"), RecordedStep.Author.PERSON);
        model.append(click("c"), RecordedStep.Author.AGENT);
    }

    @Test
    void idsAreNeverReusedAfterARemove() {
        three();
        model.remove(3);
        assertThat(model.append(click("d"), RecordedStep.Author.AGENT).id).isEqualTo(4);
        model.clear();
        assertThat(model.append(click("e"), RecordedStep.Author.AGENT).id).isEqualTo(5);
    }

    @Test
    void everyChangeMovesTheRevision() {
        int rev = model.rev();
        model.append(click("a"), RecordedStep.Author.AGENT);
        assertThat(model.rev()).isGreaterThan(rev);
        rev = model.rev();
        model.append(click("b"), RecordedStep.Author.AGENT);
        model.truncate(1);
        assertThat(model.rev()).isGreaterThan(rev + 1);
        rev = model.rev();
        model.remove(1);
        assertThat(model.rev()).isGreaterThan(rev);
        rev = model.rev();
        model.load(java.util.Collections.<io.github.kormcrete.swingui.flow.Action>singletonList(click("c")),
                RecordedStep.Author.FLOW);
        assertThat(model.rev()).isGreaterThan(rev);
        rev = model.rev();
        model.clear();
        assertThat(model.rev()).isGreaterThan(rev);
    }

    @Test
    void aPositionOutOfRangeNamesTheRange() {
        assertThat(catchThrowableOfType(() -> model.at(1), DriverException.class).getMessage())
                .contains("no step 1").contains("the recording is empty");
        three();
        DriverException zero = catchThrowableOfType(() -> model.at(0), DriverException.class);
        assertThat(zero.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(zero.getMessage()).contains("the recording has 3 steps (1-3)");
        assertThat(catchThrowableOfType(() -> model.at(4), DriverException.class).getMessage())
                .contains("no step 4").contains("the recording has 3 steps (1-3)");
    }

    @Test
    void positionOfFollowsTheStepAfterAnotherIsRemoved() {
        three();
        assertThat(model.positionOf(3)).isEqualTo(3);
        model.remove(1);
        assertThat(model.positionOf(3)).isEqualTo(2);
        assertThat(model.positionOf(1)).isEqualTo(0);
        assertThat(model.at(1).author).isEqualTo(RecordedStep.Author.PERSON);
    }
}
