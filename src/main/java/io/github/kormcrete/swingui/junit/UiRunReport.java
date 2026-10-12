package io.github.kormcrete.swingui.junit;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes target/ui-runs/latest.txt for any run that holds a {@code @UiTest} (tag "ui"), so a caller that cannot
 * watch IntelliJ's Run window reads the verdict. The {@code target} is the test module's, found from the
 * first UI test's class (see {@link ModuleTarget}), not the working directory. Registered by the test module's
 * META-INF/services/org.junit.platform.launcher.TestExecutionListener.
 */
public final class UiRunReport implements TestExecutionListener {

    private static final TestTag UI = TestTag.create("ui");
    /** Null until a run resolves it: the module of the run's first UI test. */
    private final Path file;
    private final Map<String, Long> started = new ConcurrentHashMap<String, Long>();
    private final Set<String> reported = ConcurrentHashMap.newKeySet();
    private volatile TestPlan plan;
    private volatile RunReport report;

    public UiRunReport() {
        this(null);
    }

    UiRunReport(Path file) {
        this.file = file;
    }

    @Override
    public void testPlanExecutionStarted(TestPlan plan) {
        long ui = 0;
        String firstClass = null;
        for (TestIdentifier root : plan.getRoots()) {
            for (TestIdentifier id : plan.getDescendants(root)) {
                if (!id.isTest() || !id.getTags().contains(UI)) continue;
                ui++;
                if (firstClass == null) firstClass = className(id.getSource().orElse(null));
            }
        }
        if (ui == 0) return;
        this.plan = plan;
        reported.clear();
        report = new RunReport(file != null ? file : ModuleTarget.of(firstClass).resolve("ui-runs").resolve("latest.txt"));
        report.started(ui);
    }

    @Override
    public void executionStarted(TestIdentifier id) {
        if (report != null && id.isTest()) started.put(id.getUniqueId(), System.currentTimeMillis());
    }

    @Override
    public void executionSkipped(TestIdentifier id, String reason) {
        if (report == null || !id.getTags().contains(UI)) return;
        if (id.isTest()) {
            line(id, "SKIP", 0, reason);
        } else {
            // a skipped class: JUnit never reports its children
            for (TestIdentifier t : plan.getDescendants(id)) if (t.isTest()) line(t, "SKIP", 0, reason);
        }
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (report == null || !id.getTags().contains(UI)) return;
        Throwable t = result.getThrowable().orElse(null);
        if (!id.isTest()) {
            // a class whose @BeforeAll failed: its tests never ran, so no line would say so
            if (result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {
                String why = reason(t);
                for (TestIdentifier child : plan.getDescendants(id)) {
                    if (child.isTest()) line(child, "FAIL", 0, why, evidence(t));
                }
            }
            return;
        }
        Long t0 = started.remove(id.getUniqueId());
        long ms = t0 == null ? 0 : System.currentTimeMillis() - t0;
        String status = result.getStatus() == TestExecutionResult.Status.SUCCESSFUL ? "PASS"
                : result.getStatus() == TestExecutionResult.Status.ABORTED ? "ABORT" : "FAIL";
        line(id, status, ms, t == null ? null : reason(t), evidence(t));
    }

    @Override
    public void testPlanExecutionFinished(TestPlan plan) {
        if (report != null) report.done();
        report = null;
        this.plan = null;
    }

    private void line(TestIdentifier id, String status, long ms, String failure) {
        line(id, status, ms, failure, null);
    }

    private void line(TestIdentifier id, String status, long ms, String failure, String evidence) {
        if (!reported.add(id.getUniqueId())) return; // it already wrote its own line
        report.finished(name(id), status, ms, failure, evidence);
    }

    /** The message, else the exception's simple class name: never the word null. */
    private static String reason(Throwable t) {
        if (t == null) return null;
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    private static String name(TestIdentifier id) {
        if (id.getSource().isPresent() && id.getSource().get() instanceof MethodSource) {
            MethodSource m = (MethodSource) id.getSource().get();
            String cls = m.getClassName();
            return cls.substring(cls.lastIndexOf('.') + 1) + "#" + m.getMethodName();
        }
        return id.getDisplayName();
    }

    private static String className(TestSource s) {
        if (s instanceof MethodSource) return ((MethodSource) s).getClassName();
        if (s instanceof ClassSource) return ((ClassSource) s).getClassName();
        return null;
    }

    private static String evidence(Throwable t) {
        if (t == null) return null;
        for (Throwable s : t.getSuppressed()) {
            String m = s.getMessage();
            if (m != null && m.startsWith("UI evidence: ")) return m.substring("UI evidence: ".length());
        }
        String m = t.getMessage();
        int at = m == null ? -1 : m.indexOf("UI evidence: ");
        return at < 0 ? null : m.substring(at + "UI evidence: ".length()).trim();
    }
}
