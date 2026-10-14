package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Recorder;
import io.github.kormcrete.swingui.flow.Flow;
import io.github.kormcrete.swingui.flow.FlowFiles;
import io.github.kormcrete.swingui.flow.FlowFolderNotWritable;
import io.github.kormcrete.swingui.flow.verb.ClickVerb;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class UiMcpExportFlowTest {

    @TempDir Path flows;
    private final ObjectMapper mapper = new ObjectMapper();
    private UiMcpReadToolsTest.DriverHost backend;
    private UiMcpSession session;

    @BeforeEach
    void setUp() {
        backend = new UiMcpReadToolsTest.DriverHost();
        session = new UiMcpSession(backend, new UiMcpToolsTest.FakeInventory(), mapper);
        session.flowsDir(flows, true);
        session.startRecording(true);
    }

    private void clickSave() throws Exception {
        backend.verbs.outcome.recorded.put("target", new Recorder.Recorded(Locator.of("button", "Save"), false));
        session.verb("click", mapper.readTree("{\"target\":{\"ref\":\"e3\"}}"));
    }

    private JsonNode export(String name, boolean clear) throws Exception {
        return mapper.readTree(session.exportFlow(name, "", "", clear));
    }

    @Test
    void writesTheRecordingAsAFlowFileInTheFlowsFolder() throws Exception {
        clickSave();
        JsonNode out = mapper.readTree(session.exportFlow("save-order", "", "Saves an order", true));
        Path file = flows.resolve("save-order.json");
        assertThat(out.path("savedTo").asText()).isEqualTo(file.toAbsolutePath().normalize().toString());
        assertThat(out.path("steps").asInt()).isEqualTo(1);
        assertThat(out.path("overwrote").asBoolean()).isFalse();
        assertThat(out.path("flow").path("version").asInt()).isEqualTo(1);
        Flow flow = FlowFiles.read(file);
        assertThat(flow.version).isEqualTo(1);
        assertThat(flow.start).isEqualTo("reset");
        assertThat(flow.description).isEqualTo("Saves an order");
        assertThat(flow.steps).hasSize(1);
        ClickVerb click = (ClickVerb) flow.steps.get(0);
        assertThat(click.target.role).isEqualTo("button");
        assertThat(click.target.name).isEqualTo("Save");
    }

    @Test
    void aRecordingThatDidNotBeginAtAResetIsRefused() {
        UiMcpSession fresh = new UiMcpSession(backend, new UiMcpToolsTest.FakeInventory(), mapper);
        fresh.flowsDir(flows, true);
        assertThat(catchThrowable(() -> fresh.exportFlow("x", "", "", true)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("did not begin at a session reset");
    }

    @Test
    void anEmptyRecordingAndABadNameAreRefusedTogetherAndNothingIsWritten() throws Exception {
        ExportRefused refused = catchThrowableOfType(() -> session.exportFlow("bad/name", "", "", true), ExportRefused.class);
        assertThat(refused.problems()).hasSize(2);
        assertThat(refused.problems().get(0)).isEqualTo("nothing was recorded");
        assertThat(refused.problems().get(1)).contains("is not a flow name");
        try (java.util.stream.Stream<Path> files = Files.list(flows)) {
            assertThat(files.count()).isZero();
        }
    }

    @Test
    void anExistingFileIsNeverOverwritten() throws Exception {
        clickSave();
        export("save-order", true);
        Path file = flows.resolve("save-order.json");
        byte[] before = Files.readAllBytes(file);
        session.startRecording(true);
        clickSave();
        clickSave();
        Throwable refused = catchThrowable(() -> session.exportFlow("save-order", "", "", true));
        assertThat(refused).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists; choose another name")
                .hasMessageContaining(file.toAbsolutePath().normalize().toString());
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    @Test
    void theLoadedFlowIsSavedBackOverItsFile() throws Exception {
        clickSave();
        export("save-order", true);
        session.startRecording(true);
        JsonNode loaded = mapper.readTree(session.loadFlow(flows.resolve("save-order.json")));
        assertThat(loaded.path("steps").asInt()).isEqualTo(1);
        assertThat(loaded.path("name").asText()).isEqualTo("save-order");
        // a loaded flow is stopped: the steps after it join through a redo (the palette's), which records on
        session.humanSink().resume();
        clickSave();
        JsonNode out = export("save-order", true);
        assertThat(out.path("steps").asInt()).isEqualTo(2);
        assertThat(out.path("overwrote").asBoolean()).isTrue();
        assertThat(FlowFiles.read(flows.resolve("save-order.json")).steps).hasSize(2);
    }

    @Test
    void saveAsANewNameLeavesTheLoadedFileAlone() throws Exception {
        clickSave();
        export("save-order", true);
        session.startRecording(true);
        session.loadFlow(flows.resolve("save-order.json"));
        session.humanSink().resume();
        clickSave();
        JsonNode copy = export("save-order-2", false);
        assertThat(copy.path("overwrote").asBoolean()).isFalse();
        assertThat(FlowFiles.read(flows.resolve("save-order.json")).steps).hasSize(1);
        // the new file is now the one the recording came from
        clickSave();
        JsonNode again = export("save-order-2", true);
        assertThat(again.path("overwrote").asBoolean()).isTrue();
        assertThat(again.path("steps").asInt()).isEqualTo(3);
        assertThat(FlowFiles.read(flows.resolve("save-order.json")).steps).hasSize(1);
    }

    @Test
    void withoutAPluginAFlowThatDoesNotBeginByGettingToItsScreenIsFlagged() throws Exception {
        session.startRecording(false);
        clickSave();
        assertThat(export("save-order", true).path("startWarning").asText()).contains("begin with a menu");
        session.startRecording(true);
        clickSave();
        assertThat(export("save-order-2", true).has("startWarning")).isFalse();
    }

    @Test
    void clearEmptiesTheRecordingAndForgetsTheLoadedFlow() throws Exception {
        clickSave();
        export("save-order", true);
        assertThat(catchThrowable(() -> session.exportFlow("other", "", "", true)))
                .hasMessageContaining("did not begin at a session reset");
        assertThat(session.loadedFlow()).isNull();
    }

    @Test
    void aFolderThatCannotBeWrittenIsRefusedNamingItAndKeepsTheRecording() throws Exception {
        clickSave();
        Path regular = Files.write(flows.resolve("plain.txt"), new byte[]{1});
        String dir = regular.resolve("uitest").toString();
        FlowFolderNotWritable refused = catchThrowableOfType(() -> session.exportFlow("save-order", dir, "", true),
                FlowFolderNotWritable.class);
        assertThat(refused.getMessage()).contains("plain.txt");
        assertThat(export("probe", false).path("steps").asInt()).isEqualTo(1);
    }

    @Test
    void aRelativeFolderIsUnderTheAppsWorkingDirectory() throws Exception {
        clickSave();
        Path cwd = java.nio.file.Paths.get("").toAbsolutePath();
        String dir = "target/flow-export-test-" + System.nanoTime();
        try {
            JsonNode out = mapper.readTree(session.exportFlow("save-order", dir, "", true));
            assertThat(java.nio.file.Paths.get(out.path("savedTo").asText())).isEqualTo(cwd.resolve(dir).resolve("save-order.json"));
        } finally {
            Files.deleteIfExists(cwd.resolve(dir).resolve("save-order.json"));
            Files.deleteIfExists(cwd.resolve(dir));
        }
    }

    @Test
    void loadingAFlowStartsARecordingThatExportsAsJava(@TempDir Path tests) throws Exception {
        clickSave();
        export("save-order", true);
        session.startRecording(true);
        session.loadFlow(flows.resolve("save-order.json"));
        System.setProperty(UiMcpSession.TESTS_DIR_PROP, tests.toString());
        try {
            JsonNode java = mapper.readTree(session.exportTest("LoadedUiIT", "m", "p", true));
            assertThat(java.path("source").asText()).contains("ui.button(\"Save\").click();");
        } finally {
            System.clearProperty(UiMcpSession.TESTS_DIR_PROP);
        }
    }

    @Test
    void aFlowWithAStepThatHasNoJavaFormIsNotLoaded() throws Exception {
        clickSave();
        Path file = flows.resolve("switches.json");
        // loginAs has a Java form only as the first step
        String json = "{\"version\":1,\"name\":\"switches\",\"start\":\"reset\",\"steps\":["
                + "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Save\"}},"
                + "{\"action\":\"loginAs\",\"as\":\"ada\"}]}";
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        Throwable refused = catchThrowable(() -> session.loadFlow(file));
        assertThat(refused).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("switches cannot be loaded into the recording")
                .hasMessageContaining("run it with run_flow or the JUnit runner instead");
        assertThat(export("probe", false).path("steps").asInt()).isEqualTo(1);
    }

    @Test
    void aBrokenFlowFileChangesNothing() throws Exception {
        clickSave();
        Path file = Files.write(flows.resolve("broken.json"), "{".getBytes(StandardCharsets.UTF_8));
        assertThat(catchThrowable(() -> session.loadFlow(file))).hasMessageContaining("broken.json");
        assertThat(export("probe", false).path("steps").asInt()).isEqualTo(1);
    }

    @Test
    void aResetForgetsTheLoadedFlow() throws Exception {
        clickSave();
        export("save-order", true);
        session.startRecording(true);
        session.loadFlow(flows.resolve("save-order.json"));
        session.startRecording(true);
        clickSave();
        assertThat(catchThrowable(() -> session.exportFlow("save-order", "", "", true)))
                .hasMessageContaining("already exists");
    }
}
