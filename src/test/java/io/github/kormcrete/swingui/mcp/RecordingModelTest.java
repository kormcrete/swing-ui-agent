package io.github.kormcrete.swingui.mcp;

import com.fasterxml.jackson.databind.node.TextNode;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.RowChoice;
import io.github.kormcrete.swingui.flow.Action;
import io.github.kormcrete.swingui.flow.Flow;
import io.github.kormcrete.swingui.flow.FlowGroup;
import io.github.kormcrete.swingui.flow.verb.ClickVerb;
import io.github.kormcrete.swingui.flow.verb.ExpectVerb;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class RecordingModelTest {

    private static final RecordedStep.Author AGENT = RecordedStep.Author.AGENT;
    private static final RecordedStep.Author PERSON = RecordedStep.Author.PERSON;

    private final RecordingModel model = new RecordingModel();

    private static ClickVerb click(String name) {
        ClickVerb c = new ClickVerb();
        c.target = Locator.of("button", name);
        return c;
    }

    private static ExpectVerb check(String name) {
        ExpectVerb e = new ExpectVerb();
        e.target = Locator.of("label", name);
        e.text = "ok";
        return e;
    }

    private static StepAddress at(String label) {
        return StepAddress.parse(TextNode.valueOf(label));
    }

    private void three() {
        model.append(click("a"), AGENT);
        model.append(click("b"), PERSON);
        model.append(click("c"), AGENT);
    }

    /** a, b, c with a check under b: steps a, b (k1), c. */
    private void withCheck() {
        three();
        model.insert(at("2.1"), check("k1"), AGENT);
    }

    /** Steps a b c d e, no checks. */
    private void five() {
        for (String n : new String[] {"a", "b", "c", "d", "e"}) model.append(click(n), AGENT);
    }

    /** The flat records by name; checks start with a question mark. */
    private List<String> names() {
        List<String> out = new ArrayList<String>();
        for (RecordedStep st : model.records()) out.add(name(st));
        return out;
    }

    private static String name(RecordedStep st) {
        return st.action instanceof ExpectVerb ? "?" + ((ExpectVerb) st.action).target.name : ((ClickVerb) st.action).target.name;
    }

    /** The group sentence of the record called {@code name}, null for none. */
    private String groupOf(String name) {
        for (RecordedStep st : model.records()) if (name(st).equals(name)) return st.group == null ? null : st.group.say;
        throw new AssertionError("no record " + name);
    }

    private RecordedStep record(String name) {
        for (RecordedStep st : model.records()) if (name(st).equals(name)) return st;
        throw new AssertionError("no record " + name);
    }

    // ------------------------------------------------------------------ the old behaviour, in steps

    @Test
    void idsAreNeverReusedAfterARemove() {
        three();
        model.deleteSteps(3, 3, AGENT);
        assertThat(model.append(click("d"), AGENT).id).isEqualTo(4);
        model.clear();
        assertThat(model.append(click("e"), AGENT).id).isEqualTo(5);
    }

    @Test
    void everyChangeMovesTheRevision() {
        int rev = model.rev();
        model.append(click("a"), AGENT);
        assertThat(model.rev()).isGreaterThan(rev);
        rev = model.rev();
        model.append(click("b"), AGENT);
        model.cutFromStep(2, PERSON);
        assertThat(model.rev()).isGreaterThan(rev + 1);
        rev = model.rev();
        model.deleteSteps(1, 1, PERSON);
        assertThat(model.rev()).isGreaterThan(rev);
        rev = model.rev();
        model.load(Collections.<Action>singletonList(click("c")), RecordedStep.Author.LOADED);
        assertThat(model.rev()).isGreaterThan(rev);
        rev = model.rev();
        model.clear();
        assertThat(model.rev()).isGreaterThan(rev);
    }

    @Test
    void aPositionOutOfRangeNamesTheRange() {
        assertThat(catchThrowableOfType(() -> model.at(StepAddress.ofStep(1)), DriverException.class).getMessage())
                .contains("no step 1").contains("the recording is empty");
        three();
        DriverException zero = catchThrowableOfType(() -> model.at(StepAddress.ofStep(0)), DriverException.class);
        assertThat(zero.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(zero.getMessage()).contains("the recording has 3 steps (1-3)");
        assertThat(catchThrowableOfType(() -> model.at(StepAddress.ofStep(4)), DriverException.class).getMessage())
                .contains("no step 4").contains("the recording has 3 steps (1-3)");
    }

    @Test
    void addressOfFollowsTheStepAfterAnotherIsRemoved() {
        three();
        assertThat(model.addressOf(3)).isEqualTo(StepAddress.ofStep(3));
        model.deleteSteps(1, 1, AGENT);
        assertThat(model.addressOf(3)).isEqualTo(StepAddress.ofStep(2));
        assertThat(model.addressOf(1)).isNull();
        assertThat(model.at(StepAddress.ofStep(1)).author).isEqualTo(PERSON);
    }

    @Test
    void undoRestoresEachKindOfEdit() {
        three();
        model.insert(StepAddress.ofStep(2), click("x"), AGENT);
        assertThat(names()).containsExactly("a", "x", "b", "c");
        assertThat(model.undoLabel()).isEqualTo("insert at step 2 by the agent");
        assertThat(model.undo(AGENT)).isEqualTo("undid the insert at step 2 by the agent");
        assertThat(names()).containsExactly("a", "b", "c");

        assertThat(model.deleteSteps(1, 2, PERSON)).isEqualTo("deleted steps 1-2");
        assertThat(names()).containsExactly("c");
        assertThat(model.undo(AGENT)).isEqualTo("undid the delete of steps 1-2 by the person");
        assertThat(names()).containsExactly("a", "b", "c");

        assertThat(model.moveStep(3, 1, AGENT)).isEqualTo("moved step 3 to 1");
        assertThat(names()).containsExactly("c", "a", "b");
        model.undo(AGENT);
        assertThat(names()).containsExactly("a", "b", "c");

        model.replace(StepAddress.ofStep(2), click("y"), AGENT);
        assertThat(model.at(StepAddress.ofStep(2)).edited).isTrue();
        assertThat(model.at(StepAddress.ofStep(2)).id).isEqualTo(2);
        model.undo(AGENT);
        assertThat(names()).containsExactly("a", "b", "c");
        assertThat(model.at(StepAddress.ofStep(2)).edited).isFalse();
        assertThat(model.undo(AGENT)).isNull();
        assertThat(model.undoLabel()).isNull();
    }

    @Test
    void anUndoSaysWhoUndidIt() {
        three();
        model.deleteSteps(2, 2, PERSON);
        assertThat(model.undo(AGENT)).isEqualTo("undid the delete of step 2 by the person");
        assertThat(model.lastChange()).isEqualTo("the agent undid the delete of step 2 by the person");
        model.deleteSteps(1, 1, AGENT);
        model.undo(PERSON);
        assertThat(model.lastChange()).isEqualTo("the person undid the delete of step 1 by the agent");
    }

    @Test
    void theUndoHistoryKeepsTheLast50() {
        for (int i = 0; i < 60; i++) model.append(click("s" + i), AGENT);
        for (int i = 0; i < 55; i++) model.deleteSteps(1, 1, AGENT);
        assertThat(model.steps()).isEqualTo(5);
        int undone = 0;
        while (model.undo(AGENT) != null) undone++;
        assertThat(undone).isEqualTo(50);
        assertThat(model.steps()).isEqualTo(55);
    }

    @Test
    void aCutUndoneDropsWhatWasRecordedAfterIt() {
        three();
        assertThat(model.cutFromStep(2, PERSON)).isEqualTo("cut from step 2");
        model.append(click("d"), PERSON);
        model.append(click("e"), PERSON);
        assertThat(model.undo(AGENT)).contains("undid the cut from step 2 (redo) by the person")
                .contains("2 steps recorded after it dropped");
        assertThat(names()).containsExactly("a", "b", "c");
        assertThat(model.cutFromStep(9, PERSON)).isNull();
    }

    @Test
    void anUndoneDeleteKeepsStepsRecordedSince() {
        three();
        model.deleteSteps(2, 2, AGENT);
        model.append(click("d"), AGENT);
        model.undo(AGENT);
        assertThat(names()).containsExactly("a", "b", "c", "d");
    }

    @Test
    void editedClearsWhenReplayed() {
        three();
        model.insert(StepAddress.ofStep(1), click("x"), AGENT);
        int id = model.at(StepAddress.ofStep(1)).id;
        assertThat(model.at(StepAddress.ofStep(1)).edited).isTrue();
        int rev = model.rev();
        model.markReplayed(id);
        assertThat(model.at(StepAddress.ofStep(1)).edited).isFalse();
        assertThat(model.rev()).isGreaterThan(rev);
    }

    @Test
    void startClearAndLoadEmptyTheHistory() {
        three();
        model.deleteSteps(1, 1, AGENT);
        model.start(AGENT, false, false, 1L);
        assertThat(model.undo(AGENT)).isNull();
        three();
        model.deleteSteps(1, 1, AGENT);
        model.load(Collections.<Action>singletonList(click("z")), RecordedStep.Author.LOADED);
        assertThat(model.undo(AGENT)).isNull();
        model.deleteSteps(1, 1, AGENT);
        model.clear();
        assertThat(model.undo(AGENT)).isNull();
    }

    @Test
    void outOfRangeEditsSayWhatThereIs() {
        three();
        assertThat(catchThrowableOfType(() -> model.insert(StepAddress.ofStep(5), click("x"), AGENT),
                DriverException.class).getMessage()).contains("1-4");
        assertThat(catchThrowableOfType(() -> model.deleteSteps(2, 9, AGENT),
                DriverException.class).reason()).isEqualTo(Reason.BAD_INPUT);
    }

    // ------------------------------------------------------------------ steps and checks

    @Test
    void deletingAStepDeletesItsChecks() {
        withCheck();
        model.insert(at("2.2"), check("k2"), AGENT);
        assertThat(model.deleteSteps(2, 2, AGENT)).isEqualTo("deleted step 2 and its 2 checks");
        assertThat(names()).containsExactly("a", "c");
        model.insert(at("1.1"), check("m"), AGENT);
        assertThat(model.deleteSteps(1, 1, AGENT)).isEqualTo("deleted step 1 and its 1 check");
        assertThat(names()).containsExactly("c");
    }

    @Test
    void aCheckCanBeDeletedAlone() {
        withCheck();
        model.insert(at("2.2"), check("k2"), AGENT);
        assertThat(model.deleteCheck(2, 1, AGENT)).isEqualTo("deleted check 2.1");
        assertThat(names()).containsExactly("a", "b", "?k2", "c");
        assertThat(model.steps()).isEqualTo(3);
        assertThat(catchThrowableOfType(() -> model.deleteCheck(2, 2, AGENT), DriverException.class).getMessage())
                .contains("no check 2.2");
    }

    @Test
    void movingAStepMovesItsChecks() {
        withCheck();
        assertThat(model.moveStep(2, 3, AGENT)).isEqualTo("moved step 2 to 3");
        assertThat(names()).containsExactly("a", "c", "b", "?k1");
        model.moveStep(3, 1, AGENT);
        assertThat(names()).containsExactly("b", "?k1", "a", "c");
        model.undo(AGENT);
        assertThat(names()).containsExactly("a", "c", "b", "?k1");
    }

    @Test
    void aCutFromAStepTakesItsChecks() {
        withCheck();
        model.insert(at("3.1"), check("k3"), AGENT);
        assertThat(model.cutFromStep(2, AGENT)).isEqualTo("cut from step 2");
        assertThat(names()).containsExactly("a");
    }

    @Test
    void editKeepsTheKind() {
        withCheck();
        assertThat(model.replace(at("2.1"), check("k9"), AGENT)).isEqualTo("edited check 2.1");
        assertThat(model.at(at("2.1")).edited).isTrue();
        assertThat(model.replace(StepAddress.ofStep(2), click("y"), AGENT)).isEqualTo("edited step 2");
        DriverException e = catchThrowableOfType(() -> model.replace(at("2.1"), click("x"), AGENT), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("check 2.1").contains("a check");
        assertThat(catchThrowableOfType(() -> model.replace(StepAddress.ofStep(2), check("x"), AGENT),
                DriverException.class).getMessage()).contains("step 2").contains("an action");
        assertThat(names()).containsExactly("a", "y", "?k9", "c");
    }

    @Test
    void aCheckGoesUnderAStepNotAtAStepPosition() {
        three();
        DriverException e = catchThrowableOfType(() -> model.insert(StepAddress.ofStep(2), check("k"), AGENT),
                DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("a check goes under a step").contains("\"2.1\"");
        assertThat(model.insert(at("2.1"), check("k"), AGENT)).isEqualTo("inserted check 2.1");
        assertThat(names()).containsExactly("a", "b", "?k", "c");
        // k is one past the checks, no more
        assertThat(catchThrowableOfType(() -> model.insert(at("2.3"), check("z"), AGENT), DriverException.class)
                .getMessage()).contains("2.3");
        // an action does not go at a check label
        assertThat(catchThrowableOfType(() -> model.insert(at("2.1"), click("z"), AGENT), DriverException.class)
                .getMessage()).contains("step position");
        model.insert(at("2.1"), check("first"), AGENT);
        assertThat(names()).containsExactly("a", "b", "?first", "?k", "c");
    }

    @Test
    void aCheckInsertedAt1MakesTheCheckOnlyStep() {
        three();
        assertThat(model.insert(StepAddress.ofStep(1), check("k"), AGENT)).isEqualTo("inserted check 1.1");
        assertThat(names()).containsExactly("?k", "a", "b", "c");
        assertThat(model.steps()).isEqualTo(4);
        // step 1 is check-only now: a plain position 1 is not a place for another check
        assertThat(catchThrowableOfType(() -> model.insert(StepAddress.ofStep(1), check("k2"), AGENT),
                DriverException.class).getMessage()).contains("\"1.2\"");
        model.insert(at("1.2"), check("k2"), AGENT);
        assertThat(names()).containsExactly("?k", "?k2", "a", "b", "c");
        // an empty recording takes a check-only step too
        RecordingModel empty = new RecordingModel();
        empty.insert(StepAddress.ofStep(1), check("k"), AGENT);
        assertThat(empty.steps()).isEqualTo(1);
    }

    @Test
    void nothingGoesBeforeACheckOnlyStep() {
        three();
        model.insert(StepAddress.ofStep(1), check("k"), AGENT);
        DriverException ins = catchThrowableOfType(() -> model.insert(StepAddress.ofStep(1), click("x"), AGENT),
                DriverException.class);
        assertThat(ins.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(ins.getMessage()).contains("step 1 holds only checks, which run before any action: insert at 2, or delete them first");
        assertThat(catchThrowableOfType(() -> model.moveStep(3, 1, AGENT), DriverException.class).getMessage())
                .contains("step 1 holds only checks");
        assertThat(catchThrowableOfType(() -> model.moveStep(1, 3, AGENT), DriverException.class).getMessage())
                .contains("check-only");
        model.insert(StepAddress.ofStep(2), click("x"), AGENT);
        assertThat(names()).containsExactly("?k", "x", "a", "b", "c");
    }

    @Test
    void appendedChecksJoinTheLastStep() {
        three();
        RecordedStep k = model.append(check("k"), AGENT);
        assertThat(model.addressOf(k.id)).isEqualTo(at("3.1"));
        model.append(check("k2"), AGENT);
        assertThat(names()).containsExactly("a", "b", "c", "?k", "?k2");
        assertThat(model.steps()).isEqualTo(3);
        RecordingModel empty = new RecordingModel();
        RecordedStep first = empty.append(check("k"), AGENT);
        assertThat(empty.addressOf(first.id)).isEqualTo(at("1.1"));
    }

    @Test
    void undoBringsChecksBackUnderTheirStep() {
        withCheck();
        model.deleteSteps(2, 2, AGENT);
        assertThat(names()).containsExactly("a", "c");
        model.undo(AGENT);
        assertThat(names()).containsExactly("a", "b", "?k1", "c");
        assertThat(model.addressOf(record("?k1").id)).isEqualTo(at("2.1"));
        model.deleteCheck(2, 1, AGENT);
        model.undo(AGENT);
        assertThat(names()).containsExactly("a", "b", "?k1", "c");
    }

    // ------------------------------------------------------------------ groups

    @Test
    void aStepPlacedBetweenTwoMembersJoinsTheGroup() {
        five();
        model.group(2, 4, "middle", AGENT);
        model.insert(StepAddress.ofStep(3), click("x"), AGENT);
        assertThat(groupOf("x")).isEqualTo("middle");
        // a moved outsider too
        model.moveStep(1, 3, AGENT);
        assertThat(names()).containsExactly("b", "x", "a", "c", "d", "e");
        assertThat(groupOf("a")).isEqualTo("middle");
    }

    @Test
    void aStepPlacedAtTheEdgeStaysOutside() {
        five();
        model.group(2, 4, "middle", AGENT);
        model.insert(StepAddress.ofStep(2), click("x"), AGENT);
        assertThat(groupOf("x")).isNull();
        model.insert(StepAddress.ofStep(6), click("y"), AGENT);
        assertThat(names()).containsExactly("a", "x", "b", "c", "d", "y", "e");
        assertThat(groupOf("y")).isNull();
        assertThat(groupOf("b")).isEqualTo("middle");
    }

    @Test
    void aMemberMovedToTheEdgeOfItsOwnGroupStaysIn() {
        five();
        model.group(2, 4, "middle", AGENT);
        model.moveStep(3, 4, AGENT);
        assertThat(names()).containsExactly("a", "b", "d", "c", "e");
        assertThat(groupOf("c")).isEqualTo("middle");
        model.moveStep(2, 4, AGENT);
        assertThat(groupOf("b")).isEqualTo("middle");
        // out of the group, next to the outsider only: no longer a member (and the group shrinks)
        model.moveStep(4, 1, AGENT);
        assertThat(names()).containsExactly("b", "a", "d", "c", "e");
        assertThat(groupOf("b")).isNull();
        assertThat(groupOf("d")).isEqualTo("middle");
    }

    @Test
    void aCheckTakesItsStepsGroup() {
        five();
        model.group(2, 4, "middle", AGENT);
        model.insert(at("3.1"), check("k"), AGENT);
        assertThat(groupOf("?k")).isEqualTo("middle");
        // the check-only step at 1 takes no group
        model.insert(StepAddress.ofStep(1), check("m"), AGENT);
        assertThat(groupOf("?m")).isNull();
        // a move carries the step's checks with it
        model.moveStep(4, 5, AGENT);
        assertThat(groupOf("?k")).isEqualTo("middle");
    }

    @Test
    void appendedStepsNeverJoin() {
        five();
        model.group(4, 5, "tail", AGENT);
        model.append(click("f"), AGENT);
        assertThat(groupOf("f")).isNull();
        model.append(check("k"), AGENT);
        assertThat(groupOf("?k")).isNull();
        RecordedStep inside = model.append(click("g"), AGENT);
        assertThat(inside.group).isNull();
        // an appended check under a grouped last step takes the group
        RecordingModel m = new RecordingModel();
        m.append(click("a"), AGENT);
        m.append(click("b"), AGENT);
        m.group(1, 2, "pair", AGENT);
        RecordedStep k = m.append(check("k"), AGENT);
        assertThat(k.group).isNotNull();
        assertThat(k.group.say).isEqualTo("pair");
    }

    @Test
    void aCutInsideAGroupShrinksIt() {
        five();
        model.group(2, 4, "middle", AGENT);
        model.cutFromStep(4, AGENT);
        assertThat(names()).containsExactly("a", "b", "c");
        assertThat(groupOf("b")).isEqualTo("middle");
        assertThat(groupOf("c")).isEqualTo("middle");
    }

    @Test
    void deletingEveryMemberEndsTheGroup() {
        five();
        model.group(2, 3, "pair", AGENT);
        String did = model.deleteSteps(2, 3, AGENT);
        assertThat(did).isEqualTo("deleted steps 2-3");
        assertThat(model.flow().groups).isEmpty();
        assertThat(names()).containsExactly("a", "d", "e");
    }

    @Test
    void aGroupLeftWithOneStepEndsAndKeepsItsSentence() {
        five();
        model.group(2, 3, "pair", AGENT);
        String did = model.deleteSteps(3, 3, AGENT);
        assertThat(did).startsWith("deleted step 3").contains("pair").contains("ended").contains("sentence");
        assertThat(model.flow().groups).isEmpty();
        assertThat(record("b").group).isNull();
        assertThat(record("b").action.say).isEqualTo("pair");
        // not an edit by the person: the step is not marked
        assertThat(record("b").edited).isFalse();

        // a step with its own sentence keeps it
        RecordingModel m = new RecordingModel();
        m.append(click("a"), AGENT);
        m.append(click("b"), AGENT);
        m.append(click("c"), AGENT);
        m.say(StepAddress.ofStep(2), "My own", AGENT);
        m.group(2, 3, "pair", AGENT);
        String did2 = m.deleteSteps(3, 3, AGENT);
        assertThat(did2).contains("keeps its own");
        assertThat(m.at(StepAddress.ofStep(2)).action.say).isEqualTo("My own");

        // a check-only step drops it
        RecordingModel c = new RecordingModel();
        c.append(check("k"), AGENT);
        c.append(click("a"), AGENT);
        c.append(click("b"), AGENT);
        c.group(1, 2, "first", AGENT);
        String did3 = c.deleteSteps(2, 2, AGENT);
        assertThat(did3).contains("dropped");
        assertThat(c.flow().groups).isEmpty();
        assertThat(c.records().get(0).action.say).isNull();
    }

    @Test
    void aMoveThatLeavesOneStepEndsTheGroupToo() {
        five();
        model.group(2, 3, "pair", AGENT);
        String did = model.moveStep(3, 5, AGENT);
        assertThat(did).startsWith("moved step 3 to 5").contains("ended");
        assertThat(groupOf("b")).isNull();
        assertThat(record("b").action.say).isEqualTo("pair");
    }

    @Test
    void groupsNeverNestOrOverlap() {
        five();
        model.group(2, 4, "outer", AGENT);
        assertThat(catchThrowableOfType(() -> model.group(1, 1, "one", AGENT), DriverException.class).getMessage())
                .contains("two steps");
        assertThat(catchThrowableOfType(() -> model.group(3, 4, "inner", AGENT), DriverException.class).getMessage())
                .contains("do not nest");
        assertThat(catchThrowableOfType(() -> model.group(1, 5, "all", AGENT), DriverException.class).getMessage())
                .contains("do not nest");
        DriverException overlap = catchThrowableOfType(() -> model.group(4, 5, "tail", AGENT), DriverException.class);
        assertThat(overlap.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(overlap.getMessage()).contains("overlap");
        assertThat(catchThrowableOfType(() -> model.group(2, 9, "x", AGENT), DriverException.class).getMessage())
                .contains("no step 9");
        // the same range again renames
        model.group(2, 4, "renamed", AGENT);
        assertThat(groupOf("c")).isEqualTo("renamed");
        assertThat(model.flow().groups).hasSize(1);
        assertThat(catchThrowableOfType(() -> model.group(2, 4, " ", AGENT), DriverException.class).getMessage())
                .contains("not empty");
    }

    @Test
    void ungroupKeepsTheSteps() {
        five();
        model.group(2, 4, "middle", AGENT);
        assertThat(model.ungroup(3, AGENT)).isEqualTo("ungrouped steps 2-4");
        assertThat(names()).containsExactly("a", "b", "c", "d", "e");
        assertThat(groupOf("c")).isNull();
        assertThat(catchThrowableOfType(() -> model.ungroup(3, AGENT), DriverException.class).getMessage())
                .contains("step 3 is in no group");
        model.undo(AGENT);
        assertThat(groupOf("c")).isEqualTo("middle");
        assertThat(model.undoLabel()).isEqualTo("group of steps 2-4 by the agent");
    }

    // ------------------------------------------------------------------ sentences and the rest

    @Test
    void sayCopiesTheActionSoUndoGetsTheOldSentenceBack() {
        three();
        Action original = model.at(StepAddress.ofStep(2)).action;
        assertThat(model.say(StepAddress.ofStep(2), "Open the order", AGENT)).isEqualTo("set the sentence of step 2");
        assertThat(original.say).isNull();
        assertThat(model.at(StepAddress.ofStep(2)).action.say).isEqualTo("Open the order");
        assertThat(model.at(StepAddress.ofStep(2)).action).isNotSameAs(original);
        model.say(StepAddress.ofStep(2), "Open it", PERSON);
        assertThat(model.undoLabel()).isEqualTo("sentence of step 2 by the person");
        model.undo(AGENT);
        assertThat(model.at(StepAddress.ofStep(2)).action.say).isEqualTo("Open the order");
        model.undo(AGENT);
        assertThat(model.at(StepAddress.ofStep(2)).action.say).isNull();
        assertThat(model.at(StepAddress.ofStep(2)).action).isSameAs(original);
        // an empty sentence removes it
        model.say(StepAddress.ofStep(2), "x", AGENT);
        assertThat(model.say(StepAddress.ofStep(2), "", AGENT)).isEqualTo("removed the sentence of step 2");
        assertThat(model.at(StepAddress.ofStep(2)).action.say).isNull();
        // a check has its own sentence
        withCheckAfterThree();
        assertThat(model.say(at("2.1"), "Total is right", AGENT)).isEqualTo("set the sentence of check 2.1");
        assertThat(model.undoLabel()).isEqualTo("sentence of check 2.1 by the agent");
        // bad sentences are refused
        assertThat(catchThrowableOfType(() -> model.say(StepAddress.ofStep(1), "a\nb", AGENT), DriverException.class)
                .reason()).isEqualTo(Reason.BAD_INPUT);
    }

    private void withCheckAfterThree() {
        model.insert(at("2.1"), check("k1"), AGENT);
    }

    @Test
    void aCheckOnlyStepHasNoSentenceOfItsOwn() {
        three();
        model.insert(StepAddress.ofStep(1), check("k"), AGENT);
        assertThat(catchThrowableOfType(() -> model.say(StepAddress.ofStep(1), "x", AGENT), DriverException.class)
                .getMessage()).contains("holds only checks");
        model.say(at("1.1"), "The cart is empty", AGENT);
        assertThat(model.at(at("1.1")).action.say).isEqualTo("The cart is empty");
    }

    @Test
    void sentenceAndGroupEditsDoNotMarkTheStepEdited() {
        five();
        model.say(StepAddress.ofStep(2), "x", AGENT);
        model.group(2, 3, "pair", AGENT);
        model.group(2, 3, "renamed", AGENT);
        model.ungroup(2, AGENT);
        for (RecordedStep st : model.records()) assertThat(st.edited).as(name(st)).isFalse();
        model.replace(StepAddress.ofStep(2), click("y"), AGENT);
        assertThat(record("y").edited).isTrue();
        // a say on an edited step keeps the mark
        model.say(StepAddress.ofStep(2), "z", AGENT);
        assertThat(record("y").edited).isTrue();
    }

    @Test
    void undoRestoresGroupsAndSentences() {
        five();
        model.say(StepAddress.ofStep(1), "Start", AGENT);
        model.group(2, 4, "middle", AGENT);
        model.deleteSteps(3, 4, AGENT);   // the group is left with one step and ends
        assertThat(groupOf("b")).isNull();
        assertThat(record("b").action.say).isEqualTo("middle");
        assertThat(model.undo(AGENT)).startsWith("undid the delete of steps 3-4");
        assertThat(groupOf("b")).isEqualTo("middle");
        assertThat(groupOf("c")).isEqualTo("middle");
        assertThat(groupOf("d")).isEqualTo("middle");
        assertThat(record("b").action.say).isNull();
        assertThat(record("a").action.say).isEqualTo("Start");
        model.undo(AGENT);
        assertThat(groupOf("c")).isNull();
        // steps appended after the edit that is undone never join a group
        model.group(2, 4, "middle", AGENT);
        model.append(check("k"), AGENT);
        model.append(click("f"), AGENT);
        model.undo(AGENT);
        assertThat(names()).containsExactly("a", "b", "c", "d", "e", "?k", "f");
        assertThat(groupOf("c")).isNull();
        assertThat(groupOf("f")).isNull();
    }

    @Test
    void theFlatViewHasStepsAndGroupRanges() {
        five();
        model.insert(at("2.1"), check("k"), AGENT);
        model.insert(at("2.2"), check("k2"), AGENT);
        model.group(2, 3, "pair", AGENT);
        model.group(4, 5, "tail", AGENT);
        Flow flow = model.flow();
        assertThat(flow.steps).hasSize(7);
        assertThat(flow.steps.get(2)).isInstanceOf(ExpectVerb.class);
        assertThat(flow.groups).hasSize(2);
        assertThat(flow.groups.get(0).from).isEqualTo(1);
        assertThat(flow.groups.get(0).to).isEqualTo(4);
        assertThat(flow.groups.get(0).say).isEqualTo("pair");
        assertThat(flow.groups.get(1).from).isEqualTo(5);
        assertThat(flow.groups.get(1).to).isEqualTo(6);
        assertThat(model.undoPreview().steps).hasSize(7);
        assertThat(model.undoPreview().groups).hasSize(1);
    }

    @Test
    void loadTakesGroups() {
        Flow flow = new Flow();
        flow.steps.add(check("k0"));
        flow.steps.add(click("a"));
        flow.steps.add(click("b"));
        flow.steps.add(check("k1"));
        flow.steps.add(click("c"));
        flow.groups.add(new FlowGroup(1, 3, "first two"));
        model.load(flow, RecordedStep.Author.LOADED);
        assertThat(names()).containsExactly("?k0", "a", "b", "?k1", "c");
        assertThat(groupOf("a")).isEqualTo("first two");
        assertThat(groupOf("?k1")).isEqualTo("first two");
        assertThat(groupOf("c")).isNull();
        assertThat(groupOf("?k0")).isNull();
        assertThat(model.records().get(1).author).isEqualTo(RecordedStep.Author.LOADED);
        assertThat(model.undo(AGENT)).isNull();
        Flow back = model.flow();
        assertThat(back.steps).hasSize(5);
        assertThat(back.groups).hasSize(1);
        assertThat(back.groups.get(0).from).isEqualTo(1);
        assertThat(back.groups.get(0).to).isEqualTo(3);
    }

    @Test
    void rowKnownChangesNoRevisionWhenTheSame() {
        three();
        int id = model.at(StepAddress.ofStep(2)).id;
        RowChoice choice = new RowChoice(2, Collections.<String, Object>singletonMap("Product", "Gel pen, black"));
        int rev = model.rev();
        model.rowKnown(id, choice);
        assertThat(model.rev()).isGreaterThan(rev);
        assertThat(model.at(StepAddress.ofStep(2)).row).isEqualTo(choice);
        rev = model.rev();
        model.rowKnown(id, new RowChoice(2, Collections.<String, Object>singletonMap("Product", "Gel pen, black")));
        assertThat(model.rev()).isEqualTo(rev);
        model.rowKnown(id + 100, choice);
        assertThat(model.rev()).isEqualTo(rev);
        assertThat(choice.byPosition()).isEqualTo(Collections.singletonMap("index", 2));
        assertThat(choice.byValue()).containsEntry("Product", "Gel pen, black");
    }

    @Test
    void aRowEditMarksTheStepAndSaysWhich() {
        three();
        ClickVerb rewritten = click("b");
        assertThat(model.row(StepAddress.ofStep(2), rewritten, AGENT)).isEqualTo("changed the row of step 2");
        assertThat(model.at(StepAddress.ofStep(2)).edited).isTrue();
        assertThat(model.undoLabel()).startsWith("row of step 2 by ");
    }

    @Test
    void didTextsUseLabels() {
        withCheck();
        assertThat(model.insert(at("2.2"), check("k2"), AGENT)).isEqualTo("inserted check 2.2");
        assertThat(model.deleteCheck(2, 2, AGENT)).isEqualTo("deleted check 2.2");
        assertThat(model.deleteSteps(1, 2, AGENT)).isEqualTo("deleted steps 1-2 and their 1 check");
        assertThat(model.undoLabel()).isEqualTo("delete of steps 1-2 by the agent");
        assertThat(model.insert(StepAddress.ofStep(1), click("x"), AGENT)).isEqualTo("inserted at step 1");
        assertThat(model.undoLabel()).isEqualTo("insert at step 1 by the agent");
        model.insert(at("1.1"), check("m"), AGENT);
        assertThat(model.undoLabel()).isEqualTo("insert of check 1.1 by the agent");
        assertThat(model.cutFromStep(1, AGENT)).isEqualTo("cut from step 1");
    }

    @Test
    void aCopyHoldsTheSameStepsAndGroupsAndEditsOnItLeaveTheOriginal() {
        five();
        model.group(2, 3, "Two", AGENT);
        RecordingModel copy = model.copy();
        assertThat(copy.flow().steps).hasSize(5);
        assertThat(copy.flow().groups).hasSize(1);
        copy.deleteSteps(1, 5, AGENT);
        assertThat(copy.size()).isZero();
        assertThat(model.size()).isEqualTo(5);
    }

    @Test
    void theFlatRangeOfStepsIncludesTheirChecksAndRefusesWhatIsNoStep() {
        withCheck();
        assertThat(model.flatRange(2, 2)).containsExactly(1, 2);
        assertThat(model.flatRange(1, 3)).containsExactly(0, 3);
        assertThat(catchThrowableOfType(() -> model.flatRange(1, 4), DriverException.class).getMessage()).contains("no step 4");
        assertThat(catchThrowableOfType(() -> model.flatRange(3, 2), DriverException.class).getMessage()).contains("before");
    }

    @Test
    void deleteTakesAStepWithItsChecksOrACheckAlone() {
        withCheck();
        assertThat(model.delete(at("2.1"), AGENT)).isEqualTo("deleted check 2.1");
        model.insert(at("2.1"), check("k2"), AGENT);
        assertThat(model.delete(at("2"), AGENT)).isEqualTo("deleted step 2 and its 1 check");
        assertThat(names()).containsExactly("a", "c");
    }

    @Test
    void aReplayedCheckIsNamedACheckInTheChangeText() {
        withCheck();
        int id = model.records().get(2).id;   // the check under step 2
        assertThat(model.markReplayed(id)).isTrue();
        assertThat(model.lastChange()).isEqualTo("check 2.1 passed a replay");
        model.rowKnown(id, new RowChoice(0, null));
        assertThat(model.lastChange()).isEqualTo("the row alternatives of check 2.1 are known");
    }
}
