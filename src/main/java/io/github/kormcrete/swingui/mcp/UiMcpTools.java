package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.flow.Action;
import io.github.kormcrete.swingui.flow.verb.VerbAction;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kormcrete.swingui.mcp.rpc.McpToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds the MCP tools that read and drive the live client through a UiMcpSession. */
public final class UiMcpTools {

    private UiMcpTools() {}

    /** The {@code outcome} argument of the step tools. */
    static final String OUTCOME_ARG = "true: the app is read before and after the step, and the result's 'outcome' says what "
            + "it changed (a check's is {}; null when the app cannot be read), with 'outcomeHandle': read form outcome with "
            + "that handle reads what it changed so far again, for a change the app makes late (the agent keeps the last "
            + UiMcpSession.KEPT_OUTCOMES + "). Off by default: it costs a reading of the app.";

    /** What an outcome holds, for the step tools' descriptions. */
    static final String OUTCOME_SHAPE = "only what changed, keyed by the names the person sees: opened and closed {windows, "
            + "dialogs, tabs}, fields {label: value}, texts, rows {table: {added, removed, changed [{before, after}], count}} "
            + "with each row as {column: value shown}, items {list or tree: {added, removed}}, selected, message, and "
            + "partial:true when a large table was read only around its view; a plugin's do step adds what its body "
            + "returned under step";

