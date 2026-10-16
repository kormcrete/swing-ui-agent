package io.github.kormcrete.swingui.flow;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import com.fasterxml.jackson.databind.JsonNode;
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
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The recorded test file format, versions 2 and 3: strict reading, stable writing, names and folders. */
class RecordedTestFilesTest {

    @TempDir Path tmp;

    private static final String HEAD = "{\"version\":2,\"name\":\"x\",\"start\":\"reset\",";

    private static Flow stepsOf(String... stepJson) throws Exception {
        Flow flow = new Flow();
        flow.name = "place-order";
        flow.description = "Ada orders two gel pens";
        for (String s : stepJson) flow.steps.add(FlowJson.mapper().readValue(s, Action.class));
        return flow;
    }

    private static void refused(String json, String... parts) {
        assertThatThrownBy(() -> RecordedTestFiles.parse(json, "uitest/x.json"))
                .isInstanceOfSatisfying(DriverException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
                    assertThat(DriverException.stripReason(e.getMessage(), e.reason())).startsWith("uitest/x.json: ");
                    for (String p : parts) assertThat(e.getMessage()).contains(p);
                });
    }

    @Test
    void aWrittenRecordedTestReadsBackTheSame() throws Exception {
        Flow flow = stepsOf(
                "{\"action\":\"loginAs\",\"as\":\"clerk\"}",
                "{\"action\":\"menu\",\"path\":[\"Orders\",\"New order\"]}",
                "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Save\",\"scope\":[\"Checkout\"]}}",
                "{\"action\":\"type\",\"target\":{\"role\":\"textbox\",\"name\":\"Customer\"},\"text\":\"Ada\"}",
                "{\"action\":\"expect\",\"target\":{\"role\":\"table\"},"
                        + "\"cell\":{\"row\":{\"Order\":\"ORD-1640\"},\"column\":\"Status\",\"expected\":\"Open\"}}");

        String text = RecordedTestFiles.write(flow);
        Flow back = RecordedTestFiles.parse(text, "x");

        assertThat(RecordedTestFiles.write(back)).isEqualTo(text);
        assertThat(text.indexOf("\"version\" : 2")).isGreaterThanOrEqualTo(0).isLessThan(text.indexOf("\"name\""));
        assertThat(text.indexOf("\"start\" : \"reset\"")).isGreaterThan(0).isLessThan(text.indexOf("\"steps\""));
    }

    @Test
    void theFileUsesLineFeedsOnEveryOsAndEndsWithOne() throws Exception {
        String text = RecordedTestFiles.write(stepsOf("{\"action\":\"menu\",\"path\":[\"Orders\"]}"));

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
        m.put("do", DO_ADD);
        return m;
    }

    @Test
    void everyStepTypeReadsBackStrictly() throws Exception {
        Map<String, String> samples = samples();
        assertThat(samples.keySet()).containsExactlyInAnyOrderElementsOf(FlowJson.stepTypes());
        String before = samples.get("click");
        for (Map.Entry<String, String> e : samples.entrySet()) {
            // an expect is a check: it is written under the sample before it
            Flow flow = "expect".equals(e.getKey()) ? stepsOf(before, e.getValue()) : stepsOf(e.getValue());
            String text = RecordedTestFiles.write(flow);
            Flow back;
            try {
                back = RecordedTestFiles.parse(text, e.getKey());
            } catch (DriverException ex) {
                throw new AssertionError("step type " + e.getKey() + " does not read back: " + ex.getMessage(), ex);
            }
            assertThat(RecordedTestFiles.write(back)).as(e.getKey()).isEqualTo(text);
            assertThat(back.steps).as(e.getKey()).hasSameSizeAs(flow.steps);
        }
    }

    @Test
    void everyFileIsWrittenAsVersion2() throws Exception {
        Flow flow = stepsOf("{\"action\":\"menu\",\"path\":[\"Orders\",\"New order\"]}");
        assertThat(RecordedTestFiles.versionFor(flow)).isEqualTo(2);
        assertThat(flow.version).isNull();
        assertThat(RecordedTestFiles.write(flow)).contains("\"version\" : 2,");
        flow.version = 1;
        assertThat(RecordedTestFiles.write(flow)).contains("\"version\" : 2,");
    }

    @Test
    void aNewerVersionSaysToUpdateTheAgent() {
        refused("{\"version\":4,\"start\":\"reset\",\"steps\":[]}",
                "is format version 4, written by a newer swing-ui-agent; this one reads version 3 - update the agent");
    }

    @Test
    void aMissingVersionIsRefused() {
        refused("{\"start\":\"reset\",\"steps\":[{\"action\":\"close\"}]}", "needs \"version\": 2");
        refused("{\"version\":\"2\",\"start\":\"reset\",\"steps\":[{\"action\":\"close\"}]}", "version");
        refused("{\"version\":0,\"start\":\"reset\",\"steps\":[{\"action\":\"close\"}]}", "version");
    }

    @Test
    void notJsonIsNotARecordedTestFile() {
        refused("nope", "not a recorded test file");
        refused("[]", "not a recorded test file");
    }

    @Test
    void aMissingStartIsRefused() {
        refused("{\"version\":2,\"steps\":[{\"action\":\"close\"}]}", "needs \"start\": \"reset\"");
    }

    @Test
    void anUnknownStartIsRefused() {
        refused("{\"version\":2,\"start\":\"login\",\"steps\":[{\"action\":\"close\"}]}",
                "starts at 'login'; this agent knows only 'reset'");
    }

    @Test
    void aRecordedTestWithoutStepsIsRefused() {
        refused("{\"version\":2,\"start\":\"reset\",\"steps\":[]}",
                "has no steps (a recorded test that does nothing would always pass)");
        refused("{\"version\":2,\"start\":\"reset\"}", "steps");
    }

    @Test
    void laterTopLevelFieldsAreNamedAsNewer() {
        refused("{\"version\":2,\"start\":\"reset\",\"requires\":{},\"steps\":[{\"action\":\"close\"}]}",
                "'requires' needs format version 3");
        refused("{\"version\":2,\"start\":\"reset\",\"params\":{},\"steps\":[{\"action\":\"close\"}]}",
                "'params'", "exact values only", "belong in a Java test");
    }

    @Test
    void anUnknownTopLevelFieldNamesTheKnownOnes() {
        refused("{\"version\":2,\"start\":\"reset\",\"stpes\":[],\"steps\":[{\"action\":\"close\"}]}",
                "stpes", "a recorded test file has version, name, description, start, steps");
    }

    @Test
    void anUnknownStepFieldNamesItsStep() {
        refused(HEAD + "\"steps\":[{\"action\":\"close\"},{\"action\":\"type\",\"txt\":\"a\"}]}",
                "step 2 (type)", "unknown field 'txt'", "known: ");
    }

    @Test
    void aSentenceOnAStepOrCheckIsRead() throws Exception {
        Flow flow = RecordedTestFiles.parse(HEAD + "\"steps\":[{\"action\":\"close\",\"say\":\"Close it\","
                + "\"checks\":[{\"say\":\"It is closed\",\"target\":{\"role\":\"label\",\"name\":\"Total\"},\"text\":\"1\"}]}]}", "x");

        assertThat(flow.steps).hasSize(2);
        assertThat(flow.steps.get(0).say).isEqualTo("Close it");
        assertThat(flow.steps.get(1).say).isEqualTo("It is closed");
        assertThat(flow.steps.get(1)).isInstanceOf(io.github.kormcrete.swingui.flow.verb.ExpectVerb.class);
        assertThat(flow.groups).isEmpty();
    }

    @Test
    void anUnknownStepTypeIsNamed() {
        refused(HEAD + "\"steps\":[{\"action\":\"clik\"}]}", "step 1", "there is no step type 'clik'");
    }

    // ---- version 3: do steps and the plugins they need

    private static final String DO_ADD = "{\"action\":\"do\",\"step\":\"order-desk:addToCart\","
            + "\"params\":{\"product\":\"Gel pen, black\"}}";
    private static final String V3 = "{\"version\":3,\"name\":\"x\",\"start\":\"reset\",\"requires\":[\"order-desk\"],";

    @Test
    void aDoStepNeedsVersion3() throws Exception {
        refused(HEAD + "\"steps\":[" + DO_ADD + "]}", "step 1", "'do' needs format version 3");
        refused("{\"version\":2,\"start\":\"reset\",\"requires\":[\"order-desk\"],\"steps\":[{\"action\":\"close\"}]}",
                "'requires' needs format version 3");

        Flow flow = RecordedTestFiles.parse(V3 + "\"steps\":[" + DO_ADD + "]}", "x");

        assertThat(flow.version).isEqualTo(3);
        assertThat(flow.requires).containsExactly("order-desk");
        DoAction d = (DoAction) flow.steps.get(0);
        assertThat(d.step).isEqualTo("order-desk:addToCart");
        assertThat(d.params).containsExactly(entry("product", "Gel pen, black"));
        assertThat(d.check).isNull();
        assertThat(RecordedTestFiles.versionFor(flow)).isEqualTo(3);
        assertThat(RecordedTestFiles.versionFor(stepsOf("{\"action\":\"close\"}"))).isEqualTo(2);
        // the plugins its do steps need are named, once each, and a plugin a step names is among them
        refused("{\"version\":3,\"start\":\"reset\",\"steps\":[" + DO_ADD + "]}", "needs \"requires\"", "order-desk");
        refused("{\"version\":3,\"start\":\"reset\",\"requires\":[],\"steps\":[" + DO_ADD + "]}", "\"requires\"",
                "at least one plugin");
        refused("{\"version\":3,\"start\":\"reset\",\"requires\":[\"order-desk\",\"order-desk\"],\"steps\":[" + DO_ADD
                + "]}", "\"requires\"", "'order-desk' twice");
        refused("{\"version\":3,\"start\":\"reset\",\"requires\":[\"order-desk\",3],\"steps\":[" + DO_ADD + "]}",
                "\"requires\"", "plugin names");
        refused("{\"version\":3,\"start\":\"reset\",\"requires\":[\"extras\"],\"steps\":[" + DO_ADD + "]}",
                "step 1 (do)", "plugin 'order-desk'", "not in \"requires\"");
        refused(V3 + "\"steps\":[{\"action\":\"do\",\"params\":{}}]}", "step 1 (do)", "needs \"step\"");
        refused(V3 + "\"steps\":[{\"action\":\"close\"}]}", "\"requires\" names plugins, but the recorded test has no do "
                + "steps: leave it out");
    }

    @Test
    void requiresIsWrittenFromTheDoSteps() throws Exception {
        Flow flow = stepsOf("{\"action\":\"menu\",\"path\":[\"Orders\",\"New order\"]}", DO_ADD,
                "{\"action\":\"do\",\"step\":\"extras:giftWrap\"}",
                "{\"action\":\"do\",\"step\":\"order-desk:setQuantity\",\"params\":{\"product\":\"Gel pen, black\",\"qty\":\"3\"}}");

        String text = RecordedTestFiles.write(flow);

        JsonNode root = FlowJson.mapper().readTree(text);
        assertThat(root.path("version").asInt()).isEqualTo(3);
        assertThat(root.path("requires").toString()).isEqualTo("[\"order-desk\",\"extras\"]");
        assertThat(text.indexOf("\"start\"")).isLessThan(text.indexOf("\"requires\""));
        assertThat(text.indexOf("\"requires\"")).isLessThan(text.indexOf("\"steps\""));
        assertThat(root.path("steps").get(1).toString()).isEqualTo(DO_ADD);
        Flow back = RecordedTestFiles.parse(text, "x");
        assertThat(back.requires).containsExactly("order-desk", "extras");
        assertThat(RecordedTestFiles.write(back)).isEqualTo(text);

        // without do steps it stays version 2, with no requires
        Flow plain = stepsOf("{\"action\":\"close\"}");
        plain.requires.add("order-desk");
        assertThat(RecordedTestFiles.write(plain)).contains("\"version\" : 2,").doesNotContain("requires");
        // a do step by its bare id names no plugin: the plugins the file read are kept, else it cannot be written
        Flow bare = stepsOf("{\"action\":\"do\",\"step\":\"addToCart\",\"params\":{\"product\":\"Gel pen, black\"}}");
        assertThatThrownBy(() -> RecordedTestFiles.write(bare)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires").hasMessageContaining("<plugin>:<id>");
        bare.requires.add("order-desk");
        assertThat(FlowJson.mapper().readTree(RecordedTestFiles.write(bare)).path("requires").toString())
                .isEqualTo("[\"order-desk\"]");
    }

    @Test
    void version2FilesStillLoad() throws Exception {
        String v2 = "{\"version\":2,\"name\":\"add-paper\",\"start\":\"reset\",\"steps\":["
                + "{\"say\":\"Open a new order\",\"steps\":[{\"action\":\"menu\",\"path\":[\"Orders\",\"New order\"]},"
                + "{\"action\":\"type\",\"target\":{\"role\":\"textbox\",\"name\":\"Customer\"},\"text\":\"Ada\"}]},"
                + "{\"action\":\"click\",\"target\":{\"role\":\"button\",\"name\":\"Add to cart\"},"
                + "\"checks\":[" + CHECK + "]}]}";

        Flow flow = RecordedTestFiles.parse(v2, "x");

        assertThat(flow.version).isEqualTo(2);
        assertThat(flow.requires).isEmpty();
        assertThat(flow.steps).hasSize(4);
        assertThat(flow.groups).hasSize(1);
        assertThat(StepNumbers.labels(flow.steps)).containsExactly("1", "2", "3", "3.1");
        String text = RecordedTestFiles.write(flow);
        assertThat(text).contains("\"version\" : 2,");
        assertThat(RecordedTestFiles.write(RecordedTestFiles.parse(text, "x"))).isEqualTo(text);
    }

    @Test
    void aDoCheckSitsUnderItsStep() throws Exception {
        String doCheck = "{\"action\":\"do\",\"say\":\"The cart total is 4.99\",\"step\":\"order-desk:cartTotalIs\","
                + "\"params\":{\"total\":\"4.99\"}}";
        Flow flow = RecordedTestFiles.parse(V3 + "\"steps\":[{\"action\":\"click\",\"target\":{\"role\":\"button\","
                + "\"name\":\"Add to cart\"},\"checks\":[" + doCheck + "," + CHECK + "]}," + DO_ADD + "]}", "x");

        assertThat(StepNumbers.labels(flow.steps)).containsExactly("1", "1.1", "1.2", "2");
        DoAction check = (DoAction) flow.steps.get(1);
        assertThat(check.check).isTrue();
        assertThat(check.say).isEqualTo("The cart total is 4.99");
        assertThat(((DoAction) flow.steps.get(3)).check).isNull();
        String text = RecordedTestFiles.write(flow);
        JsonNode checks = FlowJson.mapper().readTree(text).path("steps").get(0).path("checks");
        assertThat(checks.get(0).toString()).isEqualTo(doCheck);   // its action stays, "check" is never written
        assertThat(checks.get(1).has("action")).isFalse();
        assertThat(RecordedTestFiles.write(RecordedTestFiles.parse(text, "x"))).isEqualTo(text);

        refused(V3 + "\"steps\":[{\"action\":\"do\",\"step\":\"order-desk:cartTotalIs\",\"check\":true}]}",
                "step 1 (do)", "'check'", "under the step it checks");
        refused(V3 + "\"steps\":[{\"action\":\"close\",\"checks\":[{\"action\":\"do\",\"step\":\"order-desk:cartTotalIs\","
                + "\"check\":true}]}]}", "check 1.1 (do)", "'check'");
        refused(V3 + "\"steps\":[{\"action\":\"close\",\"checks\":[{\"action\":\"click\"}]}]}", "check 1.1",
                "only an expect or a domain check (do)");
    }

    @Test
    void theLineOfEachStepIsKept() {
        Flow flow = RecordedTestFiles.parse("{\"version\":3,\"start\":\"reset\",\"requires\":[\"order-desk\"],\n"
                + "\"steps\":[\n{\"action\":\"close\",\n\"checks\":[\n" + CHECK + "]},\n"
                + "{\"say\":\"Two\",\"steps\":[\n" + DO_ADD + ",\n{\"action\":\"close\"}]}]}", "x");

        assertThat(flow.lines).containsExactly(3, 5, 7, 8);
    }

    @Test
    void aTypoInALocatorIsRefused() {
        refused(HEAD + "\"steps\":[{\"action\":\"click\",\"target\":{\"nmae\":\"OK\"}}]}", "step 1", "nmae");
    }

    @Test
    void readNamesTheFileAndDefaultsTheName() throws IOException {
        Path ok = tmp.resolve("add-to-cart.json");
        Files.write(ok, "{\"version\":2,\"start\":\"reset\",\"steps\":[{\"action\":\"close\"}]}"
                .getBytes(StandardCharsets.UTF_8));
        assertThat(RecordedTestFiles.read(ok).name).isEqualTo("add-to-cart");

        Path broken = tmp.resolve("broken.json");
        Files.write(broken, "{".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> RecordedTestFiles.read(broken))
                .isInstanceOfSatisfying(DriverException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
                    assertThat(e.getMessage()).contains(broken.toAbsolutePath().normalize().toString() + ": ");
                });

        assertThatThrownBy(() -> RecordedTestFiles.read(tmp.resolve("missing.json")))
                .isInstanceOfSatisfying(DriverException.class, e ->
                        assertThat(e.getMessage()).contains("cannot read recorded test file "));
    }

    @Test
    void namesFollowOneRuleOnEveryOs() {
        assertThat(RecordedTestFiles.checkName("place-order")).isEqualTo("place-order");
        assertThat(RecordedTestFiles.checkName("Place order 2")).isEqualTo("Place order 2");
        assertThat(RecordedTestFiles.checkName("place-order.json")).isEqualTo("place-order");
        StringBuilder long101 = new StringBuilder();
        for (int i = 0; i < 101; i++) long101.append('a');
        List<String> bad = Arrays.asList("", "../x", "a/b", "a\\b", ".hidden", "x.", "CON", "nul.txt", "com1", "Lpt9.x",
                long101.toString());
        for (String name : bad) {
            assertThatThrownBy(() -> RecordedTestFiles.checkName(name)).as(name)
                    .isInstanceOfSatisfying(DriverException.class, e ->
                            assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT));
        }
        assertThat(RecordedTestFiles.file(tmp, "a.json")).isEqualTo(tmp.resolve("a.json"));
    }

    @Test
    void theListIsSortedAndHoldsOnlyRecordedTestFiles() throws IOException {
        for (String n : new String[] {"b.json", "a.json", "gate.txt", "notes.md"}) Files.write(tmp.resolve(n), new byte[0]);
        Files.createDirectory(tmp.resolve("sub"));
        Files.createDirectory(tmp.resolve("dir.json"));

        List<String> names = new ArrayList<String>();
        for (Path p : RecordedTestFiles.list(tmp)) names.add(p.getFileName().toString());

        assertThat(names).containsExactly("a.json", "b.json");
        assertThat(RecordedTestFiles.list(tmp.resolve("nowhere"))).isEmpty();
    }

    @Test
    void theFolderIsTheAgentOptionElseThePropertyElseUitest() {
        assertThat(RecordedTestFiles.dir("recorded", "prop", tmp)).isEqualTo(tmp.resolve("recorded"));
        assertThat(RecordedTestFiles.dir(" ", "prop", tmp)).isEqualTo(tmp.resolve("prop"));
        assertThat(RecordedTestFiles.dir(null, null, tmp)).isEqualTo(tmp.resolve("uitest"));
        Path abs = tmp.resolve("elsewhere").toAbsolutePath();
        assertThat(RecordedTestFiles.dir(abs.toString(), "prop", tmp)).isEqualTo(abs);
        assertThat(RecordedTestFiles.dir("a/../b", null, tmp)).isEqualTo(tmp.resolve("b"));
    }

    @Test
    void aFolderBelowAFileCannotBeWrittenAndSaysWhere() throws IOException {
        Path blocker = tmp.resolve("blocker");
        Files.write(blocker, new byte[0]);
        Path dir = blocker.resolve("uitest");

        assertThatThrownBy(() -> RecordedTestFiles.writableDir(dir))
                .isInstanceOfSatisfying(RecordedTestFolderNotWritable.class, e -> {
                    assertThat(e.getMessage()).contains(dir.toAbsolutePath().toString());
                    assertThat(e.folder()).isEqualTo(dir.toAbsolutePath());
                });
        assertThat(RecordedTestFiles.writableDir(tmp.resolve("ok"))).isDirectory();
    }

    private static final String CHECK = "{\"target\":{\"role\":\"label\",\"name\":\"Total\"},\"text\":\"1\"}";

    @Test
    void aRecordingIsWrittenNestedAndReadsBackTheSame() throws Exception {
        Flow flow = stepsOf(
                "{\"action\":\"menu\",\"say\":\"Open the orders\",\"path\":[\"Orders\"]}",
                "{\"action\":\"expect\",\"say\":\"The total is 1\",\"target\":{\"role\":\"label\",\"name\":\"Total\"},\"text\":\"1\"}",
                "{\"action\":\"close\"}");

        String text = RecordedTestFiles.write(flow);

        assertThat(text).isEqualTo("{\n"
                + "  \"version\" : 2,\n"
                + "  \"name\" : \"place-order\",\n"
                + "  \"description\" : \"Ada orders two gel pens\",\n"
                + "  \"start\" : \"reset\",\n"
                + "  \"steps\" : [\n"
                + "    {\n"
                + "      \"action\" : \"menu\",\n"
                + "      \"say\" : \"Open the orders\",\n"
                + "      \"path\" : [\n"
                + "        \"Orders\"\n"
                + "      ],\n"
                + "      \"checks\" : [\n"
                + "        {\n"
                + "          \"say\" : \"The total is 1\",\n"
                + "          \"target\" : {\n"
                + "            \"role\" : \"label\",\n"
                + "            \"name\" : \"Total\"\n"
                + "          },\n"
                + "          \"text\" : \"1\"\n"
                + "        }\n"
                + "      ]\n"
                + "    },\n"
                + "    {\n"
                + "      \"action\" : \"close\"\n"
                + "    }\n"
                + "  ]\n"
                + "}\n");
        Flow back = RecordedTestFiles.parse(text, "x");
        assertThat(back.steps).hasSize(3);
        assertThat(back.steps.get(0).say).isEqualTo("Open the orders");
        assertThat(RecordedTestFiles.write(back)).isEqualTo(text);
    }

    @Test
    void aGroupAndACheckOnlyFirstStepRoundTrip() throws Exception {
        String text = HEAD + "\"steps\":[{\"checks\":[" + CHECK + "]},"
                + "{\"say\":\"Open a new order\",\"steps\":[{\"action\":\"menu\",\"path\":[\"Orders\"]},{\"action\":\"close\"}]},"
                + "{\"action\":\"close\",\"checks\":[" + CHECK + "," + CHECK + "]}]}";

        Flow flow = RecordedTestFiles.parse(text, "x");

        assertThat(flow.steps).hasSize(6);
        assertThat(flow.groups).hasSize(1);
        assertThat(flow.groups.get(0).from).isEqualTo(1);
        assertThat(flow.groups.get(0).to).isEqualTo(2);
        assertThat(flow.groups.get(0).say).isEqualTo("Open a new order");
        assertThat(RecordedTestFiles.structureProblems(flow)).isEmpty();
        Flow again = RecordedTestFiles.parse(RecordedTestFiles.write(flow), "x");
        assertThat(RecordedTestFiles.write(again)).isEqualTo(RecordedTestFiles.write(flow));
        assertThat(again.groups).hasSize(1);
    }

    @Test
    void aVersion1FileSaysToNestTheChecks() {
        refused("{\"version\":1,\"start\":\"reset\",\"steps\":[{\"action\":\"close\"}]}",
                "is format version 1 (one flat list)", "reads versions 2 and 3", "nest each check under the step before it");
    }

    @Test
    void aCheckAtStepLevelIsRefused() {
        refused(HEAD + "\"steps\":[{\"action\":\"close\"},{\"action\":\"expect\",\"target\":{\"role\":\"label\"},\"text\":\"1\"}]}",
                "step 2 (expect)", "a check goes under the step it checks", "\"checks\"");
    }

    @Test
    void aCheckOnlyStepIsOnlyFirst() {
        String checkOnly = "{\"checks\":[" + CHECK + "]}";
        String close = "{\"action\":\"close\"}";
        assertThat(RecordedTestFiles.parse(HEAD + "\"steps\":[" + checkOnly + "," + close + "]}", "x").steps).hasSize(2);
        assertThat(RecordedTestFiles.parse(HEAD + "\"steps\":[{\"say\":\"s\",\"steps\":[" + checkOnly + "," + close + "]}]}", "x")
                .groups).hasSize(1);
        refused(HEAD + "\"steps\":[" + close + "," + close + "," + checkOnly + "]}",
                "step 3", "only checks", "first step");
        refused(HEAD + "\"steps\":[" + checkOnly + "," + checkOnly + "]}", "step 2", "only checks", "first step");
        refused(HEAD + "\"steps\":[" + close + ",{\"say\":\"s\",\"steps\":[" + close + "," + checkOnly + "]}]}",
                "step 3", "only checks", "first step");
    }

    @Test
    void anActionInsideChecksIsRefused() {
        refused(HEAD + "\"steps\":[{\"action\":\"close\",\"checks\":[{\"action\":\"click\"}]}]}",
                "check 1.1", "only an expect or a domain check (do)");
        assertThat(RecordedTestFiles.parse(HEAD + "\"steps\":[{\"action\":\"close\",\"checks\":[{\"action\":\"expect\","
                + "\"target\":{\"role\":\"label\"},\"text\":\"1\"}]}]}", "x").steps).hasSize(2);
    }

    @Test
    void emptyChecksAreRefused() {
        refused(HEAD + "\"steps\":[{\"action\":\"close\",\"checks\":[]}]}", "step 1 (close)", "checks", "not empty");
        refused(HEAD + "\"steps\":[{\"checks\":[]}]}", "step 1", "checks", "not empty");
        refused(HEAD + "\"steps\":[{\"action\":\"close\",\"checks\":{}}]}", "step 1 (close)", "checks", "array");
        refused(HEAD + "\"steps\":[{\"action\":\"close\",\"checks\":[\"x\"]}]}", "check 1.1", "JSON object");
    }

    @Test
    void aCheckOnlyStepHasNoSentence() {
        refused(HEAD + "\"steps\":[{\"say\":\"Look first\",\"checks\":[" + CHECK + "]}]}",
                "step 1", "no sentence of its own", "put it on a check");
    }

    @Test
    void numbersInMessagesAreLabels() {
        String group = "{\"say\":\"Two steps\",\"steps\":[{\"action\":\"close\"},{\"action\":\"close\"}]}";
        refused(HEAD + "\"steps\":[{\"action\":\"close\"}," + "{\"say\":\"Two steps\",\"steps\":[{\"action\":\"close\"},"
                + "{\"action\":\"close\",\"checks\":[" + CHECK + ",{\"target\":{\"role\":\"label\"},\"txt\":\"1\"}]}]}]}",
                "check 3.2 (expect)", "unknown field 'txt'", "known: ");
        refused(HEAD + "\"steps\":[" + group + ",{\"action\":\"type\",\"txt\":\"a\"}]}", "step 3 (type)", "unknown field 'txt'");
        refused(HEAD + "\"steps\":[{\"action\":\"close\"},{\"say\":\"One\",\"steps\":[{\"action\":\"close\"}]}]}",
                "the group of steps 2-2", "at least 2 steps");
    }

    @Test
    void groupsMustBeFlatHoldTwoStepsAndOnlySayAndSteps() {
        String close = "{\"action\":\"close\"}";
        refused(HEAD + "\"steps\":[{\"say\":\"Outer\",\"steps\":[" + close + ",{\"say\":\"Inner\",\"steps\":[" + close + "," + close
                + "]}]}]}", "groups do not nest");
        refused(HEAD + "\"steps\":[{\"say\":\"One\",\"steps\":[" + close + "]}]}", "the group", "at least 2 steps");
        refused(HEAD + "\"steps\":[{\"say\":\"Two\",\"steps\":[" + close + "," + close + "],\"checks\":[" + CHECK + "]}]}",
                "the group", "unknown field 'checks'", "say, steps");
        refused(HEAD + "\"steps\":[{\"steps\":[" + close + "," + close + "]}]}", "the group", "needs a \"say\"");
        refused(HEAD + "\"steps\":[{\"say\":\"Two\",\"steps\":{}}]}", "the group", "\"steps\" must be an array");
    }

    @Test
    void aSentenceIsOneLineAndNotEmpty() {
        String close = "{\"action\":\"close\",\"say\":";
        refused(HEAD + "\"steps\":[" + close + "\"\"}]}", "step 1 (close)", "sentence", "empty");
        refused(HEAD + "\"steps\":[" + close + "\"  \"}]}", "step 1 (close)", "sentence", "empty");
        refused(HEAD + "\"steps\":[" + close + "\"a\\nb\"}]}", "step 1 (close)", "one line");
        StringBuilder long201 = new StringBuilder();
        for (int i = 0; i < 201; i++) long201.append('a');
        refused(HEAD + "\"steps\":[" + close + "\"" + long201 + "\"}]}", "step 1 (close)", "200 characters");
        refused(HEAD + "\"steps\":[{\"say\":\"\",\"steps\":[{\"action\":\"close\"},{\"action\":\"close\"}]}]}", "the group", "sentence");
        refused(HEAD + "\"steps\":[{\"action\":\"close\",\"checks\":[{\"say\":\"x\\ny\",\"target\":{\"role\":\"label\"},\"text\":\"1\"}]}]}",
                "check 1.1", "one line");
        StringBuilder long200 = new StringBuilder();
        for (int i = 0; i < 200; i++) long200.append('a');
        assertThat(RecordedTestFiles.parse(HEAD + "\"steps\":[" + close + "\"" + long200 + "\"}]}", "x").steps.get(0).say).hasSize(200);
        assertThatThrownBy(() -> RecordedTestFiles.checkSay("a\rb", "step 4"))
                .isInstanceOfSatisfying(DriverException.class, e -> assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT));
        RecordedTestFiles.checkSay("Add the paper", "step 4");
    }

    @Test
    void sentencesAreTrimmedOnRead() {
        Flow f = RecordedTestFiles.parse(HEAD + "\"steps\":[{\"say\":\"  Fill the cart \",\"steps\":["
                + "{\"action\":\"close\",\"say\":\" Add paper \",\"checks\":[{\"say\":\" The total is €4.99 \","
                + "\"target\":{\"role\":\"label\"},\"text\":\"€4.99\"}]},{\"action\":\"close\"}]}]}", "x");
        assertThat(f.steps.get(0).say).isEqualTo("Add paper");
        assertThat(f.steps.get(1).say).isEqualTo("The total is €4.99");
        assertThat(f.groups.get(0).say).isEqualTo("Fill the cart");
    }

    @Test
    void anEmptyGroupSaysSo() {
        refused(HEAD + "\"steps\":[{\"action\":\"close\"},{\"say\":\"Nothing\",\"steps\":[]}]}",
                "the group at step 2 holds no steps");
    }

    @Test
    void aBadCheckSentenceNamesTheCheck() throws Exception {
        Flow f = stepsOf("{\"action\":\"close\"}", "{\"action\":\"expect\",\"target\":{\"role\":\"label\"},\"text\":\"1\"}");
        f.steps.get(1).say = "a\nb";
        assertThat(RecordedTestFiles.structureProblems(f)).containsExactly("check 1.1: a sentence is one line, without line breaks");
    }

    @Test
    void paramsAndCaptureSayValuesBelongInAJavaTest() {
        refused("{\"version\":2,\"start\":\"reset\",\"params\":{},\"steps\":[{\"action\":\"close\"}]}",
                "'params'", "exact values only", "Java test");
        refused(HEAD + "\"steps\":[{\"action\":\"close\",\"capture\":\"id\"}]}", "step 1 (close)", "'capture'", "exact values only",
                "Java test");
    }

    @Test
    void topLevelGroupsSayHowGroupsAreWritten() {
        refused("{\"version\":2,\"start\":\"reset\",\"groups\":[],\"steps\":[{\"action\":\"close\"}]}",
                "'groups'", "a group is written as {\"say\": ..., \"steps\": [...]} inside steps");
    }

    @Test
    void theReservedNamesStillSayNewerFormat() {
        refused(HEAD + "\"steps\":[{\"action\":\"close\",\"author\":\"person\"}]}", "step 1 (close)", "'author'",
                "newer recorded test format");
        refused(HEAD + "\"steps\":[" + DO_ADD + "]}", "step 1", "'do' needs format version 3");
        refused("{\"version\":2,\"start\":\"reset\",\"requires\":[],\"steps\":[{\"action\":\"close\"}]}",
                "'requires' needs format version 3");
    }

    @Test
    void writeNeverWritesWhatItsReaderRefuses() throws Exception {
        Flow base = stepsOf("{\"action\":\"close\"}", "{\"action\":\"expect\",\"target\":{\"role\":\"label\"},\"text\":\"1\"}",
                "{\"action\":\"close\"}", "{\"action\":\"close\"}");
        base.start = "reset";

        Flow newer = copy(base);
        newer.version = 4;
        assertThatThrownBy(() -> RecordedTestFiles.write(newer)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");

        Flow home = copy(base);
        home.start = "home";
        assertThatThrownBy(() -> RecordedTestFiles.write(home)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("start");

        Flow split = copy(base);   // the group ends between step 1 and its check
        split.groups.add(new FlowGroup(0, 0, "Splits"));
        assertThatThrownBy(() -> RecordedTestFiles.write(split)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("group");
        assertThat(RecordedTestFiles.structureProblems(split)).anyMatch(p -> p.contains("step boundaries"));

        Flow overlap = copy(base);
        overlap.groups.add(new FlowGroup(0, 2, "First"));
        overlap.groups.add(new FlowGroup(2, 3, "Second"));
        assertThat(RecordedTestFiles.structureProblems(overlap)).anyMatch(p -> p.contains("overlap"));

        Flow one = copy(base);
        one.groups.add(new FlowGroup(2, 2, "Alone"));
        assertThat(RecordedTestFiles.structureProblems(one)).anyMatch(p -> p.contains("at least 2 steps"));
        assertThatThrownBy(() -> RecordedTestFiles.write(one)).isInstanceOf(IllegalArgumentException.class);

        Flow badSay = copy(base);
        badSay.steps.get(0).say = "a\nb";
        badSay.groups.add(new FlowGroup(2, 3, ""));
        assertThat(RecordedTestFiles.structureProblems(badSay)).hasSize(2);

        Flow fine = copy(base);
        fine.groups.add(new FlowGroup(0, 3, "All"));
        assertThat(RecordedTestFiles.structureProblems(fine)).isEmpty();
        assertThat(RecordedTestFiles.write(fine)).contains("\"say\" : \"All\"");
    }

    private static Flow copy(Flow f) throws Exception {
        Flow c = new Flow();
        c.name = f.name;
        c.start = f.start;
        for (Action a : f.steps) c.steps.add(FlowJson.mapper().readValue(FlowJson.mapper().writeValueAsString(a), Action.class));
        return c;
    }
}
