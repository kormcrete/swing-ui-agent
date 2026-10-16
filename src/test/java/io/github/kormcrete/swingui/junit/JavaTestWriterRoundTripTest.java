package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.testing.RecordingApp;
import io.github.kormcrete.swingui.testing.RecordingVerbs;

import io.github.kormcrete.swingui.driver.Expectation;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.flow.Action;
import io.github.kormcrete.swingui.flow.CloseAction;
import io.github.kormcrete.swingui.flow.ContextMenuAction;
import io.github.kormcrete.swingui.flow.NavigateAction;
import io.github.kormcrete.swingui.flow.verb.*;
import io.github.kormcrete.swingui.mcp.JavaTestWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaTestWriterRoundTripTest {

    @TempDir Path dir;

    /** One of each exportable step type, with scopes, rows, timeouts and a fragile nth. */
    static List<Action> allSteps() {
        List<Action> s = new ArrayList<Action>();
        NavigateAction nav = new NavigateAction(); nav.target = "orders-finder"; s.add(nav);
        Locator save = Locator.of("button", "Save"); save.window = "dialog:Assign";
        save.scope = Collections.singletonList("tab:Line items");
        ClickVerb click = new ClickVerb(); click.target = save; s.add(click);
        ClickVerb dbl = new ClickVerb(); dbl.target = table("Orders", "Order no.", "ORD-1640"); dbl.count = 2; s.add(dbl);
        ClickVerb right = new ClickVerb(); right.target = Locator.of("clickable", "x"); right.button = "right"; s.add(right);
        TypeVerb fill = new TypeVerb(); fill.target = Locator.of("text", "Short"); fill.text = "Ä \"q\""; fill.replace = true; fill.commit = true; s.add(fill);
        TypeVerb type = new TypeVerb(); type.target = Locator.of("text", "Note"); type.text = "abc"; s.add(type);
        PressVerb press = new PressVerb(); press.keys = "ctrl S"; s.add(press);
        CheckVerb check = new CheckVerb(); check.target = Locator.of("checkbox", "Active"); check.checked = false; s.add(check);
        SelectVerb select = new SelectVerb(); select.target = Locator.of("combo", "Status"); select.item = "Approved"; s.add(select);
        PickVerb pick = new PickVerb(); pick.path = Arrays.asList("Statistics"); s.add(pick);
        MenuVerb menu = new MenuVerb(); menu.path = Arrays.asList("File", "Export"); menu.timeout = 5000L; s.add(menu);
        AnswerVerb answer = new AnswerVerb(); answer.button = "OK"; s.add(answer);
        ChooseFileVerb choose = new ChooseFileVerb(); choose.path = "/tmp/x.pdf"; s.add(choose);
        DragVerb drag = new DragVerb(); drag.from = Locator.of("label", "a"); drag.to = Locator.of("label", "b"); s.add(drag);
        DropVerb drop = new DropVerb(); drop.target = Locator.of("table", "Attachments"); drop.files = Arrays.asList("/tmp/a.txt"); s.add(drop);
        // The driver takes exactly one of to/pages/row, so the writer maps ScrollVerb to
        // scrollTo/scrollBy/scrollIntoView - covered one each below.
        SetClipboardVerb clip = new SetClipboardVerb(); clip.text = "7.2\t<0.5\n12\tn/a\n"; s.add(clip);
        PressVerb paste = new PressVerb(); paste.keys = "Mod+V"; paste.target = Locator.of("table", "Orders"); s.add(paste);
        ScrollVerb scrollTo = new ScrollVerb(); scrollTo.target = Locator.of("table", "Orders"); scrollTo.to = "bottom"; s.add(scrollTo);
        ScrollVerb scrollBy = new ScrollVerb(); scrollBy.target = Locator.of("table", "Orders"); scrollBy.pages = 2; s.add(scrollBy);
        ScrollVerb scrollIntoView = new ScrollVerb(); scrollIntoView.target = table("Orders", "Order no.", "26-3"); s.add(scrollIntoView);
        ContextMenuAction ctx = new ContextMenuAction(); ctx.target = table("Orders", "Order no.", "26-1"); ctx.path = Arrays.asList("Delete"); s.add(ctx);
        HoverVerb hover = new HoverVerb(); hover.target = Locator.of("button", "Save"); s.add(hover);
        HoverVerb unhover = new HoverVerb(); unhover.target = Locator.of("button", "Save"); unhover.inside = false; s.add(unhover);
        Locator nameColumn = Locator.of("table", "Orders"); nameColumn.column = "Order no.";
        ResizeColumnVerb narrow = new ResizeColumnVerb(); narrow.target = nameColumn; narrow.delta = -40; s.add(narrow);
        ResizeWindowVerb resize = new ResizeWindowVerb(); resize.window = "main"; resize.width = 1200; resize.height = 800; s.add(resize);
        CloseWindowVerb closeHint = new CloseWindowVerb(); closeHint.window = "dialog:Notice"; s.add(closeHint);
        s.add(new FocusFileChooserNameVerb());
        Locator nextButton = Locator.of("button", "Next"); nextButton.nth = 1;
        ClickVerb fragile = new ClickVerb(); fragile.target = nextButton; fragile.fragile = true; s.add(fragile);
        // The recorder's own fragile fallback (driver/model/Recorder.java) can leave 'name' null: role + nth only.
        Locator noName = Locator.of("text", null); noName.nth = 3;
        ClickVerb fragileNoName = new ClickVerb(); fragileNoName.target = noName; fragileNoName.fragile = true; s.add(fragileNoName);
        s.add(expectState("!disabled"));
        s.add(expectState("editable", "!disabled"));
        ExpectVerb rows = new ExpectVerb(); rows.target = Locator.of("table", "Orders");
        rows.rows = new Expectation.Count(); rows.rows.op = "gte"; rows.rows.value = 1; rows.timeout = 20_000L; s.add(rows);
        ExpectVerb cell = new ExpectVerb(); cell.target = Locator.of("table", "Orders");
        cell.cell = new Expectation.Cell(); cell.cell.row = table("Orders", "Order no.", "26-2").row;
        cell.cell.column = "Customer"; cell.cell.expected = "Test"; s.add(cell);
        ExpectVerb value = new ExpectVerb(); value.target = Locator.of("text", "Short"); value.value = "Widget"; s.add(value);
        ExpectVerb byId = new ExpectVerb(); byId.target = Locator.ofId("orderList.export.pdf"); byId.state = Collections.singletonList("disabled"); s.add(byId);
        ExpectVerb shown = new ExpectVerb(); shown.target = Locator.of("tab", "Orders"); shown.shown = true; s.add(shown);
        ExpectVerb absent = new ExpectVerb(); absent.target = Locator.of("tab", "ORD-1640"); absent.absent = true; s.add(absent);
        s.add(new CloseAction());
        return s;
    }

    private static Locator table(String name, String column, String value) {
        Locator l = Locator.of("table", name);
        l.row = new java.util.LinkedHashMap<String, Object>();
        l.row.put(column, value);
        return l;
    }

    private static ExpectVerb expectState(String... tokens) {
        ExpectVerb x = new ExpectVerb(); x.target = Locator.of("button", "Save"); x.state = Arrays.asList(tokens); return x;
    }

    @Test
    void theExportedTestMakesExactlyTheRecordedCalls() throws Exception {
        List<Action> steps = allSteps();

        RecordingVerbs direct = new RecordingVerbs();
        for (Action a : steps) {
            // navigate and close go to the app hooks, as FlowExecutor sends them to its StepHost
            if (a instanceof NavigateAction) direct.calls.add("hook navigate " + ((NavigateAction) a).target);
            else if (a instanceof CloseAction) direct.calls.add("hook closeActiveTab");
            else ((VerbAction) a).run(direct);
        }

        String src = JavaTestWriter.write(steps, "rt", "RoundTripUiIT", "walk");
        RecordingVerbs viaJava = new RecordingVerbs();
        run(src, new Client(viaJava, new RecordingApp(viaJava.calls), "rt"));

        assertThat(viaJava.calls).containsExactlyElementsOf(direct.calls);
    }

    @Test
    void theGoldenSourceMatches() throws Exception {
        String src = JavaTestWriter.write(allSteps(), "rt", "RoundTripUiIT", "walk");
        String golden = new String(Files.readAllBytes(
                java.nio.file.Paths.get("src", "test", "resources", "export", "all-verbs.java.txt")), StandardCharsets.UTF_8);
        assertThat(src).isEqualTo(golden);
    }

    @Test
    void aDescribedExportCompilesAndMakesTheRecordedCalls() throws Exception {
        Locator add = Locator.of("button", "Add to cart");
        ClickVerb first = new ClickVerb(); first.target = add; first.say = "Add the first product";
        ExpectVerb shown = new ExpectVerb(); shown.target = Locator.of("label", "Total"); shown.state = Collections.singletonList("!hidden");
        shown.say = "The total is shown";
        ClickVerb second = new ClickVerb(); second.target = Locator.of("button", "Checkout"); second.say = "Go to checkout";
        io.github.kormcrete.swingui.flow.Flow flow = new io.github.kormcrete.swingui.flow.Flow();
        flow.steps.addAll(Arrays.<Action>asList(first, shown, second));
        flow.groups.add(new io.github.kormcrete.swingui.flow.FlowGroup(0, 1, "Fill the cart"));

        RecordingVerbs direct = new RecordingVerbs();
        for (Action a : flow.steps) ((VerbAction) a).run(direct);

        String src = JavaTestWriter.write(flow, "rt", "RoundTripUiIT", "walk");
        assertThat(src).contains("// Fill the cart", "// Add the first product", "// The total is shown", "// Go to checkout");
        RecordingVerbs viaJava = new RecordingVerbs();
        run(src, new Client(viaJava, new RecordingApp(viaJava.calls), "rt"));
        assertThat(viaJava.calls).containsExactlyElementsOf(direct.calls);
    }

    @Test
    void anItemAndAPointRoundTrip() throws Exception {
        Locator entry = Locator.of("list", null); entry.window = "popup"; entry.item = Arrays.asList("Ada Shop Ltd");
        ClickVerb pickEntry = new ClickVerb(); pickEntry.target = entry;
        Locator node = Locator.of("tree", "Categories"); node.item = Arrays.asList("Paper", "A4 paper, 500 sheets");
        ClickVerb pickNode = new ClickVerb(); pickNode.target = node;
        Locator spot = Locator.of("clickable", "Canvas"); spot.point = new java.util.LinkedHashMap<String, Double>();
        spot.point.put("x", 0.5); spot.point.put("y", 0.25);
        ClickVerb clickSpot = new ClickVerb(); clickSpot.target = spot;
        List<Action> steps = Arrays.<Action>asList(pickEntry, pickNode, clickSpot);

        RecordingVerbs direct = new RecordingVerbs();
        for (Action a : steps) ((VerbAction) a).run(direct);
        String src = JavaTestWriter.write(steps, "rt", "RoundTripUiIT", "walk");
        RecordingVerbs viaJava = new RecordingVerbs();
        run(src, new Client(viaJava, new RecordingApp(viaJava.calls), "rt"));

        assertThat(viaJava.calls).containsExactlyElementsOf(direct.calls);
        assertThat(viaJava.calls.get(0)).contains("Ada Shop Ltd");
    }

    @Test
    void aScaledScreenshotStepRoundTrips() throws Exception {
        ScreenshotVerb shot = new ScreenshotVerb(); shot.path = "shots/a.png"; shot.scale = 2.0;
        List<Action> steps = Collections.<Action>singletonList(shot);

        String src = JavaTestWriter.write(steps, "rt", "RoundTripUiIT", "walk");
        RecordingVerbs viaJava = new RecordingVerbs();
        run(src, new Client(viaJava, new RecordingApp(viaJava.calls), "rt", dir.resolve("shots")));

        assertThat(viaJava.calls).containsExactly("screenshot null 2.0");
    }

    private void run(String src, Client client) throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("rt"));
        Path file = pkg.resolve("RoundTripUiIT.java");
        Files.write(file, src.getBytes(StandardCharsets.UTF_8));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertThat(javac).as("tests must run on a JDK").isNotNull();
        int rc = javac.run(null, null, null, "-encoding", "UTF-8", "-classpath", System.getProperty("java.class.path"),
                "-d", dir.toString(), file.toString());
        assertThat(rc).as("generated source compiles:\n" + src).isZero();
        try (URLClassLoader cl = new URLClassLoader(new URL[] {dir.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> c = cl.loadClass("rt.RoundTripUiIT");
            java.lang.reflect.Constructor<?> ctor = c.getDeclaredConstructor();
            ctor.setAccessible(true);
            Method m = c.getDeclaredMethod("walk", Client.class);
            m.setAccessible(true);
            m.invoke(ctor.newInstance(), client);
        }
    }
}
