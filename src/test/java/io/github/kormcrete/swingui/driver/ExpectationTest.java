package io.github.kormcrete.swingui.driver;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ExpectationTest {

    @Test
    void exactlyOneKindIsRequired() {
        Expectation none = new Expectation();
        assertBad(none, "exactly one");
        Expectation two = new Expectation();
        two.text = "a";
        two.value = "b";
        assertBad(two, "exactly one");
        Expectation ok = new Expectation();
        ok.state = Arrays.asList("checked", "!disabled");
        ok.validate();
    }

    @Test
    void rowsNeedAKnownOperator() {
        Expectation e = new Expectation();
        e.rows = new Expectation.Count();
        e.rows.op = "ge";
        e.rows.value = 1;
        assertBad(e, "rows.op");
        e.rows.op = "gte";
        e.validate();
    }

    @Test
    void aCellNeedsRowColumnAndExpected() {
        Expectation e = new Expectation();
        e.cell = new Expectation.Cell();
        e.cell.row = Collections.<String, Object>singletonMap("index", 0);
        e.cell.column = "Status";
        assertBad(e, "'cell' needs");
        e.cell.expected = "open";
        e.validate();
    }

    @Test
    void comparisonsFollowTheirOperator() {
        assertThat(Expectation.compare(3, "eq", 3)).isTrue();
        assertThat(Expectation.compare(3, "gt", 3)).isFalse();
        assertThat(Expectation.compare(3, "gte", 3)).isTrue();
        assertThat(Expectation.compare(2, "lt", 3)).isTrue();
        assertThat(Expectation.compare(4, "lte", 3)).isFalse();
    }

    private static void assertBad(Expectation e, String text) {
        DriverException ex = catchThrowableOfType(e::validate, DriverException.class);
        assertThat(ex.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(ex.getMessage()).contains(text);
    }
}
