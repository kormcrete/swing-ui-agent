package io.github.kormcrete.swingui.flow;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The flow file format, version 1: strict reading, stable writing, names and folders. */
class FlowFilesTest {

    @TempDir Path tmp;

    private static final String HEAD = "{\"version\":1,\"name\":\"x\",\"start\":\"reset\",";

    private static Flow flowOf(String... stepJson) throws Exception {
        Flow flow = new Flow();
        flow.name = "place-order";
        flow.description = "Ada orders two gel pens";
        for (String s : stepJson) flow.steps.add(FlowJson.mapper().readValue(s, Action.class));
        return flow;
    }

    private static void refused(String json, String... parts) {
        assertThatThrownBy(() -> FlowFiles.parse(json, "uitest/x.json"))
                .isInstanceOfSatisfying(DriverException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
                    assertThat(DriverException.stripReason(e.getMessage(), e.reason())).startsWith("uitest/x.json: ");
                    for (String p : parts) assertThat(e.getMessage()).contains(p);
                });
    }

    @Test
    void aWrittenFlowReadsBackTheSame() throws Exception {
        Flow flow = flowOf(
                "{\"action\":\"loginAs\",\"as\":\"clerk\"}",
                "{\"action\":\"menu\",\"path\":[\"Orders\",\"New order\"]}",
                "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Save\",\"scope\":[\"Checkout\"]}}",
                "{\"action\":\"type\",\"target\":{\"role\":\"textbox\",\"name\":\"Customer\"},\"text\":\"Ada\"}",
                "{\"action\":\"expect\",\"target\":{\"role\":\"table\"},"
                        + "\"cell\":{\"row\":{\"Order\":\"ORD-1640\"},\"column\":\"Status\",\"expected\":\"Open\"}}");

        String text = FlowFiles.write(flow);
        Flow back = FlowFiles.parse(text, "x");

        assertThat(FlowFiles.write(back)).isEqualTo(text);
        assertThat(text.indexOf("\"version\" : 1")).isGreaterThanOrEqualTo(0).isLessThan(text.indexOf("\"name\""));
        assertThat(text.indexOf("\"start\" : \"reset\"")).isGreaterThan(0).isLessThan(text.indexOf("\"steps\""));
    }

    @Test
    void theFileUsesLineFeedsOnEveryOsAndEndsWithOne() throws Exception {
        String text = FlowFiles.write(flowOf("{\"action\":\"menu\",\"path\":[\"Orders\"]}"));

        assertThat(text).doesNotContain("\r").endsWith("}\n");
    }

    private static Map<String, String> samples() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        String t = "\"target\":{\"role\":\"button\",\"name\":\"OK\"}";
        m.put("click", "{\"action\":\"click\"," + t + ",\"count\":2,\"modifiers\":[\"shift\"]}");
        m.put("type", "{\"action\":\"type\"," + t + ",\"text\":\"a\",\"replace\":true,\"commit\":true}");
        m.put("press", "{\"action\":\"press\",\"keys\":\"ctrl+s\"}");
        m.put("check", "{\"action\":\"check\"," + t + ",\"checked\":true}");
        m.put("select", "{\"action\":\"select\"," + t + ",\"item\":\"Ada\"}");
        m.put("pick", "{\"action\":\"pick\",\"path\":[\"Ada\"]}");
        m.put("menu", "{\"action\":\"menu\",\"path\":[\"Orders\",\"New order\"]}");
        m.put("answer", "{\"action\":\"answer\",\"button\":\"Yes\"}");
        m.put("chooseFile", "{\"action\":\"chooseFile\",\"path\":\"/tmp/a.csv\",\"approve\":true}");
        m.put("drag", "{\"action\":\"drag\",\"from\":{\"role\":\"list\"},\"to\":{\"role\":\"table\"}}");
        m.put("drop", "{\"action\":\"drop\"," + t + ",\"files\":[\"/tmp/a.csv\"]}");
        m.put("expect", "{\"action\":\"expect\"," + t + ",\"text\":\"OK\",\"state\":[\"enabled\"],"
                + "\"rows\":{\"op\":\">=\",\"value\":1},\"selected\":[0]}");
        m.put("scroll", "{\"action\":\"scroll\"," + t + ",\"to\":\"end\",\"pages\":1}");
        m.put("setClipboard", "{\"action\":\"setClipboard\",\"text\":\"ORD-1640\"}");
        m.put("screenshot", "{\"action\":\"screenshot\",\"path\":\"a.png\"," + t + ",\"maxSize\":800,\"scale\":2.0}");
        m.put("contextMenu", "{\"action\":\"contextMenu\"," + t + ",\"path\":[\"Export\",\"CSV\"]}");
        m.put("hover", "{\"action\":\"hover\"," + t + ",\"inside\":false}");
        m.put("resizeColumn", "{\"action\":\"resizeColumn\",\"target\":{\"role\":\"table\",\"column\":\"Name\"},\"delta\":-40}");
        m.put("resizeWindow", "{\"action\":\"resizeWindow\",\"window\":\"main\",\"width\":1200,\"height\":800}");
        m.put("closeWindow", "{\"action\":\"closeWindow\",\"window\":\"main\"}");
        m.put("focusFileChooserName", "{\"action\":\"focusFileChooserName\"}");
        m.put("navigate", "{\"action\":\"navigate\",\"target\":\"Orders\"}");
        m.put("close", "{\"action\":\"close\"}");
        m.put("loginAs", "{\"action\":\"loginAs\",\"as\":\"clerk\"}");
        return m;
    }

    @Test
    void everyStepTypeReadsBackStrictly() throws Exception {
        Map<String, String> samples = samples();
        assertThat(samples.keySet()).containsExactlyInAnyOrderElementsOf(FlowJson.stepTypes());
        for (Map.Entry<String, String> e : samples.entrySet()) {
            Flow flow = flowOf(e.getValue());
            String text = FlowFiles.write(flow);
            Flow back;
            try {
                back = FlowFiles.parse(text, e.getKey());
            } catch (DriverException ex) {
                throw new AssertionError("step type " + e.getKey() + " does not read back: " + ex.getMessage(), ex);
            }
            assertThat(FlowFiles.write(back)).as(e.getKey()).isEqualTo(text);
        }
    }

    @Test
    void aPlainFlowIsWrittenAsTheLowestVersionThatHoldsIt() throws Exception {
        Flow flow = flowOf("{\"action\":\"menu\",\"path\":[\"Orders\",\"New order\"]}");
        assertThat(FlowFiles.versionFor(flow)).isEqualTo(1);
        assertThat(flow.version).isNull();
        assertThat(FlowFiles.write(flow)).contains("\"version\" : 1,");
    }

    @Test
    void aNewerVersionSaysToUpdateTheAgent() {
        refused("{\"version\":2,\"start\":\"reset\",\"steps\":[]}",
                "is flow version 2, written by a newer swing-ui-agent; this one reads version 1 - update the agent");
    }

    @Test
    void aMissingVersionIsRefused() {
        refused("{\"start\":\"reset\",\"steps\":[{\"action\":\"close\"}]}", "needs \"version\": 1");
        refused("{\"version\":\"1\",\"start\":\"reset\",\"steps\":[{\"action\":\"close\"}]}", "version");
        refused("{\"version\":0,\"start\":\"reset\",\"steps\":[{\"action\":\"close\"}]}", "version");
    }

    @Test
    void notJsonIsNotAFlowFile() {
        refused("nope", "not a flow file");
        refused("[]", "not a flow file");
    }

    @Test
    void aMissingStartIsRefused() {
        refused("{\"version\":1,\"steps\":[{\"action\":\"close\"}]}", "needs \"start\": \"reset\"");
    }

    @Test
    void anUnknownStartIsRefused() {
        refused("{\"version\":1,\"start\":\"login\",\"steps\":[{\"action\":\"close\"}]}",
                "starts at 'login'; this agent knows only 'reset'");
    }

    @Test
    void aFlowWithoutStepsIsRefused() {
        refused("{\"version\":1,\"start\":\"reset\",\"steps\":[]}",
                "has no steps (a flow that does nothing would always pass)");
        refused("{\"version\":1,\"start\":\"reset\"}", "steps");
    }

    @Test
    void laterTopLevelFieldsAreNamedAsNewer() {
        refused("{\"version\":1,\"start\":\"reset\",\"params\":{},\"groups\":[],\"steps\":[{\"action\":\"close\"}]}",
                "params", "groups", "newer flow format");
    }

    @Test
    void anUnknownTopLevelFieldNamesTheKnownOnes() {
        refused("{\"version\":1,\"start\":\"reset\",\"stpes\":[],\"steps\":[{\"action\":\"close\"}]}",
                "stpes", "a flow file has version, name, description, start, steps");
    }

    @Test
    void anUnknownStepFieldNamesItsStep() {
        refused(HEAD + "\"steps\":[{\"action\":\"close\"},{\"action\":\"type\",\"txt\":\"a\"}]}",
                "step 2 (type)", "unknown field 'txt'", "known: ");
    }

    @Test
    void aSentenceOnAStepNeedsANewerFormat() {
        refused(HEAD + "\"steps\":[{\"action\":\"close\",\"say\":\"Close it\"}]}",
                "step 1 (close)", "'say'", "newer flow format");
    }

    @Test
    void aDoStepNeedsANewerFormat() {
        refused(HEAD + "\"steps\":[{\"action\":\"do\"}]}", "step 1", "'do'", "newer flow format");
        refused(HEAD + "\"steps\":[{\"action\":\"clik\"}]}", "step 1", "there is no step type 'clik'");
    }

    @Test
    void aTypoInALocatorIsRefused() {
        refused(HEAD + "\"steps\":[{\"action\":\"click\",\"target\":{\"nmae\":\"OK\"}}]}", "step 1", "nmae");
    }

    @Test
    void readNamesTheFileAndDefaultsTheName() throws IOException {
        Path ok = tmp.resolve("add-to-cart.json");
        Files.write(ok, "{\"version\":1,\"start\":\"reset\",\"steps\":[{\"action\":\"close\"}]}"
                .getBytes(StandardCharsets.UTF_8));
        assertThat(FlowFiles.read(ok).name).isEqualTo("add-to-cart");

        Path broken = tmp.resolve("broken.json");
        Files.write(broken, "{".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> FlowFiles.read(broken))
                .isInstanceOfSatisfying(DriverException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
                    assertThat(e.getMessage()).contains(broken.toAbsolutePath().normalize().toString() + ": ");
                });

        assertThatThrownBy(() -> FlowFiles.read(tmp.resolve("missing.json")))
                .isInstanceOfSatisfying(DriverException.class, e ->
                        assertThat(e.getMessage()).contains("cannot read flow file "));
    }

    @Test
    void namesFollowOneRuleOnEveryOs() {
        assertThat(FlowFiles.checkName("place-order")).isEqualTo("place-order");
        assertThat(FlowFiles.checkName("Place order 2")).isEqualTo("Place order 2");
        assertThat(FlowFiles.checkName("place-order.json")).isEqualTo("place-order");
        StringBuilder long101 = new StringBuilder();
        for (int i = 0; i < 101; i++) long101.append('a');
        List<String> bad = Arrays.asList("", "../x", "a/b", "a\\b", ".hidden", "x.", "CON", "nul.txt", "com1", "Lpt9.x",
                long101.toString());
        for (String name : bad) {
            assertThatThrownBy(() -> FlowFiles.checkName(name)).as(name)
                    .isInstanceOfSatisfying(DriverException.class, e ->
                            assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT));
        }
        assertThat(FlowFiles.file(tmp, "a.json")).isEqualTo(tmp.resolve("a.json"));
    }

    @Test
    void theListIsSortedAndHoldsOnlyFlowFiles() throws IOException {
        for (String n : new String[] {"b.json", "a.json", "gate.txt", "notes.md"}) Files.write(tmp.resolve(n), new byte[0]);
        Files.createDirectory(tmp.resolve("sub"));
        Files.createDirectory(tmp.resolve("dir.json"));

        List<String> names = new ArrayList<String>();
        for (Path p : FlowFiles.list(tmp)) names.add(p.getFileName().toString());

        assertThat(names).containsExactly("a.json", "b.json");
        assertThat(FlowFiles.list(tmp.resolve("nowhere"))).isEmpty();
    }

    @Test
    void theFolderIsTheAgentOptionElseThePropertyElseUitest() {
        assertThat(FlowFiles.dir("flows", "prop", tmp)).isEqualTo(tmp.resolve("flows"));
        assertThat(FlowFiles.dir(" ", "prop", tmp)).isEqualTo(tmp.resolve("prop"));
        assertThat(FlowFiles.dir(null, null, tmp)).isEqualTo(tmp.resolve("uitest"));
        Path abs = tmp.resolve("elsewhere").toAbsolutePath();
        assertThat(FlowFiles.dir(abs.toString(), "prop", tmp)).isEqualTo(abs);
        assertThat(FlowFiles.dir("a/../b", null, tmp)).isEqualTo(tmp.resolve("b"));
    }

    @Test
    void aFolderBelowAFileCannotBeWrittenAndSaysWhere() throws IOException {
        Path blocker = tmp.resolve("blocker");
        Files.write(blocker, new byte[0]);
        Path dir = blocker.resolve("uitest");

        assertThatThrownBy(() -> FlowFiles.writableDir(dir))
                .isInstanceOfSatisfying(FlowFolderNotWritable.class, e -> {
                    assertThat(e.getMessage()).contains(dir.toAbsolutePath().toString());
                    assertThat(e.folder()).isEqualTo(dir.toAbsolutePath());
                });
        assertThat(FlowFiles.writableDir(tmp.resolve("ok"))).isDirectory();
    }
}
