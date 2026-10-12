package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.flow.FailureDiagnostics;
import io.github.kormcrete.swingui.flow.StepDiagnostics;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

/** Writes what a failed UI test saw: evidence.txt (+ a PNG) in the given folder. Never throws. */
public final class Evidence {
    private Evidence() {}

    public static Path write(Path dir, Verbs verbs, Throwable failure, Supplier<String> edtStack) {
        StringBuilder b = new StringBuilder();
        b.append("failure: ").append(failure).append('\n');
        try {
            Files.createDirectories(dir);
            if (verbs == null) {
                b.append("Diagnostics: none (no driver)\n");
            } else {
                Locator target = targetOf(failure);
                // a client-level action (navigate, closeActiveTab, menu, ...) has no locator: say so, then dump every window
                if (target == null && failure instanceof UiActionFailed) b.append("target: n/a (a client-level action)\n");
                Map<String, Locator> targets = target == null
                        ? Collections.<String, Locator>emptyMap() : Collections.singletonMap("target", target);
                StepDiagnostics d = new FailureDiagnostics(dir).diagnose(targets, verbs, "failure", 0);
                append(b, d);
            }
        } catch (RuntimeException | IOException e) {
            b.append("diagnostics failed: ").append(e).append('\n');
        }
        if (isTimeout(failure)) b.append(edtText(edtStack));
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        b.append("\n--- stack ---\n").append(trace);
        try {
            Files.write(dir.resolve("evidence.txt"), b.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[ui-evidence] could not write " + dir + ": " + e);
        }
        return dir;
    }

    private static Locator targetOf(Throwable t) {
        if (t instanceof UiActionFailed) return ((UiActionFailed) t).target();
        if (t instanceof UiAssertionError) return ((UiAssertionError) t).target();
        return null;
    }

    private static boolean isTimeout(Throwable t) {
        return t instanceof UiActionFailed && ((UiActionFailed) t).reason() == Reason.TIMEOUT;
    }

    private static void append(StringBuilder b, StepDiagnostics d) {
        for (StepDiagnostics.Match m : d.matches) {
            b.append("target ").append(m.field).append(": ").append(m.locator).append('\n');
            // null: the candidates could not be listed (FailureDiagnostics says why under problems)
            if (m.candidates != null) for (String c : m.candidates) b.append("  candidate: ").append(c).append('\n');
        }
        if (d.scope != null) b.append("tree (").append(d.scope).append("):\n");
        for (String line : d.tree) b.append("  ").append(line).append('\n');
        if (d.screenshot != null) b.append("screenshot: ").append(d.screenshot).append('\n');
        for (String p : d.problems) b.append("problem: ").append(p).append('\n');
    }

    /** A timed-out action usually means a busy EDT: its stack says what it was busy with. */
    private static String edtText(Supplier<String> edtStack) {
        try {
            String s = edtStack == null ? null : edtStack.get();
            return "\n" + (s == null ? "EDT stack: unavailable (none given)" : s) + "\n";
        } catch (RuntimeException ex) {
            return "\nEDT stack: unavailable (" + ex.getMessage() + ")\n";
        }
    }
}
