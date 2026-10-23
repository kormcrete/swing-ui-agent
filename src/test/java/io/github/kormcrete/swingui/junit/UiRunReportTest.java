package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.journal.RunFolder;
import io.github.kormcrete.swingui.testing.FixtureClass;
import io.github.kormcrete.swingui.testing.FixtureClassCondition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

class UiRunReportTest {

    @FixtureClass
    @Tag("ui")
    static class BrokenSetup {
        @BeforeAll
        static void server() { throw new IllegalStateException("server down\r\nsecond line"); }
        @Test void a() { }
        @Test void b() { }
    }

    @FixtureClass
    @Tag("ui")
    static class Mixed {
        @Test void passes() { }
        @Test void noMessage() { throw new IllegalStateException(); }
    }

    @FixtureClass
    @Tag("ui")
    @Disabled("not today")
    static class Off {
        @Test void c() { }
    }

    /** Real @Test methods (not a factory): the report counts only the plan's static UI tests. Driven by hand, never run. */
    @FixtureClass
    @Tag("ui")
    static class ThirtyTwo {
        @Test void t00() { }
        @Test void t01() { }
        @Test void t02() { }
        @Test void t03() { }
        @Test void t04() { }
        @Test void t05() { }
        @Test void t06() { }
        @Test void t07() { }
        @Test void t08() { }
        @Test void t09() { }
        @Test void t10() { }
        @Test void t11() { }
        @Test void t12() { }
        @Test void t13() { }
        @Test void t14() { }
        @Test void t15() { }
        @Test void t16() { }
        @Test void t17() { }
        @Test void t18() { }
        @Test void t19() { }
        @Test void t20() { }
        @Test void t21() { }
        @Test void t22() { }
        @Test void t23() { }
        @Test void t24() { }
        @Test void t25() { }
        @Test void t26() { }
        @Test void t27() { }
        @Test void t28() { }
        @Test void t29() { }
        @Test void t30() { }
        @Test void t31() { }
    }

    private List<String> run(Path dir, Class<?>... classes) throws Exception {
        return run(dir, "on", classes);
    }

    private List<String> run(Path dir, String journal, Class<?>... classes) throws Exception {
        return run(dir, journal, new TestExecutionListener[] {new UiRunReport(dir.resolve("latest.txt"))}, classes);
    }

