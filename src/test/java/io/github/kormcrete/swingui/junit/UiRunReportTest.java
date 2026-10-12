package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.testing.FixtureClass;
import io.github.kormcrete.swingui.testing.FixtureClassCondition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
        Path file = dir.resolve("latest.txt");
        LauncherDiscoveryRequestBuilder req = LauncherDiscoveryRequestBuilder.request();
        for (Class<?> c : classes) req.selectors(selectClass(c));
        req.configurationParameter(FixtureClassCondition.PARAMETER, FixtureClassCondition.VALUE);
        Launcher launcher = LauncherFactory.create();
        launcher.execute(req.build(), new UiRunReport(file));
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    @Test
    void aClassWhoseSetupFailsReportsEachOfItsTestsAsFailed(@TempDir Path dir) throws Exception {
        List<String> lines = run(dir, BrokenSetup.class);
        assertThat(lines.get(0)).endsWith(" tests=2");
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
