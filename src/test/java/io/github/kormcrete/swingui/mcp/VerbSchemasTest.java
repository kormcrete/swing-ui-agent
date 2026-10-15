package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.flow.ContextMenuAction;
import io.github.kormcrete.swingui.flow.verb.ChooseFileVerb;
import io.github.kormcrete.swingui.flow.verb.ClickVerb;
import io.github.kormcrete.swingui.flow.verb.ExpectVerb;
import io.github.kormcrete.swingui.flow.verb.SetClipboardVerb;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Typed schemas generated from the verb classes. */
class VerbSchemasTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<String>();
        for (JsonNode n : array) out.add(n.asText());
        return out;
    }

    @Test
    void toolNamesMapToRecordedTestActionNames() {
        assertThat(VerbSchemas.TOOLS).hasSize(15);
        assertThat(VerbSchemas.actionName(SetClipboardVerb.class)).isEqualTo("setClipboard");
        assertThat(VerbSchemas.actionName(ClickVerb.class)).isEqualTo("click");
        assertThat(VerbSchemas.actionName(ContextMenuAction.class)).isEqualTo("contextMenu");
        assertThat(VerbSchemas.actionName(ChooseFileVerb.class)).isEqualTo("chooseFile");
        assertThat(VerbSchemas.TOOLS.get("context_menu")).isEqualTo(ContextMenuAction.class);
    }

    @Test
    void theClickSchemaIsTyped() {
        JsonNode schema = VerbSchemas.inputSchema(mapper, ClickVerb.class);
        JsonNode p = schema.path("properties");
        assertThat(texts(schema.path("required"))).containsExactly("target");
        assertThat(texts(p.path("target").path("properties").path("role").path("enum"))).contains("button", "clickable");
        assertThat(texts(p.path("button").path("enum"))).containsExactly("left", "right");
        assertThat(p.path("count").path("type").asText()).isEqualTo("integer");
        assertThat(p.path("modifiers").path("items").path("type").asText()).isEqualTo("string");
        assertThat(p.path("timeout").path("type").asText()).isEqualTo("integer");
        assertThat(p.path("snapshot").path("type").asText()).isEqualTo("boolean");
        assertThat(p.has("via")).isFalse();
        assertThat(p.has("fragile")).isFalse();
        assertThat(VerbSchemas.description(ClickVerb.class)).contains("posted").contains("recorded");
    }

    @Test
    void theContextMenuToolOffersOnlyTheVerbForm() {
        JsonNode schema = VerbSchemas.inputSchema(mapper, ContextMenuAction.class);
        assertThat(schema.path("properties").has("match")).isFalse();
        assertThat(texts(schema.path("required"))).containsExactlyInAnyOrder("path", "target");
    }

    @Test
    void nestedObjectsGetTheirProperties() {
        JsonNode p = VerbSchemas.inputSchema(mapper, ExpectVerb.class).path("properties");
        assertThat(p.path("rows").path("properties").has("op")).isTrue();
        assertThat(p.path("cell").path("properties").has("expected")).isTrue();
        assertThat(p.path("state").path("type").asText()).isEqualTo("array");
    }
}
