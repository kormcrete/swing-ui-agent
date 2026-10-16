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
                        + "'answer' verb. A step may carry say: its sentence (one line, kept in the recording and the recorded test).", schema);
            }
            @Override public String invoke(JsonNode args) throws Exception {
                JsonNode action = args.path("action");
                if (action.isMissingNode() || action.isNull()) {
                    throw new IllegalArgumentException("Missing required 'action' object");
                }
                return session.doAction(action, args.path("record").asBoolean(true));
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
                        + "{\"action\":\"close\"}]}. A step may carry say: its sentence.", schema);
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
                item.putArray("required").add("verb");
                ObjectNode snapshot = p.putObject("snapshot");
                snapshot.put("description", "true, or the snapshot tool's arguments (target, depth, compact, ...): "
                        + "a snapshot taken after the last step that ran, pass or fail.");
                schema.putArray("required").add("steps");
                return tool(m, "batch",
                        "Run SEVERAL steps in ONE call, in order, stopping at the first that does not pass - use it "
                        + "whenever the next steps are already known. "
                        + "Each step runs, answers and records exactly as its own tool call would, so export_test "
                        + "writes the same test either way; 'read' steps record nothing, and a 'read' that finds "
                        + "nothing stops the batch. Returns {passed, ran, total, ms, steps:[{verb, passed, message, "
                        + "ms, recorded?, notRecorded?, ...}], snapshot?, snapshotError?}. A snapshot that cannot be taken keeps the steps and reports {errorType, message, details} as snapshotError. A harness error (CAPABILITY_GAP, INPUT_ERROR) is a tool "
                        + "error naming the step; the steps before it ran and were recorded. Example: {\"steps\":["
                        + "{\"verb\":\"click\",\"target\":{\"role\":\"tab\",\"name\":\"Customer\"}},"
                        + "{\"verb\":\"type\",\"target\":{\"role\":\"text\",\"name\":\"Name\"},\"text\":\"X\"},"
                        + "{\"verb\":\"expect\",\"target\":{\"role\":\"button\",\"name\":\"Save\"},"
                        + "\"state\":[\"!disabled\"]}]}.", schema);
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
                        + "module, else the working directory; dir or -Dswingui.testsDir overrides, and a working directory with no pom.xml or build.gradle makes the result carry a warning naming where the file went). The test starts where "
                        + "the recording did, right after a reset, so it must begin by getting to its own starting "
                        + "screen: without an app plugin a reset only closes dialogs, and startWarning flags a test "
                        + "that does not begin with menu, press or navigate. Only steps with a Java form are recorded "
                        + "(verbs, including expect for checks; navigate, close, a leading loginAs; do steps), except a "
                        + "do step whose domain step has no Java form: a recording with one is refused, naming it - "
                        + "export it with export_recorded_test instead. Refuses a "
                        + "recording that did not begin at a reset or a recording start, a bad class/method/package "
                        + "name, an existing file, or an empty recording, naming each problem; a refusal keeps the "
                        + "recording. A step and its checks are one block; sentences become comments.",
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
                        .put("description", "Empty the recording after exporting (default: true); reset before "
                                + "recording the next recorded test. Refused while a recording started by recording start or "
                                + "the person's Record runs: export with clear false, or stop it first");
                schema.putArray("required").add("name");
                return tool(m, "export_recorded_test",
                        "Write the steps recorded since the last session reset as a recorded test file "
                        + "<recorded tests folder>/<name>.json: UI steps and checks of what is visible, no compiling; run it "
                        + "with run_recorded_test, from the palette, or in Maven with the JUnit runner (RecordedTests). Same rules as "
                        + "export_test: it must have begun at a reset or a recording start, startWarning flags a recorded test "
                        + "that does not begin with menu, press or navigate, an empty recording or a bad name is refused, and an existing "
                        + "file is never overwritten - except the recorded test the recording came from (loaded in the "
                        + "palette, or exported before without clear). A folder that cannot be written is refused, "
                        + "naming it. A recorded test holds exact values: check data the test creates or data known to exist; "
                        + "a value the app makes up (a new order number, today's date) only when your start makes it "
                        + "predictable - otherwise check something else or write a Java test. Format version 2 (3 when it holds "
                        + "do steps, with requires naming their plugins): each step with its checks nested, sentences and groups "
                        + "kept.", schema);
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
                        + "[{n, say?, action, passed, ms, message?, reason?, details?, warning?, diagnostics?}], n a step "
                        + "number (7) or a check label (\"7.2\"); action is the step's technical line. After a "
                        + "failure take a new snapshot before using refs. Refused (BUSY) while a person records, "
                        + "answers or replays in the palette. From a shell the CLI exits 4 when the recorded test fails.", schema);
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
                        + "menuItems ({found,items}; needs 'path', takes no target)");
                ArrayNode forms = form.putArray("enum");
                for (String f : new String[] {"node", "all", "geometry", "candidates", "menuItems"}) forms.add(f);
                p.putObject("path").put("type", "array").put("description", "menuItems form: the menu path to list the items under")
                        .putObject("items").put("type", "string");
                return readOnly(tool(m, "read",
                        "Reads one element without acting: its node, or with row/column one table cell or row. "
                        + "'target' is required for every form except menuItems. "
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
                        + "A read: nothing is recorded; to capture screens in a recorded test, use the "
                        + "'screenshot' step in a batch, which writes a file. Use it for an "
                        + "aesthetic call - alignment, cropping, overlap, proportion - and an 'expect' for "
                        + "anything that is really a number or a boolean.",
                        schema));
            }
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
                        .add("fold").add("unfold").add("keepRaw").add("replay").add("redo").add("export").add("load");
                ObjectNode at = p.putObject("at");
                at.putArray("type").add("integer").add("string");
                at.put("description", "insert: where it goes. An action: the step number it gets (1 to steps+1), before "
                        + "the step now there. A check: the label it gets, a step number and its place under it "
                        + "(\"7.2\"; k up to the step's checks + 1), or 1 for a check-only step 1. A step number (7) "
                        + "or a check label (\"7.2\").");
                ObjectNode n = p.putObject("n");
                n.putArray("type").add("integer").add("string");
                n.put("description", "delete, move, edit, ungroup, row, unfold: a step number (7) or a check label (\"7.2\"). "
                        + "delete 7 deletes the step and its checks, delete \"7.2\" only that check; move takes a step "
                        + "(with its checks); edit \"7.2\" edits that check; ungroup takes any step of the group; unfold "
                        + "takes a folded step.");
                p.putObject("from").put("type", "integer").put("description",
                        "delete, group, fold, keepRaw: first step of a range; replay: first step (default 1); redo: the step "
                        + "to record again (steps+1 continues).");
                p.putObject("speed").put("type", "string").put("description",
                        "replay: pauses for a person watching.").putArray("enum").add("fast").add("normal").add("slow");
                p.putObject("as").put("type", "string").put("description", "export: the format (required).")
                        .putArray("enum").add("recorded").add("java");
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
                        "delete, group, fold, keepRaw: last step of a range; move: the step number it has afterwards; "
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
                stepParam.put("description", "insert, edit: a step or check as do_action takes it (an object); fold: the id "
                        + "of the domain step to fold into, when more than one fits (a string, from vocabulary list).");
                p.set("target", LocatorSchema.of(m));
                p.putObject("text").put("type", "string").put("description",
                        "edit: the new text of a type, expect, setClipboard or drop step or check.");
                p.putObject("rev").put("type", "integer").put("description",
                        "insert, delete, move, edit, group, ungroup, row, undo, fold, unfold, keepRaw, redo: the rev of your "
                        + "last list; refused if the recording changed since.");
                p.putObject("reset").put("type", "boolean").put("description",
                        "start: reset the app first, as a test starts (default false).");
                p.putObject("wait").put("type", "integer").put("description",
                        "list: milliseconds to wait until the recording stops (default 0: just read), at most "
                        + "540000; a session reset's recording, which only you stop, is not waited for.");
                schema.putArray("required").add("op");
                return tool(m, "recording",
                        "The recording the agent and the person at the app make together (works with the palette on or "
                        + "off). start {reset?}: a fresh recording; with the palette on the person's steps join it and "
                        + "the palette shows it; reset:true resets the app first, as a test starts. stop: ends it (the "
                        + "person's Done does too); after it no step joins. Steps are numbered 1..n; a step is an action "
                        + "and the checks after it, numbered n.k (7.2); checks before any action are step 1. list {wait?}: "
                        + "state, who started it, rev, and every step with n, author (person, agent, loaded), line, java, "
                        + "its step JSON, say, edited (changed and not replayed since), group (its number in groups) and its "
                        + "checks nested under it ({n: \"7.1\", ...}), plus groups "
                        + "[{from, to, say}] in step numbers; a check-only step 1 has no line, step or java; wait (ms) waits until it stops (not for a session reset's). "
                        + "insert {at, step}: an action at a step number (before the step now there) or a check at a "
                        + "label (\"7.2\"), NOT run (marked edited until a replay passes it); nothing can go in front of a "
                        + "check-only step 1. delete {n | from, to}: a step goes with its checks, \"7.2\" only that check. "
                        + "move {n, to}: a step with its checks (a check is deleted and inserted again). edit {n, target | "
                        + "text | step | say}: n is a step or a check; say sets its sentence (one line, \"\" removes it) "
                        + "and may come with target, text or step. group {from, to, say}: steps from..to (two or more, "
                        + "whole steps, groups do not nest or overlap) under one sentence; the same range renames it. "
                        + "ungroup {n}: ends the group that holds step n, its steps stay. row {n, by}: a step or check on a "
                        + "table row names it by value (the key column's text: it still finds the row after a sort or a new "
                        + "row) or by position (its index); list shows each one's row {by, value, index}; the other way is "
                        + "known once the step ran (a loaded step: after one passing replay); an edit like the others. undo: takes back the last edit, "
                        + "by you or the person (a fold too). With a plugin vocabulary, raw steps fold as they are recorded: "
                        + "a run of one author's steps that is a domain step's pattern becomes one do step (step JSON "
                        + "{action: do, step, params, say}); list shows folded (how many raw steps) and raw (their lines), "
                        + "keepRaw, and where a run did not fold foldCandidates (a tie), foldSuggested (a step that only "
                        + "suggests) or foldUndecided (why). Checks, do steps, an author change, groups, steps with their own "
                        + "sentence, kept raw steps and a replay's start stop a fold. fold {from, to, step?}: folds those "
                        + "steps into the domain step whose pattern they match (step names it when more fit). unfold {n}: "
                        + "puts a fold's raw steps back, kept raw. keepRaw {from, to}: those steps never fold. Numbers close up after a delete; deleting or moving a step takes its checks "
                        + "along; pass rev from your last list so an edit is refused if the person changed the recording "
                        + "meanwhile. Every edit returns the new list. While one runs, session reset and test runs are "
                        + "refused; stop it first. replay {from?, to?, speed?}: runs the steps (with their checks) again "
                        + "without recording them and returns {passed, ran, total, reset, steps}: ran and total count steps, with one {n, action, passed, ms, "
                        + "message?} entry per step or check run (n as in list; action is its technical line); from step 1 it resets the app first, as the exported test starts. redo "
                        + "{from}: cuts step from and the ones after it, replays the steps before it from a reset, and "
                        + "records on (from = the number after the last step continues a stopped recording). export "
                        + "{as: recorded|java, ...}: as export_recorded_test / export_test, but keeps the recording. load "
                        + "{name | path}: a recorded test file becomes the recording (stopped; redo or insert to add to it).",
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
                        + "[{from, to, kind: tie|suggested|undecided, steps, why?}]. Call one in do_action as {\"action\":\"do\",\"step\":<id>,\"params\":{...}}. Without "
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
