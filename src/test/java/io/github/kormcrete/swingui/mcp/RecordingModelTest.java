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
        model.delete(3, 3, RecordedStep.Author.AGENT);
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
        model.cutFrom(2, RecordedStep.Author.PERSON);
        assertThat(model.rev()).isGreaterThan(rev + 1);
        rev = model.rev();
        model.delete(1, 1, RecordedStep.Author.PERSON);
        assertThat(model.rev()).isGreaterThan(rev);
        rev = model.rev();
        model.load(java.util.Collections.<io.github.kormcrete.swingui.flow.Action>singletonList(click("c")),
                RecordedStep.Author.LOADED);
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
        model.delete(1, 1, RecordedStep.Author.AGENT);
        assertThat(model.positionOf(3)).isEqualTo(2);
        assertThat(model.positionOf(1)).isEqualTo(0);
        assertThat(model.at(1).author).isEqualTo(RecordedStep.Author.PERSON);
    }

    private java.util.List<String> names() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (RecordedStep st : model.steps()) out.add(((ClickVerb) st.action).target.name);
        return out;
    }

    @Test
    void undoRestoresEachKindOfEdit() {
        three();
        model.insert(2, click("x"), RecordedStep.Author.AGENT);
        assertThat(names()).containsExactly("a", "x", "b", "c");
        assertThat(model.undoLabel()).isEqualTo("insert at step 2 by the agent");
        assertThat(model.undo(RecordedStep.Author.AGENT)).isEqualTo("undid the insert at step 2 by the agent");
        assertThat(names()).containsExactly("a", "b", "c");

        assertThat(model.delete(1, 2, RecordedStep.Author.PERSON)).isEqualTo("deleted steps 1-2");
        assertThat(names()).containsExactly("c");
        assertThat(model.undo(RecordedStep.Author.AGENT)).isEqualTo("undid the delete of steps 1-2 by the person");
        assertThat(names()).containsExactly("a", "b", "c");

        assertThat(model.move(3, 1, RecordedStep.Author.AGENT)).isEqualTo("moved step 3 to 1");
        assertThat(names()).containsExactly("c", "a", "b");
        model.undo(RecordedStep.Author.AGENT);
        assertThat(names()).containsExactly("a", "b", "c");

        model.replace(2, click("y"), RecordedStep.Author.AGENT);
        assertThat(model.at(2).edited).isTrue();
        assertThat(model.at(2).id).isEqualTo(2);
        model.undo(RecordedStep.Author.AGENT);
        assertThat(names()).containsExactly("a", "b", "c");
        assertThat(model.at(2).edited).isFalse();
        assertThat(model.undo(RecordedStep.Author.AGENT)).isNull();
        assertThat(model.undoLabel()).isNull();
    }

    @Test
    void anUndoSaysWhoUndidIt() {
        three();
        model.delete(2, 2, RecordedStep.Author.PERSON);
        assertThat(model.undo(RecordedStep.Author.AGENT)).isEqualTo("undid the delete of step 2 by the person");
        assertThat(model.lastChange()).isEqualTo("the agent undid the delete of step 2 by the person");
        model.delete(1, 1, RecordedStep.Author.AGENT);
        model.undo(RecordedStep.Author.PERSON);
        assertThat(model.lastChange()).isEqualTo("the person undid the delete of step 1 by the agent");
    }

    @Test
    void theUndoHistoryKeepsTheLast50() {
        for (int i = 0; i < 60; i++) model.append(click("s" + i), RecordedStep.Author.AGENT);
        for (int i = 0; i < 55; i++) model.delete(1, 1, RecordedStep.Author.AGENT);
        assertThat(model.size()).isEqualTo(5);
        int undone = 0;
        while (model.undo(RecordedStep.Author.AGENT) != null) undone++;
        assertThat(undone).isEqualTo(50);
        assertThat(model.size()).isEqualTo(55);
    }

    @Test
    void aCutUndoneDropsWhatWasRecordedAfterIt() {
        three();
        assertThat(model.cutFrom(2, RecordedStep.Author.PERSON)).isEqualTo("cut from step 2");
        model.append(click("d"), RecordedStep.Author.PERSON);
        model.append(click("e"), RecordedStep.Author.PERSON);
        assertThat(model.undo(RecordedStep.Author.AGENT)).contains("undid the cut from step 2 (redo) by the person")
                .contains("2 steps recorded after it dropped");
        assertThat(names()).containsExactly("a", "b", "c");
        assertThat(model.cutFrom(9, RecordedStep.Author.PERSON)).isNull();
    }

    @Test
    void anUndoneDeleteKeepsStepsRecordedSince() {
        three();
        model.delete(2, 2, RecordedStep.Author.AGENT);
        model.append(click("d"), RecordedStep.Author.AGENT);
        model.undo(RecordedStep.Author.AGENT);
        assertThat(names()).containsExactly("a", "b", "c", "d");
    }

    @Test
    void editedClearsWhenReplayed() {
        three();
        model.insert(1, click("x"), RecordedStep.Author.AGENT);
        int id = model.at(1).id;
        assertThat(model.at(1).edited).isTrue();
        int rev = model.rev();
        model.markReplayed(id);
        assertThat(model.at(1).edited).isFalse();
        assertThat(model.rev()).isGreaterThan(rev);
    }

    @Test
    void startClearAndLoadEmptyTheHistory() {
        three();
        model.delete(1, 1, RecordedStep.Author.AGENT);
        model.start(RecordedStep.Author.AGENT, false, false, 1L);
        assertThat(model.undo(RecordedStep.Author.AGENT)).isNull();
        three();
        model.delete(1, 1, RecordedStep.Author.AGENT);
        model.load(java.util.Collections.<io.github.kormcrete.swingui.flow.Action>singletonList(click("z")),
                RecordedStep.Author.LOADED);
        assertThat(model.undo(RecordedStep.Author.AGENT)).isNull();
        model.delete(1, 1, RecordedStep.Author.AGENT);
        model.clear();
        assertThat(model.undo(RecordedStep.Author.AGENT)).isNull();
    }

    @Test
    void outOfRangeEditsSayWhatThereIs() {
        three();
        assertThat(catchThrowableOfType(() -> model.insert(5, click("x"), RecordedStep.Author.AGENT),
                DriverException.class).getMessage()).contains("1-4");
        assertThat(catchThrowableOfType(() -> model.delete(2, 9, RecordedStep.Author.AGENT),
                DriverException.class).reason()).isEqualTo(Reason.BAD_INPUT);
    }
}
