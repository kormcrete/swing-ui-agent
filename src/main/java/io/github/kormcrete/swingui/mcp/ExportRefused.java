package io.github.kormcrete.swingui.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link JavaTestWriter#write} refuses to emit anything when the recorded steps do not all have a
 * Java form: every problem found is collected first, then thrown together, so one export names
 * everything wrong with it instead of stopping at the first.
 */
public final class ExportRefused extends IllegalArgumentException {

    private final List<String> problems;

    public ExportRefused(List<String> problems) {
        super(message(problems));
        this.problems = Collections.unmodifiableList(new ArrayList<String>(problems));
    }

    public List<String> problems() {
        return problems;
    }

    private static String message(List<String> problems) {
        StringBuilder sb = new StringBuilder("export refused:");
        for (String p : problems) sb.append('\n').append(p);
        return sb.toString();
    }
}
