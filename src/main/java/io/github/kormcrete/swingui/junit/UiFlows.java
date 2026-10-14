package io.github.kormcrete.swingui.junit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kormcrete.swingui.flow.Flow;
import io.github.kormcrete.swingui.flow.FlowFiles;
import io.github.kormcrete.swingui.flow.FlowSelection;
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
 * Runs the flow files of a folder as JUnit tests: one dynamic test per flow, each through the agent's
 * {@code run_flow} on the test's leased {@link Client}. No plugin is needed. The whole test class:
 * <pre>
 * &#64;UiTest class FlowsUiIT {
 *     &#64;TestFactory Stream&lt;DynamicTest&gt; flows(Client ui) { return UiFlows.tests(ui); }
 * }
 * </pre>
 * <p>The folder is, in order: the {@code folder} argument, {@code -Dswingui.flowsDir}, else {@code uitest} in the test
 * module (a relative one is under the module). {@code -Dflow=<name>} runs just that flow; a {@code gate.txt} in the
 * folder (one name per line, {@code #} comments) chooses which flows run and in what order, otherwise all run sorted
 * by name. A run that selects nothing fails. Each flow begins with the app reset; a failing flow fails its test with the
 * report, and the report and the failing step's picture go to {@code target/failsafe-reports/flow-failures}. A file
 * that cannot be read is a failed test too, never a skipped one.
 */
public final class UiFlows {

    private UiFlows() {
    }

    /** The flows in the default folder ({@code -Dswingui.flowsDir}, else {@code uitest} in the test module). */
    public static Stream<DynamicTest> tests(Client ui) {
        return tests(ui, caller(), null);
    }

    /** The flows in {@code folder} (a relative one is under the test module). */
    public static Stream<DynamicTest> tests(Client ui, Path folder) {
        return tests(ui, caller(), folder);
    }

    private static String caller() {
        for (StackTraceElement f : new Throwable().getStackTrace()) {
            if (!UiFlows.class.getName().equals(f.getClassName())) return f.getClassName();
        }
        return null;
    }

    private static Stream<DynamicTest> tests(Client ui, String caller, Path folder) {
        Path target = ModuleTarget.of(caller).toAbsolutePath();
        Path parent = target.getParent();
        Path module = "target".equals(String.valueOf(target.getFileName())) && parent != null
                ? parent : Paths.get("").toAbsolutePath();
        Path dir;
        if (folder != null) {
            dir = folder;
        } else {
            String prop = System.getProperty(FlowFiles.DIR_PROP);
            dir = Paths.get(prop != null && !prop.trim().isEmpty() ? prop.trim() : FlowFiles.DEFAULT_DIR);
        }
        dir = (dir.isAbsolute() ? dir : module.resolve(dir)).normalize();
        Path evidence = target.resolve("failsafe-reports").resolve("flow-failures");
        List<DynamicTest> out = new ArrayList<DynamicTest>();
        for (Path file : FlowSelection.select(dir, System.getProperty(FlowSelection.FLOW_PROPERTY))) {
            String base = file.getFileName().toString();
            if (base.endsWith(FlowFiles.SUFFIX)) base = base.substring(0, base.length() - FlowFiles.SUFFIX.length());
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
                    flow = FlowFiles.parse(json, file.toString());
                } catch (RuntimeException e) {
                    throw new AssertionError(e.getMessage(), e);
                }
                if (flow.name == null || flow.name.trim().isEmpty()) {
                    // the run, its picture and its report go by the file's name, like the test
                    flow.name = name;
                    json = FlowFiles.write(flow);
                }
                JsonNode result = new ObjectMapper().readTree(ui.app().runFlow(json, evidence.toString()));
                if (result.path("passed").asBoolean(false)) {
                    System.out.println("[ui-flow] " + name + " passed: " + result.path("ran").asInt() + " steps, "
                            + result.path("ms").asLong() + " ms");
                    return;
                }
                String report = result.path("report").asText("flow " + name + " FAILED: " + result);
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