    public static List<McpToolDefinition> build(final UiMcpSession session, final ObjectMapper mapper) {
        List<McpToolDefinition> tools = new ArrayList<McpToolDefinition>();

        tools.add(new McpToolDefinition() {
            @Override public String name() { return "describe_ui"; }
            @Override public ObjectNode descriptor(ObjectMapper m) {
                return readOnly(tool(m, "describe_ui",
                        "The app facts the app plugin reports for the live client: activeTab, the navigate targets "
                        + "(navTargets: the friendly names, and navTargetEntryPointIds: the entry-point ids), the "
                        + "toolbar's toolbarActions (id/label/enabled/submenu), the menu bar's leaf menuPaths, "
                        + "navEntryPoints (roots, children), the open view's viewTabs and sections (every section it "
                        + "registered, including one never built - 'created':false; target it by its 'id') with "
                        + "sectionProblems, the plugins' domain steps as 'vocabulary' sentences (id, kind, sentence; the vocabulary tool has "
                        + "their params), and any waiting dialog ('dialog': title, message, buttons - answer it "
                        + "with the 'answer' verb). "
                        + "Tables, buttons and fields are read from the element tree with 'snapshot' or 'read'. "
                        + "Pass 'fields' to get only the parts you need.",
                        describeSchema(m)));
            }
            @Override public String invoke(JsonNode args) {
                List<String> fields = new ArrayList<String>();
                if (args != null && args.path("fields").isArray()) {
                    for (JsonNode f : args.get("fields")) fields.add(f.asText());
                }
                return session.describe(fields);
            }
        });

        tools.add(new McpToolDefinition() {
            @Override public String name() { return "do_action"; }
            @Override public ObjectNode descriptor(ObjectMapper m) {
                ObjectNode schema = m.createObjectNode();
                schema.put("type", "object");
                schema.putObject("properties").set("action", actionSchema(m));
                schema.putArray("required").add("action");
                schema.with("properties").putObject("record").put("type", "boolean").put("default", true)
                        .put("description", "false keeps this step out of export_test's recording (used by tests)");
                schema.with("properties").putObject("outcome").put("type", "boolean").put("default", false)
                        .put("description", OUTCOME_ARG);
                return tool(m, "do_action",
                        "Execute ONE step against the live client and return pass/fail and its "
                        + "duration ('ms'). A step is a verb (click, type, press, ..., contextMenu) or one of the "
                        + "app steps navigate, close, loginAs. A successful step is recorded, unless it has no Java "
                        + "form (a loginAs after the first step, a target by ref): it runs but is not recorded, and "
                        + "'notRecorded' says why. A do step is recorded even when its domain step has no Java form: "
                        + "export_recorded_test writes it, export_test refuses it. The 'action' object uses the step schema, e.g. "
                        + "{\"action\":\"navigate\",\"target\":\"home\"} or {\"action\":\"expect\","
                        + "\"target\":{\"role\":\"table\"},\"rows\":{\"op\":\"gte\",\"value\":1}}. A step "
                        + "that opens a message or confirmation returns at once with 'dialog' (title, message, "
                        + "buttons); navigate, close and loginAs refuse to run until it is answered with the "
                        + "'answer' verb. A step may carry say: its sentence (one line, kept in the recording and the recorded test). "
                        + "outcome:true adds 'outcome': what the step changed (" + OUTCOME_SHAPE + ").", schema);
            }
            @Override public String invoke(JsonNode args) throws Exception {
                JsonNode action = args.path("action");
                if (action.isMissingNode() || action.isNull()) {
                    throw new IllegalArgumentException("Missing required 'action' object");
                }
                return session.doAction(action, args.path("record").asBoolean(true), args.path("outcome").asBoolean(false));
            }
        });

        tools.add(new McpToolDefinition() {
            @Override public String name() { return "do_actions"; }
            @Override public ObjectNode descriptor(ObjectMapper m) {
                ObjectNode schema = m.createObjectNode();
                schema.put("type", "object");
                ObjectNode actions = m.createObjectNode();
                actions.put("type", "array");
                actions.put("description", "Steps to run in order");
                actions.set("items", actionSchema(m));
                schema.putObject("properties").set("actions", actions);
                schema.putArray("required").add("actions");
                return tool(m, "do_actions",
                        "Execute SEVERAL steps in order against the live client, in one call, "
                        + "stopping at the first failure. Prefer this over repeated do_action when the "
                        + "next steps are already known. "
                        + "Returns {passed, ran, total, steps:[{passed, action, message}]}; successful "
                        + "steps are recorded for export_test exactly as do_action records them. "
                        + "Example: {\"actions\":[{\"action\":\"navigate\",\"target\":\"home\"},"
                        + "{\"action\":\"click\",\"target\":{\"role\":\"tab\",\"name\":\"Customer\"}},"
                        + "{\"action\":\"close\"}]}. A step may carry say: its sentence, and outcome:true: its entry then holds "
                        + "'outcome', what it changed, as do_action has it.", schema);
            }
            @Override public String invoke(JsonNode args) throws Exception {
                JsonNode actions = args.path("actions");
                if (actions.isMissingNode() || actions.isNull()) {
                    throw new IllegalArgumentException("Missing required 'actions' array");
                }
                return session.doActions(actions);
            }
        });

        tools.add(new McpToolDefinition() {
            @Override public String name() { return "batch"; }
            @Override public ObjectNode descriptor(ObjectMapper m) {
                ObjectNode schema = emptyObjectSchema(m);
                ObjectNode p = schema.with("properties");
                ObjectNode steps = p.putObject("steps");
                steps.put("type", "array");
                steps.put("description", "The steps, in order. Each is {\"verb\": <name>, ...the arguments that "
                        + "verb's own tool takes}.");
                ObjectNode item = steps.putObject("items");
                item.put("type", "object");
                ObjectNode verb = item.putObject("properties").putObject("verb");
                verb.put("type", "string");
                verb.put("description", "A verb tool's name (" + String.join(", ", VerbSchemas.TOOLS.keySet())
                        + "), 'read', or a step type (navigate, close, loginAs, screenshot, ...).");
                item.with("properties").putObject("outcome").put("type", "boolean").put("description", OUTCOME_ARG);
                item.putArray("required").add("verb");
                ObjectNode snapshot = p.putObject("snapshot");
                snapshot.put("description", "true, or the snapshot tool's arguments (target, depth, compact, ...): "
                        + "a snapshot taken after the last step that ran, pass or fail.");
                p.putObject(UiMcpSession.CONTINUE_ON_FAILED_CHECK).put("type", "boolean").put("description",
                        "true: a failed check (expect, remember, a domain check) is reported and the batch goes on, so one "
                        + "call can report several values; a failed action still stops it, and passed is false if any "
                        + "step failed. Default false.");
                schema.putArray("required").add("steps");
                return tool(m, "batch",
                        "Run SEVERAL steps in ONE call, in order, stopping at the first that does not pass - use it "
                        + "whenever the next steps are already known. With continueOnFailedCheck true, a failed check "
                        + "does not stop the batch, only a failed action or read does. "
                        + "Each step runs, answers and records exactly as its own tool call would, so export_test "
                        + "writes the same test either way; 'read' steps record nothing, and a 'read' that finds "
                        + "nothing stops the batch. Returns {passed, ran, total, ms, steps:[{verb, passed, message, "
                        + "ms, recorded?, notRecorded?, ...}], snapshot?, snapshotError?}. A snapshot that cannot be "
                        + "taken keeps the steps and reports {errorType, message, details} as snapshotError. A harness "
                        + "error (CAPABILITY_GAP, INPUT_ERROR) is a tool "
                        + "error naming the step; the steps before it ran and were recorded. Example: {\"steps\":["
                        + "{\"verb\":\"menu\",\"path\":[\"File\",\"New order\"]},"
                        + "{\"verb\":\"type\",\"target\":{\"role\":\"text\",\"name\":\"Customer\"},\"text\":\"Ada\"},"
                        + "{\"verb\":\"press\",\"keys\":\"Enter\"},"
                        + "{\"verb\":\"expect\",\"target\":{\"role\":\"tab\",\"name\":\"New order · Ada Shop Ltd\"},"
                        + "\"state\":[\"selected\"]}]}. A step with \"outcome\":true has 'outcome' in its entry: what it changed, "
                        + "as do_action has it (e.g. the new row a Copy added, to use in the next call).", schema);
            }
            @Override public String invoke(JsonNode args) { return session.batch(args); }
        });

        tools.add(new McpToolDefinition() {
            @Override public String name() { return "export_test"; }
            @Override public ObjectNode descriptor(ObjectMapper m) {
                ObjectNode schema = emptyObjectSchema(m);
                ObjectNode p = schema.with("properties");
                p.putObject("class").put("type", "string")
                        .put("description", "Test class name; must end in UiIT, e.g. PlaceOrderUiIT");
                p.putObject("method").put("type", "string").put("description", "Test method name (default: recorded)");
                p.putObject("package").put("type", "string")
                        .put("description", "Java package (default: the app plugin's export package, else 'recorded')");
                p.putObject("dir").put("type", "string")
                        .put("description", "The test sources root to write under, e.g. <project>/src/test/java; wins over "
                                + "-Dswingui.testsDir and the plugin's module. A relative one is under the APP's working directory. "
                                + "Name it when the app runs outside the project");
                p.putObject("clear").put("type", "boolean")
                        .put("description", "Empty the recording after exporting (default: true); reset before "
                                + "recording the next test. Refused while a recording started by recording start or "
                                + "the person's Record runs: export with clear false, or stop it first");
                schema.putArray("required").add("class");
                return tool(m, "export_test",
                        "Write the steps recorded since the last session reset as a JUnit @UiTest class under "
                        + "<module>/src/test/java/<package>/<class>.java (the module is the app plugin's export "
                        + "module, else the working directory; dir or -Dswingui.testsDir overrides, and a working "
                        + "directory with no pom.xml or build.gradle makes the result carry a warning naming where the "
                        + "file went). The test starts where "
                        + "the recording did, right after a reset, so it must begin by getting to its own starting "
                        + "screen: without an app plugin a reset only closes dialogs, and startWarning flags a test "
                        + "that does not begin with menu, press or navigate. Only steps with a Java form are recorded "
                        + "(verbs, including expect for checks; navigate, close, a leading loginAs; do steps), except a "
                        + "do step whose domain step has no Java form: a recording with one is refused, naming it - "
                        + "export it with export_recorded_test instead. Refuses a "
                        + "recording that did not begin at a reset or a recording start, a bad class/method/package "
                        + "name, an existing file, or an empty recording, naming each problem; a refusal keeps the "
                        + "recording. A step and its checks are one block; sentences and groups become "
                        + "ui.step(\"...\") sections, a check's sentence a comment.",
                        schema);
            }
            @Override public String invoke(JsonNode args) throws Exception {
                return session.exportTest(args.path("class").asText(""), args.path("method").asText(""),
                        args.path("package").asText(""), args.path("clear").asBoolean(true), args.path("dir").asText(""));
            }
        });

        tools.add(new McpToolDefinition() {
            @Override public String name() { return "export_recorded_test"; }
            @Override public ObjectNode descriptor(ObjectMapper m) {
                ObjectNode schema = emptyObjectSchema(m);
                ObjectNode p = schema.with("properties");
                p.putObject("name").put("type", "string")
                        .put("description", "The file name without .json, e.g. place-order");
                p.putObject("dir").put("type", "string")
                        .put("description", "The folder; default the recorded tests folder: agent option recorded=, else "
                                + "-Dswingui.recordedDir, else uitest in the app's working directory");
                p.putObject("description").put("type", "string").put("description", "What the recorded test does, one sentence");
                p.putObject("clear").put("type", "boolean").put("default", true)
                        .put("description", "Empty the recording after exporting (default: true; false keeps it to fix and save again over "
                                + "the same file); reset before recording the next recorded test. Refused while a recording started by recording start or "
                                + "the person's Record runs: export with clear false, or stop it first");
                schema.putArray("required").add("name");
                return tool(m, "export_recorded_test",
                        "Write the steps recorded since the last session reset as a recorded test file "
                        + "<recorded tests folder>/<name>.json: UI steps and checks of what is visible, no compiling; run it "
                        + "with run_recorded_test, from the palette, or in Maven with the JUnit runner (RecordedTests). Same rules as "
                        + "export_test: it must have begun at a reset or a recording start, startWarning flags a recorded test "
                        + "that does not begin with menu, press or navigate, an empty recording or a bad name is refused, and an existing "
                        + "file is never overwritten - except the recorded test the recording came from (loaded in the "
                        + "palette or with recording load, or exported before without clear). A folder that cannot be written is refused, "
                        + "naming it. A recorded test holds exact values except its links (a step that uses what an earlier step "
                        + "changed or remembered, a text unique per run: see the recording tool), so a record the test creates, "
                        + "such as a new order number, is found again on each run; check data the test creates or data known to "
                        + "exist. Format version 2 (3 when it holds do steps, with requires naming their plugins; 4 when it has "
                        + "links or remember entries): each step with its checks nested, sentences and groups kept. "
                        + "It empties the recording (clear, default true): use it for the last save. Save while you still fix the "
                        + "test (run it, edit, run again) with clear:false or recording export, which keep the "
                        + "recording and may save over the same file; after a clearing save, recording load {name} brings "
                        + "the file back to edit.", schema);
            }
            @Override public String invoke(JsonNode args) throws Exception {
                return session.exportRecordedTest(args.path("name").asText(""), args.path("dir").asText(""),
                        args.path("description").asText(""), args.path("clear").asBoolean(true));
            }
        });

        tools.add(new McpToolDefinition() {
            @Override public String name() { return "run_recorded_test"; }
            @Override public ObjectNode descriptor(ObjectMapper m) {
                ObjectNode schema = emptyObjectSchema(m);
                ObjectNode p = schema.with("properties");
                p.putObject("name").put("type", "string")
                        .put("description", "A recorded test in the recorded tests folder, without .json, e.g. place-order");
                p.putObject("path").put("type", "string")
                        .put("description", "A recorded test file, absolute or relative to the app's working directory");
                p.putObject("test").put("type", "object")
                        .put("description", "The recorded test file's JSON inline (what export_recorded_test writes)");
                p.putObject("evidence").put("type", "string")
                        .put("description", "The folder for the failing step's picture; default "
                                + "target/ui-screenshots/failures");
                return tool(m, "run_recorded_test",
                        "Run a recorded test file on the live app and report it: one of 'name' (in the recorded tests folder), "
                        + "'path', or 'test' (the file's JSON inline). It begins as every recorded test does, with a reset "
                        + "(dialogs closed; with an app plugin its reset), runs every step as do_action would WITHOUT "
                        + "recording, and stops at the first that fails; that step carries what its locators match "
                        + "now, a scoped tree and a picture of the window (written under 'evidence', default "
                        + "target/ui-screenshots/failures). Returns {passed, ran, total, steps, report}; ran and total count steps, "
                        + "each entry of steps is a step or a check: "
                        + "[{n, say?, action, passed, ms, message?, reason?, details?, warning?, resolved?, diagnostics?}], n a step "
                        + "number (7) or a check label (\"7.2\"); action is the step's technical line. A step that uses what an "
                        + "earlier step changed, a remembered value or the run's values (${copy.rows.Orders.added[0].Number}, "
                        + "${number}, ${run.id}) carries resolved {reference: value}: what each stood for in this run, which "
                        + "report shows too; run.id is new on each run. A value no earlier step made fails its step, saying "
                        + "which, never falling back to the recorded one. After a "
                        + "failure take a new snapshot before using refs. Refused (BUSY) while a person records, "
                        + "answers or replays in the palette. A file with do steps is checked before anything runs: a "
                        + "plugin its 'requires' names that is not ready is CAPABILITY_GAP, a step or param that plugin "
                        + "does not offer is BAD_INPUT naming the step and its line. From a shell the CLI exits 4 when "
                        + "the recorded test fails.", schema);
            }
            @Override public String invoke(JsonNode args) throws Exception {
                return session.runRecordedTest(args);
            }
        });

        tools.add(new McpToolDefinition() {
            @Override public String name() { return "snapshot"; }
            @Override public ObjectNode descriptor(ObjectMapper m) {
                ObjectNode schema = emptyObjectSchema(m);
                ObjectNode p = schema.with("properties");
                snapshotProperties(m, p);
                return readOnly(tool(m, "snapshot",
                        "The live client as an element tree: every showing window, dialog and popup (focused window "
                        + "first, popups last), each element with role, name, value, state and a ref (e1, e2, ...). "
                        + "Refs stay valid until the next snapshot; pass one as {\"ref\":\"e12\"} as a verb's target. "
                        + "SCOPE IT: an unscoped snapshot is the whole app and costs thousands of tokens. Give a "
                        + "'target' locator (e.g. {\"scope\":[\"tab:Customer\"],\"role\":\"section\",\"name\":\"Header\"} "
                        + "or {\"role\":\"tab\",\"name\":\"Line items\"}) for one element's subtree, 'depth' for a few "
                        + "levels, and compact:true to drop rows and items. The answer ends with 'size' {nodes, "
                        + "bytes, scoped}.",
                        schema));
            }
            @Override public String invoke(JsonNode args) { return session.snapshot(args); }
        });

        tools.add(new McpToolDefinition() {
            @Override public String name() { return "read"; }
            @Override public ObjectNode descriptor(ObjectMapper m) {
                ObjectNode schema = emptyObjectSchema(m);
                ObjectNode p = schema.with("properties");
                p.set("target", LocatorSchema.of(m));
                p.putObject("maxRows").put("type", "integer").put("description", "Rows for a table, list or tree");
                ObjectNode form = p.putObject("form");
                form.put("type", "string").put("description", "What to read: node (default; {found,node}), all "
                        + "(every match; {found,nodes}), geometry ({found,geometry}), candidates ({candidates}) or "
                        + "menuItems ({found,items}; needs 'path', takes no target) or outcome ({found,outcome}; needs "
                        + "'handle', takes no target: what a step run with outcome:true changed so far, the app read again "
                        + "against the reading before that step)");
                ArrayNode forms = form.putArray("enum");
                for (String f : new String[] {"node", "all", "geometry", "candidates", "menuItems", "outcome"}) forms.add(f);
                p.putObject("handle").put("type", "integer").put("description",
                        "outcome form: the 'outcomeHandle' a step's result gave");
                p.putObject("path").put("type", "array").put("description", "menuItems form: the menu path to list the items under")
                        .putObject("items").put("type", "string");
                return readOnly(tool(m, "read",
                        "Reads one element without acting: its node, or with row/column one table cell or row. "
                        + "'target' is required for every form except menuItems and outcome. "
                        + "A node carries 'tooltip' when the element (or the rendered cell) shows one. "
                        + "A target that does not resolve returns found:false with candidates.", schema));
            }
            @Override public String invoke(JsonNode args) { return session.read(args); }
        });

        tools.add(new McpToolDefinition() {
            @Override public String name() { return "screenshot"; }
            @Override public ObjectNode descriptor(ObjectMapper m) {
                ObjectNode schema = emptyObjectSchema(m);
                ObjectNode p = schema.with("properties");
                p.set("target", LocatorSchema.of(m));
                p.putObject("window").put("type", "string").put("description",
                        "A whole window instead of the current one: main | dialog | dialog:<title> | window:<title> "
                        + "(a title ending in * matches a prefix). Ignored when 'target' is given.");
                ObjectNode region = p.putObject("region");
                region.put("type", "object");
                region.put("description", "A snippet of the target instead of all of it: x, y, w, h as "
                        + "fractions 0..1 of its bounds (the way 'point' works).");
                ObjectNode rp = region.putObject("properties");
                rp.putObject("x").put("type", "number");
                rp.putObject("y").put("type", "number");
                rp.putObject("w").put("type", "number");
                rp.putObject("h").put("type", "number");
                region.putArray("required").add("x").add("y").add("w").add("h");
                p.putObject("maxSize").put("type", "integer").put("description",
                        "Longest side of the returned image, default 1200 times 'scale'; it is scaled down to fit.");
                p.putObject("scale").put("type", "number").put("description",
                        "Image pixels per screen pixel, 1 to 4, default 1: 2 paints the app as a 2x (HiDPI) display "
                        + "would, same layout, finer text and lines - for images people will look at.");
                p.putObject("palette").put("type", "boolean").put("description",
                        "true keeps the person's palette and open menus in the picture (for documentation); "
                        + "default false.");
                p.putObject("checkpoint").put("type", "boolean").put("description",
                        "true: a picture for comparing runs: the pointer leaves the window, the app settles, the text "
                        + "caret is hidden while it paints; always scale 1, never scaled down; a cell, row or column "
                        + "target is just that part. Takes no region, maxSize, scale or palette.");
                ObjectNode masks = p.putObject("masks");
                masks.put("type", "array");
                masks.put("description", "With checkpoint: elements whose rectangles, relative to the picture, come "
                        + "back in 'masks' (to leave out when comparing); nothing is painted over them.");
                masks.set("items", LocatorSchema.of(m));
                return readOnly(tool(m, "screenshot",
                        "A PNG of the app: the target element, or the current window when no target is given. "
                        + "It PAINTS the app's own components into an offscreen image - it never grabs the screen - so "
                        + "it captures the client whether or not it is in front, takes no focus, and cannot catch "
                        + "another application's window. Give a 'target' for one dialog, panel, table or toolbar, "
                        + "and 'region' for a snippet inside it. Anything drawn in a window of its own (an open "
                        + "popup menu, a combo dropdown, a tooltip) is NOT in the image - capture that window "
                        + "instead. The person's palette and any boxes shown in the app are not in the image. "
                        + "'palette': true keeps the palette, the boxes and the open menus in the picture, as the "
                        + "person sees the window (for documentation); default false. "
                        + "'checkpoint': true moves the pointer off the window, so it needs the lease like an action; "
                        + "its text answer adds checkpoint:true and masks [{x, y, w, h, clipped}]. "
                        + "A read: nothing is recorded; to capture screens in a recorded test, use the "
                        + "'screenshot' step in a batch, which writes a file. Use it for an "
                        + "aesthetic call - alignment, cropping, overlap, proportion - and an 'expect' for "
                        + "anything that is really a number or a boolean.",
                        schema));
            }
            /** A checkpoint moves the pointer: that call is gated like an action, a plain screenshot is not. */
            @Override public java.util.Set<String> actingFlags() { return java.util.Collections.singleton("checkpoint"); }
            @Override public String invoke(JsonNode args) { return session.screenshot(args); }
        });

        tools.add(new McpToolDefinition() {
            @Override public String name() { return "inspect"; }
            @Override public ObjectNode descriptor(ObjectMapper m) {
                ObjectNode schema = emptyObjectSchema(m);
                ObjectNode p = schema.with("properties");
                ObjectNode op = p.putObject("op");
                op.put("type", "string");
                op.putArray("enum").add("highlight").add("pick").add("hover").add("clear");
                p.set("target", LocatorSchema.of(m));
                p.putObject("question").put("type", "string").put("description",
                        "highlight, pick: the question on the bar in the app window.");
                p.putObject("wait").put("type", "integer").put("description",
                        "Milliseconds to wait for the person: highlight and pick default 60000, at most 300000 "
                        + "(highlight with 0 only shows the boxes).");
                p.putObject("on").put("type", "boolean").put("description", "hover: true starts it, false stops it.");
                schema.putArray("required").add("op");
                return tool(m, "inspect",
                        "Work WITH the person at the app, only when the palette is on (the default; off with "
                        + "palette=off, mode=background or headless); otherwise a CAPABILITY_GAP. highlight: box every "
                        + "match of 'target' (numbered when several) with 'question' on a bar; they click the check "
                        + "mark on the one they mean (outcome confirmed, chosen n, its locator) or 'None of these' "
                        + "(rejected). pick: they click the element they mean - the "
                        + "click does not reach the app - and you get its locator. hover: a box follows their pointer "
                        + "and names what is under it as they see it (not the locator). clear: removes your boxes and "
                        + "question. The recording itself: the recording tool. While a person records, picks, answers or replays, no test run can take "
                        + "the lease.", schema);
            }
            @Override public String invoke(JsonNode args) { return session.inspect(args); }
        });

        tools.add(new McpToolDefinition() {
            @Override public String name() { return "recording"; }
            @Override public java.util.Set<String> readOnlyOps() { return java.util.Collections.singleton("list"); }
            @Override public ObjectNode descriptor(ObjectMapper m) {
                ObjectNode schema = emptyObjectSchema(m);
                ObjectNode p = schema.with("properties");
                p.putObject("op").put("type", "string").putArray("enum").add("start").add("stop").add("list")
                        .add("insert").add("delete").add("move").add("edit").add("group").add("ungroup").add("row").add("undo")
                        .add("fold").add("unfold").add("keepRaw").add("link").add("unlink").add("remember").add("unique")
                        .add("replay").add("redo").add("export").add("load");
                ObjectNode at = p.putObject("at");
                at.putArray("type").add("integer").add("string");
                at.put("description", "insert: where it goes. An action: the step number it gets (1 to steps+1), before "
                        + "the step now there. A check: the label it gets, a step number and its place under it "
                        + "(\"7.2\"; k up to the step's checks + 1), or 1 for a check-only step 1. A step number (7) "
                        + "or a check label (\"7.2\").");
                ObjectNode n = p.putObject("n");
                n.putArray("type").add("integer").add("string");
                n.put("description", "delete, move, edit, ungroup, row, unfold, link, unlink, unique: a step number (7) or a "
                        + "check label (\"7.2\"). delete 7 deletes the step and its checks, delete \"7.2\" only that check; move "
                        + "takes a step (with its checks; checks cannot be moved; delete and insert); edit \"7.2\" edits that check; ungroup takes any step of the group; "
                        + "unfold takes a folded step; link and unlink the step or check that uses a value; unique a typed step. "
                        + "remember: the step the entry goes under (default: the last).");
                p.putObject("from").put("type", "integer").put("description",
                        "delete, group, fold, keepRaw: first step of a range; replay: first step (default 1); redo: the step "
                        + "to record again (steps+1 continues).");
                p.putObject("speed").put("type", "string").put("description",
                        "replay: pauses for a person watching.").putArray("enum").add("fast").add("normal").add("slow");
                p.putObject("as").put("type", "string").put("description",
                        "export: the format, recorded or java (required); remember: the name to keep the value under (a "
                        + "letter, then letters, digits or _; not run; not a name the recording has already).");
                p.putObject("class").put("type", "string").put("description", "export java: the test class name.");
                p.putObject("method").put("type", "string").put("description", "export java: the test method (default recorded).");
                p.putObject("package").put("type", "string").put("description", "export java: the package.");
                p.putObject("name").put("type", "string").put("description",
                        "export recorded: the file name without .json; load: a recorded test in the recorded tests folder.");
                p.putObject("dir").put("type", "string").put("description", "export recorded: the folder; export java: the test sources root (e.g. <project>/src/test/java).");
                p.putObject("description").put("type", "string").put("description", "export recorded: what the recorded test does.");
                p.putObject("clear").put("type", "boolean").put("description",
                        "export: empty the recording afterwards (default false); refused while a recording started "
                        + "by start or the person's Record runs.");
                p.putObject("path").put("type", "string").put("description", "load: a recorded test file.");
                p.putObject("to").put("type", "integer").put("description",
                        "delete, group, fold, keepRaw: last step of a range; move: the step number the step has afterwards; "
                        + "replay: last step (default: the last).");
                p.putObject("say").put("type", "string").put("description",
                        "edit: the step's or check's sentence, one line of at most 200 characters (\"\" removes it), "
                        + "alone or with target, text or step; group: the group's sentence.");
                p.putObject("by").put("type", "string").put("description",
                        "row: how the table row of step or check n is named: value (its key column's text, as recorded "
                        + "when a column told the rows apart) or position (its index).").putArray("enum")
                        .add("value").add("position");
                ObjectNode stepParam = p.putObject("step");
                stepParam.putArray("type").add("object").add("string");
                stepParam.put("description", "insert, edit: a step or check as a recorded test file holds it (an object): "
                        + "${id.path} in it is a reference to an earlier step's id or a remembered name, $${ a literal ${; "
                        + "checked as a file is (edit keeps the step's id when it names none); fold: the id "
                        + "of the domain step to fold into, when more than one fits (a string, from vocabulary list).");
                p.set("target", LocatorSchema.of(m));
                p.putObject("text").put("type", "string").put("description",
                        "edit: the new text of a type, expect, setClipboard or drop step or check, taken as typed (a ${ "
                        + "in it is text; so is one in an edit's target); a link stays where the new text has the value it "
                        + "stood for.");
                p.putObject("ref").put("type", "string").put("description",
                        "link: the value to use - ${3.rows.Orders.added[0].Number} (step 3 and the path in what it changed, "
                        + "as list shows it under changed), ${copy.fields.Number} (a step's id) or a remembered name "
                        + "(number or ${number}).");
                p.putObject("field").put("type", "string").put("description",
                        "link: the one place in step or check n to link, a path into its step JSON (text, target.name, "
                        + "target.row.Number, params.qty); default: wherever it has the value. When that place holds a "
                        + "value of an earlier run (a loaded file), every place with that value is linked.");
                p.putObject("where").put("type", "string").put("description",
                        "unique: where the run's id goes in the typed text: end, start, or off to take it away.")
                        .putArray("enum").add("end").add("start").add("off");
                p.putObject("selected").put("type", "boolean").put("description",
                        "remember: the target's selected row's cell (in the target's column), selected item or selected "
                        + "tab's title instead of its own text.");
                p.putObject("rev").put("type", "integer").put("description",
                        "insert, delete, move, edit, group, ungroup, row, undo, fold, unfold, keepRaw, link, unlink, remember, "
                        + "unique, redo: the rev of your last list; refused if the recording changed since.");
                p.putObject("reset").put("type", "boolean").put("description",
                        "start: reset the app first, as a test starts (default false).");
                p.putObject("wait").put("type", "integer").put("description",
                        "list: milliseconds to wait until the recording stops (default 0: just read), at most "
                        + "540000; a session reset's recording, which only you stop, is not waited for.");
                schema.putArray("required").add("op");
                return tool(m, "recording",
                        "The recording the agent and the person at the app make together (palette on or off)."
                        + "\n\nStart and stop: start {reset?} begins a fresh recording; with the palette on the person's "
                        + "steps join it and the palette shows it; reset:true resets the app first, as a test starts "
                        + "(startNote: see session reset). stop "
                        + "ends it (the person's Done does too); after it no step joins. While one runs, session reset and "
                        + "test runs are refused; stop it first."
                        + "\n\nNumbers: steps are numbered 1..n; a step is an action and the checks after it, numbered n.k "
                        + "(7.2); checks before any action are step 1. Numbers close up after a delete."
                        + "\n\nList: list {wait?} gives state, who started it, rev, and every step with n, author (person, "
                        + "agent, loaded), line, java, its step JSON, say, edited (changed and not replayed since), group "
                        + "(its number in groups) and its checks nested under it ({n: \"7.1\", ...}), plus groups [{from, "
                        + "to, say}] in step numbers; a check-only step 1 has no line, step or java. On each step or check: "
                        + "changed (path: value, what a later step can link to; the paths later steps use first, at most "
                        + "20; never one of several added or changed rows or items), changedMore (how many it leaves out), "
                        + "uses [{from, value, unknown?}], usedBy and unique; on a remember entry remembered. wait (ms) "
                        + "waits until it stops (not for a session reset's)."
                        + "\n\nEdit: insert {at, step}: an action at a step number (before the step now there) or a check "
                        + "at a label (\"7.2\"), NOT run (marked edited until a replay passes it); nothing can go in front "
                        + "of a check-only step 1. delete {n | from, to}: a step goes with its checks, \"7.2\" only that "
                        + "check. move {n, to}: a step with its checks; checks cannot be moved; delete and insert. edit {n, "
                        + "target | text | step | say}: n is a step or a check; say sets its sentence (one line, \"\" "
                        + "removes it) and may come with target, text or step. A check's step (insert, edit) is in the "
                        + "file's form and needs no action. Every edit returns the new list; pass rev from your last list "
                        + "so an edit is refused if the person changed the recording meanwhile."
                        + "\n\nGroups, rows, undo: group {from, to, say}: steps from..to (two or more, whole steps, groups "
                        + "do not nest or overlap) under one sentence; the same range renames it. ungroup {n}: ends the "
                        + "group that holds step n, its steps stay. row {n, by}: a step or check on a table row names it by "
                        + "value (the key column's text: it still finds the row after a sort or a new row) or by position "
                        + "(its index); list shows each one's row {by, value, index}; the other way is known once the step "
                        + "ran (a loaded step: after one passing replay); an edit like the others. undo: takes back the "
                        + "last edit, by you or the person (a fold too)."
                        + "\n\nFolding: with a plugin vocabulary, raw steps fold as they are recorded: a run of one "
                        + "author's steps that is a domain step's pattern becomes one do step (step JSON {action: do, step, "
                        + "params, say}); list shows folded (how many raw steps) and raw (their lines), keepRaw, and where a "
                        + "run did not fold foldCandidates (a tie), foldSuggested (a step that only suggests) or "
                        + "foldUndecided (why). Checks, do steps, an author change, groups, steps with their own sentence, "
                        + "kept raw steps, links and a replay's start stop a fold. fold {from, to, step?}: folds those steps "
                        + "into the domain step whose pattern they match (step names it when more fit). unfold {n}: puts a "
                        + "fold's raw steps back, kept raw. keepRaw {from, to}: those steps never fold."
                        + "\n\nFailed domain step: a do step that fails part way records the steps of it that ran as plain "
                        + "steps, kept raw (never folded back automatically); its result lists them in recordedPart and "
                        + "says what happened in recordedPartNote. When none can be kept, recordedPart is empty and the "
                        + "note says the recording does not match the screen and how to redo."
                        + "\n\nLinks (the person sees a 🔗 on the step, in words): a step may use what an earlier step "
                        + "changed (its outcome), a remembered value or a text unique per run, and a replay then uses that "
                        + "run's value, never the recorded one. While recording, a step whose value an earlier step newly "
                        + "made (4 characters or more, not yes/no or a small number) is linked at once; the title of a tab: "
                        + "or section: scope step or a dialog: or window: window is linked when it is that value whole, like "
                        + "a name (a * title never). A title a step opened or closed is never linked automatically: link it "
                        + "by hand. No value is linked through a path that names the value itself. A domain check's param "
                        + "is never linked to what the check reads (it would compare the value with itself); another place "
                        + "with the value is taken. A value in an id: or text: scope step or a column stays exact, and the "
                        + "step's result says so in linkNote."
                        + "\n\nLink by hand: link {n, ref, field?}: n uses the value ref names where it has that value, "
                        + "whole or whole inside typed text; the source gets an id. field names one place (a path into the "
                        + "step JSON); when it holds a value of an earlier run (a loaded file), every place with that value "
                        + "is linked. A link through a path that names the value itself, or to one of several added or "
                        + "changed rows or items, is refused. A check linked to the very place it checks, made by the step it "
                        + "sits under, is refused: it would compare the value with itself (also a value remembered from "
                        + "there under that step, and such a reference in insert or edit). When what a domain check reads "
                        + "is not known (inserted, loaded, or its run showed no expect), any field or text that step "
                        + "changed, and any value remembered under it, counts as such a place. unlink {n}: its links become "
                        + "the values they stood for."
                        + "\n\nRemember and unique: remember "
                        + "{target, as, n?, selected?}: reads the text target shows now and keeps it under the name as, a "
                        + "check-like entry (n.k) under step n; missing or empty is refused, nothing recorded. unique {n, "
                        + "where}: step n's typed text gets the run's id at its end or start (off takes it away), and the "
                        + "steps that use that text follow; nothing is typed again. Each is one edit undo takes back."
                        + "\n\nNever type ${...} into a step yourself: text and target (edit) and what do_action and batch "
                        + "run are taken as typed, though an edit keeps a link where its new text or target has the value "
                        + "the link stood for; a whole step (insert, edit step) is in the file's form, where ${id.path} is a "
                        + "reference, and an edit that leaves a reference without an EARLIER source or an id twice is "
                        + "refused."
                        + "\n\nReplay: replay {from?, to?, speed?}: runs the steps (with their checks) again without "
                        + "recording them and returns {passed, ran, total, reset, steps}: ran and total count steps, with "
                        + "one {n, say?, action, passed, ms, message?, resolved?} entry per step or check run (n as in "
                        + "list; say is the step's sentence; action is its technical line); from step 1 it resets the app first, as the exported test starts, "
                        + "and says startNote when the start screen holds a filled search. "
                        + "A replay from the middle that needs a value of an earlier step this session does not know is refused "
                        + "in words (\"Check 7.2 uses what step 3 changed; replay from step 3 or earlier\"). "
                        + "redo {from}: cuts step from and the ones after it, replays the steps before it from a reset, and "
                        + "records on (from = the number after the last step continues a stopped recording)."
                        + "\n\nFiles: export {as: recorded|java, ...}: as export_recorded_test / export_test, but keeps "
                        + "the recording: use it while you still fix the test (export_recorded_test empties it unless "
                        + "clear:false). load {name | path}: a recorded test file becomes the recording (stopped; redo or "
                        + "insert to add to it).",
                        schema);
            }
            @Override public String invoke(JsonNode args) throws Exception { return session.recording(args); }
        });

        tools.add(new McpToolDefinition() {
            @Override public String name() { return "vocabulary"; }
            @Override public ObjectNode descriptor(ObjectMapper m) {
                ObjectNode schema = emptyObjectSchema(m);
                ObjectNode p = schema.with("properties");
                p.putObject("op").put("type", "string").putArray("enum").add("list").add("describe").add("match");
                p.putObject("step").put("type", "string").put("description",
                        "describe: a step id from list (<plugin>:<id> when two plugins use the same id).");
                p.putObject("from").put("type", "integer").put("description", "match: the first step of the recording range.");
                p.putObject("to").put("type", "integer").put("description", "match: the last step of the recording range.");
                schema.putArray("required").add("op");
                return readOnly(tool(m, "vocabulary",
                        "The domain steps the ready plugins offer: one step that stands for the clicks and checks it takes, "
                        + "e.g. \"Add {product} to the cart\". list: every step with id, kind (act or check), sentence, about, "
                        + "params {name, type, optional, default}, plugin and folds (a one-line summary of the patterns a "
                        + "recording must match to fold into it, or why it has none), plus problems for steps a plugin "
                        + "offered that were dropped. describe {step}: one step in full: its params with choices (the values "
                        + "that fit in the app right now, e.g. product names; they help you pick, a call may pass others), "
                        + "its patterns, its body as steps with {param} placeholders, and its Java call. match {from, to}: "
                        + "a dry run of what steps from..to of the recording would fold into, each raw step looked at as if "
                        + "just recorded (the recording is not changed): folds [{from, to, step, params, say}] and notes "
                        + "[{from, to, kind: tie|suggested|undecided, steps, why?}], outOfTime:true when the matching ran "
                        + "out of time first; with no pattern to match, a hint instead. Call one with do_action "
                        + "{\"action\":{\"action\":\"do\",\"step\":<id>,\"params\":{...}}} (a batch step: "
                        + "{\"verb\":\"do\",\"step\":<id>,\"params\":{...}}). Without "
                        + "a plugin that offers a vocabulary, list is empty with a hint.", schema));
            }
            @Override public String invoke(JsonNode args) { return session.vocabulary(args); }
        });

        tools.add(new McpToolDefinition() {
            @Override public String name() { return "read_clipboard"; }
            @Override public ObjectNode descriptor(ObjectMapper m) {
                return readOnly(tool(m, "read_clipboard",
                        "The system clipboard's text as {text}, e.g. to check what a copy in the app left there "
                        + "(a tab between cells, a line break between rows). text is null when the clipboard holds "
                        + "no text. A read: nothing is recorded; a test reads it with ui.clipboardText().",
                        emptyObjectSchema(m)));
            }
            @Override public String invoke(JsonNode args) { return session.readClipboard(); }
        });

        tools.add(new McpToolDefinition() {
            @Override public String name() { return "read_file_chooser"; }
            @Override public ObjectNode descriptor(ObjectMapper m) {
                return readOnly(tool(m, "read_file_chooser",
                        "The showing file chooser as {found, folder, name, nameFocused, lookAndFeel}: the absolute "
                        + "path of the folder it shows, the file-name field's text, whether that field has focus (so "
                        + "a press with no target lands in it), and the look and feel whose key bindings it runs. One "
                        + "look; it waits the driver's default unless 'timeout' (ms, 0 = no wait) is given; found is false with the reason when no chooser shows. A read: nothing is "
                        + "recorded; a test reads it with ui.fileChooser().",
                        fileChooserSchema(m)));
            }
            @Override public String invoke(JsonNode args) { return session.readFileChooser(args); }
        });

        for (Map.Entry<String, Class<? extends VerbAction>> verb : VerbSchemas.TOOLS.entrySet()) {
            tools.add(verbTool(session, verb.getKey(), verb.getValue()));
        }

        return tools;
    }

