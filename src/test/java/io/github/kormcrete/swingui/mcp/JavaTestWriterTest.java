package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.Expectation;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.flow.Action;
import io.github.kormcrete.swingui.flow.CloseAction;
import io.github.kormcrete.swingui.flow.Flow;
import io.github.kormcrete.swingui.flow.FlowGroup;
import io.github.kormcrete.swingui.flow.LoginAsAction;
import io.github.kormcrete.swingui.flow.NavigateAction;
import io.github.kormcrete.swingui.flow.verb.ClickVerb;
import io.github.kormcrete.swingui.flow.verb.ExpectVerb;
import io.github.kormcrete.swingui.flow.verb.HoverVerb;
import io.github.kormcrete.swingui.flow.verb.MenuVerb;
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

    // ---- Java layout: a step is one block (action + its checks), sentences and groups as comments

    private static ExpectVerb stateCheck(String role, String name, String state) {
        ExpectVerb e = new ExpectVerb();
        e.target = Locator.of(role, name);
        e.state = Collections.singletonList(state);
        return e;
    }

    private static <A extends Action> A say(A a, String say) { a.say = say; return a; }

    private static Flow flow(Action... steps) {
        Flow f = new Flow();
        f.steps.addAll(Arrays.asList(steps));
        return f;
    }

    private static String[] body(String java) {
        String head = "(Client ui) {\n";
        String b = java.substring(java.indexOf(head) + head.length(), java.lastIndexOf("    }\n}"));
        return b.split("\n", -1);
    }

    private static String[] bodyOf(Flow f) {
        return body(JavaTestWriter.write(f, "com.example.uitest.recorded", "SmokeUiIT", "walk"));
    }

    @Test
    void anActionAndItsChecksAreOneBlock() {
        ScreenshotVerb shot = new ScreenshotVerb();
        shot.path = "01.png";
        String[] lines = body(write(click(Locator.of("button", "New order")), stateCheck("tab", "New order", "selected"), shot,
                click(Locator.of("button", "Add to cart")), click(Locator.of("button", "Add to cart")),
                stateCheck("label", "€31.32", "!hidden")));
        assertThat(lines[0]).contains("button(\"New order\").click()");
        assertThat(lines[1]).contains("assertThat(ui.tab(\"New order\"))");
        assertThat(lines[2]).contains("saveScreenshot");
        assertThat(lines[3]).isEmpty();                       // checking turns back to acting
        assertThat(lines[4]).contains("Add to cart");
        assertThat(lines[5]).contains("Add to cart");         // check-less steps read as one sequence
        assertThat(lines[6]).contains("€31.32");              // no blank between action and its check
        assertThat(lines).hasSize(8);
        assertThat(lines[7]).isEmpty();
    }

    @Test
    void checklessStepsReadAsOneSequence() {
        String[] lines = body(write(click(Locator.of("button", "A")), click(Locator.of("button", "B")),
                click(Locator.of("button", "C"))));
        assertThat(lines).hasSize(4);
        assertThat(lines[0]).contains("\"A\"");
        assertThat(lines[2]).contains("\"C\"");
    }

    @Test
    void aStepSentenceIsACommentAboveItsBlock() {
        String[] lines = bodyOf(flow(click(Locator.of("button", "A")),
                say(click(Locator.of("button", "B")), "Add the second product"),
                stateCheck("label", "x", "!hidden")));
        assertThat(lines[0]).contains("\"A\"");
        assertThat(lines[1]).isEmpty();
        assertThat(lines[2]).isEqualTo("        // Add the second product");
        assertThat(lines[3]).contains("\"B\"");
        assertThat(lines[4]).contains("assertThat");
    }

    @Test
    void aCheckSentenceIsACommentAboveTheCheck() {
        String[] lines = bodyOf(flow(click(Locator.of("button", "A")),
                say(stateCheck("label", "x", "!hidden"), "The total is shown"),
                stateCheck("label", "y", "!hidden")));
        assertThat(lines[0]).contains("\"A\"");
        assertThat(lines[1]).isEqualTo("        // The total is shown");
        assertThat(lines[2]).contains("assertThat(ui.label(\"x\"))");
        assertThat(lines[3]).contains("assertThat(ui.label(\"y\"))");
    }

    @Test
    void aGroupIsACommentAboveItsFirstStep() {
        Flow f = flow(click(Locator.of("button", "A")), click(Locator.of("button", "B")),
                say(click(Locator.of("button", "C")), "Third"), click(Locator.of("button", "D")));
        f.groups.add(new FlowGroup(1, 2, "Fill the cart"));
        String[] lines = bodyOf(f);
        assertThat(lines[0]).contains("\"A\"");
        assertThat(lines[1]).isEmpty();
        assertThat(lines[2]).isEqualTo("        // Fill the cart");
        assertThat(lines[3]).contains("\"B\"");
        assertThat(lines[4]).isEmpty();                       // the step with a sentence
        assertThat(lines[5]).isEqualTo("        // Third");
        assertThat(lines[6]).contains("\"C\"");
        assertThat(lines[7]).contains("\"D\"");
    }

    @Test
    void aGroupAndTheSentenceOfItsFirstStepShareOneBlankLine() {
        Flow f = flow(click(Locator.of("button", "A")), say(click(Locator.of("button", "B")), "Second"));
        f.groups.add(new FlowGroup(1, 1, "Group"));
        String[] lines = bodyOf(f);
        assertThat(lines[1]).isEmpty();
        assertThat(lines[2]).isEqualTo("        // Group");
        assertThat(lines[3]).isEqualTo("        // Second");
        assertThat(lines[4]).contains("\"B\"");
    }

    @Test
    void aSentenceOnTheLeadingLoginIsTheFirstBodyLine() {
        LoginAsAction login = new LoginAsAction();
        login.as = "ada";
        login.say = "Sign in as Ada";
        Flow f = flow(login, click(Locator.of("button", "A")));
        f.groups.add(new FlowGroup(0, 1, "Whole test"));
        String java = JavaTestWriter.write(f, "p", "SmokeUiIT", "walk");
        assertThat(java).contains("@LoginAs(\"ada\")");
        String[] lines = body(java);
        assertThat(lines[0]).isEqualTo("        // Whole test");
        assertThat(lines[1]).isEqualTo("        // Sign in as Ada");
        assertThat(lines[2]).contains("\"A\"");
    }

    @Test
    void commentsAreSafeForJavac() throws Exception {
        Flow f = flow(say(click(Locator.of("button", "A")), "evil \\u000a ui.hack(); */ and \\\\u000a and \r\n more"));
        f.groups.add(new FlowGroup(0, 0, "grp \\u000d"));
        String src = JavaTestWriter.write(f, "p", "SmokeUiIT", "walk");
        // every line of the comment stays a comment: no raw line break and no eligible unicode escape
        for (String line : src.split("\n")) {
            if (line.contains("evil") || line.contains("grp")) assertThat(line.trim()).startsWith("//");
        }
        assertThat(src).doesNotContain("\r");
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("jw");
        java.nio.file.Path file = dir.resolve("SmokeUiIT.java");
        java.nio.file.Files.write(file, src.getBytes("UTF-8"));
        javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertThat(javac).isNotNull();
        int rc = javac.run(null, null, null, "-encoding", "UTF-8", "-classpath", System.getProperty("java.class.path"),
                "-d", dir.toString(), file.toString());
        assertThat(rc).as("generated source compiles:\n" + src).isZero();
    }

    @Test
    void linesStayOneEntryPerRecordWithoutComments() {
        Flow f = flow(say(click(Locator.of("button", "A")), "First"), say(stateCheck("label", "x", "!hidden"), "Shown"),
                click(Locator.of("button", "B")));
        f.groups.add(new FlowGroup(0, 1, "Group"));
        List<String> lines = JavaTestWriter.lines(f);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).isEqualTo("ui.button(\"A\").click();");
        assertThat(lines.get(1)).startsWith("assertThat(ui.label(\"x\"))");
        assertThat(lines.get(2)).isEqualTo("ui.button(\"B\").click();");
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
        // A step type with no Java form: the recorded test schema has none, so a bare Action stands in.
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

    @Test
    void oneJavaLinePerStep() {
        LoginAsAction login = new LoginAsAction(); login.as = "ada";
        MenuVerb menu = new MenuVerb(); menu.path = Arrays.asList("File", "New order");
        Locator next = Locator.of("button", "Next"); next.nth = 1;
        ClickVerb fragile = click(next); fragile.fragile = true;
        TypeVerb type = new TypeVerb(); type.target = Locator.of("textbox", "Customer"); type.text = "Ada";
        ExpectVerb cell = new ExpectVerb(); cell.target = Locator.of("table", "Orders");
        cell.cell = new Expectation.Cell();
        cell.cell.row = Collections.<String, Object>singletonMap("Order no.", "ORD-1640");
        cell.cell.column = "Customer"; cell.cell.expected = "Ada";
        List<Action> steps = Arrays.<Action>asList(login, menu, fragile, type, cell);

        List<String> lines = JavaTestWriter.lines(steps);

        assertThat(lines).hasSize(5);
        assertThat(lines.get(0)).isEqualTo("@LoginAs(\"ada\")");
        assertThat(lines.get(2)).endsWith("  // FRAGILE: the recorder needed nth to make this target unique");
        String java = JavaTestWriter.write(steps, "com.example.uitest.recorded", "SmokeUiIT", "walk");
        for (int i = 1; i < lines.size(); i++) {
            String statement = lines.get(i).replace("  // FRAGILE: the recorder needed nth to make this target unique", "");
            assertThat(java).contains("        " + statement + "\n");
        }
    }

    @Test
    void linesRefuseWhatWriteRefuses() {
        Locator point = Locator.of("clickable", "Canvas");
        point.point = new LinkedHashMap<String, Double>();
        point.point.put("x", 0.5);
        point.point.put("y", 0.5);
        ExportRefused e = catchThrowableOfType(() -> JavaTestWriter.lines(Arrays.<Action>asList(click(point))),
                ExportRefused.class);
        assertThat(e.problems().get(0)).contains("step 1");

        HoverVerb hover = new HoverVerb(); hover.target = Locator.of("button", "Save");
        assertThat(JavaTestWriter.lines(Arrays.<Action>asList(hover))).containsExactly("ui.button(\"Save\").hover();");
    }
}
