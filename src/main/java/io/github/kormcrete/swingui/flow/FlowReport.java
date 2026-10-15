package io.github.kormcrete.swingui.flow;

import java.util.List;

/**
 * A run result as text, for a replay's output. Capability report first. A passing step is one line with its
 * timing (plus its note, if any), numbered as the file is: {@code [7]} a step, {@code [7.1]} one of its checks (indented
 * more), with its sentence in place of the technical line when it has one, which then follows on the next line, and a
 * group's sentence above its first step. A failing step adds what is needed to fix the recorded test without a live session: its
 * reason and details, what each locator matches now, a scoped compact tree, and a screenshot path
 * ({@link StepDiagnostics}).
 *
 * <pre>
 *   [1] PASS navigate(Orders)                          ms=812 edtMs=40 edtWaitMs=1 offEdtMs=771
 *   [2] FAIL click(button 'Save' [tab:Customer])       ms=10012 edtMs=12 edtWaitMs=0 offEdtMs=10000
 *       NOT_FOUND: nothing matches button 'Save' [tab:Customer]
 *       target button 'Save' [tab:Customer] matched 0
 *       tree of tab 'Customer' (depth 4, compact, 9 nodes):
 *         section 'Header'
 *           button 'Store' #save
 *       screenshot: /.../target/failsafe-reports/recorded-test-failures/place-order-step2.png
 * </pre>
 */
public final class FlowReport {

    static final int MAX_DETAILS = 10;
    private static final String MORE = "        ";

    private FlowReport() {}

    public static String format(Flow flow, FlowResult result) {
        String title = flow.name;
        StringBuilder sb = new StringBuilder("Recorded test '" + title + "' " + (result.passed() ? "passed" : "failed") + ":\n");
        if (result.capabilities != null) sb.append("  driver: ").append(result.capabilities).append('\n');
        List<String> labels = StepNumbers.labels(flow.steps);
        FlowGroup shown = null;
        for (StepResult s : result.steps) {
            boolean known = s.index >= 0 && s.index < flow.steps.size();
            FlowGroup g = known ? groupOf(flow, s.index) : null;
            if (g != null && g != shown) sb.append("  \u2014 ").append(g.say).append('\n');
            shown = g;
            String say = known ? flow.steps.get(s.index).say : null;
            step(sb, s, known ? labels.get(s.index) : String.valueOf(s.index + 1), say != null && !say.isEmpty() ? say : null);
        }
        return sb.toString();
    }

    private static FlowGroup groupOf(Flow flow, int index) {
        for (FlowGroup g : flow.groups) if (index >= g.from && index <= g.to) return g;
        return null;
    }

    private static void step(StringBuilder sb, StepResult s, String label, String say) {
        String pad = label.indexOf('.') >= 0 ? "    " : "  ";
        String shownText = say != null ? say : s.action;
        sb.append(String.format("%s[%s] %s %-44s %s", pad, label, s.passed ? "PASS" : "FAIL", shownText, timing(s)));
        if (s.via != null) sb.append(" via=").append(s.via);
        if (s.pending != null) sb.append(" pending=").append(s.pending.summary());
        if (s.realInput != null) sb.append(" realInput=").append(s.realInput);
        if (s.activations != null) sb.append(" activations=").append(s.activations);
        sb.append('\n');
        if (say != null) sb.append(pad).append("    ").append(s.action).append('\n');
        if (s.passed) {
            if (s.message != null && !s.message.isEmpty()) sb.append("      ").append(s.message).append('\n');
            return;
        }
        String message = s.message == null || s.message.isEmpty() ? "(no message)" : s.message;
        boolean reasonShown = s.reason == null || message.startsWith(s.reason);
        sb.append("      ").append(reasonShown ? "" : s.reason + ": ").append(message).append('\n');
        if (s.details != null && !s.details.isEmpty()) {
            sb.append("      details:\n");
            list(sb, s.details);
        }
        StepDiagnostics d = s.diagnostics;
        if (d == null) return;
        for (StepDiagnostics.Match m : d.matches) {
            if (m.candidates == null) continue;
            sb.append("      ").append(m.field).append(' ').append(m.locator).append(" matched ").append(m.candidates.size());
            sb.append(m.candidates.isEmpty() ? "\n" : ":\n");
            list(sb, m.candidates);
        }
        if (d.scope != null) {
            sb.append("      tree of ").append(d.scope).append(":\n");
            for (String line : d.tree) sb.append(MORE).append(line).append('\n');
        }
        if (d.screenshot != null) sb.append("      screenshot: ").append(d.screenshot).append('\n');
        for (String p : d.problems) sb.append("      (").append(p).append(")\n");
    }

    /** The same numbers a {@code UIMCP-TIMING} line carries, for one step. */
    static String timing(StepResult s) {
        return "ms=" + s.ms + " edtMs=" + s.edtMs + " edtWaitMs=" + s.edtWaitMs
                + " offEdtMs=" + Math.max(0, s.ms - s.edtMs - s.edtWaitMs);
    }

    private static void list(StringBuilder sb, List<String> items) {
        int shown = 0;
        for (String item : items) {
            if (shown == MAX_DETAILS) {
                sb.append(MORE).append("- ... ").append(items.size() - shown).append(" more\n");
                break;
            }
            sb.append(MORE).append("- ").append(item).append('\n');
            shown++;
        }
    }
}