    private static McpToolDefinition verbTool(final UiMcpSession session, final String name,
                                              final Class<? extends VerbAction> type) {
        final String action = VerbSchemas.actionName(type);
        return new McpToolDefinition() {
            @Override public String name() { return name; }
            @Override public ObjectNode descriptor(ObjectMapper m) {
                return tool(m, name, VerbSchemas.description(type), VerbSchemas.inputSchema(m, type));
            }
            @Override public String invoke(JsonNode args) throws Exception { return session.verb(action, args); }
        };
    }

    /** The snapshot tool's arguments; {@code batch}'s {@code snapshot} takes the same. */
    static void snapshotProperties(ObjectMapper m, ObjectNode p) {
        p.putObject("window").put("type", "string").put("description",
                "Only the roots this names: main | popup | dialog | dialog:<title> | window:<title>");
        p.putObject("ref").put("type", "string").put("description",
                "Only this element's subtree (a ref from the previous snapshot)");
        ObjectNode target = LocatorSchema.of(m);
        target.put("description", "Only this element's subtree, found like a verb's target - a tab's content "
                + "for a tab. At most one of window, ref and target.");
        p.set("target", target);
        p.putObject("depth").put("type", "integer").put("description",
                "Levels of nodes to include below the windows (or the scoped element), which is level 1; "
                + "deeper nodes are cut and their parent marked truncated. 2-4 is usually enough.");
        p.putObject("compact").put("type", "boolean").put("description",
                "Drop table/list/tree rows and items, scroll geometry and expanded paths (columns and "
                + "rowCount stay); maxRows defaults to 0.");
        p.putObject("maxNodes").put("type", "integer").put("description",
                "Node budget, default 400; cut parts are marked truncated");
        p.putObject("maxRows").put("type", "integer").put("description",
                "Rows per table, list or tree, default 20");
    }

