package io.github.kormcrete.swingui.flow;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Node;
import io.github.kormcrete.swingui.flow.verb.ScreenshotVerb;
import io.github.kormcrete.swingui.mcp.FakeVerbs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/** What a failed replay step reports so an offline-authored flow can be fixed from the output alone. */
class FlowFailureDiagnosticsTest {

    @TempDir
    Path dir;

    private final RecordingStepHost backend = new RecordingStepHost(new FakeVerbs());

    @AfterEach
    void clear() {
        System.clearProperty(ScreenshotVerb.DIR_PROP);
    }

    private FlowResult replay(String... steps) {
        return new FlowExecutor().diagnoseFailures(new FailureDiagnostics(dir.resolve("screens")))
                .run(FlowJson.parse("{\"name\":\"pricing walk\",\"steps\":[" + String.join(",", steps) + "]}"), backend);
    }

    private static final String PRESS = "{\"action\":\"press\",\"keys\":\"Escape\"}";
    private static final String SCOPED_CLICK = "{\"action\":\"click\",\"target\":{\"window\":\"dialog\","
            + "\"scope\":[\"tab:Pricing\",\"section:Discounts\"],\"role\":\"button\",\"name\":\"Save\"}}";

    private void failSecondCall() {
        backend.verbs.during = () -> backend.verbs.failure = backend.verbs.calls.size() == 2
                ? new DriverException(Reason.NOT_FOUND, "nothing matches button 'Save'",
                        Collections.singletonList("button 'Store' #save in dialog:Order [tab:Pricing]"))
                : null;
    }

    @Test
    void aFailedStepCarriesItsMatchesAScopedCompactTreeAndAScreenshot() throws Exception {
        failSecondCall();
        backend.verbs.candidates = Arrays.asList("button 'Save' #save in dialog:Order [tab:Pricing, section:Discounts]",
                "button 'Save' #save2 in dialog:Order [tab:Pricing, section:Discounts]");
        Node section = new Node("section", "Discounts");
        Node table = new Node("table", "Discounts");
        table.columns = Arrays.asList("Line items", "Max");
        table.rows = Collections.singletonList(Arrays.asList("Screws", "9"));
        table.rowCount = 12;
        section.addChild(table);
        Node app = new Node("app", null);
        app.addChild(section);
        backend.verbs.node = app;

        FlowResult result = replay(PRESS, SCOPED_CLICK, PRESS);
        assertThat(result.steps).hasSize(2);
        StepResult passed = result.steps.get(0);
        assertThat(passed.diagnostics).isNull();
        StepResult failed = result.steps.get(1);
        StepDiagnostics d = failed.diagnostics;
        assertThat(d).isNotNull();
        assertThat(d.matches).hasSize(1);
        assertThat(d.matches.get(0).field).isEqualTo("target");
        assertThat(d.matches.get(0).candidates).hasSize(2);
        // the tree is the innermost scope step, scoped inside the ones before it - never the whole app
        assertThat(backend.verbs.calls).contains("snapshot null null section 'Discounts' [tab:Pricing] in dialog "
                + FailureDiagnostics.SCOPE_DEPTH + " " + FailureDiagnostics.MAX_NODES + " 0");
        assertThat(d.scope).startsWith("section 'Discounts' (depth 4, compact");
        assertThat(d.tree).containsExactly("section 'Discounts'", "  table 'Discounts' columns=[Line items, Max] rows=12");
        assertThat(table.rows).isNull();
        assertThat(d.screenshot).endsWith("pricing-walk-step1.png");
        assertThat(Files.exists(Paths.get(d.screenshot))).isTrue();
        assertThat(d.problems).isEmpty();

        String report = FlowReport.format(flow("pricing walk"), result);
        assertThat(report).contains("[0] PASS press(Escape)").contains("ms=").contains("edtMs=");
        assertThat(report).contains("[1] FAIL click(").contains("NOT_FOUND")
                .contains("details:").contains("#save in dialog:Order")
                .contains("target button 'Save'").contains("matched 2:")
                .contains("tree of section 'Discounts'").contains("screenshot: ");
        // a passing step is one line; the failing one follows it directly
        String[] lines = report.split("\n");
        int pass = Arrays.asList(lines).indexOf(Arrays.stream(lines).filter(l -> l.startsWith("  [0] PASS")).findFirst().get());
        assertThat(lines[pass + 1]).startsWith("  [1] FAIL");
    }

