package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.mcp.rpc.McpToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import io.github.kormcrete.swingui.mcp.UiMcpSession;
import io.github.kormcrete.swingui.spi.PluginParts;
import io.github.kormcrete.swingui.spi.StepHost;

import java.nio.file.Paths;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionToolTest {

    private static final long STARTED = 1_700_000_000_000L;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentResetTest.App app = new AgentResetTest.App();
    private final AgentResetTest.Dialogs dialogs = new AgentResetTest.Dialogs();
    private final Plugins fixture = fixturePlugins();
    private final McpToolDefinition fixtureTool = tool(fixture, "port=0", () -> 1);

    private Plugins fixturePlugins() {
        StepHost host = mock(StepHost.class);
        when(host.verbs()).thenReturn(dialogs);
        Plugins p = new Plugins(dialogs);
        p.add("fixture", Paths.get("fixture")).ready(new PluginParts(host, null, app, null, null, null), "fixture ok");
        return p;
    }

    private McpToolDefinition tool(Plugins plugins, String rawArgs, IntSupplier port) {
        return new SessionTool(plugins, new Lease(System::currentTimeMillis), new UiMcpSession(plugins, plugins, mapper),
                dialogs, new AgentInfo("2.0", "x"), AgentArgs.parse(rawArgs), port, STARTED);
    }

    private JsonNode call(String json) throws Exception {
        return mapper.readTree(fixtureTool.invoke(mapper.readTree(json)));
    }

    private JsonNode callOnEmpty(String json) throws Exception {
        return mapper.readTree(tool(new Plugins(null), null, () -> 1).invoke(mapper.readTree(json)));
    }

    @Test
    void infoReportsAgentEndpointModeAndPlugin() throws Exception {
        Plugins plugins = new Plugins(mock(Verbs.class));
        plugins.add("demo", Paths.get("plugin-dir")).waiting("waiting for com.example.orders.AppContext to load");
        McpToolDefinition tool = tool(plugins, "port=8766,mode=background", () -> 8766);
        JsonNode info = mapper.readTree(tool.invoke(mapper.readTree("{\"op\":\"info\"}")));
        assertThat(info.path("agent").path("version").asText()).isEqualTo("2.0");
        assertThat(info.path("endpoint").path("port").asInt()).isEqualTo(8766);
        assertThat(info.path("mode").asText()).isEqualTo("background");
        assertThat(info.path("plugin").path("state").asText()).isEqualTo("waiting");
        assertThat(info.path("plugin").path("detail").asText()).contains("com.example.orders.AppContext");
        assertThat(info.path("plugins").get(0).path("name").asText()).isEqualTo("demo");
        assertThat(info.path("plugins").get(0).path("state").asText()).isEqualTo("waiting");
        assertThat(info.path("plugins").get(0).path("location").asText()).isEqualTo("plugin-dir");
        assertThat(info.path("plugins").get(0).path("locationTime").isNull()).isTrue();
    }

    @Test
    void infoSaysWhetherThePaletteIsOn() throws Exception {
        Plugins none = new Plugins(null);
        UiMcpSession session = new UiMcpSession(none, none, mapper);
        McpToolDefinition tool = new SessionTool(none, new Lease(System::currentTimeMillis), session, dialogs,
                new AgentInfo("2.0", "x"), AgentArgs.parse(null), () -> 1, STARTED);
        // the effective state, not the argument: a headless app asked for none gets none
        assertThat(mapper.readTree(tool.invoke(mapper.readTree("{}"))).path("palette").asBoolean()).isFalse();
        session.palette(true);
        assertThat(mapper.readTree(tool.invoke(mapper.readTree("{}"))).path("palette").asBoolean()).isTrue();
        assertThat(mapper.readTree(tool.invoke(mapper.readTree("{}"))).has("inspector")).isFalse();
    }

    @Test
    void infoListsTheClientClasspathDirectoriesOnly() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("cp").toFile();
        java.io.File jar = java.io.File.createTempFile("cpjar", ".jar");
        String saved = System.getProperty("java.class.path");
        System.setProperty("java.class.path", dir + java.io.File.pathSeparator + jar + java.io.File.pathSeparator + "no/such/dir");
        try {
            JsonNode cp = call("{}").path("app").path("classpath");
            assertThat(cp.size()).isEqualTo(1);
            assertThat(cp.get(0).asText()).isEqualTo(dir.getAbsolutePath());
        } finally {
            System.setProperty("java.class.path", saved);
            dir.delete();
            jar.delete();
        }
    }

    @Test
    void infoResolvesDirectoriesNamedByAManifestJarClassPath() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("cpm");
        java.nio.file.Path spaced = java.nio.file.Files.createDirectories(root.resolve("a dir/classes"));
        java.nio.file.Path rel = java.nio.file.Files.createDirectories(root.resolve("rel/classes"));
        java.nio.file.Path otherJar = java.nio.file.Files.createFile(root.resolve("other.jar"));
        java.util.jar.Manifest mf = new java.util.jar.Manifest();
        mf.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(java.util.jar.Attributes.Name.CLASS_PATH,
                spaced.toUri().toString() + "/ rel/classes/ " + otherJar.toUri() + " missing/dir/");
        java.nio.file.Path jar = root.resolve("shortened.jar");
        new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar), mf).close();
        String saved = System.getProperty("java.class.path");
        System.setProperty("java.class.path", jar + java.io.File.pathSeparator + jar);
        try {
            JsonNode cp = call("{}").path("app").path("classpath");
            assertThat(cp.size()).isEqualTo(2);
            assertThat(cp.get(0).asText()).isEqualTo(spaced.toRealPath().toString());
            assertThat(cp.get(1).asText()).isEqualTo(rel.toRealPath().toString());
        } finally {
            System.setProperty("java.class.path", saved);
        }
    }

    @Test
    void anUnreadableJarOnTheClassPathIsSkipped() throws Exception {
        java.io.File junk = java.io.File.createTempFile("junk", ".jar");
        java.nio.file.Files.write(junk.toPath(), new byte[] {1, 2, 3});
        String saved = System.getProperty("java.class.path");
        System.setProperty("java.class.path", junk.getPath());
        try {
            assertThat(call("{}").path("app").path("classpath").size()).isZero();
        } finally {
            System.setProperty("java.class.path", saved);
            junk.delete();
        }
    }

    @Test
    void infoReportsTheBoundPortNotTheRequestedOne() throws Exception {
        McpToolDefinition tool = tool(new Plugins(null), "port=0", () -> 54321);
        JsonNode info = mapper.readTree(tool.invoke(mapper.readTree("{}")));
        assertThat(info.path("endpoint").path("port").asInt()).isEqualTo(54321);
    }

    @Test
    void infoIsTheDefaultOp() throws Exception {
        McpToolDefinition tool = tool(new Plugins(null), null, () -> 0);
        assertThat(mapper.readTree(tool.invoke(mapper.readTree("{}"))).has("plugin")).isTrue();
    }

    @Test
    void anUnknownOpIsBadInput() {
        McpToolDefinition tool = tool(new Plugins(null), null, () -> 0);
        assertThatThrownBy(() -> tool.invoke(mapper.readTree("{\"op\":\"bogus\"}")))
                .isInstanceOf(DriverException.class)
                .hasMessageContaining("unknown op 'bogus' (known: info, lease, release, renew, reset, login, edt)");
    }

    @Test
    void theDescriptorIsReadOnly() {
        McpToolDefinition tool = tool(new Plugins(null), null, () -> 0);
        JsonNode d = tool.descriptor(mapper);
        assertThat(d.path("name").asText()).isEqualTo("session");
        assertThat(d.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
    }

    @Test
    void withoutAnyPluginTheAppPluginStateIsNone() throws Exception {
        McpToolDefinition tool = tool(new Plugins(null), null, () -> 0);
        JsonNode info = mapper.readTree(tool.invoke(mapper.readTree("{}")));
        assertThat(info.path("plugin").path("state").asText()).isEqualTo("none");
        assertThat(info.path("plugins").size()).isZero();
    }

    @Test
    void pluginIsNotReadyWhileTheAppPluginStillLoads() throws Exception {
        Plugins plugins = new Plugins(null);
        plugins.add("hooks", Paths.get("h")).ready(new io.github.kormcrete.swingui.spi.PluginParts(null, null), "ok");
        plugins.add("demo", Paths.get("l")).waiting("waiting for a.B to load");
        McpToolDefinition tool = tool(plugins, null, () -> 0);
        JsonNode info = mapper.readTree(tool.invoke(mapper.readTree("{}")));
        assertThat(info.path("plugin").path("state").asText()).isEqualTo("waiting");
    }

    @Test
    void pluginIsReadyOnceTheAppSlotIs() throws Exception {
        Plugins plugins = new Plugins(null);
        plugins.add("hooks", Paths.get("h")).ready(new io.github.kormcrete.swingui.spi.PluginParts(null, null), "ok");
        plugins.add("demo", Paths.get("l")).ready(new io.github.kormcrete.swingui.spi.PluginParts(mock(io.github.kormcrete.swingui.spi.StepHost.class), null), "demo ok");
        McpToolDefinition tool = tool(plugins, null, () -> 0);
        JsonNode info = mapper.readTree(tool.invoke(mapper.readTree("{}")));
        assertThat(info.path("plugin").path("state").asText()).isEqualTo("ready");
        assertThat(info.path("plugin").path("detail").asText()).isEqualTo("demo ok");
    }

    @Test
    void leaseResetRelease() throws Exception {
        JsonNode g = call("{\"op\":\"lease\",\"owner\":\"T#m (run 1)\"}");
        String token = g.get("token").asText();
        assertThat(g.get("owner").asText()).isEqualTo("T#m (run 1)");
        assertThat(call("{\"op\":\"info\"}").path("lease").path("owner").asText()).isEqualTo("T#m (run 1)");
        call("{\"op\":\"reset\",\"alias\":\"alice\",\"_lease\":\"" + token + "\"}");
        assertThat(app.calls).containsExactly("reset alias:alice");
        assertThat(call("{\"op\":\"release\",\"token\":\"" + token + "\"}").get("released").asBoolean()).isTrue();
        assertThat(call("{\"op\":\"info\"}").get("lease").isNull()).isTrue();
    }

    @Test
    void resetWithoutTheHoldersTokenIsBusy() throws Exception {
        call("{\"op\":\"lease\",\"owner\":\"suite\"}");
        assertThatThrownBy(() -> call("{\"op\":\"reset\"}")).hasMessageContaining("busy: leased by suite");
    }

    @Test
    void loginRelogsTheCurrentUser() throws Exception {
        call("{\"op\":\"login\",\"user\":\"customer1\",\"password\":\"pw\"}");
        call("{\"op\":\"login\",\"relogin\":true}");
        assertThat(app.calls).containsExactly("login customer1 false", "login null true");
    }

    @Test
    void infoReportsTheAppAndItsStart() throws Exception {
        JsonNode info = call("{}");
        assertThat(info.path("app").path("name").asText()).isEqualTo("fixture");
        assertThat(info.path("app").path("startedAtMillis").asLong()).isEqualTo(STARTED);
        assertThat(info.path("app").path("startedAt").asText()).endsWith("Z");
        assertThat(info.path("plugins").get(0).path("state").asText()).isEqualTo("ready");
        assertThat(info.path("plugin").path("state").asText()).isEqualTo("ready");
        assertThat(info.path("agent").has("jar")).isTrue();
    }

    @Test
    void edtReturnsAStackText() throws Exception {
        assertThat(call("{\"op\":\"edt\"}").path("stack").asText()).contains("AWT-EventQueue");
    }

    @Test
    void withoutAPluginAResetClosesDialogsOnly() throws Exception {
        assertThat(callOnEmpty("{\"op\":\"reset\"}").path("reset").asText()).isEqualTo("dialogs");
        assertThat(dialogs.calls).containsExactly("resetPointer", "snapshot dialog");
    }

    @Test
    void aResetStartsAFreshRecordingForExportTest() throws Exception {
        Plugins none = new Plugins(null);
        UiMcpSession recording = new UiMcpSession(none, none, mapper);
        McpToolDefinition tool = new SessionTool(none, new Lease(System::currentTimeMillis), recording, dialogs,
                new AgentInfo("2.0", "x"), AgentArgs.parse(null), () -> 1, STARTED);
        assertThatThrownBy(() -> recording.exportTest("AUiIT", "m", "p", true))
                .hasMessageContaining("did not begin at a session reset");
        tool.invoke(mapper.readTree("{\"op\":\"reset\"}"));
        // past the reset check: what is left to refuse is the empty recording
        assertThatThrownBy(() -> recording.exportTest("AUiIT", "m", "p", true))
                .hasMessageNotContaining("did not begin at a session reset");
    }

    @Test
    void withoutAPluginAResetThatNamesAUserIsACapabilityGap() {
        assertThatThrownBy(() -> callOnEmpty("{\"op\":\"reset\",\"alias\":\"alice\"}"))
                .hasMessage("CAPABILITY_GAP: logging in as alice needs an app plugin with AppHooks;"
                        + " without one a reset only closes dialogs");
        assertThat(dialogs.calls).isEmpty();
    }

    @Test
    void aConfiguredAppPluginThatIsNotReadyFailsTheReset() {
        Plugins plugins = new Plugins(null);
        plugins.add("demo", Paths.get("l")).waiting("waiting for a.B to load");
        assertThatThrownBy(() -> tool(plugins, null, () -> 0).invoke(mapper.readTree("{\"op\":\"reset\"}")))
                .hasMessageContaining("CAPABILITY_GAP: reset needs the app plugin; demo is waiting");
    }

    @Test
    void renewAnswersWhetherTheTokenIsTheLiveLease() throws Exception {
        McpToolDefinition tool = tool(fixture, "port=0", () -> 1);
        String token = mapper.readTree(tool.invoke(mapper.readTree("{\"op\":\"lease\",\"owner\":\"t\"}"))).path("token").asText();
        assertThat(mapper.readTree(tool.invoke(mapper.readTree("{\"op\":\"renew\",\"token\":\"" + token + "\"}")))
                .path("renewed").asBoolean()).isTrue();
        assertThat(mapper.readTree(tool.invoke(mapper.readTree("{\"op\":\"renew\",\"token\":\"other\"}")))
                .path("renewed").asBoolean()).isFalse();
    }
}