    private List<String> run(Path dir, String journal, TestExecutionListener[] reports, Class<?>... classes)
            throws Exception {
        LauncherDiscoveryRequestBuilder req = LauncherDiscoveryRequestBuilder.request();
        for (Class<?> c : classes) req.selectors(selectClass(c));
        req.configurationParameter(FixtureClassCondition.PARAMETER, FixtureClassCondition.VALUE);
        req.configurationParameter("swingui.journal", journal);
        Launcher launcher = LauncherFactory.create();
        try {
            launcher.execute(req.build(), reports);
        } finally {
            RunFolder.forget(dir);
        }
        return Files.readAllLines(dir.resolve("latest.txt"), StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static List<String> names(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    @Test
    void theLibraryJarRegistersTheRunReport(@TempDir Path dir) throws Exception {
        String services = "META-INF/services/" + TestExecutionListener.class.getName();
        List<TestExecutionListener> reports = new ArrayList<TestExecutionListener>();
        for (TestExecutionListener l : ServiceLoader.load(TestExecutionListener.class)) {
            if (l instanceof UiRunReport) reports.add(l);
        }
        assertThat(reports).hasSize(1);

        // a test module that still lists it in its own services file gets one report, not two
        Path own = Files.createDirectories(dir.resolve("META-INF/services")).resolve(TestExecutionListener.class.getName());
        Files.write(own, (UiRunReport.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
        try (URLClassLoader module = new URLClassLoader(new URL[] {dir.toUri().toURL()}, UiRunReportTest.class.getClassLoader())) {
            int listing = 0;
            for (URL url : Collections.list(module.getResources(services))) {
                try (InputStream in = url.openStream()) {
                    if (new String(readAll(in), StandardCharsets.UTF_8).contains(UiRunReport.class.getName())) listing++;
                }
            }
            assertThat(listing).isEqualTo(2);
            List<TestExecutionListener> loaded = new ArrayList<TestExecutionListener>();
            for (TestExecutionListener l : ServiceLoader.load(TestExecutionListener.class, module)) {
                if (l instanceof UiRunReport) loaded.add(l);
            }
            assertThat(loaded).hasSize(1);
        }
    }

    @Test
    void aSecondRunReportOfTheSamePlanWritesNothing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("latest.txt");
        List<String> lines = run(dir, "on", new TestExecutionListener[] {new UiRunReport(file), new UiRunReport(file)},
                Mixed.class);
        assertThat(lines).hasSize(4);
        assertThat(lines.get(0)).startsWith("STARTED ").contains(" tests=2 run=");
        assertThat(lines.get(3)).isEqualTo("DONE passed=1 failed=1 skipped=0 aborted=0");
        String runId = lines.get(0).substring(lines.get(0).lastIndexOf(" run=") + " run=".length());
        assertThat(Files.readAllLines(dir.resolve(runId).resolve("report.txt"), StandardCharsets.UTF_8)).isEqualTo(lines);
    }

    @Test
    void aJUnitPlatformWithoutConfigurationParametersGetsTheDefaultsWithOneWarning(@TempDir Path dir) throws Exception {
        Function<TestPlan, Function<String, Optional<String>>> old = plan -> key -> {
            throw new NoSuchMethodError("org.junit.platform.launcher.TestPlan.getConfigurationParameters()");
        };
        Path first = dir.resolve("first");
        Path second = dir.resolve("second");
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
        List<String> a;
        List<String> b;
        try {
            a = run(first, "off", new TestExecutionListener[] {new UiRunReport(first.resolve("latest.txt"), old)}, Mixed.class);
            b = run(second, "off", new TestExecutionListener[] {new UiRunReport(second.resolve("latest.txt"), old)}, Mixed.class);
        } finally {
            System.setErr(err);
        }
        assertThat(a.get(0)).contains(" tests=2 run=");
        assertThat(b.get(0)).contains(" tests=2 run=");
        assertThat(a.get(a.size() - 1)).isEqualTo("DONE passed=1 failed=1 skipped=0 aborted=0");
        String warnings = new String(captured.toByteArray(), StandardCharsets.UTF_8);
        assertThat(warnings.split("\\[ui-warning]", -1)).hasSize(2);
        assertThat(warnings).startsWith("[ui-warning] ").contains("defaults");
    }

    @Test
    void theReportNamesItsRunFolderAndCopiesItsLinesThere(@TempDir Path dir) throws Exception {
        List<String> lines = run(dir, Mixed.class);
        String runId = lines.get(0).substring(lines.get(0).lastIndexOf(" run=") + " run=".length());
        assertThat(runId).isNotEmpty();
        assertThat(names(dir)).containsExactly(runId, "latest-run.txt", "latest.txt");
        assertThat(Files.readAllLines(dir.resolve(runId).resolve("report.txt"), StandardCharsets.UTF_8)).isEqualTo(lines);
    }

    @Test
    void withTheJournalOffTheReportMakesNoRunFolder(@TempDir Path dir) throws Exception {
        List<String> lines = run(dir, "off", Mixed.class);
        assertThat(lines.get(0)).startsWith("STARTED ").endsWith(" tests=2");
        assertThat(lines.get(lines.size() - 1)).isEqualTo("DONE passed=1 failed=1 skipped=0 aborted=0");
        assertThat(names(dir)).containsExactly("latest.txt");
    }

    @Test
    void aClassWhoseSetupFailsReportsEachOfItsTestsAsFailed(@TempDir Path dir) throws Exception {
        List<String> lines = run(dir, BrokenSetup.class);
        assertThat(lines.get(0)).contains(" tests=2 run=");
        assertThat(lines.subList(1, lines.size())).hasSize(3);
        assertThat(lines.get(1)).startsWith("FAIL UiRunReportTest$BrokenSetup#").endsWith("0ms — server down");
        assertThat(lines.get(2)).startsWith("FAIL UiRunReportTest$BrokenSetup#").endsWith("0ms — server down");
        assertThat(lines.get(3)).isEqualTo("DONE passed=0 failed=2 skipped=0 aborted=0");
    }

    @Test
    void aSkippedClassReportsSkipsAndOwnLinesAreNotWrittenTwice(@TempDir Path dir) throws Exception {
        List<String> lines = run(dir, Mixed.class, Off.class);
        assertThat(lines).hasSize(5);
        assertThat(lines).anyMatch(l -> l.startsWith("PASS UiRunReportTest$Mixed#passes "));
        assertThat(lines).anyMatch(l -> l.startsWith("FAIL UiRunReportTest$Mixed#noMessage ") && l.endsWith("ms — IllegalStateException"));
        assertThat(lines).anyMatch(l -> l.equals("SKIP UiRunReportTest$Off#c 0ms — not today"));
        assertThat(lines.get(4)).isEqualTo("DONE passed=1 failed=1 skipped=1 aborted=0");
    }

    @Test
    void aCarriageReturnEndsNoFailureLineEarly(@TempDir Path dir) throws Exception {
        RunReport r = new RunReport(dir.resolve("l.txt"));
        r.started(1);
        r.finished("A#a", "FAIL", 1, "boom\r\nat x", null);
        assertThat(Files.readAllLines(dir.resolve("l.txt"), StandardCharsets.UTF_8).get(1)).isEqualTo("FAIL A#a 1ms — boom");
    }

    @Test
    void eightThreadsReportingAtOnceLoseNoLine(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("latest.txt");
        TestPlan plan = LauncherFactory.create().discover(
                LauncherDiscoveryRequestBuilder.request().selectors(selectClass(ThirtyTwo.class)).build());
        List<TestIdentifier> tests = new ArrayList<TestIdentifier>();
        for (TestIdentifier root : plan.getRoots()) {
            for (TestIdentifier id : plan.getDescendants(root)) if (id.isTest()) tests.add(id);
        }
        assertThat(tests).hasSize(32);
        UiRunReport report = new UiRunReport(file);
        report.testPlanExecutionStarted(plan);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<Future<?>>();
        for (TestIdentifier id : tests) {
            done.add(pool.submit(() -> {
                go.await();
                report.executionStarted(id);
                report.executionFinished(id, TestExecutionResult.successful());
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : done) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        report.testPlanExecutionFinished(plan);

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines.stream().filter(l -> l.startsWith("PASS ")).count()).isEqualTo(32);
        assertThat(lines.get(lines.size() - 1)).startsWith("DONE").contains("passed=32");
    }
}