    @Test
    void anIdScopeStepDumpsTheNamedComponentByIdInsideTheStepsBeforeIt() {
        Locator l = Locator.of("button", "Open");
        l.window = "main";
        l.scope = Arrays.asList("tab:Summary", "id:summary.column.ORD-1610");
        Locator scope = FailureDiagnostics.scopeOf(l);
        assertThat(scope.id).isEqualTo("summary.column.ORD-1610");
        assertThat(scope.role).isNull();
        assertThat(scope.name).isNull();
        assertThat(scope.window).isEqualTo("main");
        assertThat(scope.scope).containsExactly("tab:Summary");
    }

    @Test
    void anUnscopedTargetThatMatchedOnceDumpsThatElement() {
        failSecondCall();
        backend.verbs.candidates = Collections.singletonList("button 'Save' in main");
        FlowResult result = replay(PRESS, "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Save\"}}");
        assertThat(backend.verbs.calls).contains("snapshot null null button 'Save' "
                + FailureDiagnostics.ELEMENT_DEPTH + " " + FailureDiagnostics.MAX_NODES + " 0");
        assertThat(result.steps.get(1).diagnostics.scope).startsWith("the matched element");
    }

    @Test
    void aScopeThatDoesNotResolveFallsBackToTheWindowAndSaysSo() {
        failSecondCall();
        backend.verbs.scopedSnapshotFailure = new DriverException(Reason.NOT_FOUND, "nothing matches section 'Discounts'");
        FlowResult result = replay(PRESS, SCOPED_CLICK);
        StepDiagnostics d = result.steps.get(1).diagnostics;
        assertThat(d.problems).anyMatch(p -> p.startsWith("no tree for section 'Discounts'"));
        assertThat(d.scope).startsWith("window dialog");
        assertThat(backend.verbs.calls).contains("snapshot dialog null null " + FailureDiagnostics.SCOPE_DEPTH + " "
                + FailureDiagnostics.MAX_NODES + " 0");
    }

    @Test
    void anAppStepGetsAShallowTreeOfEveryWindow() {
        backend.verbs.candidates = Collections.emptyList();
        backend.failNavigateTo = "Nowhere";
        FlowResult result = replay("{\"action\":\"navigate\",\"target\":\"Nowhere\"}");
        StepResult failed = result.steps.get(0);
        assertThat(failed.passed).isFalse();
        assertThat(failed.diagnostics.matches).isEmpty();
        assertThat(failed.diagnostics.scope).startsWith("every window (depth " + FailureDiagnostics.APP_DEPTH);
    }

    @Test
    void withoutDiagnosisAStepCarriesNone() {
        failSecondCall();
        FlowResult result = new FlowExecutor().run(FlowJson.parse("{\"name\":\"t\",\"steps\":[" + PRESS + ","
                + SCOPED_CLICK + "]}"), backend);
        assertThat(result.steps.get(1).passed).isFalse();
        assertThat(result.steps.get(1).diagnostics).isNull();
        assertThat(backend.verbs.calls).noneMatch(c -> c.startsWith("candidates") || c.startsWith("screenshot"));
    }

    @Test
    void aScreenshotStepWritesItsPngAndSaysWhere() throws Exception {
        System.setProperty(ScreenshotVerb.DIR_PROP, dir.toString());
        FlowResult result = replay("{\"action\":\"screenshot\",\"window\":\"dialog:Validation*\","
                + "\"path\":\"shots/pricing.png\"}");
        StepResult step = result.steps.get(0);
        assertThat(step.passed).as(step.message).isTrue();
        Path png = dir.resolve("shots").resolve("pricing.png");
        assertThat(Files.readAllBytes(png)).isEqualTo(backend.verbs.shot.png);
        assertThat(step.message).startsWith("wrote ").contains("pricing.png").contains("(4x3)");
        assertThat(backend.verbs.calls).containsExactly("screenshot in dialog:Validation* null");
        assertThat(FlowReport.format(flow("x"), result)).contains("wrote ");
    }

    @Test
    void aScreenshotStepRefusesARefAndTwoTargets() {
        FlowResult ref = replay("{\"action\":\"screenshot\",\"target\":{\"ref\":\"e3\"},\"path\":\"a.png\"}");
        assertThat(ref.steps.get(0).reason).isEqualTo("BAD_INPUT");
        FlowResult both = replay("{\"action\":\"screenshot\",\"window\":\"main\",\"target\":{\"role\":\"tab\","
                + "\"name\":\"Pricing\"},\"path\":\"a.png\"}");
        assertThat(both.steps.get(0).reason).isEqualTo("BAD_INPUT");
    }

    private static Flow flow(String name) {
        Flow f = new Flow();
        f.name = name;
        return f;
    }
}
