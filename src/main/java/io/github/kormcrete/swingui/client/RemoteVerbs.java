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
    private final StepJson steps;

    public RemoteVerbs(AgentClient agent) {
        this.agent = agent;
        this.json = agent.json();
        this.steps = new StepJson(json);
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
        return run(steps.click(target, button, count, modifiers, timeout));
    }

    @Override public VerbOutcome type(Locator target, String text, Boolean replace, Boolean commit, Long timeout) {
        return run(steps.type(target, text, replace, commit, timeout));
    }

    @Override public VerbOutcome press(String keys, Locator target, Long timeout) {
        return run(steps.press(keys, target, timeout));
    }

    @Override public VerbOutcome check(Locator target, Boolean checked, Long timeout) {
        return run(steps.check(target, checked, timeout));
    }

    @Override public VerbOutcome select(Locator target, List<String> items, Long timeout) {
        return run(steps.select(target, items, timeout));
    }

    @Override public VerbOutcome pick(List<String> path, Long timeout) {
        return run(steps.pick(path, timeout));
    }

    @Override public VerbOutcome menu(List<String> path, Long timeout) {
        return run(steps.menu(path, timeout));
    }

    @Override public VerbOutcome contextMenu(Locator target, List<String> path, Long timeout) {
        return run(steps.contextMenu(target, path, timeout));
    }

    @Override public VerbOutcome answer(String button, Long timeout) {
        return run(steps.answer(button, timeout));
    }

    @Override public VerbOutcome chooseFile(String path, Boolean approve, Long timeout) {
        return run(steps.chooseFile(path, approve, timeout));
    }

    @Override public VerbOutcome focusFileChooserName(Long timeout) {
        return run(steps.focusFileChooserName(timeout));
    }

    @Override public VerbOutcome drag(Locator from, Locator to, Long timeout) {
        return run(steps.drag(from, to, timeout));
    }

    @Override public VerbOutcome drop(Locator target, List<String> files, String text, Long timeout) {
        return run(steps.drop(target, files, text, timeout));
    }

    @Override public VerbOutcome resizeColumn(Locator column, int delta, Long timeout) {
        return run(steps.resizeColumn(column, delta, timeout));
    }

    @Override public VerbOutcome resizeWindow(String window, int width, int height, Long timeout) {
        return run(steps.resizeWindow(window, width, height, timeout));
    }

    @Override public VerbOutcome closeWindow(String window, Long timeout) {
        return run(steps.closeWindow(window, timeout));
    }

    @Override public VerbOutcome hover(Locator target, Boolean inside, Long timeout) {
        return run(steps.hover(target, inside, timeout));
    }

    @Override public VerbOutcome expect(Locator target, Expectation expectation, Long timeout) {
        return run(steps.expect(target, expectation, timeout));
    }

    @Override public VerbOutcome scroll(Locator target, String to, Integer pages, Long timeout) {
        return run(steps.scroll(target, to, pages, timeout));
    }

    @Override public VerbOutcome setClipboard(String text, Long timeout) {
        return run(steps.setClipboard(text, timeout));
    }

    // resetPointer stays the no-op default: the agent's session reset moves the pointer home.

    // ---- the wire

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
        steps.put(o, field, value);
    }

    private static String text(JsonNode r, String field) {
        return r.hasNonNull(field) ? r.get(field).asText() : null;
    }
}
