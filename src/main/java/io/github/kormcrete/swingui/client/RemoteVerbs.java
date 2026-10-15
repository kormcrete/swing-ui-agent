package io.github.kormcrete.swingui.client;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Expectation;
import io.github.kormcrete.swingui.driver.FileChooserState;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.Shot;
import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Geometry;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Node;
import io.github.kormcrete.swingui.driver.platform.CapabilityReport;
import io.github.kormcrete.swingui.driver.platform.Watch;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Base64;
import java.util.List;

/**
 * The driver, out of process: every verb is one do_action step (not recorded), every read one read,
 * snapshot, screenshot, read_clipboard or read_file_chooser call. A failed step throws the DriverException the
 * in-process driver would have thrown: same reason, same message, same details.
 */
public final class RemoteVerbs implements Verbs {

    private final AgentClient agent;
    private final ObjectMapper json;

    public RemoteVerbs(AgentClient agent) {
        this.agent = agent;
        this.json = agent.json();
    }

    @Override public CapabilityReport capabilities() { throw Verbs.missing("capabilities"); }
    @Override public Watch watch() { throw Verbs.missing("watch"); }

    // ---- reads

    @Override public Node snapshot(String window, String ref, Integer maxNodes, Integer maxRows) {
        return snapshot(window, ref, null, null, maxNodes, maxRows);
    }

    @Override public Node snapshot(String window, String ref, Locator target, Integer depth, Integer maxNodes, Integer maxRows) {
        ObjectNode a = agent.args();
        put(a, "window", window);
        put(a, "ref", ref);
        put(a, "target", target);
        put(a, "depth", depth);
        put(a, "maxNodes", maxNodes);
        put(a, "maxRows", maxRows);
        return json.convertValue(agent.callJson("snapshot", a), Node.class);
    }

    @Override public Node read(Locator target, Integer maxRows) {
        return json.convertValue(read("node", target, maxRows, null).get("node"), Node.class);
    }

    @Override public List<Node> readAll(Locator target, Integer maxRows) {
        return json.convertValue(read("all", target, maxRows, null).get("nodes"), new TypeReference<List<Node>>() {});
    }

    @Override public List<String> candidates(Locator target) {
        return AgentClient.strings(read("candidates", target, null, null).get("candidates"));
    }

    @Override public Geometry geometry(Locator target) {
        return json.convertValue(read("geometry", target, null, null).get("geometry"), Geometry.class);
    }

    @Override public List<String> menuItems(List<String> path) {
        return AgentClient.strings(read("menuItems", null, null, path).get("items"));
    }

    private JsonNode read(String form, Locator target, Integer maxRows, List<String> path) {
        ObjectNode a = agent.args();
        a.put("form", form);
        put(a, "target", target);
        put(a, "maxRows", maxRows);
        put(a, "path", path);
        JsonNode r = agent.callJson("read", a);
        if (r.has("found") && !r.get("found").asBoolean()) throw failure(r, "candidates");
        return r;
    }

    @Override public Shot screenshot(Locator target, double[] region, Integer maxSize, Double scale, boolean palette) {
        ObjectNode a = agent.args();
        put(a, "target", target);
        if (region != null) {
            a.putObject("region").put("x", region[0]).put("y", region[1]).put("w", region[2]).put("h", region[3]);
        }
        put(a, "maxSize", maxSize);
        put(a, "scale", scale);
        if (palette) a.put("palette", true);
        JsonNode result = agent.call("screenshot", a);
        byte[] png = null;
        JsonNode meta = json.createObjectNode();
        for (JsonNode c : result.path("content")) {
            if ("image".equals(c.path("type").asText())) png = Base64.getDecoder().decode(c.path("data").asText());
            if ("text".equals(c.path("type").asText())) meta = agent.parse(c.path("text").asText());
        }
        if (png == null) throw new DriverException(Reason.APP_ERROR, "screenshot: the agent sent no image");
        return new Shot(png, meta.path("width").asInt(), meta.path("height").asInt(), meta.path("sourceWidth").asInt(),
                meta.path("sourceHeight").asInt(), meta.path("scaled").asBoolean());
    }

    @Override public String clipboardText() {
        JsonNode r = agent.callJson("read_clipboard", agent.args());
        return r.hasNonNull("text") ? r.get("text").asText() : null;
    }

    @Override public FileChooserState fileChooser(Long timeout) {
        ObjectNode a = agent.args();
        put(a, "timeout", timeout);
        JsonNode r = agent.callJson("read_file_chooser", a);
        if (!r.path("found").asBoolean(false)) throw failure(r, "details");
        return new FileChooserState(text(r, "folder"), text(r, "name"), r.path("nameFocused").asBoolean(),
                text(r, "lookAndFeel"));
    }

    // ---- verbs: one do_action step each

    @Override public VerbOutcome click(Locator target, String button, Integer count, List<String> modifiers, Long timeout) {
        ObjectNode s = step("click", timeout);
        put(s, "target", target);
        put(s, "button", button);
        put(s, "count", count);
        put(s, "modifiers", modifiers);
        return run(s);
    }

    @Override public VerbOutcome type(Locator target, String text, Boolean replace, Boolean commit, Long timeout) {
        ObjectNode s = step("type", timeout);
        put(s, "target", target);
        put(s, "text", text);
        put(s, "replace", replace);
        put(s, "commit", commit);
        return run(s);
    }

