package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Expectation;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Locator;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

/**
 * One method = one driver {@code expect}, which polls until it holds or its wait ends ({@link #within},
 * else the target's timeout, else the driver's expect wait, 10 s). The checks run in the client app, not on a
 * value held here.
 */
public final class TargetAssert {

    private static final long ABSENT_POLL_MS = 100;
    private static final long DEFAULT_ABSENT_WAIT_MS = 10_000;

    private final Target actual;
    private Long withinMillis;

    TargetAssert(Target actual) {
        if (actual == null) throw new IllegalArgumentException("assertThat(null)");
        this.actual = actual;
    }

    public TargetAssert within(Duration d) {
        withinMillis = Target.millis(d);
        return this;
    }

    public TargetAssert isEnabled() { return hasStates("!disabled"); }
    public TargetAssert isDisabled() { return hasStates("disabled"); }
    public TargetAssert isChecked() { return hasStates("checked"); }
    public TargetAssert isNotChecked() { return hasStates("!checked"); }
    public TargetAssert isSelected() { return hasStates("selected"); }
    public TargetAssert isNotSelected() { return hasStates("!selected"); }
    public TargetAssert isVisible() { return hasStates("!hidden"); }
    public TargetAssert isHidden() { return hasStates("hidden"); }
    public TargetAssert isEditable() { return hasStates("editable"); }
    public TargetAssert isReadOnly() { return hasStates("readonly"); }

    public TargetAssert hasStates(String... tokens) {
        Expectation e = new Expectation();
        e.state = Arrays.asList(tokens);
        return expect("have state " + e.state, e);
    }

    public TargetAssert hasText(String text) {
        Expectation e = new Expectation();
        e.text = text;
        return expect("have text '" + text + "'", e);
    }

    public TargetAssert hasValue(String value) {
        Expectation e = new Expectation();
        e.value = value;
        return expect("have value '" + value + "'", e);
    }

    /** The tooltip shown on hover (a cell target: the rendered cell's), HTML stripped; {@code ""} asserts none. */
    public TargetAssert hasTooltip(String tooltip) {
        Expectation e = new Expectation();
        e.tooltip = tooltip;
        return expect("have tooltip '" + tooltip + "'", e);
    }

    public TargetAssert hasRowCount(int n) { return hasRowCount("eq", n); }

    public TargetAssert hasRowCount(String op, int n) {
        Expectation e = new Expectation();
        e.rows = new Expectation.Count();
        e.rows.op = op;
        e.rows.value = n;
        return expect("have rows " + op + " " + n, e);
    }

    public TargetAssert hasCell(Map<String, Object> row, String column, String expected) {
        Expectation e = new Expectation();
        e.cell = new Expectation.Cell();
        e.cell.row = row;
        e.cell.column = column;
        e.cell.expected = expected;
        return expect("have cell " + row + "/" + column + " = '" + expected + "'", e);
    }

    public TargetAssert hasSelectedRows(Integer... rows) {
        Expectation e = new Expectation();
        e.selected = Arrays.asList(rows);
        return expect("have selected rows " + e.selected, e);
    }

    /** Passes once the target no longer resolves; fails if it is still there when the wait ends. */
    public TargetAssert isAbsent() {
        long wait = withinMillis != null ? withinMillis
                : actual.timeoutMillis() != null ? actual.timeoutMillis() : DEFAULT_ABSENT_WAIT_MS;
        long start = System.currentTimeMillis();
        long deadline = start + wait;
        Locator target = actual.locator();
        while (true) {
            try {
                actual.client().verbs().read(target, 0);
            } catch (DriverException e) {
                if (e.reason() == Reason.NOT_FOUND) return this;
                throw asFailure("be absent", e, System.currentTimeMillis() - start);
            }
            if (System.currentTimeMillis() >= deadline) {
                long elapsed = System.currentTimeMillis() - start;
                throw new UiAssertionError("Expected " + target + " to be absent\nbut it was still there after "
                        + elapsed + " ms", target, null);
            }
            sleep();
        }
    }

    private TargetAssert expect(String what, Expectation e) {
        Long wait = withinMillis != null ? withinMillis : actual.timeoutMillis();
        Locator target = actual.locator();
        long start = System.currentTimeMillis();
        try {
            actual.client().verbs().expect(target, e, wait);
        } catch (DriverException ex) {
            throw asFailure(what, ex, System.currentTimeMillis() - start);
        }
        return this;
    }

    private RuntimeException asFailureAction(String what, DriverException ex) {
        return new UiActionFailed("expect " + what, actual.locator(), ex, actual.client().candidatesFor(actual.locator(), ex));
    }

    private Error asFailureAssertion(String what, DriverException ex, long elapsedMillis) {
        return new UiAssertionError("Expected " + actual.locator() + " to " + what + "\nbut: " + ex.getMessage()
                + "\nwaited: " + elapsedMillis + " ms", actual.locator(), ex);
    }

    /** BAD_INPUT and CAPABILITY_GAP are the test's or the driver's fault, not the app's: report as an error. */
    private RuntimeException asFailure(String what, DriverException ex, long elapsedMillis) {
        if (ex.reason() == Reason.BAD_INPUT || ex.reason() == Reason.CAPABILITY_GAP) {
            return asFailureAction(what, ex);
        }
        throw asFailureAssertion(what, ex, elapsedMillis);
    }

    private static void sleep() {
        try {
            Thread.sleep(ABSENT_POLL_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting", ie);
        }
    }
}
