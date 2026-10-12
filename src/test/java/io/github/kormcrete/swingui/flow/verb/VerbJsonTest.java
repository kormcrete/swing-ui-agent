package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.flow.Action;
import io.github.kormcrete.swingui.flow.ContextMenuAction;
import io.github.kormcrete.swingui.flow.FlowJson;
import io.github.kormcrete.swingui.mcp.FakeVerbs;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerbJsonTest {

    /** A Long field serialises as a LongNode, parsed JSON reads 5000 as an IntNode; compare numbers by value. */
    private static final Comparator<JsonNode> NUMBERS_BY_VALUE = (a, b) ->
            a.equals(b) || (a.isNumber() && b.isNumber() && a.decimalValue().compareTo(b.decimalValue()) == 0) ? 0 : 1;

    private static boolean sameJson(JsonNode actual, JsonNode expected) {
        return actual.equals(NUMBERS_BY_VALUE, expected);
    }

    private static final String[] VERBS = {
            "{\"action\":\"click\",\"target\":{\"role\":\"table\",\"row\":{\"index\":0}},\"modifiers\":[\"Mod\"]}",
            "{\"action\":\"type\",\"target\":{\"role\":\"text\",\"name\":\"Customer\"},\"text\":\"Test\",\"commit\":true}",
            "{\"action\":\"press\",\"keys\":\"Escape\"}",
            "{\"action\":\"check\",\"target\":{\"role\":\"checkbox\",\"name\":\"Active\"},\"checked\":true}",
            "{\"action\":\"select\",\"target\":{\"role\":\"tabs\"},\"item\":\"Line items\"}",
            "{\"action\":\"pick\",\"path\":[\"incl. line items\"]}",
            "{\"action\":\"menu\",\"path\":[\"File\",\"Exit\"]}",
            "{\"action\":\"contextMenu\",\"target\":{\"ref\":\"e4\"},\"path\":[\"Delete\"]}",
            "{\"action\":\"answer\",\"button\":\"escape\"}",
            "{\"action\":\"chooseFile\",\"approve\":false}",
            "{\"action\":\"drag\",\"from\":{\"role\":\"list\",\"item\":[\"a\"]},\"to\":{\"role\":\"text\",\"name\":\"Target\"}}",
            "{\"action\":\"drop\",\"target\":{\"role\":\"clickable\",\"id\":\"attachments\"},\"files\":[\"/tmp/a.pdf\"]}",
            "{\"action\":\"expect\",\"target\":{\"role\":\"table\"},\"rows\":{\"op\":\"gte\",\"value\":1},\"timeout\":5000}",
            "{\"action\":\"scroll\",\"target\":{\"role\":\"table\"},\"to\":\"bottom\"}"
    };

    @Test
    void everyVerbRoundTripsWithoutNulls() throws Exception {
        for (String json : VERBS) {
            Action action = FlowJson.mapper().readValue(json, Action.class);
            assertThat(action).as(json).isInstanceOf(VerbAction.class);
            JsonNode back = FlowJson.mapper().valueToTree(action);
            assertThat(sameJson(back, FlowJson.mapper().readTree(json))).as(json + " came back as " + back).isTrue();
        }
    }

    @Test
    void aContextMenuTakesATargetAndNothingElse() throws Exception {
        String json = "{\"action\":\"contextMenu\",\"target\":{\"role\":\"table\",\"name\":\"Orders\"},\"path\":[\"Delete\"]}";
        ContextMenuAction a = (ContextMenuAction) FlowJson.mapper().readValue(json, Action.class);
        JsonNode back = FlowJson.mapper().valueToTree(a);
        assertThat(sameJson(back, FlowJson.mapper().readTree(json))).as(back.toString()).isTrue();
        assertThat(back.has("match")).isFalse();
    }

    @Test
    void recorderFieldsAreWrittenOnlyWhenSet() throws Exception {
        ClickVerb click = (ClickVerb) FlowJson.mapper().readValue(VERBS[0], Action.class);
        click.via = "api";
        click.fragile = true;
        JsonNode node = FlowJson.mapper().valueToTree(click);
        assertThat(node.get("via").asText()).isEqualTo("api");
        assertThat(node.get("fragile").asBoolean()).isTrue();
    }

    @Test
    void everyVerbClassIsRegisteredAndDelegatesToItsVerb() throws Exception {
        List<String> names = new ArrayList<String>();
        for (JsonSubTypes.Type t : Action.class.getAnnotation(JsonSubTypes.class).value()) names.add(t.name());
        assertThat(names).contains("click", "type", "press", "check", "select", "pick", "menu", "contextMenu",
                "answer", "chooseFile", "drag", "drop", "expect", "scroll");
        FakeVerbs verbs = new FakeVerbs();
        for (String json : VERBS) {
            ((VerbAction) FlowJson.mapper().readValue(json, Action.class)).run(verbs);
        }
        List<String> called = new ArrayList<String>();
        for (String c : verbs.calls) called.add(c.split(" ")[0]);
        assertThat(called).containsExactly("click", "type", "press", "check", "select", "pick", "menu",
                "contextMenu", "answer", "chooseFile", "drag", "drop", "expect", "scroll");
        assertThat(verbs.calls.get(4)).isEqualTo("select tabs [Line items]");
    }

    @Test
    void itemAndItemsTogetherAreBadInput() throws Exception {
        SelectVerb s = (SelectVerb) FlowJson.mapper().readValue(VERBS[4], Action.class);
        s.items = Arrays.asList("A", "B");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> s.run(new FakeVerbs()))
                .hasMessageContaining("'item' or 'items'");
    }
}