    @Override public VerbOutcome press(String keys, Locator target, Long timeout) {
        ObjectNode s = step("press", timeout);
        put(s, "keys", keys);
        put(s, "target", target);
        return run(s);
    }

    @Override public VerbOutcome check(Locator target, Boolean checked, Long timeout) {
        ObjectNode s = step("check", timeout);
        put(s, "target", target);
        put(s, "checked", checked);
        return run(s);
    }

    @Override public VerbOutcome select(Locator target, List<String> items, Long timeout) {
        ObjectNode s = step("select", timeout);
        put(s, "target", target);
        put(s, "items", items);
        return run(s);
    }

    @Override public VerbOutcome pick(List<String> path, Long timeout) {
        ObjectNode s = step("pick", timeout);
        put(s, "path", path);
        return run(s);
    }

    @Override public VerbOutcome menu(List<String> path, Long timeout) {
        ObjectNode s = step("menu", timeout);
        put(s, "path", path);
        return run(s);
    }

    @Override public VerbOutcome contextMenu(Locator target, List<String> path, Long timeout) {
        ObjectNode s = step("contextMenu", timeout);
        put(s, "target", target);
        put(s, "path", path);
        return run(s);
    }

    @Override public VerbOutcome answer(String button, Long timeout) {
        ObjectNode s = step("answer", timeout);
        put(s, "button", button);
        return run(s);
    }

    @Override public VerbOutcome chooseFile(String path, Boolean approve, Long timeout) {
        ObjectNode s = step("chooseFile", timeout);
        put(s, "path", path);
        put(s, "approve", approve);
        return run(s);
    }

    @Override public VerbOutcome focusFileChooserName(Long timeout) {
        return run(step("focusFileChooserName", timeout));
    }

    @Override public VerbOutcome drag(Locator from, Locator to, Long timeout) {
        ObjectNode s = step("drag", timeout);
        put(s, "from", from);
        put(s, "to", to);
        return run(s);
    }

    @Override public VerbOutcome drop(Locator target, List<String> files, String text, Long timeout) {
        ObjectNode s = step("drop", timeout);
        put(s, "target", target);
        put(s, "files", files);
        put(s, "text", text);
        return run(s);
    }

    @Override public VerbOutcome resizeColumn(Locator column, int delta, Long timeout) {
        ObjectNode s = step("resizeColumn", timeout);
        put(s, "target", column);
        s.put("delta", delta);
        return run(s);
    }

    @Override public VerbOutcome resizeWindow(String window, int width, int height, Long timeout) {
        ObjectNode s = step("resizeWindow", timeout);
        put(s, "window", window);
        s.put("width", width);
        s.put("height", height);
        return run(s);
    }

    @Override public VerbOutcome closeWindow(String window, Long timeout) {
        ObjectNode s = step("closeWindow", timeout);
        put(s, "window", window);
        return run(s);
    }

    @Override public VerbOutcome hover(Locator target, Boolean inside, Long timeout) {
        ObjectNode s = step("hover", timeout);
        put(s, "target", target);
        put(s, "inside", inside);
        return run(s);
    }

    @Override public VerbOutcome expect(Locator target, Expectation expectation, Long timeout) {
        ObjectNode s = step("expect", timeout);
        put(s, "target", target);
        s.setAll((ObjectNode) json.valueToTree(expectation));
        return run(s);
    }

    @Override public VerbOutcome scroll(Locator target, String to, Integer pages, Long timeout) {
        ObjectNode s = step("scroll", timeout);
        put(s, "target", target);
        put(s, "to", to);
        put(s, "pages", pages);
        return run(s);
    }

    @Override public VerbOutcome setClipboard(String text, Long timeout) {
        ObjectNode s = step("setClipboard", timeout);
        put(s, "text", text);
        return run(s);
    }

    // resetPointer stays the no-op default: the agent's session reset moves the pointer home.

    // ---- the wire

    private ObjectNode step(String action, Long timeout) {
        ObjectNode s = json.createObjectNode();
        s.put("action", action);
        put(s, "timeout", timeout);
        return s;
    }

    /** One step through do_action, kept out of export_test's recording. */
    VerbOutcome run(ObjectNode step) {
        ObjectNode a = agent.args();
        a.set("action", step);
        a.put("record", false);
        JsonNode r = agent.callJson("do_action", a);
        if (!r.path("passed").asBoolean(false)) throw failure(r, "details");
        VerbOutcome out = new VerbOutcome();
        out.warning = text(r, "warning");
        return out;
    }

    private DriverException failure(JsonNode r, String detailsField) {
        Reason reason = r.hasNonNull("reason") ? Reason.valueOf(r.get("reason").asText()) : Reason.APP_ERROR;
        return new DriverException(reason, DriverException.stripReason(r.path("message").asText(), reason),
                AgentClient.strings(r.get(detailsField)));
    }

    private void put(ObjectNode o, String field, Object value) {
        if (value == null) return;
        o.set(field, json.valueToTree(value));
    }

    private static String text(JsonNode r, String field) {
        return r.hasNonNull(field) ? r.get(field).asText() : null;
    }
}
