package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.flow.Action;
import io.github.kormcrete.swingui.flow.CloseAction;
import io.github.kormcrete.swingui.flow.LoginAsAction;
import io.github.kormcrete.swingui.flow.NavigateAction;
import io.github.kormcrete.swingui.flow.verb.ClickVerb;
import io.github.kormcrete.swingui.flow.verb.ExpectVerb;
import io.github.kormcrete.swingui.flow.verb.ScreenshotVerb;
import io.github.kormcrete.swingui.flow.verb.TypeVerb;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class JavaTestWriterTest {

    private static ClickVerb click(Locator l) { ClickVerb c = new ClickVerb(); c.target = l; return c; }

    private static String write(Action... steps) {
        return JavaTestWriter.write(Arrays.asList(steps), "com.example.uitest.recorded", "SmokeUiIT", "walk");
    }

    @Test
    void aClickIsOneStatementWithTheChain() {
        Locator l = Locator.of("button", "Save");
        l.window = "dialog:Assign";
        l.scope = Collections.singletonList("tab:Line items");
        assertThat(write(click(l))).contains("        ui.dialog(\"Assign\").tab(\"Line items\").button(\"Save\").click();");
    }

    @Test
    void anIdScopeStepIsWrittenAsTheByIdItWasChainedFrom() {
        Locator l = Locator.of("button", "Open");
        l.scope = Arrays.asList("tab:Summary", "id:summary.column.ORD-1610");
        assertThat(write(click(l)))
                .contains("        ui.tab(\"Summary\").byId(\"summary.column.ORD-1610\").button(\"Open\").click();");
    }

    @Test
    void literalsAreEscapedAndUnicodeKept() {
        TypeVerb t = new TypeVerb();
        t.target = Locator.of("text", "Note \"draft\"");
        t.text = "a\\b\nTotal 5 € ▾";
        t.replace = true; t.commit = true;
        assertThat(write(t)).contains("ui.field(\"Note \\\"draft\\\"\").fill(\"a\\\\b\\nTotal 5 € ▾\");");
    }

    @Test
    void aFragileTargetGetsAComment() {
        Locator l = Locator.of("button", "Next"); l.nth = 1;
        ClickVerb c = click(l); c.fragile = true;
        assertThat(write(c)).contains("// FRAGILE:").contains("ui.button(\"Next\").nth(1).click();");
    }

    @Test
    void loginAsFirstBecomesTheAnnotation() {
        LoginAsAction login = new LoginAsAction(); login.as = "customer";
        assertThat(write(login, click(Locator.of("button", "OK"))))
                .contains("    @LoginAs(\"customer\")\n    void walk(Client ui) {")
                .doesNotContain("loginAs(");
    }

    @Test
    void everyProblemIsListedInOneRefusal() {
        // A step type with no Java form: the flow schema has none, so a bare Action stands in.
        Action noJavaForm = new Action() {
            @Override public String describe() { return "noJavaForm"; }
        };
        Locator byRef = Locator.ofRef("e3");
        LoginAsAction late = new LoginAsAction(); late.as = "x";
        NavigateAction nowhere = new NavigateAction();
        ExportRefused e = catchThrowableOfType(() -> write(noJavaForm, click(byRef), late, nowhere), ExportRefused.class);
        List<String> p = e.problems();
        assertThat(p).hasSize(4);
        assertThat(p.get(0)).contains("step 1", "Java form");
        assertThat(p.get(1)).contains("step 2", "ref");
        assertThat(p.get(2)).contains("step 3", "loginAs");
        assertThat(p.get(3)).contains("step 4", "navigate needs a target");
    }

    @Test
    void navigateAndCloseHaveTheirClientForms() {
        NavigateAction nav = new NavigateAction(); nav.target = "orders-finder";
        assertThat(write(nav, click(Locator.of("button", "OK")), new CloseAction()))
                .contains("        ui.navigate(\"orders-finder\");\n"
                        + "        ui.button(\"OK\").click();\n"
                        + "        ui.closeActiveTab();\n");
    }

    @Test
    void nothingRecordedIsRefused() {
        ExportRefused e = catchThrowableOfType(() -> JavaTestWriter.write(Collections.<Action>emptyList(),
                "p", "SmokeUiIT", "walk"), ExportRefused.class);
        assertThat(e.problems()).containsExactly("nothing was recorded");
    }

    @Test
    void aClassNameMustEndInUiIT() {
        ExportRefused e = catchThrowableOfType(() -> JavaTestWriter.write(Arrays.<Action>asList(click(Locator.of("button", "OK"))),
                "p", "Smoke", "walk"), ExportRefused.class);
        assertThat(e.problems().get(0)).contains("UiIT");
    }

    @Test
    void anExpectWithATimeoutUsesWithin() {
        ExpectVerb x = new ExpectVerb();
        x.target = Locator.of("button", "OK");
        x.state = Collections.singletonList("!disabled");
        x.timeout = 90_000L;
        String src = write(x);
        assertThat(src).contains("assertThat(ui.button(\"OK\")).within(Duration.ofMillis(90000L)).isEnabled();")
                .contains("import java.time.Duration;");
    }

    @Test
    void aScaledScreenshotKeepsItsScale() {
        ScreenshotVerb s = new ScreenshotVerb();
        s.path = "shots/a.png";
        s.scale = 2.0;
        assertThat(write(s)).contains("ui.saveScreenshot(\"shots/a.png\", 2);");
        ScreenshotVerb w = new ScreenshotVerb();
        w.path = "shots/b.png";
        w.window = "dialog";
        w.scale = 1.5;
        assertThat(write(w)).contains("ui.inWindow(\"dialog\").saveScreenshot(\"shots/b.png\", 1.5);");
    }

    @Test
    void aScreenshotWithNoWindowOrTargetSavesDirectly() {
        ScreenshotVerb s = new ScreenshotVerb();
        s.path = "shots/a.png";
        assertThat(write(s)).contains("ui.saveScreenshot(\"shots/a.png\");");
    }

    @Test
    void anItemLocatorIsRefused() {
        Locator l = Locator.of("list", "Favourites");
        l.item = Arrays.asList("a");
        ExportRefused e = catchThrowableOfType(() -> write(click(l)), ExportRefused.class);
        assertThat(e.problems().get(0)).contains("step 1", "item");
    }

    @Test
    void aPointLocatorIsRefused() {
        Locator l = Locator.of("clickable", "Canvas");
        l.point = new LinkedHashMap<>();
        l.point.put("x", 0.5);
        l.point.put("y", 0.5);
        ExportRefused e = catchThrowableOfType(() -> write(click(l)), ExportRefused.class);
        assertThat(e.problems().get(0)).contains("step 1", "point");
    }

    @Test
    void aClassNameEndingInUiITButOtherwiseInvalidIsRefused() {
        ExportRefused e = catchThrowableOfType(() -> JavaTestWriter.write(Arrays.<Action>asList(click(Locator.of("button", "OK"))),
                "p", "my-UiIT", "walk"), ExportRefused.class);
        assertThat(e.problems().get(0)).contains("UiIT");
    }

    @Test
    void aBadMethodNameIsRefused() {
        ExportRefused e = catchThrowableOfType(() -> JavaTestWriter.write(Arrays.<Action>asList(click(Locator.of("button", "OK"))),
                "p", "SmokeUiIT", "Walk"), ExportRefused.class);
        assertThat(e.problems().get(0)).contains("method");
    }

    @Test
    void aRowWithMoreThanTwoKeysIsRefused() {
        Locator l = Locator.of("table", "Orders");
        l.row = new LinkedHashMap<>();
        l.row.put("a", "1"); l.row.put("b", "2"); l.row.put("c", "3");
        ExportRefused e = catchThrowableOfType(() -> write(click(l)), ExportRefused.class);
        assertThat(e.problems().get(0)).contains("step 1", "more than two keys");
    }

    @Test
    void aRoleWithNoNameEmitsALiteralNullRatherThanCrashing() {
        // The recorder's own fragile fallback can leave 'name' null: role + nth only.
        Locator l = Locator.of("text", null); l.nth = 3;
        assertThat(write(click(l))).contains("ui.field(null).nth(3).click();");
    }

    @Test
    void aNullClassNameIsRefused() {
        ExportRefused e = catchThrowableOfType(() -> JavaTestWriter.write(Arrays.<Action>asList(click(Locator.of("button", "OK"))),
                "p", null, "walk"), ExportRefused.class);
        assertThat(e.problems().get(0)).contains("class name");
    }

    @Test
    void aNullMethodNameIsRefused() {
        ExportRefused e = catchThrowableOfType(() -> JavaTestWriter.write(Arrays.<Action>asList(click(Locator.of("button", "OK"))),
                "p", "SmokeUiIT", null), ExportRefused.class);
        assertThat(e.problems().get(0)).contains("method name");
    }

    @Test
    void aNullPackageNameIsRefused() {
        ExportRefused e = catchThrowableOfType(() -> JavaTestWriter.write(Arrays.<Action>asList(click(Locator.of("button", "OK"))),
                null, "SmokeUiIT", "walk"), ExportRefused.class);
        assertThat(e.problems().get(0)).contains("package name");
    }

    @Test
    void aNullLoginAliasIsRefused() {
        LoginAsAction login = new LoginAsAction(); login.as = null;
        ExportRefused e = catchThrowableOfType(() -> write(login, click(Locator.of("button", "OK"))), ExportRefused.class);
        assertThat(e.problems().get(0)).contains("loginAs");
    }

    @Test
    void writesTheWindowAndPointerSteps() throws Exception {
        io.github.kormcrete.swingui.flow.verb.HoverVerb hover = new io.github.kormcrete.swingui.flow.verb.HoverVerb();
        hover.target = Locator.of("button", "OK");
        io.github.kormcrete.swingui.flow.verb.HoverVerb unhover = new io.github.kormcrete.swingui.flow.verb.HoverVerb();
        unhover.target = Locator.of("button", "OK");
        unhover.inside = false;
        io.github.kormcrete.swingui.flow.verb.ResizeColumnVerb col = new io.github.kormcrete.swingui.flow.verb.ResizeColumnVerb();
        col.target = Locator.of("table", null);
        col.target.column = "Name";
        col.delta = -40;
        io.github.kormcrete.swingui.flow.verb.ResizeWindowVerb size = new io.github.kormcrete.swingui.flow.verb.ResizeWindowVerb();
        size.window = "main"; size.width = 1200; size.height = 800;
        io.github.kormcrete.swingui.flow.verb.CloseWindowVerb close = new io.github.kormcrete.swingui.flow.verb.CloseWindowVerb();
        close.window = "dialog:Notice";
        String src = write(hover, unhover, col, size, close, new io.github.kormcrete.swingui.flow.verb.FocusFileChooserNameVerb());
        assertThat(src).contains("ui.button(\"OK\").hover();", "ui.button(\"OK\").unhover();",
                ".resizeColumn(-40);", "ui.main().resizeTo(1200, 800);", "ui.dialog(\"Notice\").close();",
                "ui.focusFileChooserName();");
    }
}
