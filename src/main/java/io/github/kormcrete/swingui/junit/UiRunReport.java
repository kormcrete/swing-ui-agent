package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.journal.JournalSettings;
import io.github.kormcrete.swingui.journal.RunFolder;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Writes target/ui-runs/latest.txt for any run that holds a {@code @UiTest} (tag "ui"), so a caller that cannot
 * watch IntelliJ's Run window reads the verdict. The {@code target} is the test module's, found from the
 * first UI test's class (see {@link ModuleTarget}), not the working directory. With the journal on it also makes the
 * run folder ({@link RunFolder}): STARTED ends with {@code run=<runId>}, every line goes to its report.txt too.
 * Registered by the library jar's META-INF/services/org.junit.platform.launcher.TestExecutionListener, so it is on
 * whenever the library is on the test class path; a plan without UI tests gets no report. A second instance (a test
 * module that also registers or passes one) writes nothing for a plan the first one reports.
 */
public final class UiRunReport implements TestExecutionListener {

    private static final TestTag UI = TestTag.create("ui");
    /** The run journal settings of a plan: JUnit configuration parameters (JUnit Platform 1.8 or later). */
    private static final Function<TestPlan, Function<String, Optional<String>>> CONFIGURATION =
            plan -> key -> plan.getConfigurationParameters().get(key);
    /** Per runs directory, the plan whose report is being written there. */
    private static final Map<Path, TestPlan> REPORTING = new HashMap<Path, TestPlan>();
    private static final AtomicBoolean NO_SETTINGS_WARNED = new AtomicBoolean();
    /** The report file a test gives; null: {@code <module>/target/ui-runs/latest.txt} of the run's first UI test. */
    private final Path file;
    private final Map<String, Long> started = new ConcurrentHashMap<String, Long>();
    private final Set<String> reported = ConcurrentHashMap.newKeySet();
    private final Function<TestPlan, Function<String, Optional<String>>> parameters;
    private volatile TestPlan plan;
    private volatile RunReport report;
    /** The runs directory this instance claimed for the current plan; null when it reports nothing. */
    private volatile Path claimed;

    public UiRunReport() {
        this(null);
    }

    UiRunReport(Path file) {
        this(file, CONFIGURATION);
    }

    /** A report whose plans' settings come from {@code parameters} (for tests). */
    UiRunReport(Path file, Function<TestPlan, Function<String, Optional<String>>> parameters) {
        this.file = file;
        this.parameters = parameters;
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
        Path latest = file != null ? file : ModuleTarget.of(firstClass).resolve("ui-runs").resolve("latest.txt");
        Path runsDir = latest.toAbsolutePath().normalize().getParent();
        synchronized (REPORTING) {
            if (REPORTING.get(runsDir) == plan) return; // another instance reports this plan here
            REPORTING.put(runsDir, plan);
        }
        claimed = runsDir;
        this.plan = plan;
        reported.clear();
        report = new RunReport(latest, runFolder(plan, latest.getParent()));
        report.started(ui);
    }

    /**
     * The run's folder, made now when the journal is on. Settings that cannot be read (JUnit Platform before 1.8 has no
     * configuration parameters on the plan) give the defaults and, once per JVM, a warning; null when even that fails.
     */
    private RunFolder runFolder(TestPlan plan, Path runsDir) {
        RunFolder folder;
        try {
            try {
                folder = RunFolder.of(runsDir, parameters.apply(plan));
            } catch (RuntimeException | LinkageError e) {
                if (NO_SETTINGS_WARNED.compareAndSet(false, true)) {
                    System.err.println("[ui-warning] cannot read the run journal settings, using the defaults: " + e);
                }
                folder = RunFolder.of(runsDir, JournalSettings.defaults());
            }
        } catch (RuntimeException e) {
            System.err.println("[ui-warning] run journal off for this run: " + e);
            return null;
        }
        if (folder.settings().journal) {
            try {
                folder.create();
            } catch (RuntimeException e) {
                folder.broken(e);
            }
        }
        return folder;
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
        Path dir = claimed;
        claimed = null;
        if (dir != null) {
            synchronized (REPORTING) {
                if (REPORTING.get(dir) == plan) REPORTING.remove(dir);
            }
        }
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
            return TestNames.of(m.getClassName(), m.getMethodName(), id.getUniqueId());
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
