package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.PendingDialog;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.flow.RecordingStepHost;
import io.github.kormcrete.swingui.spi.UiInventory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class UiMcpToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path testsDir;

    @BeforeEach
    void pointExportsAtTempDir() {
        // Keep exportTest from writing into the real src/test/java during unit tests.
        System.setProperty(UiMcpSession.TESTS_DIR_PROP, testsDir.toString());
    }

    @AfterEach
    void clearProperty() {
        System.clearProperty(UiMcpSession.TESTS_DIR_PROP);
    }

    /**
     * A map-backed {@link UiInventory}: each fact under the name of the method it backs, defaulting to a
     * "Orders" tab with one toolbar action and one menu path. A test replaces a fact with {@code facts.put}.
     */
    static class FakeInventory implements UiInventory {
        final Map<String, Object> facts = new LinkedHashMap<String, Object>();

        FakeInventory() {
            facts.put("activeTab", "Orders");
            facts.put("navTargetEntryPointIds", Collections.singletonList("orders-finder"));
            facts.put("toolbarActions", Collections.singletonList(row("id", "toolbar.new", "label", "New", "enabled", true)));
            facts.put("menuPaths", Collections.singletonList("File"));
            Map<String, List<String>> nav = new LinkedHashMap<String, List<String>>();
            nav.put("roots", Arrays.asList("Orders", "Customers"));
            nav.put("children", Collections.<String>emptyList());
            facts.put("navEntryPoints", nav);
            facts.put("viewTabs", new LinkedHashMap<String, List<String>>());
            facts.put("viewSections", Collections.<Map<String, Object>>emptyList());
            facts.put("viewSectionProblems", Collections.<String>emptyList());
        }

        @SuppressWarnings("unchecked")
        private <T> T fact(String field) {
            return (T) facts.get(field);
        }

        @Override public String activeTabTitle() { return fact("activeTab"); }
        @Override public List<String> navTargets() { return Collections.singletonList("Home"); }
        @Override public List<String> entryPointIds() { return fact("navTargetEntryPointIds"); }
        @Override public List<Map<String, Object>> toolbarActions() { return fact("toolbarActions"); }
        @Override public List<String> menuPaths() { return fact("menuPaths"); }
        @Override public Map<String, List<String>> navEntryPoints() { return fact("navEntryPoints"); }
        @Override public Map<String, List<String>> viewTabs() { return fact("viewTabs"); }
        @Override public List<Map<String, Object>> viewSections() { return fact("viewSections"); }
        @Override public List<String> viewSectionProblems() { return fact("viewSectionProblems"); }
    }

    private final RecordingStepHost host = new RecordingStepHost(new FakeVerbs());
    private final FakeInventory inventory = new FakeInventory();

    /** A session right after an app plugin's reset, recording. */
    private UiMcpSession session() {
        UiMcpSession s = new UiMcpSession(host, inventory, mapper);
        s.startRecording(true);
        return s;
    }

    private static PendingDialog dialog(String title, String message, String... buttons) {
        return new PendingDialog(title, message, Arrays.asList(buttons), Collections.<String>emptyList());
    }

    // ---- dialogs --------------------------------------------------------------------------

    @Test
    void aStepThatOpensADialogReportsWhatItSaysAndOffers() throws Exception {
        UiMcpSession s = session();
        host.verbs.during = () -> host.waiting = dialog("Delete line item",
                "Only line items without a quantity can be deleted.", "OK");

        JsonNode r = mapper.readTree(s.doAction(mapper.readTree("{\"action\":\"click\","
                + "\"target\":{\"role\":\"button\",\"name\":\"Remove Selected\"}}")));

        assertThat(r.path("passed").asBoolean()).isTrue();
        assertThat(r.path("dialog").path("title").asText()).isEqualTo("Delete line item");
        assertThat(r.path("dialog").path("message").asText())
                .isEqualTo("Only line items without a quantity can be deleted.");
        assertThat(r.path("dialog").path("buttons").get(0).asText()).isEqualTo("OK");
    }

    @Test
    void aStepThatOpensNoDialogReportsNone() throws Exception {
        JsonNode r = mapper.readTree(session().doAction(
                mapper.readTree("{\"action\":\"navigate\",\"target\":\"Orders\"}")));

        assertThat(r.has("dialog")).isFalse();
        assertThat(host.calls).containsExactly("navigate Orders");
    }

    @Test
    void anAppStepBehindAWaitingDialogIsRefusedOverMcp() throws Exception {
        host.waiting = dialog("Confirmation", "Delete?", "Yes", "No");

        JsonNode r = mapper.readTree(session().doAction(
                mapper.readTree("{\"action\":\"navigate\",\"target\":\"Orders\"}")));

        assertThat(r.path("passed").asBoolean()).isFalse();
        assertThat(r.path("message").asText()).contains("a dialog is waiting for an answer").contains("Delete?");
        assertThat(r.path("dialog").path("buttons").get(1).asText()).isEqualTo("No");
        assertThat(host.calls).isEmpty();
    }

    // ---- describe_ui -----------------------------------------------------------------------

    @Test
    void describeUiReportsAWaitingDialog() throws Exception {
        host.waiting = dialog("Confirmation", "Delete?", "Yes", "No");

        JsonNode out = mapper.readTree(session().describe());

        assertThat(out.path("dialog").path("title").asText()).isEqualTo("Confirmation");
        assertThat(out.path("dialog").path("buttons").get(1).asText()).isEqualTo("No");
    }

    @Test
    void describeUiReportsEveryField() throws Exception {
        JsonNode out = mapper.readTree(session().describe());

        List<String> fields = new ArrayList<String>();
        out.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("activeTab", "navTargets", "navTargetEntryPointIds", "toolbarActions",
                "menuPaths", "navEntryPoints", "viewTabs", "sections", "sectionProblems", "dialog", "vocabulary");
        assertThat(out.path("activeTab").asText()).isEqualTo("Orders");
        assertThat(out.path("navTargets").get(0).asText()).isEqualTo("Home");
        assertThat(out.path("navTargetEntryPointIds").get(0).asText()).isEqualTo("orders-finder");
        assertThat(out.path("toolbarActions").get(0).path("id").asText()).isEqualTo("toolbar.new");
        assertThat(out.path("toolbarActions").get(0).path("label").asText()).isEqualTo("New");
        assertThat(out.path("menuPaths").get(0).asText()).isEqualTo("File");
        assertThat(out.path("navEntryPoints").path("roots").get(1).asText()).isEqualTo("Customers");
        assertThat(out.path("viewTabs").isObject()).isTrue();
        assertThat(out.path("dialog").isNull()).isTrue();
    }

    /** An inventory that names no navigate targets yields an empty {@code navTargets} array, not a missing field. */
    @Test
    void describeUiReportsNoNavTargetsForAnInventoryWithoutAny() throws Exception {
        UiInventory plain = new UiInventory() {
            @Override public String activeTabTitle() { return "T"; }
            @Override public List<String> entryPointIds() { return Collections.emptyList(); }
            @Override public List<Map<String, Object>> toolbarActions() { return Collections.emptyList(); }
            @Override public List<String> menuPaths() { return Collections.emptyList(); }
            @Override public Map<String, List<String>> navEntryPoints() { return Collections.emptyMap(); }
            @Override public Map<String, List<String>> viewTabs() { return Collections.emptyMap(); }
            @Override public List<Map<String, Object>> viewSections() { return Collections.emptyList(); }
            @Override public List<String> viewSectionProblems() { return Collections.emptyList(); }
        };
        JsonNode out = mapper.readTree(new UiMcpSession(new RecordingStepHost(), plain, mapper).describe());
        assertThat(out.path("navTargets").isArray()).isTrue();
        assertThat(out.path("navTargets")).isEmpty();
    }

    @Test
    void anUnknownDescribeFieldIsBadInputNamingTheFields() {
        DriverException e = catchThrowableOfType(() -> session().describe(Collections.singletonList("rows")),
                DriverException.class);
        assertThat(e).isNotNull();
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("'rows'")
                .contains("activeTab", "navTargetEntryPointIds", "toolbarActions", "menuPaths",
                        "navEntryPoints", "viewTabs", "sections", "sectionProblems", "dialog");
    }

    @Test
    void describeUiCutToFieldsReturnsOnlyThose() throws Exception {
        JsonNode out = mapper.readTree(session().describe(Arrays.asList("activeTab", "dialog")));

        List<String> fields = new ArrayList<String>();
        out.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("activeTab", "dialog");
    }

    // ---- describe_ui's sections projection --------------

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) row.put((String) kv[i], kv[i + 1]);
        return row;
    }

    @Test
    void describeUiListsEveryRegisteredSectionFlatWithAViewOpen() throws Exception {
        // A normal, tab-located, fully-built component: every boolean is a real answer.
        Map<String, Object> built = row(
                "id", "orderGeneral", "name", "General", "location", "NORTH_LOCATION",
                "pane", "north", "tab", "General", "created", true,
                "selected", true, "visible", true, "editable", true, "saveable", true,
                "saveConditions", null, "hint", null);
        // A button location: pane and tab are null (absent from the two tabbed panes), not omitted.
        Map<String, Object> button = row(
                "id", "orderSave", "name", "Save", "location", "BUTTON_RIGHT_LOCATION",
                "pane", null, "tab", null, "created", true,
                "selected", false, "visible", true, "editable", true, "saveable", false,
                "saveConditions", "Please enter a quantity", "hint", "Saves the order");
        inventory.facts.put("viewSections", Arrays.asList(built, button));
        inventory.facts.put("viewSectionProblems", Collections.singletonList(
                "com.example.uitest.DemoEditorSections threw java.lang.NullPointerException"));

        JsonNode out = mapper.readTree(session().describe());

        JsonNode components = out.path("sections");
        assertThat(components.isArray()).isTrue();
        assertThat(components).hasSize(2);
        JsonNode row0 = components.get(0);
        assertThat(row0.path("id").asText()).isEqualTo("orderGeneral");
        assertThat(row0.path("pane").asText()).isEqualTo("north");
        assertThat(row0.path("tab").asText()).isEqualTo("General");
        assertThat(row0.path("created").asBoolean()).isTrue();
        assertThat(row0.path("selected").asBoolean()).isTrue();
        assertThat(row0.has("error")).isFalse();
        JsonNode row1 = components.get(1);
        assertThat(row1.path("pane").isNull()).isTrue();
        assertThat(row1.path("tab").isNull()).isTrue();

        // Problems the registry recorded ride alongside, not swallowed, and appear once.
        assertThat(out.path("sectionProblems").isArray()).isTrue();
        assertThat(out.path("sectionProblems")).hasSize(1);
        assertThat(out.path("sectionProblems").get(0).asText()).contains("threw");

        // viewTabs is a separate projection and stays an object.
        assertThat(out.path("viewTabs").isObject()).isTrue();
    }

    @Test
    void describeUiSectionsIsEmptyWithNoViewOpen() throws Exception {
        JsonNode out = mapper.readTree(session().describe());

        assertThat(out.path("sections").isArray()).isTrue();
        assertThat(out.path("sections")).isEmpty();
        assertThat(out.path("sectionProblems").isArray()).isTrue();
        assertThat(out.path("sectionProblems")).isEmpty();
    }

    @Test
    void describeUiSectionsReportsAThrownSectionsError() throws Exception {
        Map<String, Object> thrown = row(
                "id", "orderBroken", "name", null, "location", null, "pane", null, "tab", null,
                "created", false, "selected", null, "visible", null, "editable", null, "saveable", null,
                "saveConditions", null, "hint", null, "error", "java.lang.NullPointerException");
        inventory.facts.put("viewSections", Collections.singletonList(thrown));

        JsonNode out = mapper.readTree(session().describe());

        JsonNode row = out.path("sections").get(0);
        assertThat(row.path("created").asBoolean()).isFalse();
        assertThat(row.path("error").asText()).isEqualTo("java.lang.NullPointerException");
    }

    @Test
    void describeUiSectionsReportsNullBooleansNotFalseWhenStateIsAbsent() throws Exception {
        // Never built and no error: the unknown booleans come out null, not a false claim.
        Map<String, Object> uncreated = row(
                "id", "orderHistory", "name", "History", "location", "SOUTH_LOCATION",
                "pane", "south", "tab", "History", "created", false,
                "selected", null, "visible", null, "editable", null, "saveable", null,
                "saveConditions", null, "hint", null);
        inventory.facts.put("viewSections", Collections.singletonList(uncreated));

        JsonNode out = mapper.readTree(session().describe());

        JsonNode row = out.path("sections").get(0);
        assertThat(row.path("created").isBoolean()).isTrue();
        assertThat(row.path("created").asBoolean()).isFalse();
        assertThat(row.has("selected")).isTrue();
        assertThat(row.path("selected").isNull()).isTrue();
        assertThat(row.path("visible").isNull()).isTrue();
        assertThat(row.path("editable").isNull()).isTrue();
        assertThat(row.path("saveable").isNull()).isTrue();
        assertThat(row.has("error")).isFalse();
    }

    // ---- do_action -------------------------------------------------------------------------

    @Test
    void doActionRunsAndRecordsOnSuccess() throws Exception {
        UiMcpSession s = session();
        JsonNode r1 = mapper.readTree(s.doAction(mapper.readTree("{\"action\":\"navigate\",\"target\":\"Orders\"}")));
        assertThat(r1.path("passed").asBoolean()).isTrue();

        // loginAs has a Java form only as the first step: here it runs but cannot be recorded
        JsonNode r2 = mapper.readTree(s.doAction(mapper.readTree("{\"action\":\"loginAs\",\"as\":\"customer\"}")));
        assertThat(r2.path("passed").asBoolean()).isTrue();

        // a passing step with a Java form reports the step it recorded; one without passes unrecorded
        // and says why (recorded, it would make every later export_test refuse)
        assertThat(r1.path("recorded").path("action").asText()).isEqualTo("navigate");
        assertThat(r2.has("recorded")).isFalse();
        assertThat(r2.path("notRecorded").asText()).contains("loginAs");
        assertThat(host.calls).containsExactly("navigate Orders", "loginAs customer");
    }

    @Test
    void doActionFailureIsNotRecorded() throws Exception {
        host.failNavigateTo = "Nowhere";
        JsonNode r = mapper.readTree(session().doAction(
                mapper.readTree("{\"action\":\"navigate\",\"target\":\"Nowhere\"}")));
        assertThat(r.path("passed").asBoolean()).isFalse();
        assertThat(r.path("message").asText()).contains("no tab opened");

        assertThat(r.has("recorded")).isFalse();
    }

    /** Over MCP, an unknown step type is a BAD_INPUT error naming it and the step types there are. */
    @Test
    void anUnknownStepTypeIsBadInputOverMcp() {
        DriverException e = catchThrowableOfType(() -> session().doAction(
                mapper.readTree("{\"action\":\"countRows\",\"op\":\"gte\",\"value\":1}")), DriverException.class);

        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("'countRows'").contains("navigate", "close", "loginAs", "click",
                "contextMenu", "screenshot");
    }

    @Test
    void toolsBuildExposesAllTools() {
        List<io.github.kormcrete.swingui.mcp.rpc.McpToolDefinition> tools = UiMcpTools.build(session(), mapper);
        List<String> names = new ArrayList<String>();
        for (io.github.kormcrete.swingui.mcp.rpc.McpToolDefinition t : tools) names.add(t.name());
        assertThat(names).containsExactlyInAnyOrder(
                "describe_ui", "do_action", "do_actions", "batch",
                "export_test", "export_recorded_test", "run_recorded_test", "snapshot", "read", "screenshot", "inspect", "recording", "vocabulary", "read_clipboard", "read_file_chooser",
                "click", "type", "press", "check", "select", "pick", "menu", "context_menu",
                "answer", "choose_file", "drag", "drop", "expect", "scroll", "set_clipboard", "hover", "resize_column", "resize_window", "close_window");
    }

    @Test
    void theVocabularyToolIsReadOnlyWithThreeOps() {
        for (io.github.kormcrete.swingui.mcp.rpc.McpToolDefinition t : UiMcpTools.build(session(), mapper)) {
            if (!t.name().equals("vocabulary")) continue;
            com.fasterxml.jackson.databind.JsonNode d = t.descriptor(mapper);
            assertThat(d.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
            List<String> ops = new ArrayList<String>();
            for (com.fasterxml.jackson.databind.JsonNode o : d.path("inputSchema").path("properties").path("op").path("enum")) {
                ops.add(o.asText());
            }
            assertThat(ops).containsExactly("list", "describe", "match");
            assertThat(d.path("inputSchema").path("required")).hasSize(1);
            return;
        }
        throw new AssertionError("no vocabulary tool");
    }

    @Test
    void noToolDescriptionSaysFlow() {
        for (io.github.kormcrete.swingui.mcp.rpc.McpToolDefinition t : UiMcpTools.build(session(), mapper)) {
            String text = t.descriptor(mapper).toString().replaceAll("(?i)workflow", "");
            assertThat(text.toLowerCase()).as(t.name()).doesNotContain("flow");
        }
    }

    @Test
    void theStepSchemaNamesOnlyTheStepTypesThereAre() {
        io.github.kormcrete.swingui.mcp.rpc.McpToolDefinition doAction = null;
        for (io.github.kormcrete.swingui.mcp.rpc.McpToolDefinition t : UiMcpTools.build(session(), mapper)) {
            if (t.name().equals("do_action")) doAction = t;
        }
        JsonNode names = doAction.descriptor(mapper).path("inputSchema").path("properties").path("action")
                .path("properties").path("action").path("enum");
        List<String> types = new ArrayList<String>();
        for (JsonNode n : names) types.add(n.asText());
        assertThat(types).hasSize(25).contains("hover", "resizeColumn", "resizeWindow", "closeWindow", "focusFileChooserName", "navigate", "close", "loginAs", "contextMenu", "click", "do");
    }

    // ---- do_actions: one round trip for a whole run of steps ----------------------------
    // Each action still goes through the same FlowExecutor as a single do_action.

    @Test
    void doActionsRunsEveryActionWhenAllPass() throws Exception {
        UiMcpSession s = session();
        JsonNode batch = mapper.readTree("[{\"action\":\"navigate\",\"target\":\"Orders\"},"
                + "{\"action\":\"close\"}]");

        JsonNode r = mapper.readTree(s.doActions(batch));

        assertThat(r.path("passed").asBoolean()).isTrue();
        assertThat(r.path("ran").asInt()).isEqualTo(2);
        assertThat(r.path("total").asInt()).isEqualTo(2);
        assertThat(r.path("steps")).hasSize(2);
        assertThat(r.path("steps").get(0).path("passed").asBoolean()).isTrue();
        assertThat(r.path("steps").get(1).path("passed").asBoolean()).isTrue();
    }

    @Test
    void doActionsStopsAtTheFirstFailure() throws Exception {
        UiMcpSession s = session();
        host.failNavigateTo = "Nowhere";
        // The middle step fails; the third must never run — continuing past a failed step would
        // drive the UI from a state the recorded test never established.
        JsonNode batch = mapper.readTree("[{\"action\":\"navigate\",\"target\":\"Orders\"},"
                + "{\"action\":\"navigate\",\"target\":\"Nowhere\"},"
                + "{\"action\":\"navigate\",\"target\":\"Customers\"}]");

        JsonNode r = mapper.readTree(s.doActions(batch));

        assertThat(r.path("passed").asBoolean()).isFalse();
        assertThat(r.path("ran").asInt()).isEqualTo(2);
        assertThat(r.path("total").asInt()).isEqualTo(3);
        assertThat(r.path("steps")).hasSize(2);
        assertThat(r.path("steps").get(1).path("passed").asBoolean()).isFalse();
        assertThat(host.calls).containsExactly("navigate Orders", "navigate Nowhere");
    }

    @Test
    void doActionsNamesTheFailingStepSoItIsDiagnosableWithoutARerun() throws Exception {
        UiMcpSession s = session();
        host.failNavigateTo = "Nowhere";
        JsonNode batch = mapper.readTree("[{\"action\":\"navigate\",\"target\":\"Nowhere\"}]");

        JsonNode r = mapper.readTree(s.doActions(batch));

        assertThat(r.path("steps").get(0).path("message").asText()).contains("no tab opened");
        assertThat(r.path("steps").get(0).path("action").asText()).contains("navigate(Nowhere)");
    }

    @Test
    void doActionsRecordsOnlyTheStepsThatPassed() throws Exception {
        UiMcpSession s = session();
        host.failNavigateTo = "Nowhere";
        JsonNode batch = mapper.readTree("[{\"action\":\"navigate\",\"target\":\"Orders\"},"
                + "{\"action\":\"navigate\",\"target\":\"Nowhere\"}]");

        s.doActions(batch);

        // The first navigate passed and stays recorded; the failed one does not, as with do_action.
        JsonNode out = mapper.readTree(s.exportTest("PartialUiIT", "partial", "p", true));
        assertThat(out.path("steps").asInt()).isEqualTo(1);
    }

    @Test
    void doActionsRefusesSomethingThatIsNotAnArray() throws Exception {
        UiMcpSession s = session();
        JsonNode notAnArray = mapper.readTree("{\"action\":\"navigate\",\"target\":\"Orders\"}");

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> s.doActions(notAnArray))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("array");
    }

    @Test
    void doActionsRefusesAnEmptyBatch() throws Exception {
        UiMcpSession s = session();

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> s.doActions(mapper.readTree("[]")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doActionWithRecordFalseRunsTheStepAndRecordsNothing() throws Exception {
        io.github.kormcrete.swingui.mcp.rpc.McpToolDefinition doAction = null;
        for (io.github.kormcrete.swingui.mcp.rpc.McpToolDefinition t : UiMcpTools.build(session(), mapper)) {
            if (t.name().equals("do_action")) doAction = t;
        }
        // build() makes a session of its own; use one session for both calls
        UiMcpSession s = session();
        for (io.github.kormcrete.swingui.mcp.rpc.McpToolDefinition t : UiMcpTools.build(s, mapper)) {
            if (t.name().equals("do_action")) doAction = t;
        }
        JsonNode r = mapper.readTree(doAction.invoke(mapper.readTree(
                "{\"action\":{\"action\":\"press\",\"keys\":\"ENTER\"},\"record\":false}")));
        assertThat(r.path("passed").asBoolean()).isTrue();
        assertThat(r.has("recorded")).isFalse();
        assertThat(r.has("notRecorded")).isFalse();
        assertThatThrownBy(() -> s.exportTest("NothingUiIT", "nothing", "p", false))
                .hasMessageContaining("nothing was recorded");
    }

    @Test
    void theRecordingToolTakesStepNumbersAndCheckLabels() {
        for (io.github.kormcrete.swingui.mcp.rpc.McpToolDefinition t : UiMcpTools.build(session(), mapper)) {
            if (!t.name().equals("recording")) continue;
            JsonNode d = t.descriptor(mapper);
            JsonNode props = d.path("inputSchema").path("properties");
            for (String name : new String[] {"at", "n"}) {
                JsonNode type = props.path(name).path("type");
                assertThat(type.isArray()).isTrue();
                List<String> types = new ArrayList<String>();
                for (JsonNode x : type) types.add(x.asText());
                assertThat(types).containsExactly("integer", "string");
                assertThat(props.path(name).path("description").asText()).contains("7.2");
            }
            for (String name : new String[] {"from", "to"}) assertThat(props.path(name).path("type").asText()).isEqualTo("integer");
            assertThat(props.path("say").path("type").asText()).isEqualTo("string");
            List<String> ops = new ArrayList<String>();
            for (JsonNode n : props.path("op").path("enum")) ops.add(n.asText());
            assertThat(ops).contains("group", "ungroup");
            String text = d.path("description").asText();
            assertThat(text).contains("7.2").contains("group {from, to, say}").contains("ungroup {n}")
                    .doesNotContain("later version");
            return;
        }
        throw new AssertionError("no recording tool");
    }

    @Test
    void theRecordingToolsListOpIsReadOnly() {
        for (io.github.kormcrete.swingui.mcp.rpc.McpToolDefinition t : UiMcpTools.build(session(), mapper)) {
            if (t.name().equals("recording")) {
                assertThat(t.readOnlyOps()).containsExactly("list");
                assertThat(t.descriptor(mapper).path("annotations").path("readOnlyHint").asBoolean()).isFalse();
                List<String> ops = new ArrayList<String>();
                for (JsonNode n : t.descriptor(mapper).path("inputSchema").path("properties").path("op").path("enum")) ops.add(n.asText());
                assertThat(ops).containsExactly("start", "stop", "list", "insert", "delete", "move", "edit", "group", "ungroup", "row", "undo",
                        "fold", "unfold", "keepRaw", "replay", "redo", "export", "load");
                JsonNode props = t.descriptor(mapper).path("inputSchema").path("properties");
                assertThat(props.path("step").path("type").toString()).isEqualTo("[\"object\",\"string\"]");
                assertThat(props.path("rev").path("description").asText()).contains("fold, unfold, keepRaw");
                assertThat(props.path("rev").path("description").asText()).contains("redo");
                assertThat(props.path("wait").path("description").asText()).contains("session reset");
                return;
            }
        }
        throw new AssertionError("no recording tool");
    }
}
