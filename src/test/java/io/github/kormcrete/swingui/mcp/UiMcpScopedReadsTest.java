package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Node;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Scoped snapshot, its size block, and describe_ui cut to the fields a caller asks for. */
class UiMcpScopedReadsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UiMcpReadToolsTest.DriverHost backend = new UiMcpReadToolsTest.DriverHost();
    private final UiMcpSession session = new UiMcpSession(backend, new UiMcpToolsTest.FakeInventory(), mapper);

    private JsonNode snapshot(String args) throws Exception {
        return mapper.readTree(session.snapshot(mapper.readTree(args)));
    }

    @Test
    void aTargetAndADepthScopeTheSnapshot() throws Exception {
        JsonNode out = snapshot("{\"target\":{\"scope\":[\"tab:Customer\"],\"role\":\"section\",\"name\":\"Header\"},\"depth\":3}");
        assertThat(backend.verbs.calls).containsExactly("snapshot null null section 'Header' [tab:Customer] 3 null null");
        assertThat(out.path("size").path("scoped").asBoolean()).isTrue();
        assertThat(out.path("size").path("nodes").asInt()).isEqualTo(1);
    }

    @Test
    void compactStripsRowsAndDefaultsMaxRowsToZero() throws Exception {
        Node app = new Node("app", null);
        Node table = new Node("table", "Orders");
        table.columns = Arrays.asList("No.", "Status");
        table.rows = Collections.singletonList(Arrays.asList("26-1", "open"));
        table.rowCount = 25;
        app.addChild(table);
        backend.verbs.node = app;
        JsonNode out = snapshot("{\"compact\":true}");
        assertThat(backend.verbs.calls).containsExactly("snapshot null null null 0");
        JsonNode t = out.path("children").get(0);
        assertThat(t.has("rows")).isFalse();
        assertThat(t.path("rowCount").asInt()).isEqualTo(25);
        assertThat(t.path("columns")).hasSize(2);
    }

    @Test
    void anUnscopedSnapshotSaysWhatItCostAndABigOneHintsAtScoping() throws Exception {
        Node app = new Node("app", null);
        for (int i = 0; i < 400; i++) {
            Node b = new Node("button", "Toolbar button no. " + i + " with a rather long label");
            b.ref = "e" + i;
            app.addChild(b);
        }
        backend.verbs.node = app;
        JsonNode out = snapshot("{}");
        JsonNode size = out.path("size");
        assertThat(size.path("scoped").asBoolean()).isFalse();
        assertThat(size.path("nodes").asInt()).isEqualTo(401);
        assertThat(size.path("bytes").asInt()).isGreaterThan(UiMcpSession.LARGE_SNAPSHOT_BYTES);
        assertThat(size.path("hint").asText()).contains("target").contains("depth");

        backend.verbs.node = new Node("app", null);
        assertThat(snapshot("{}").path("size").has("hint")).isFalse();
    }

    @Test
    void aTargetMustBeALocatorObject() {
        DriverException e = catchThrowableOfType(() -> snapshot("{\"target\":\"e3\"}"), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
    }

    @Test
    void describeUiReturnsOnlyTheFieldsAsked() throws Exception {
        JsonNode out = mapper.readTree(session.describe(Arrays.asList("activeTab", "dialog")));
        assertThat(out.size()).isEqualTo(2);
        assertThat(out.has("activeTab")).isTrue();
        assertThat(out.has("dialog")).isTrue();
        DriverException e = catchThrowableOfType(() -> session.describe(Collections.singletonList("nope")),
                DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("'nope'").contains("sections");
        assertThat(mapper.readTree(session.describe(Collections.<String>emptyList())).has("menuPaths")).isTrue();
    }
}
