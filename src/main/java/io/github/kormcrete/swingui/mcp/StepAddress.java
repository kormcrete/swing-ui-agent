package io.github.kormcrete.swingui.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;

/** Where something sits in a recording: step {@code step}, or its {@code check}th check ({@code 0} = the step itself). */
final class StepAddress {

    final int step;
    /** 0 for the step (its action); 1 and up for a check. */
    final int check;

    StepAddress(int step, int check) {
        this.step = step;
        this.check = check;
    }

    static StepAddress ofStep(int step) {
        return new StepAddress(step, 0);
    }

    /** An integer ({@code 7}) or a string ({@code "7"}, {@code "7.2"}); anything else is BAD_INPUT. */
    static StepAddress parse(JsonNode node) {
        if (node != null) {
            if (node.isInt() && node.intValue() >= 1) return new StepAddress(node.intValue(), 0);
            if (node.isTextual()) {
                String s = node.textValue().trim();
                if (s.matches("[1-9][0-9]{0,8}")) return new StepAddress(Integer.parseInt(s), 0);
                if (s.matches("[1-9][0-9]{0,8}\\.[1-9][0-9]{0,8}")) {
                    int dot = s.indexOf('.');
                    return new StepAddress(Integer.parseInt(s.substring(0, dot)), Integer.parseInt(s.substring(dot + 1)));
                }
            }
        }
        throw new DriverException(Reason.BAD_INPUT, "a step number (7) or a check label (\"7.2\"), not "
                + (node == null ? "nothing" : node.toString()));
    }

    boolean isCheck() {
        return check > 0;
    }

    @Override public String toString() {
        return check == 0 ? String.valueOf(step) : step + "." + check;
    }

    @Override public boolean equals(Object o) {
        return o instanceof StepAddress && ((StepAddress) o).step == step && ((StepAddress) o).check == check;
    }

    @Override public int hashCode() {
        return step * 31 + check;
    }
}
