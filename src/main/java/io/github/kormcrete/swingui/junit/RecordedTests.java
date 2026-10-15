package io.github.kormcrete.swingui.junit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kormcrete.swingui.flow.Flow;
import io.github.kormcrete.swingui.flow.RecordedTestFiles;
import io.github.kormcrete.swingui.flow.RecordedTestSelection;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs the recorded test files of a folder as JUnit tests: one dynamic test per recorded test, each through the agent's
 * {@code run_recorded_test} on the test's leased {@link Client}. No plugin is needed. The whole test class:
 * <pre>
 * &#64;UiTest class RecordedUiIT {
 *     &#64;TestFactory Stream&lt;DynamicTest&gt; recorded(Client ui) { return RecordedTests.of(ui); }
 * }
 * </pre>
 * <p>The folder is, in order: the {@code folder} argument, {@code -Dswingui.recordedDir}, else {@code uitest} in the test
 * module (a relative one is under the module). {@code -Drecorded=<name>} runs just that recorded test; a {@code gate.txt} in the
 * folder (one name per line, {@code #} comments) chooses which recorded tests run and in what order, otherwise all run sorted
 * by name. A run that selects nothing fails. Each recorded test begins with the app reset; a failing recorded test fails its test with the
 * report, and the report and the failing step's picture go to {@code target/failsafe-reports/recorded-test-failures}. A file
 * that cannot be read is a failed test too, never a skipped one.
 */
public final class RecordedTests {

    private RecordedTests() {
    }

    /** The recorded tests in the default folder ({@code -Dswingui.recordedDir}, else {@code uitest} in the test module). */
    public static Stream<DynamicTest> of(Client ui) {
        return of(ui, caller(), null);
    }

    /** The recorded tests in {@code folder} (a relative one is under the test module). */
    public static Stream<DynamicTest> of(Client ui, Path folder) {
        return of(ui, caller(), folder);
    }

    private static String caller() {
        for (StackTraceElement f : new Throwable().getStackTrace()) {
            if (!RecordedTests.class.getName().equals(f.getClassName())) return f.getClassName();
        }
        return null;
    }

    private static Stream<DynamicTest> of(Client ui, String caller, Path folder) {
        Path target = ModuleTarget.of(caller).toAbsolutePath();
        Path parent = target.getParent();
        Path module = "target".equals(String.valueOf(target.getFileName())) && parent != null
                ? parent : Paths.get("").toAbsolutePath();
        Path dir;
        if (folder != null) {
            dir = folder;
        } else {
            String prop = System.getProperty(RecordedTestFiles.DIR_PROP);
            dir = Paths.get(prop != null && !prop.trim().isEmpty() ? prop.trim() : RecordedTestFiles.DEFAULT_DIR);
        }
        dir = (dir.isAbsolute() ? dir : module.resolve(dir)).normalize();
        Path evidence = target.resolve("failsafe-reports").resolve("recorded-test-failures");
        List<DynamicTest> out = new ArrayList<DynamicTest>();
        for (Path file : RecordedTestSelection.select(dir, System.getProperty(RecordedTestSelection.PROPERTY))) {
            String base = file.getFileName().toString();
            if (base.endsWith(RecordedTestFiles.SUFFIX)) base = base.substring(0, base.length() - RecordedTestFiles.SUFFIX.length());
            out.add(DynamicTest.dynamicTest(base, file.toUri(), run(ui, base, file, evidence)));
        }
        return out.stream();
    }

    private static Executable run(final Client ui, final String name, final Path file, final Path evidence) {
        return new Executable() {
            @Override public void execute() throws Throwable {
                String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Flow flow;
                try {
                    flow = RecordedTestFiles.parse(json, file.toString());
                } catch (RuntimeException e) {
                    throw new AssertionError(e.getMessage(), e);
                }
                if (flow.name == null || flow.name.trim().isEmpty()) {
                    // the run, its picture and its report go by the file's name, like the test
                    flow.name = name;
                    json = RecordedTestFiles.write(flow);
                }
                JsonNode result = new ObjectMapper().readTree(ui.app().runRecordedTest(json, evidence.toString()));
                if (result.path("passed").asBoolean(false)) {
                    System.out.println("[recorded-test] " + name + " passed: " + result.path("ran").asInt() + " steps, "
                            + result.path("ms").asLong() + " ms");
                    return;
                }
                String report = result.path("report").asText("recorded test " + name + " FAILED: " + result);
                Path txt = evidence.resolve(name + ".txt");
                try {
                    Files.createDirectories(evidence);
                    Files.write(txt, report.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new AssertionError(report + "\n(the report could not be written to " + txt + ": " + e + ")");
                }
                throw new AssertionError(report + "\nUI evidence: " + txt);
            }
        };
    }
}
