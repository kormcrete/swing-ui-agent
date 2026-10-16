package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.spi.PluginParts;
import io.github.kormcrete.swingui.agent.Plugins;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Recorder;
import io.github.kormcrete.swingui.flow.Flow;
import io.github.kormcrete.swingui.flow.RecordedTestFiles;
import io.github.kormcrete.swingui.flow.RecordedTestFolderNotWritable;
import io.github.kormcrete.swingui.flow.verb.ClickVerb;
import io.github.kormcrete.swingui.spi.DomainStep;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class UiMcpExportRecordedTestTest {

    @TempDir Path testsDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private UiMcpReadToolsTest.DriverHost backend;
    private UiMcpSession session;

    @BeforeEach
    void setUp() {
        backend = new UiMcpReadToolsTest.DriverHost();
        session = new UiMcpSession(backend, new UiMcpToolsTest.FakeInventory(), mapper);
        session.recordedDir(testsDir, true);
        session.startRecording(true);
    }

    private void clickSave() throws Exception {
        backend.verbs.outcome.recorded.put("target", new Recorder.Recorded(Locator.of("button", "Save"), false));
        session.verb("click", mapper.readTree("{\"target\":{\"ref\":\"e3\"}}"));
    }

    private JsonNode export(String name, boolean clear) throws Exception {
        return mapper.readTree(session.exportRecordedTest(name, "", "", clear));
    }

    @Test
    void writesTheRecordingAsARecordedTestFileInTheRecordedTestsFolder() throws Exception {
        clickSave();
        JsonNode out = mapper.readTree(session.exportRecordedTest("save-order", "", "Saves an order", true));
        Path file = testsDir.resolve("save-order.json");
        assertThat(out.path("savedTo").asText()).isEqualTo(file.toAbsolutePath().normalize().toString());
        assertThat(out.path("steps").asInt()).isEqualTo(1);
        assertThat(out.path("overwrote").asBoolean()).isFalse();
        assertThat(out.path("test").path("version").asInt()).isEqualTo(2);
        Flow flow = RecordedTestFiles.read(file);
        assertThat(flow.version).isEqualTo(2);
        assertThat(flow.start).isEqualTo("reset");
        assertThat(flow.description).isEqualTo("Saves an order");
        assertThat(flow.steps).hasSize(1);
        ClickVerb click = (ClickVerb) flow.steps.get(0);
        assertThat(click.target.role).isEqualTo("button");
        assertThat(click.target.name).isEqualTo("Save");
    }

    @Test
    void aRecordingIsWrittenAsVersion2() throws Exception {
        clickSave();
        session.recording(mapper.readTree("{\"op\":\"insert\",\"at\":\"1.1\",\"step\":"
                + "{\"action\":\"expect\",\"target\":{\"id\":\"status\"},\"text\":\"Saved\"}}"));
        session.recording(mapper.readTree("{\"op\":\"edit\",\"n\":1,\"say\":\"Save the order\"}"));
        mapper.readTree(session.exportRecordedTest("v2", "", "", false));
        JsonNode file = mapper.readTree(Files.readAllBytes(testsDir.resolve("v2.json")));
        assertThat(file.path("version").asInt()).isEqualTo(2);
        assertThat(file.path("steps")).hasSize(1);
        JsonNode step = file.path("steps").get(0);
        assertThat(step.path("action").asText()).isEqualTo("click");
        assertThat(step.path("say").asText()).isEqualTo("Save the order");
        assertThat(step.path("checks")).hasSize(1);
        assertThat(step.path("checks").get(0).has("action")).isFalse();
        assertThat(step.path("checks").get(0).path("text").asText()).isEqualTo("Saved");
        Flow back = RecordedTestFiles.read(testsDir.resolve("v2.json"));
        assertThat(back.steps).hasSize(2);
        assertThat(back.steps.get(0).say).isEqualTo("Save the order");
    }

    @Test
    void aRecordingThatDidNotBeginAtAResetIsRefused() {
        UiMcpSession fresh = new UiMcpSession(backend, new UiMcpToolsTest.FakeInventory(), mapper);
        fresh.recordedDir(testsDir, true);
        assertThat(catchThrowable(() -> fresh.exportRecordedTest("x", "", "", true)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("did not begin at a session reset");
    }

    @Test
    void anEmptyRecordingAndABadNameAreRefusedTogetherAndNothingIsWritten() throws Exception {
        ExportRefused refused = catchThrowableOfType(() -> session.exportRecordedTest("bad/name", "", "", true), ExportRefused.class);
        assertThat(refused.problems()).hasSize(2);
        assertThat(refused.problems().get(0)).isEqualTo("nothing was recorded");
        assertThat(refused.problems().get(1)).contains("is not a recorded test name");
        try (java.util.stream.Stream<Path> files = Files.list(testsDir)) {
            assertThat(files.count()).isZero();
        }
    }

    @Test
    void anExistingFileIsNeverOverwritten() throws Exception {
        clickSave();
        export("save-order", true);
        Path file = testsDir.resolve("save-order.json");
        byte[] before = Files.readAllBytes(file);
        session.startRecording(true);
        clickSave();
        clickSave();
        Throwable refused = catchThrowable(() -> session.exportRecordedTest("save-order", "", "", true));
        assertThat(refused).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists; choose another name")
                .hasMessageContaining(file.toAbsolutePath().normalize().toString());
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    @Test
    void theLoadedRecordedTestIsSavedBackOverItsFile() throws Exception {
        clickSave();
        export("save-order", true);
        session.startRecording(true);
        JsonNode loaded = mapper.readTree(session.loadRecordedTest(testsDir.resolve("save-order.json")));
        assertThat(loaded.path("steps").asInt()).isEqualTo(1);
        assertThat(loaded.path("name").asText()).isEqualTo("save-order");
        // a loaded recorded test is stopped: the steps after it join through a redo (the palette's), which records on
        session.humanSink().resume();
        clickSave();
        // a clearing export waits for the recording to stop (the person's Done)
        session.humanSink().end();
        JsonNode out = export("save-order", true);
        assertThat(out.path("steps").asInt()).isEqualTo(2);
        assertThat(out.path("overwrote").asBoolean()).isTrue();
        assertThat(RecordedTestFiles.read(testsDir.resolve("save-order.json")).steps).hasSize(2);
    }

    @Test
    void saveAsANewNameLeavesTheLoadedFileAlone() throws Exception {
        clickSave();
        export("save-order", true);
        session.startRecording(true);
        session.loadRecordedTest(testsDir.resolve("save-order.json"));
        session.humanSink().resume();
        clickSave();
        JsonNode copy = export("save-order-2", false);
        assertThat(copy.path("overwrote").asBoolean()).isFalse();
        assertThat(RecordedTestFiles.read(testsDir.resolve("save-order.json")).steps).hasSize(1);
        // the new file is now the one the recording came from
        clickSave();
        session.humanSink().end();
        JsonNode again = export("save-order-2", true);
        assertThat(again.path("overwrote").asBoolean()).isTrue();
        assertThat(again.path("steps").asInt()).isEqualTo(3);
        assertThat(RecordedTestFiles.read(testsDir.resolve("save-order.json")).steps).hasSize(1);
    }

    @Test
    void withoutAPluginARecordedTestThatDoesNotBeginByGettingToItsScreenIsFlagged() throws Exception {
        session.startRecording(false);
        clickSave();
        assertThat(export("save-order", true).path("startWarning").asText()).contains("begin with a menu");
        session.startRecording(true);
        clickSave();
        assertThat(export("save-order-2", true).has("startWarning")).isFalse();
    }

    /** A session whose order-desk plugin offers one domain step made of {@code body}; recording began at no defined start. */
    private UiMcpSession withDomainStep(java.util.function.Consumer<io.github.kormcrete.swingui.junit.Client> body) {
        Plugins plugins = new Plugins(backend.verbs);
        plugins.add("order-desk", Paths.get("order-desk")).ready(new PluginParts(() -> Collections.singletonList(
                DomainStep.act("openNewOrder", "Open a new order").code((ui, p) -> body.accept(ui)).build())), "ok");
        UiMcpSession s = new UiMcpSession(plugins, new UiMcpToolsTest.FakeInventory(), mapper);
        s.recordedDir(testsDir, true);
        s.startRecording(false);
        return s;
    }

    @Test
    void aLeadingDoStepThatOpensAMenuIsADefinedStart() throws Exception {
        UiMcpSession s = withDomainStep(ui -> ui.menu("Orders", "New order"));
        s.doAction(mapper.readTree("{\"action\":\"do\",\"step\":\"openNewOrder\"}"));
        assertThat(mapper.readTree(s.exportRecordedTest("open", "", "", true)).has("startWarning")).isFalse();
    }

    @Test
    void aLeadingDoStepThatClicksStillWarns() throws Exception {
        UiMcpSession s = withDomainStep(ui -> ui.button("Archive").click());
        s.doAction(mapper.readTree("{\"action\":\"do\",\"step\":\"openNewOrder\"}"));
        assertThat(mapper.readTree(s.exportRecordedTest("open", "", "", true)).path("startWarning").asText())
                .contains("begin with a menu");
    }

    @Test
    void clearEmptiesTheRecordingAndForgetsTheLoadedRecordedTest() throws Exception {
        clickSave();
        export("save-order", true);
        assertThat(catchThrowable(() -> session.exportRecordedTest("other", "", "", true)))
                .hasMessageContaining("did not begin at a session reset");
        assertThat(session.loadedTest()).isNull();
    }

    @Test
    void aFolderThatCannotBeWrittenIsRefusedNamingItAndKeepsTheRecording() throws Exception {
        clickSave();
        Path regular = Files.write(testsDir.resolve("plain.txt"), new byte[]{1});
        String dir = regular.resolve("uitest").toString();
        RecordedTestFolderNotWritable refused = catchThrowableOfType(() -> session.exportRecordedTest("save-order", dir, "", true),
                RecordedTestFolderNotWritable.class);
        assertThat(refused.getMessage()).contains("plain.txt");
        assertThat(export("probe", false).path("steps").asInt()).isEqualTo(1);
    }

    @Test
    void aRelativeFolderIsUnderTheAppsWorkingDirectory() throws Exception {
        clickSave();
        Path cwd = java.nio.file.Paths.get("").toAbsolutePath();
        String dir = "target/recorded-test-export-test-" + System.nanoTime();
        try {
            JsonNode out = mapper.readTree(session.exportRecordedTest("save-order", dir, "", true));
            assertThat(java.nio.file.Paths.get(out.path("savedTo").asText())).isEqualTo(cwd.resolve(dir).resolve("save-order.json"));
        } finally {
            Files.deleteIfExists(cwd.resolve(dir).resolve("save-order.json"));
            Files.deleteIfExists(cwd.resolve(dir));
        }
    }

    @Test
    void loadingARecordedTestStartsARecordingThatExportsAsJava(@TempDir Path tests) throws Exception {
        clickSave();
        export("save-order", true);
        session.startRecording(true);
        session.loadRecordedTest(testsDir.resolve("save-order.json"));
        System.setProperty(UiMcpSession.TESTS_DIR_PROP, tests.toString());
        try {
            JsonNode java = mapper.readTree(session.exportTest("LoadedUiIT", "m", "p", true));
            assertThat(java.path("source").asText()).contains("ui.button(\"Save\").click();");
        } finally {
            System.clearProperty(UiMcpSession.TESTS_DIR_PROP);
        }
    }

    @Test
    void aRecordedTestWithAStepThatHasNoJavaFormIsNotLoaded() throws Exception {
        clickSave();
        Path file = testsDir.resolve("switches.json");
        // loginAs has a Java form only as the first step
        String json = "{\"version\":2,\"name\":\"switches\",\"start\":\"reset\",\"steps\":["
                + "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Save\"}},"
                + "{\"action\":\"loginAs\",\"as\":\"ada\"}]}";
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        Throwable refused = catchThrowable(() -> session.loadRecordedTest(file));
        assertThat(refused).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("switches cannot be loaded into the recording")
                .hasMessageContaining("run it with run_recorded_test or the JUnit runner instead");
        assertThat(export("probe", false).path("steps").asInt()).isEqualTo(1);
    }

    @Test
    void aBrokenRecordedTestFileChangesNothing() throws Exception {
        clickSave();
        Path file = Files.write(testsDir.resolve("broken.json"), "{".getBytes(StandardCharsets.UTF_8));
        assertThat(catchThrowable(() -> session.loadRecordedTest(file))).hasMessageContaining("broken.json");
        assertThat(export("probe", false).path("steps").asInt()).isEqualTo(1);
    }

    @Test
    void aResetForgetsTheLoadedRecordedTest() throws Exception {
        clickSave();
        export("save-order", true);
        session.startRecording(true);
        session.loadRecordedTest(testsDir.resolve("save-order.json"));
        session.startRecording(true);
        clickSave();
        assertThat(catchThrowable(() -> session.exportRecordedTest("save-order", "", "", true)))
                .hasMessageContaining("already exists");
    }
}
