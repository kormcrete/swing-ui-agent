package io.github.kormcrete.swingui.client;

import io.github.kormcrete.swingui.driver.Expectation;
import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * One step's JSON per verb and app step, as do_action takes it and a recorded test holds it: {@link RemoteVerbs}
 * sends it, {@link TracingVerbs} keeps it. Fields left null are left out.
 */
final class StepJson {

    private final ObjectMapper json;

    StepJson(ObjectMapper json) {
        this.json = json;
    }

    ObjectNode click(Locator target, String button, Integer count, List<String> modifiers, Long timeout) {
        ObjectNode s = step("click", timeout);
        put(s, "target", target);
        put(s, "button", button);
        put(s, "count", count);
        put(s, "modifiers", modifiers);
        return s;
    }

    ObjectNode type(Locator target, String text, Boolean replace, Boolean commit, Long timeout) {
        ObjectNode s = step("type", timeout);
        put(s, "target", target);
        put(s, "text", text);
        put(s, "replace", replace);
        put(s, "commit", commit);
        return s;
    }

    ObjectNode press(String keys, Locator target, Long timeout) {
        ObjectNode s = step("press", timeout);
        put(s, "keys", keys);
        put(s, "target", target);
        return s;
    }

    ObjectNode check(Locator target, Boolean checked, Long timeout) {
        ObjectNode s = step("check", timeout);
        put(s, "target", target);
        put(s, "checked", checked);
        return s;
    }

    ObjectNode select(Locator target, List<String> items, Long timeout) {
        ObjectNode s = step("select", timeout);
        put(s, "target", target);
        put(s, "items", items);
        return s;
    }

    ObjectNode pick(List<String> path, Long timeout) {
        ObjectNode s = step("pick", timeout);
        put(s, "path", path);
        return s;
    }

    ObjectNode menu(List<String> path, Long timeout) {
        ObjectNode s = step("menu", timeout);
        put(s, "path", path);
        return s;
    }

    ObjectNode contextMenu(Locator target, List<String> path, Long timeout) {
        ObjectNode s = step("contextMenu", timeout);
        put(s, "target", target);
        put(s, "path", path);
        return s;
    }

    ObjectNode answer(String button, Long timeout) {
        ObjectNode s = step("answer", timeout);
        put(s, "button", button);
        return s;
    }

    ObjectNode chooseFile(String path, Boolean approve, Long timeout) {
        ObjectNode s = step("chooseFile", timeout);
        put(s, "path", path);
        put(s, "approve", approve);
        return s;
    }

    ObjectNode focusFileChooserName(Long timeout) {
        return step("focusFileChooserName", timeout);
    }

    ObjectNode drag(Locator from, Locator to, Long timeout) {
        ObjectNode s = step("drag", timeout);
        put(s, "from", from);
        put(s, "to", to);
        return s;
    }

    ObjectNode drop(Locator target, List<String> files, String text, Long timeout) {
        ObjectNode s = step("drop", timeout);
        put(s, "target", target);
        put(s, "files", files);
        put(s, "text", text);
        return s;
    }

    ObjectNode resizeColumn(Locator column, int delta, Long timeout) {
        ObjectNode s = step("resizeColumn", timeout);
        put(s, "target", column);
        s.put("delta", delta);
        return s;
    }

    ObjectNode resizeWindow(String window, int width, int height, Long timeout) {
        ObjectNode s = step("resizeWindow", timeout);
        put(s, "window", window);
        s.put("width", width);
        s.put("height", height);
        return s;
    }

    ObjectNode closeWindow(String window, Long timeout) {
        ObjectNode s = step("closeWindow", timeout);
        put(s, "window", window);
        return s;
    }

    ObjectNode hover(Locator target, Boolean inside, Long timeout) {
        ObjectNode s = step("hover", timeout);
        put(s, "target", target);
        put(s, "inside", inside);
        return s;
    }

    ObjectNode expect(Locator target, Expectation expectation, Long timeout) {
        ObjectNode s = step("expect", timeout);
        put(s, "target", target);
        s.setAll((ObjectNode) json.valueToTree(expectation));
        return s;
    }

    ObjectNode scroll(Locator target, String to, Integer pages, Long timeout) {
        ObjectNode s = step("scroll", timeout);
        put(s, "target", target);
        put(s, "to", to);
        put(s, "pages", pages);
        return s;
    }

    ObjectNode setClipboard(String text, Long timeout) {
        ObjectNode s = step("setClipboard", timeout);
        put(s, "text", text);
        return s;
    }

    /** The app step that opens a view by name. */
    ObjectNode navigate(String target) {
        ObjectNode s = json.createObjectNode();
        s.put("action", "navigate");
        s.put("target", target);
        return s;
    }

    /** The app step that closes the active tab. */
    ObjectNode close() {
        return json.createObjectNode().put("action", "close");
    }

    /** The app step that logs in as the user {@code alias} names. */
    ObjectNode loginAs(String alias) {
        ObjectNode s = json.createObjectNode();
        s.put("action", "loginAs");
        s.put("as", alias);
        return s;
    }

    /** Sets {@code field} to {@code value} as JSON, or leaves it out when null. */
    void put(ObjectNode o, String field, Object value) {
        if (value == null) return;
        o.set(field, json.valueToTree(value));
    }

    private ObjectNode step(String action, Long timeout) {
        ObjectNode s = json.createObjectNode();
        s.put("action", action);
        put(s, "timeout", timeout);
        return s;
    }
}
