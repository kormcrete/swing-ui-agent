package io.github.kormcrete.swingui.flow;

import io.github.kormcrete.swingui.driver.Shot;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Node;
import io.github.kormcrete.swingui.driver.model.ScopeStep;
import io.github.kormcrete.swingui.flow.verb.VerbAction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Diagnoses a failed replay step (see {@link StepDiagnostics}) so an offline-authored flow can be
 * corrected from the run's output alone. Three parts, each best effort:
 *
 * <ol>
 *   <li>each locator of the step and what it matches now - zero, one or several candidates;</li>
 *   <li>a SCOPED, depth-limited, compact tree of where the step failed: the locator's innermost scope
 *       step (a tab's content, a section), else its window, else the one element it matched, else
 *       every window to a shallow depth. Never the full tree;</li>
 *   <li>a PNG of the current window, written to {@code screenshotDir} when one is given.</li>
 * </ol>
 *
 * <p>For replay only: the tree is a snapshot, which resets refs - harmless in a replay, where nobody
 * holds one, and the reason the live MCP session does not diagnose its steps this way.
 */
public final class FailureDiagnostics {

    static final int SCOPE_DEPTH = 4;
    static final int ELEMENT_DEPTH = 2;
    static final int APP_DEPTH = 3;
    static final int MAX_NODES = 60;

    private final Path screenshotDir;

    /** {@code screenshotDir} null: no picture. */
    public FailureDiagnostics(Path screenshotDir) {
        this.screenshotDir = screenshotDir;
    }

    public StepDiagnostics diagnose(Action action, Verbs verbs, String flowName, int index) {
        Map<String, Locator> targets = action instanceof VerbAction
                ? ((VerbAction) action).targets() : Collections.<String, Locator>emptyMap();
        return diagnose(targets, verbs, flowName, index);
    }

    /** The same diagnosis for any named locators - a flow step's, or a Java UI test's failed target. */
    public StepDiagnostics diagnose(Map<String, Locator> targets, Verbs verbs, String name, int index) {
        StepDiagnostics d = new StepDiagnostics();
        if (verbs == null) {
            d.problems.add("no UI driver is installed: nothing to diagnose with");
            return d;
        }
        Locator first = null;
        int firstMatches = -1;
        for (Map.Entry<String, Locator> t : targets.entrySet()) {
            if (t.getValue() == null) continue;
            List<String> candidates = candidates(verbs, t.getValue(), d);
            d.matches.add(new StepDiagnostics.Match(t.getKey(), t.getValue().toString(), candidates));
            if (first == null) {
                first = t.getValue();
                firstMatches = candidates == null ? -1 : candidates.size();
            }
        }
        tree(verbs, first, firstMatches, d);
        if (screenshotDir != null) screenshot(verbs, name, index, d);
        return d;
    }

    private static List<String> candidates(Verbs verbs, Locator l, StepDiagnostics d) {
        try {
            return verbs.candidates(l);
        } catch (RuntimeException e) {
            d.problems.add("could not list what " + l + " matches: " + e.getMessage());
            return null;
        }
    }

    /** The innermost scope, else the window, else the one matched element, else the app - first that works. */
    private void tree(Verbs verbs, Locator l, int matches, StepDiagnostics d) {
        if (l != null && l.ref == null && l.scope != null && !l.scope.isEmpty()) {
            Locator scope = scopeOf(l);
            String what = scope.id != null ? "id '" + scope.id + "'" : scope.role + " '" + scope.name + "'";
            if (dump(verbs, null, scope, SCOPE_DEPTH, what, d)) return;
        }
        if (l != null && l.window != null) {
            if (dump(verbs, l.window, null, SCOPE_DEPTH, "window " + l.window, d)) return;
        }
        if (l != null && matches == 1) {
            if (dump(verbs, null, l.element(), ELEMENT_DEPTH, "the matched element", d)) return;
        }
        dump(verbs, null, null, APP_DEPTH, "every window", d);
    }

    /**
     * The locator of {@code l}'s innermost scope step - a tab, a section, or the component an id step
     * names (an id locator, no role) - inside the steps before it.
     */
    static Locator scopeOf(Locator l) {
        ScopeStep last = ScopeStep.parse(l.scope.get(l.scope.size() - 1));
        Locator s = new Locator();
        s.window = l.window;
        if (l.scope.size() > 1) s.scope = new ArrayList<String>(l.scope.subList(0, l.scope.size() - 1));
        if (last.kind == ScopeStep.Kind.ID) {
            s.id = last.title;
        } else {
            s.role = last.kind == ScopeStep.Kind.TAB ? "tab" : "section";
            s.name = last.title;
        }
        return s;
    }

    private static boolean dump(Verbs verbs, String window, Locator target, int depth, String what, StepDiagnostics d) {
        try {
            Node root = verbs.snapshot(window, null, target, depth, MAX_NODES, 0).compact();
            d.scope = what + " (depth " + depth + ", compact, " + root.count() + " nodes)";
            render(root, -1, d.tree);
            return true;
        } catch (RuntimeException e) {
            d.problems.add("no tree for " + what + ": " + e.getMessage());
            return false;
        }
    }

    /** One line per node, indented by depth; the app root itself is not a line. */
    public static void render(Node n, int depth, List<String> out) {
        if (depth >= 0) out.add(indent(depth) + line(n));
        if (n.children != null) for (Node c : n.children) render(c, depth + 1, out);
    }

    static String line(Node n) {
        StringBuilder b = new StringBuilder(n.role == null ? "?" : n.role);
        if (n.name != null) b.append(" '").append(n.name).append('\'');
        if (n.id != null) b.append(" #").append(n.id);
        if (n.value != null) b.append(" = \"").append(n.value).append('"');
        if (n.tooltip != null && !n.tooltip.equals(n.name)) b.append(" tip=\"").append(n.tooltip).append('"');
        if (n.state != null && !n.state.isEmpty()) b.append(' ').append(n.state);
        if (n.columns != null) b.append(" columns=").append(n.columns);
        if (n.rowCount != null) b.append(" rows=").append(n.rowCount);
        if (n.modal != null && n.modal) b.append(" modal");
        if (n.blockedBy != null) b.append(" blockedBy=").append(n.blockedBy);
        if (n.truncated != null && n.truncated) b.append(" ...");
        return b.toString();
    }

    private static String indent(int depth) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < depth; i++) b.append("  ");
        return b.toString();
    }

    private void screenshot(Verbs verbs, String flowName, int index, StepDiagnostics d) {
        try {
            Shot shot = verbs.screenshot(null, null, null);
            Files.createDirectories(screenshotDir);
            Path file = screenshotDir.resolve(safe(flowName) + "-step" + index + ".png");
            Files.write(file, shot.png);
            d.screenshot = file.toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            d.problems.add("no screenshot: " + e.getMessage());
        }
    }

    private static String safe(String name) {
        String s = name == null ? "flow" : name.replaceAll("[^a-zA-Z0-9._-]+", "-");
        return s.isEmpty() ? "flow" : s;
    }

    /** A timed-out action usually means a busy EDT: its stack says what it was busy with. */
    public static String edtStack() {
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            if (e.getKey().getName().startsWith("AWT-EventQueue")) {
                StringBuilder b = new StringBuilder("\n--- ").append(e.getKey().getName())
                        .append(" (").append(e.getKey().getState()).append(") ---\n");
                for (StackTraceElement s : e.getValue()) b.append("  at ").append(s).append('\n');
                return b.toString();
            }
        }
        return "\nAWT-EventQueue-0: not running\n";
    }
}