    private static ObjectNode describeSchema(ObjectMapper m) {
        ObjectNode schema = emptyObjectSchema(m);
        ObjectNode fields = schema.with("properties").putObject("fields");
        fields.put("type", "array");
        fields.put("description", "Only these top-level fields, e.g. [\"sections\",\"dialog\"] or "
                + "[\"activeTab\",\"viewTabs\"]; default all.");
        fields.putObject("items").put("type", "string");
        return schema;
    }

    private static ObjectNode readOnly(ObjectNode descriptor) {
        descriptor.putObject("annotations").put("readOnlyHint", true);
        return descriptor;
    }

    private static ObjectNode tool(ObjectMapper m, String name, String description, ObjectNode inputSchema) {
        ObjectNode d = m.createObjectNode();
        d.put("name", name);
        d.put("description", description);
        d.set("inputSchema", inputSchema);
        return d;
    }

    private static ObjectNode fileChooserSchema(ObjectMapper m) {
        ObjectNode s = emptyObjectSchema(m);
        s.with("properties").putObject("timeout").put("type", "integer").put("description",
                "Milliseconds to wait for a chooser to show; 0 looks once. Omitted: the driver's default wait.");
        return s;
    }

    private static ObjectNode emptyObjectSchema(ObjectMapper m) {
        ObjectNode s = m.createObjectNode();
        s.put("type", "object");
        s.putObject("properties");
        return s;
    }

    private static ObjectNode actionSchema(ObjectMapper m) {
        ObjectNode s = m.createObjectNode();
        s.put("type", "object");
        s.put("description", "A step named by its 'action' field: a verb, navigate / close / loginAs, or do (a plugin's "
                + "domain step: {\"action\":\"do\",\"step\":<id>,\"params\":{<name>:<value as text>}}, which reports its "
                + "filled sentence and the steps it ran, and is recorded as one step). The verbs are also tools of their own, "
                + "with full argument schemas.");
        ObjectNode action = s.putObject("properties").putObject("action");
        action.put("type", "string");
        ArrayNode names = action.putArray("enum");
        for (JsonSubTypes.Type t : Action.class.getAnnotation(JsonSubTypes.class).value()) names.add(t.name());
        s.putArray("required").add("action");
        return s;
    }
}
