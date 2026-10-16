package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Recorder;
import io.github.kormcrete.swingui.spi.ExportDefaults;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class UiMcpExportTestTest {

    @TempDir Path testsDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private UiMcpReadToolsTest.DriverHost backend;
    private UiMcpSession session;

    @BeforeEach
    void setUp() {
        System.setProperty(UiMcpSession.TESTS_DIR_PROP, testsDir.toString());
        backend = new UiMcpReadToolsTest.DriverHost();
        session = new UiMcpSession(backend, new UiMcpToolsTest.FakeInventory(), mapper);
        session.startRecording(true);
    }

    @AfterEach
    void clear() { System.clearProperty(UiMcpSession.TESTS_DIR_PROP); }

    private void clickSave() throws Exception {
        backend.verbs.outcome.recorded.put("target", new Recorder.Recorded(Locator.of("button", "Save"), false));
        session.verb("click", mapper.readTree("{\"target\":{\"ref\":\"e3\"}}"));
    }

    @Test
    void writesACompilableLookingTestIntoThePackageFolder() throws Exception {
        clickSave();
        JsonNode out = mapper.readTree(session.exportTest("SaveUiIT", "savesIt", "com.example.uitest.recorded", true));
        Path file = testsDir.resolve("com/example/uitest/recorded/SaveUiIT.java");
        assertThat(out.path("savedTo").asText()).isEqualTo(file.toAbsolutePath().normalize().toString());
        assertThat(out.path("steps").asInt()).isEqualTo(1);
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                .contains("class SaveUiIT").contains("ui.button(\"Save\").click();");
    }

    @Test
    void thePluginsExportDefaultsDecideTheFolderAndPackage(@TempDir Path cwd) throws Exception {
        Path module = Files.createDirectories(cwd.resolve("mod/src/test/java"));
        System.clearProperty(UiMcpSession.TESTS_DIR_PROP);
        UiMcpSession plugged = new UiMcpSession(backend, new UiMcpToolsTest.FakeInventory(), mapper,
                () -> new ExportDefaults("mod", "x.y"));
        plugged.startRecording(true);
        backend.verbs.outcome.recorded.put("target", new Recorder.Recorded(Locator.of("button", "Save"), false));
        plugged.verb("click", mapper.readTree("{\"target\":{\"ref\":\"e3\"}}"));
        Path file = module.resolve("x/y/DefaultsUiIT.java");
        assertThat(UiMcpSession.testsDir(cwd, "mod")).isEqualTo(module);
        assertThat(UiMcpSession.testsDir(cwd, null)).isEqualTo(cwd.resolve("src/test/java"));
        assertThat(UiMcpSession.testsDir(cwd, "absent")).isEqualTo(cwd.resolve("src/test/java"));
        System.setProperty(UiMcpSession.TESTS_DIR_PROP, module.toString());
        JsonNode out = mapper.readTree(plugged.exportTest("DefaultsUiIT", "m", null, true));
        assertThat(out.path("savedTo").asText()).isEqualTo(file.toAbsolutePath().normalize().toString());
        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    void exportingToTheGenericFallbackWarnsThatNoPluginIsReady() throws Exception {
        clickSave();
        // the package is named: no warning
        assertThat(mapper.readTree(session.exportTest("NamedUiIT", "m", "p", false)).has("warning")).isFalse();
        // no package and the generic defaults: no warning while the tests dir property names the place, else one
        JsonNode out = mapper.readTree(session.exportTest("GenericUiIT", "m", null, false));
        assertThat(out.has("warning")).isFalse(); // swingui.testsDir is set by setUp
        System.clearProperty(UiMcpSession.TESTS_DIR_PROP);
        java.nio.file.Path cwdFile = java.nio.file.Paths.get("src/test/java/recorded/WarnUiIT.java");
        try {
            JsonNode warned = mapper.readTree(session.exportTest("WarnUiIT", "m", null, false));
            assertThat(warned.path("warning").asText()).contains("no app plugin is ready");
        } finally {
            Files.deleteIfExists(cwdFile);
            Files.deleteIfExists(cwdFile.getParent());
        }
    }

    @Test
    void dirWinsOverTheDefault(@TempDir Path elsewhere) throws Exception {
        clickSave(); // swingui.testsDir (setUp) names another place: dir still wins
        JsonNode out = mapper.readTree(session.exportTest("DirUiIT", "m", "p", false, elsewhere.toString()));
        Path file = elsewhere.resolve("p/DirUiIT.java");
        assertThat(out.path("savedTo").asText()).isEqualTo(file.toAbsolutePath().normalize().toString());
        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.exists(testsDir.resolve("p/DirUiIT.java"))).isFalse();
        assertThat(out.has("warning")).isFalse();
    }

    @Test
    void recordingExportAsJavaHonoursDir(@TempDir Path elsewhere) throws Exception {
        clickSave();
        JsonNode out = mapper.readTree(session.recordingExport(mapper.readTree(
                "{\"as\":\"java\",\"class\":\"ViaToolUiIT\",\"package\":\"p\",\"dir\":"
                        + mapper.writeValueAsString(elsewhere.toString()) + "}")));
        assertThat(out.path("savedTo").asText())
                .isEqualTo(elsewhere.resolve("p/ViaToolUiIT.java").toAbsolutePath().normalize().toString());
    }

    @Test
    void aDirThatIsAFileIsRefusedByName(@TempDir Path elsewhere) throws Exception {
        clickSave();
        Path file = Files.write(elsewhere.resolve("notes.txt"), "x".getBytes(StandardCharsets.UTF_8));
        Throwable t = catchThrowable(() -> session.exportTest("FileUiIT", "m", "p", false, file.toString()));
        assertThat(t).hasMessageContaining("is a file, not a folder").hasMessageContaining(file.getFileName().toString());
        // the recording was kept: another dir works
        assertThat(mapper.readTree(session.exportTest("FileUiIT", "m", "p", false, elsewhere.toString())).path("steps").asInt()).isEqualTo(1);
    }

    @Test
    void aWorkingDirWithoutABuildFileWarns(@TempDir Path cwd) throws Exception {
        clickSave();
        System.clearProperty(UiMcpSession.TESTS_DIR_PROP);
        session.workingDir(cwd);
        JsonNode warned = mapper.readTree(session.exportTest("NoBuildUiIT", "m", "p", false));
        // still written, into the working directory
        assertThat(Files.exists(cwd.resolve("src/test/java/p/NoBuildUiIT.java"))).isTrue();
        assertThat(warned.path("warning").asText()).contains(cwd.toAbsolutePath().normalize().toString()).contains("dir");

        // a build file there: no warning
        Files.write(cwd.resolve("pom.xml"), "<project/>".getBytes(StandardCharsets.UTF_8));
        assertThat(mapper.readTree(session.exportTest("BuildUiIT", "m", "p", false)).has("warning")).isFalse();
        // dir named: no warning either, whatever the working directory holds
        Files.delete(cwd.resolve("pom.xml"));
        assertThat(mapper.readTree(session.exportTest("DirNamedUiIT", "m", "p", false, cwd.resolve("x").toString()))
                .has("warning")).isFalse();
    }

    @Test
    void anExistingFileIsNeverOverwritten() throws Exception {
        clickSave();
        session.exportTest("SaveUiIT", "a", "p", false);
        Throwable t = catchThrowable(() -> session.exportTest("SaveUiIT", "b", "p", false));
        assertThat(t).hasMessageContaining("already exists");
    }

    @Test
    void clearEmptiesTheRecording() throws Exception {
        clickSave();
        session.exportTest("OneUiIT", "a", "p", true);
        session.startRecording(true);
        Throwable t = catchThrowable(() -> session.exportTest("TwoUiIT", "a", "p", true));
        assertThat(t).hasMessageContaining("nothing was recorded");
    }

    @Test
    void aRefusalNamesEveryProblemWritesNothingAndKeepsTheRecording() throws Exception {
        clickSave();
        ExportRefused e = catchThrowableOfType(() -> session.exportTest("Save", "Bad", "p", true), ExportRefused.class);
        assertThat(e.problems()).hasSize(2);
        assertThat(Files.exists(testsDir.resolve("p/Save.java"))).isFalse();

        // clear:true did not empty it: the same recording exports under good names
        JsonNode out = mapper.readTree(session.exportTest("SaveUiIT", "a", "p", true));
        assertThat(out.path("steps").asInt()).isEqualTo(1);
    }

    @Test
    void aStepWithNoJavaFormRunsButIsNotRecordedAndCannotPoisonTheExport() throws Exception {
        // A click by ref the recorder could not turn into a locator: a test cannot express a ref.
        JsonNode r = mapper.readTree(session.doAction(mapper.readTree(
                "{\"action\":\"click\",\"target\":{\"ref\":\"e7\"}}")));
        assertThat(r.path("passed").asBoolean()).isTrue();
        assertThat(r.has("recorded")).isFalse();
        assertThat(r.path("notRecorded").asText()).contains("e7").contains("no Java form")
                .contains("by hand");

        clickSave();
        JsonNode out = mapper.readTree(session.exportTest("AfterUnrecordedUiIT", "a", "p", true));
        assertThat(out.path("steps").asInt()).isEqualTo(1);
        assertThat(out.path("source").asText()).contains("ui.button(\"Save\").click();");
    }

    @Test
    void aLoginAsIsRecordedOnlyAsTheFirstStep() throws Exception {
        clickSave();
        JsonNode late = mapper.readTree(session.doAction(mapper.readTree("{\"action\":\"loginAs\",\"as\":\"customer\"}")));
        assertThat(late.has("recorded")).isFalse();
        assertThat(late.path("notRecorded").asText()).contains("only exportable as the first step");
        assertThat(mapper.readTree(session.exportTest("LateLoginUiIT", "a", "p", true)).path("steps").asInt()).isEqualTo(1);
    }

    @Test
    void aRecordedNavigateAndCloseExportAsTheirClientForms() throws Exception {
        session.doAction(mapper.readTree("{\"action\":\"navigate\",\"target\":\"Orders\"}"));
        clickSave();
        session.doAction(mapper.readTree("{\"action\":\"close\"}"));
        session.exportTest("NavUiIT", "a", "p", true);
        assertThat(new String(Files.readAllBytes(testsDir.resolve("p/NavUiIT.java")), StandardCharsets.UTF_8))
                .contains("ui.navigate(\"Orders\");", "ui.button(\"Save\").click();", "ui.closeActiveTab();");
    }

    @Test
    void aRecordingThatDidNotBeginAtAResetIsRefused() throws Exception {
        UiMcpSession fresh = new UiMcpSession(backend, new UiMcpToolsTest.FakeInventory(), mapper);
        backend.verbs.outcome.recorded.put("target", new Recorder.Recorded(Locator.of("button", "Save"), false));
        fresh.verb("click", mapper.readTree("{\"target\":{\"ref\":\"e3\"}}"));
        assertThat(catchThrowable(() -> fresh.exportTest("EarlyUiIT", "m", "p", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("did not begin at a session reset");
        assertThat(Files.exists(testsDir.resolve("p/EarlyUiIT.java"))).isFalse();
    }

    @Test
    void afterAClearingExportTheNextRecordingNeedsAReset() throws Exception {
        clickSave();
        session.exportTest("FirstUiIT", "m", "p", true);
        clickSave();
        assertThat(catchThrowable(() -> session.exportTest("SecondUiIT", "m", "p", true)))
                .hasMessageContaining("did not begin at a session reset");
        session.startRecording(true);
        clickSave();
        assertThat(mapper.readTree(session.exportTest("SecondUiIT", "m", "p", true)).path("steps").asInt()).isEqualTo(1);
    }

    @Test
    void withoutAPluginATestThatDoesNotBeginByGettingToItsScreenIsFlagged() throws Exception {
        session.startRecording(false);
        clickSave();
        JsonNode out = mapper.readTree(session.exportTest("ClickFirstUiIT", "m", "p", true));
        assertThat(out.path("startWarning").asText()).contains("begins with").contains("its own starting screen");

        session.startRecording(false);
        backend.verbs.outcome.recorded.clear();
        session.verb("menu", mapper.readTree("{\"path\":[\"Orders\",\"New order\"]}"));
        clickSave();
        assertThat(mapper.readTree(session.exportTest("MenuFirstUiIT", "m", "p", true)).has("startWarning")).isFalse();
    }

    @Test
    void afterAPluginResetAnyFirstStepIsFine() throws Exception {
        clickSave();
        assertThat(mapper.readTree(session.exportTest("PluginStartUiIT", "m", "p", true)).has("startWarning")).isFalse();
    }
}
