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
                        + "sectionProblems, and any waiting dialog ('dialog': title, message, buttons - answer it "
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
                        "Execute ONE flow step against the live client and return pass/fail and its "
                        + "duration ('ms'). A step is a verb (click, type, press, ..., contextMenu) or one of the "
                        + "app steps navigate, close, loginAs. A successful step with a Java form is recorded for "
                        + "export_test; one without (a loginAs after the first step, a target by ref) runs but is "
                        + "not recorded, and 'notRecorded' says why. The 'action' object uses the flow schema, e.g. "
                        + "{\"action\":\"navigate\",\"target\":\"home\"} or {\"action\":\"expect\","
                        + "\"target\":{\"role\":\"table\"},\"rows\":{\"op\":\"gte\",\"value\":1}}. A step "
                        + "that opens a message or confirmation returns at once with 'dialog' (title, message, "
                        + "buttons); navigate, close and loginAs refuse to run until it is answered with the "
                        + "'answer' verb.", schema);
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
                actions.put("description", "Flow actions to run in order");
                actions.set("items", actionSchema(m));
                schema.putObject("properties").set("actions", actions);
                schema.putArray("required").add("actions");
                return tool(m, "do_actions",
                        "Execute SEVERAL flow steps in order against the live client, in one call, "
                        + "stopping at the first failure. Prefer this over repeated do_action when the "
                        + "next steps are already known. "
                        + "Returns {passed, ran, total, steps:[{passed, action, message}]}; successful "
                        + "steps are recorded for export_test exactly as do_action records them. "
                        + "Example: {\"actions\":[{\"action\":\"navigate\",\"target\":\"home\"},"
                        + "{\"action\":\"click\",\"target\":{\"role\":\"tab\",\"name\":\"Customer\"}},"
                        + "{\"action\":\"close\"}]}.", schema);
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
                        + "), 'read', or a flow step type (navigate, close, loginAs, screenshot, ...).");
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
                        + "ms, recorded?, notRecorded?, ...}], snapshot?}. A harness error (CAPABILITY_GAP, INPUT_ERROR) is a tool "
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
                        .put("description", "Test class name; must end in UiIT, e.g. OrderSaveUiIT");
                p.putObject("method").put("type", "string").put("description", "Test method name (default: recorded)");
                p.putObject("package").put("type", "string")
                        .put("description", "Java package (default: the app plugin's export package, else 'recorded')");
                p.putObject("clear").put("type", "boolean")
                        .put("description", "Empty the recording after exporting (default: true); reset before "
                                + "recording the next test");
                schema.putArray("required").add("class");
                return tool(m, "export_test",
                        "Write the steps recorded since the last session reset as a JUnit @UiTest class under "
                        + "<module>/src/test/java/<package>/<class>.java (the module is the app plugin's export "
                        + "module, else the working directory; -Dswingui.testsDir overrides). The test starts where "
                        + "the recording did, right after a reset, so it must begin by getting to its own starting "
                        + "screen: without an app plugin a reset only closes dialogs, and startWarning flags a test "
                        + "that does not begin with menu, press or navigate. Only steps with a Java form are recorded "
                        + "(verbs, including expect for checks; navigate, close, a leading loginAs). Refuses a "
                        + "recording that did not begin at a reset, a bad class/method/package name, an existing "
                        + "file, or an empty recording, naming each problem; a refusal keeps the recording.",
                        schema);
            }
            @Override public String invoke(JsonNode args) throws Exception {
                return session.exportTest(args.path("class").asText(""), args.path("method").asText(""),
                        args.path("package").asText(""), args.path("clear").asBoolean(true));
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
                return readOnly(tool(m, "screenshot",
                        "A PNG of the app: the target element, or the current window when no target is given. "
                        + "It PAINTS the app's own components into an offscreen image - it never grabs the screen - so "
                        + "it captures the client whether or not it is in front, takes no focus, and cannot catch "
                        + "another application's window. Give a 'target' for one dialog, panel, table or toolbar, "
                        + "and 'region' for a snippet inside it. Anything drawn in a window of its own (an open "
                        + "popup menu, a combo dropdown, a tooltip) is NOT in the image - capture that window "
                        + "instead. A read: nothing is recorded and there is no flow step for it. Use it for an "
                        + "aesthetic call - alignment, cropping, overlap, proportion - and an 'expect' for "
                        + "anything that is really a number or a boolean.",
                        schema));
            }
            @Override public String invoke(JsonNode args) { return session.screenshot(args); }
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
        s.put("description", "A flow step named by its 'action' field: a verb, or navigate / close / loginAs. "
                + "The verbs are also tools of their own, with full argument schemas.");
        ObjectNode action = s.putObject("properties").putObject("action");
        action.put("type", "string");
        ArrayNode names = action.putArray("enum");
        for (JsonSubTypes.Type t : Action.class.getAnnotation(JsonSubTypes.class).value()) names.add(t.name());
        s.putArray("required").add("action");
        return s;
    }
}
