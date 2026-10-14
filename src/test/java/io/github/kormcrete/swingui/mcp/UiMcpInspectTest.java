package io.github.kormcrete.swingui.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.inspect.Inspector;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.flow.Flow;
import io.github.kormcrete.swingui.flow.FlowFiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** The person's steps go into the same recording as the agent's; replay, redo and delete work on it. */
class UiMcpInspectTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UiMcpReadToolsTest.DriverHost backend = new UiMcpReadToolsTest.DriverHost();
    private final UiMcpSession session = new UiMcpSession(backend, new UiMcpToolsTest.FakeInventory(), mapper);
    private final FolderMemory memory = FolderMemory.inMemory();
    private Inspector.Sink sink;
    @TempDir Path testsDir;

    @BeforeEach
    void setUp() {
        session.folderMemory(memory);
        session.startRecording(true);
        sink = session.humanSink();
        System.setProperty(UiMcpSession.TESTS_DIR_PROP, testsDir.toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(UiMcpSession.TESTS_DIR_PROP);
    }

    @Test
    void withoutThePaletteTheToolRefusesAndSaysHowItIsTurnedOff() throws Exception {
        DriverException e = catchThrowableOfType(() -> session.inspect(mapper.readTree("{\"op\":\"pick\"}")),
                DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.CAPABILITY_GAP);
        assertThat(e.getMessage()).contains("the palette is off").contains("palette=off");
    }

    @Test
    void aStepThePersonDidIsRecordedWithoutRunningAgain() {
        Inspector.Result r = sink.done(step("click", Locator.ofId("save")));
        assertThat(r.ok).isTrue();
        assertThat(lines()).containsExactly("click(#save)");
        assertThat(backend.verbs.calls).isEmpty();
    }

    @Test
    void aStepExportCannotWriteIsNotRecordedAndSaysWhy() {
        Locator at = Locator.ofId("canvas");
        at.point = new LinkedHashMap<String, Double>();
        at.point.put("x", 0.5);
        at.point.put("y", 0.5);
        Inspector.Result r = sink.done(step("click", at));
        assertThat(r.ok).isFalse();
        assertThat(lines()).isEmpty();
    }

    @Test
    void aCellCheckIsBoxedOnTheCellNotTheWholeTable() {
        Map<String, Object> s = step("expect", Locator.of("table", null));
        Map<String, Object> cell = new LinkedHashMap<String, Object>();
        cell.put("row", Collections.singletonMap("Order", "ORD-1640"));
        cell.put("column", "Status");
        cell.put("expected", "Open");
        s.put("cell", cell);
        assertThat(sink.done(s).ok).isTrue();
        Locator target = sink.target(0);
        assertThat(target.role).isEqualTo("table");
        assertThat(target.row).containsEntry("Order", "ORD-1640");
        assertThat(target.column).isEqualTo("Status");
    }

    @Test
    void replayRunsAStepAgainWithoutRecordingIt() {
        sink.done(step("click", Locator.ofId("save")));
        Inspector.Result r = sink.replay(0);
        assertThat(r.ok).isTrue();
        assertThat(backend.verbs.calls).anyMatch(c -> c.startsWith("click"));
        assertThat(lines()).hasSize(1);
    }

    @Test
    void redoCutsTheRecordingAndDeleteDropsOneStep() {
        sink.done(step("click", Locator.ofId("new")));
        sink.done(step("click", Locator.ofId("save")));
        sink.done(step("click", Locator.ofId("delete")));
        assertThat(sink.delete(id(1)).line).isEqualTo("deleted step 2");
        assertThat(lines()).containsExactly("click(#new)", "click(#delete)");
        assertThat(sink.cutFrom(id(1)).line).isEqualTo("cut from step 2");
        assertThat(lines()).containsExactly("click(#new)");
    }

    @Test
    void thePersonsDeleteAndRedoAreUndoable() {
        sink.done(step("click", Locator.ofId("new")));
        sink.done(step("click", Locator.ofId("save")));
        sink.done(step("click", Locator.ofId("delete")));
        assertThat(sink.undoLabel()).isNull();

        assertThat(sink.delete(id(1)).ok).isTrue();
        assertThat(sink.undoLabel()).isEqualTo("delete of step 2 by the person");
        Inspector.Result undone = sink.undo();
        assertThat(undone.ok).isTrue();
        assertThat(undone.line).isEqualTo("delete of step 2 by the person");
        assertThat(lines()).containsExactly("click(#new)", "click(#save)", "click(#delete)");

        assertThat(sink.cutFrom(id(1)).ok).isTrue();
        assertThat(sink.undoLabel()).endsWith("by the person");
        assertThat(sink.undo().ok).isTrue();
        assertThat(lines()).containsExactly("click(#new)", "click(#save)", "click(#delete)");
        // the steps keep their ids through the undo
        assertThat(sink.steps()).extracting(s -> s.author).containsOnly("person");

        Inspector.Result nothing = sink.undo();
        assertThat(nothing.ok).isFalse();
        assertThat(nothing.line).contains("nothing to undo");
    }

    @Test
    void aDeleteOfAStepThatIsGoneSaysSo() {
        sink.done(step("click", Locator.ofId("save")));
        int id = id(0);
        assertThat(sink.delete(id).ok).isTrue();
        Inspector.Result again = sink.delete(id);
        assertThat(again.ok).isFalse();
        assertThat(again.line).contains("gone");
        Inspector.Result cut = sink.cutFrom(id);
        assertThat(cut.ok).isFalse();
        assertThat(cut.line).contains("gone");
        assertThat(lines()).isEmpty();
    }

    @Test
    void theStepsListSaysWhoMadeEachStepAndWhichWereEdited() throws Exception {
        sink.done(step("click", Locator.ofId("new")));
        session.verb("click", mapper.readTree("{\"target\":{\"id\":\"save\"}}"));
        session.recording(mapper.readTree("{\"op\":\"insert\",\"at\":1,\"step\":{\"action\":\"click\",\"target\":{\"id\":\"x\"}}}"));
        List<Inspector.StepLine> steps = sink.steps();
        assertThat(steps).extracting(s -> s.author).containsExactly("agent", "person", "agent");
        assertThat(steps).extracting(s -> s.edited).containsExactly(true, false, false);
        assertThat(steps.get(0).line).isEqualTo("click(#x)");
        // a replay from the palette that passes the edited step takes its edit mark away
        assertThat(sink.replay(0).ok).isTrue();
        assertThat(sink.steps().get(0).edited).isFalse();
    }

    @Test
    void aCheckThePersonChoseRunsAndIsRecordedWhenItPasses() {
        Map<String, Object> s = step("expect", Locator.ofId("status"));
        s.put("text", "saved");
        Inspector.Result r = sink.check(s);
        assertThat(r.ok).isTrue();
        assertThat(lines()).hasSize(1);
        assertThat(backend.verbs.calls).anyMatch(c -> c.startsWith("expect"));
    }

    /** The recording's lines, as the palette's steps list reads them. */
    private List<String> lines() {
        List<String> out = new java.util.ArrayList<String>();
        for (Inspector.StepLine s : sink.steps()) out.add(s.line);
        return out;
    }

    /** The id of the step in row {@code row} (0-based). */
    private int id(int row) {
        return sink.steps().get(row).id;
    }

    private static Map<String, Object> step(String action, Locator target) {
        Map<String, Object> s = new LinkedHashMap<String, Object>();
        s.put("action", action);
        s.put("target", target);
        return s;
    }

    @Test
    void thePersonsRecordStartsAFreshRecordingFromWhereTheAppIs() {
        sink.done(step("click", Locator.ofId("save")));
        sink.begin();
        assertThat(lines()).isEmpty();
        assertThat(sink.done(step("click", Locator.ofId("new"))).ok).isTrue();
        assertThat(lines()).containsExactly("click(#new)");
        assertThat(backend.verbs.calls).isEmpty();
    }

    @Test
    void thePersonExportsTheRecordingAsAJUnitClassAndKeepsIt() throws Exception {
        sink.done(step("click", Locator.ofId("save")));
        Inspector.Result r = sink.export("SaveUiIT", "com.example.orders");
        assertThat(r.ok).isTrue();
        Path file = testsDir.resolve("com/example/orders/SaveUiIT.java");
        assertThat(file).exists();
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).contains("class SaveUiIT");
        assertThat(r.line).contains("SaveUiIT.java");
        // the recording stays: replay, redo or another export are still possible
        assertThat(lines()).containsExactly("click(#save)");
    }

    @Test
    void aRecordingOfOnlyChecksExportsAsATestThatOnlyAsserts() throws Exception {
        session.startRecording(false);
        Map<String, Object> s = step("expect", Locator.ofId("status"));
        s.put("text", "Placed");
        assertThat(sink.check(s).ok).isTrue();
        Inspector.Result r = sink.export("StatusUiIT", "com.example");
        assertThat(r.ok).isTrue();
        String source = new String(Files.readAllBytes(testsDir.resolve("com/example/StatusUiIT.java")), StandardCharsets.UTF_8);
        assertThat(source).contains("assertThat(").contains("Placed");
        // no defined start and no menu or shortcut first: the palette shows why the test may start on the wrong screen
        assertThat(r.line).contains("wrote").contains(" - ");
    }

    @Test
    void anExportThatIsRefusedWritesNothingAndSaysWhy() throws Exception {
        sink.done(step("click", Locator.ofId("save")));
        Inspector.Result r = sink.export("1Save", "com.example");
        assertThat(r.ok).isFalse();
        assertThat(r.line).contains("1Save");
        assertThat(lines()).hasSize(1);
        assertThat(Files.exists(testsDir.resolve("com/example"))).isFalse();
    }

    @Test
    void thePersonExportsAFlowAndKeepsTheRecording(@TempDir Path flows) throws Exception {
        session.flowsDir(flows, false);
        sink.done(step("click", Locator.ofId("save")));
        Inspector.Result r = sink.exportFlow("place-order", flows);
        assertThat(r.ok).isTrue();
        assertThat(r.folder).isFalse();
        Path file = flows.resolve("place-order.json").toAbsolutePath().normalize();
        assertThat(file).exists();
        assertThat(r.line).startsWith("wrote ").contains("place-order.json");
        assertThat(lines()).containsExactly("click(#save)");
        // the file is now the one the recording came from: the panel offers to save over it
        assertThat(sink.loadedFlow()).isEqualTo(file);
        assertThat(memory.get()).isEqualTo(file.getParent());
    }

    @Test
    void anExplicitFlowsFolderWinsOverTheRememberedOne(@TempDir Path flows, @TempDir Path remembered) {
        memory.put(remembered);
        session.flowsDir(flows, true);
        assertThat(sink.flowsFolder()).isEqualTo(flows);
    }

    @Test
    void withoutOneThePaletteOffersTheLastFolderUsed(@TempDir Path flows, @TempDir Path other) throws Exception {
        session.flowsDir(flows, false);
        assertThat(sink.flowsFolder()).isEqualTo(flows);
        sink.done(step("click", Locator.ofId("save")));
        assertThat(sink.exportFlow("place-order", other).ok).isTrue();
        assertThat(sink.flowsFolder()).isEqualTo(other.toAbsolutePath().normalize());
    }

    @Test
    void aFolderThatCannotBeWrittenIsAFolderProblem(@TempDir Path flows) throws Exception {
        session.flowsDir(flows, false);
        Path aFile = flows.resolve("orders.txt");
        Files.write(aFile, "ORD-1640".getBytes(StandardCharsets.UTF_8));
        sink.done(step("click", Locator.ofId("save")));
        // a folder below a regular file cannot be made on any OS
        Inspector.Result r = sink.exportFlow("place-order", aFile.resolve("flows"));
        assertThat(r.ok).isFalse();
        assertThat(r.folder).isTrue();
        assertThat(r.line).contains("Cannot write to").contains("orders.txt");
        assertThat(memory.get()).isNull();
        assertThat(lines()).containsExactly("click(#save)");
    }

    @Test
    void theExportPackageDefaultsToThePackageOfTheAppsMainClass(@TempDir Path jars) throws Exception {
        assertThat(UiMcpSession.mainPackage("io.github.kormcrete.orderdesk.OrderDesk --demo")).isEqualTo("io.github.kormcrete.orderdesk");
        Path jar = jars.resolve("app.jar");
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        m.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "com.example.orders.App");
        new JarOutputStream(new FileOutputStream(jar.toFile()), m).close();
        assertThat(UiMcpSession.mainPackage(jar + " --flag")).isEqualTo("com.example.orders");
        // a class in the default package, an unreadable jar or no command at all: the generic package
        assertThat(UiMcpSession.mainPackage("Main")).isEqualTo("recorded");
        assertThat(UiMcpSession.mainPackage(jars.resolve("missing.jar").toString())).isEqualTo("recorded");
        assertThat(UiMcpSession.mainPackage(null)).isEqualTo("recorded");
    }

    // ---- Load flow… and Run ---------------------------------------------------------------------------------------

    @Test
    void thePaletteListsTheFlowsWithTheirSteps(@TempDir Path flows) throws Exception {
        session.flowsDir(flows, false);
        sink.done(step("click", Locator.ofId("save")));
        assertThat(sink.exportFlow("place-order", flows).ok).isTrue();
        Files.write(flows.resolve("broken.json"), "{".getBytes(StandardCharsets.UTF_8));
        Files.write(flows.resolve("notes.txt"), "ORD-1640".getBytes(StandardCharsets.UTF_8));

        List<Inspector.FlowEntry> listed = sink.flows(flows);

        assertThat(listed).hasSize(2);
        Inspector.FlowEntry broken = listed.get(0);
        assertThat(broken.name).isEqualTo("broken");
        assertThat(broken.problem).contains("broken.json").doesNotStartWith("BAD_INPUT");
        assertThat(broken.file).isEqualTo(flows.resolve("broken.json").toAbsolutePath().normalize());
        Inspector.FlowEntry placeOrder = listed.get(1);
        assertThat(placeOrder.name).isEqualTo("place-order");
        assertThat(placeOrder.problem).isNull();
        assertThat(placeOrder.steps).isEqualTo(1);
        // a folder that does not exist (yet) has no flows
        assertThat(sink.flows(flows.resolve("missing"))).isEmpty();
    }

    @Test
    void thePaletteLoadsAFlowIntoTheRecording(@TempDir Path flows, @TempDir Path other) throws Exception {
        session.flowsDir(flows, false);
        sink.done(step("click", Locator.ofId("save")));
        assertThat(sink.exportFlow("place-order", other).ok).isTrue();
        Path file = other.resolve("place-order.json").toAbsolutePath().normalize();
        memory.put(flows);
        session.startRecording(false);
        sink.done(step("click", Locator.ofId("new")));

        Inspector.Result r = sink.loadFlow(file);

        assertThat(r.ok).isTrue();
        assertThat(r.line).isEqualTo("place-order");
        assertThat(lines()).containsExactly("click(#save)");
        assertThat(sink.loadedFlow()).isEqualTo(file);
        // the folder the person loaded from is offered next time
        assertThat(memory.get()).isEqualTo(other.toAbsolutePath().normalize());
        assertThat(sink.flowsFolder()).isEqualTo(other.toAbsolutePath().normalize());
        // the loaded recording exports as Java like any other
        Inspector.Result java = sink.export("PlaceOrderUiIT", "com.example");
        assertThat(java.ok).isTrue();
        assertThat(testsDir.resolve("com/example/PlaceOrderUiIT.java")).exists();

        // a broken file is refused with the reason and changes nothing
        Path broken = Files.write(other.resolve("broken.json"), "{".getBytes(StandardCharsets.UTF_8));
        Inspector.Result refused = sink.loadFlow(broken);
        assertThat(refused.ok).isFalse();
        assertThat(refused.line).contains("broken.json");
        assertThat(lines()).containsExactly("click(#save)");
        assertThat(sink.loadedFlow()).isEqualTo(file);
    }

    @Test
    void savingALoadedFlowKeepsItsNameAndDescription(@TempDir Path flows) throws Exception {
        Path file = flows.resolve("place-order.json");
        String json = "{\"version\":1,\"name\":\"Place an order\",\"description\":\"Ada orders two gel pens\","
                + "\"start\":\"reset\",\"steps\":[{\"action\":\"click\",\"target\":{\"id\":\"save\"}}]}";
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        assertThat(sink.loadFlow(file).ok).isTrue();
        // a loaded flow is stopped: the person goes on from its last step with a redo, which records on
        sink.resume();
        sink.done(step("click", Locator.ofId("new")));

        // the palette's Save gives no description: the file keeps its own, and its own name
        assertThat(sink.exportFlow("place-order", flows).ok).isTrue();
        Flow saved = FlowFiles.read(file);
        assertThat(saved.steps).hasSize(2);
        assertThat(saved.description).isEqualTo("Ada orders two gel pens");
        assertThat(saved.name).isEqualTo("Place an order");
        // and again: what was written is what a further save keeps
        assertThat(sink.exportFlow("place-order", flows).ok).isTrue();
        assertThat(FlowFiles.read(file).description).isEqualTo("Ada orders two gel pens");

        // a save under a new name is a new flow: its name is the file's, and it has no description unless given
        assertThat(sink.exportFlow("place-order-copy", flows).ok).isTrue();
        Flow copy = FlowFiles.read(flows.resolve("place-order-copy.json"));
        assertThat(copy.name).isEqualTo("place-order-copy");
        assertThat(copy.description).isNull();

        // an agent's export with a description replaces the kept one
        session.exportFlow("place-order-copy", flows.toString(), "Two gel pens for Ada", false);
        session.exportFlow("place-order-copy", flows.toString(), null, false);
        assertThat(FlowFiles.read(flows.resolve("place-order-copy.json")).description)
                .isEqualTo("Two gel pens for Ada");

        // a fresh recording forgets them
        session.startRecording(true);
        sink.done(step("click", Locator.ofId("save")));
        assertThat(sink.exportFlow("place-order-new", flows).ok).isTrue();
        assertThat(FlowFiles.read(flows.resolve("place-order-new.json")).description).isNull();
    }

    @Test
    void theRunsResetIsTheAppReset() {
        sink.done(step("click", Locator.ofId("save")));
        // without one the session cannot reset: the run says why and runs nothing
        Inspector.Result none = sink.reset();
        assertThat(none.ok).isFalse();
        assertThat(none.line).contains("this session has no reset").doesNotStartWith("CAPABILITY_GAP");

        int[] resets = {0};
        session.appReset(new UiMcpSession.AppReset() {
            @Override public boolean defined() { return false; }
            @Override public void run() { resets[0]++; }
        });
        Inspector.Result r = sink.reset();
        assertThat(r.ok).isTrue();
        assertThat(resets[0]).isEqualTo(1);
        // the reset leaves the recording alone: the run replays it next
        assertThat(lines()).containsExactly("click(#save)");
        assertThat(backend.verbs.calls).isEmpty();
    }
}
