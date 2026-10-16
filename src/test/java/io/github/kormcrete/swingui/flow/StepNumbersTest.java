package io.github.kormcrete.swingui.flow;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The step structure derived from the flat list: each action starts a step, checks belong to the step before. */
class StepNumbersTest {

    private static final String CLICK = "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Save\"}}";
    private static final String EXPECT = "{\"action\":\"expect\",\"target\":{\"role\":\"label\",\"name\":\"Total\"},\"text\":\"1\"}";
    private static final String MENU = "{\"action\":\"menu\",\"path\":[\"Orders\",\"New order\"]}";
    private static final String TYPE = "{\"action\":\"type\",\"target\":{\"role\":\"text\",\"name\":\"Customer\"},\"text\":\"Ada\"}";
    private static final String SHOT = "{\"action\":\"screenshot\",\"path\":\"a.png\"}";

    private static List<Action> flat(String... json) throws Exception {
        List<Action> out = new ArrayList<Action>();
        for (String j : json) out.add(FlowJson.mapper().readValue(j, Action.class));
        return out;
    }

    @Test
    void eachActionStartsAStepAndChecksFollowIt() throws Exception {
        List<Action> f = flat(CLICK, EXPECT, EXPECT, MENU, TYPE, EXPECT);

        assertThat(StepNumbers.labels(f)).containsExactly("1", "1.1", "1.2", "2", "3", "3.1");
        List<StepNumbers.Step> steps = StepNumbers.of(f);
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0).number).isEqualTo(1);
        assertThat(steps.get(0).action).isEqualTo(0);
        assertThat(steps.get(0).checks).containsExactly(1, 2);
        assertThat(steps.get(0).first()).isEqualTo(0);
        assertThat(steps.get(0).last()).isEqualTo(2);
        assertThat(steps.get(0).checkOnly()).isFalse();
        assertThat(steps.get(1).checks).isEmpty();
        assertThat(steps.get(1).last()).isEqualTo(3);
        assertThat(steps.get(2).number).isEqualTo(3);
        assertThat(steps.get(2).checks).containsExactly(5);
        assertThat(StepNumbers.isCheck(f.get(1))).isTrue();
        assertThat(StepNumbers.isCheck(f.get(0))).isFalse();
        assertThat(StepNumbers.label(f, 5)).isEqualTo("3.1");
    }

    private static final String DO_ACT = "{\"action\":\"do\",\"step\":\"order-desk:addToCart\",\"params\":{\"product\":\"Gel pen, black\"}}";
    private static final String DO_CHECK = "{\"action\":\"do\",\"step\":\"order-desk:cartTotalIs\",\"params\":{\"total\":\"4.99\"},\"check\":true}";

    @Test
    void aDoCheckIsNumberedAsACheck() throws Exception {
        List<Action> f = flat(CLICK, DO_CHECK, EXPECT, DO_ACT, DO_CHECK);

        assertThat(StepNumbers.labels(f)).containsExactly("1", "1.1", "1.2", "2", "2.1");
        assertThat(StepNumbers.isCheck(f.get(1))).isTrue();
        assertThat(StepNumbers.isCheck(f.get(3))).isFalse();
        assertThat(StepNumbers.of(flat(DO_CHECK, DO_ACT)).get(0).checkOnly()).isTrue();
        DoAction unmarked = (DoAction) flat(DO_CHECK).get(0);
        unmarked.check = false;
        assertThat(StepNumbers.isCheck(unmarked)).isFalse();
    }

    @Test
    void leadingChecksAreStepOne() throws Exception {
        List<Action> f = flat(EXPECT, EXPECT, CLICK, EXPECT);

        assertThat(StepNumbers.labels(f)).containsExactly("1.1", "1.2", "2", "2.1");
        StepNumbers.Step first = StepNumbers.of(f).get(0);
        assertThat(first.checkOnly()).isTrue();
        assertThat(first.action).isEqualTo(-1);
        assertThat(first.checks).containsExactly(0, 1);
        assertThat(first.first()).isEqualTo(0);
        assertThat(first.last()).isEqualTo(1);
        assertThat(StepNumbers.of(f).get(1).number).isEqualTo(2);
    }

    @Test
    void flatIndexNamesWhatIsMissing() throws Exception {
        List<Action> f = flat(CLICK, EXPECT, EXPECT, MENU);

        assertThat(StepNumbers.flatIndex(f, 1, 0)).isEqualTo(0);
        assertThat(StepNumbers.flatIndex(f, 1, 2)).isEqualTo(2);
        assertThat(StepNumbers.flatIndex(f, 2, 0)).isEqualTo(3);
        assertBadInput(() -> StepNumbers.flatIndex(f, 9, 0), "no step 9: the recording has 2 steps");
        assertBadInput(() -> StepNumbers.flatIndex(f, 0, 0), "no step 0: the recording has 2 steps");
        assertBadInput(() -> StepNumbers.flatIndex(f, 1, 3), "no check 1.3: step 1 has 2 checks");
        assertBadInput(() -> StepNumbers.flatIndex(f, 2, 1), "no check 2.1: step 2 has no checks");
        assertBadInput(() -> StepNumbers.flatIndex(new ArrayList<Action>(), 1, 0), "no step 1: the recording has no steps");
    }

    @Test
    void aCheckOnlyStepHasNoActionToAddress() throws Exception {
        List<Action> f = flat(EXPECT, CLICK);

        assertThat(StepNumbers.flatIndex(f, 1, 1)).isEqualTo(0);
        assertBadInput(() -> StepNumbers.flatIndex(f, 1, 0), "step 1 holds only checks");
    }

    @Test
    void aScreenshotIsAnActionStep() throws Exception {
        List<Action> f = flat(CLICK, EXPECT, SHOT, EXPECT);

        assertThat(StepNumbers.labels(f)).containsExactly("1", "1.1", "2", "2.1");
        assertThat(StepNumbers.isCheck(f.get(2))).isFalse();
    }

    @Test
    void anEmptyListHasNoSteps() {
        assertThat(StepNumbers.of(new ArrayList<Action>())).isEmpty();
        assertThat(StepNumbers.labels(new ArrayList<Action>())).isEmpty();
    }

    private static void assertBadInput(Runnable r, String message) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(DriverException.class, e -> {
            assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
            assertThat(e.getMessage()).contains(message);
        });
    }
}
