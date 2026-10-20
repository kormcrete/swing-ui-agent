package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.model.Roles;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The JSON schema of a target, shared by read and every verb tool. */
final class LocatorSchema {

    private LocatorSchema() {}

    static ObjectNode of(ObjectMapper m) {
        ObjectNode s = m.createObjectNode();
        s.put("type", "object");
        s.put("description", "A UI element: {\"ref\":\"e12\"} from the last snapshot, or a locator. "
                + "row/column/item/point address a part of it.");
        ObjectNode p = s.putObject("properties");
        string(p, "ref", "Ref from the last snapshot, e.g. e12; valid until the next snapshot.");
        string(p, "window", "main | popup | dialog | dialog:<title> | window:<title>; a title ending in * matches a prefix.");
        array(p, "scope", "Ordered tab:<title> / section:<caption> steps the element sits in (a section is any titled border, "
                + "even one on a clickable box); a title or caption ending in * matches a prefix (tab:New order*); an id:<name> step means "
                + "strictly inside the component whose getName() is <name> (not a contributed section id); a text:<name> step strictly "
                + "inside the text field of that name, such as its clear X (id clear: the app's own X named so, or FlatLaf's).");
        ArrayNode roles = string(p, "role", "The element's role.").putArray("enum");
        for (String r : Roles.ALL) roles.add(r);
        string(p, "name", "Its name: component name, own text, label, titled border, tooltip, or action name.");
        string(p, "match", "How name compares.").putArray("enum").add("exact").add("contains");
        string(p, "id", "The component's getName().");
        p.putObject("row").put("type", "object")
                .put("description", "Table row: {\"<column header>\": \"<cell text>\", ...} or {\"index\": n}.");
        string(p, "column", "Table column header: with row, that cell; without row, the header itself.");
        array(p, "item", "List or combo item text (one element), or a tree path.");
        ObjectNode point = p.putObject("point");
        point.put("type", "object");
        point.put("description", "{x, y}: fractions (0..1) of the element's bounds, for custom-painted content.");
        point.putObject("properties").putObject("x").put("type", "number");
        point.with("properties").putObject("y").put("type", "number");
        p.putObject("nth").put("type", "integer")
                .put("description", "Which match, in tree order, when the locator is not unique (makes the step fragile).");
        return s;
    }

    private static ObjectNode string(ObjectNode properties, String name, String description) {
        return properties.putObject(name).put("type", "string").put("description", description);
    }

    private static void array(ObjectNode properties, String name, String description) {
        ObjectNode a = properties.putObject(name);
        a.put("type", "array");
        a.put("description", description);
        a.putObject("items").put("type", "string");
    }
}
