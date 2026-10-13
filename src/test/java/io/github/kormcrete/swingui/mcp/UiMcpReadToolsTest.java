package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Geometry;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Node;
import io.github.kormcrete.swingui.testing.RecordingHost;
import io.github.kormcrete.swingui.testing.RecordingVerbs;
import io.github.kormcrete.swingui.flow.RecordingStepHost;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class UiMcpReadToolsTest {

    /** A step host with a driver installed: its {@code verbs} are a {@link FakeVerbs}. */
    static class DriverHost extends RecordingStepHost {
        DriverHost() { super(new FakeVerbs()); }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final DriverHost backend = new DriverHost();
    private final UiMcpSession session = new UiMcpSession(backend, new UiMcpToolsTest.FakeInventory(), mapper);

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    @Test
    void snapshotReturnsTheTreeWithoutNullFields() throws Exception {
        Node app = new Node("app", null);
        Node button = new Node("button", "Save");
        button.ref = "e1";
        app.addChild(button);
        backend.verbs.node = app;
        JsonNode out = json(session.snapshot(json("{\"window\":\"dialog\",\"maxRows\":5}")));
        // The size block is the snapshot tool's own addition, not part of the tree.
        ((com.fasterxml.jackson.databind.node.ObjectNode) out).remove("size");
        assertThat(out).isEqualTo(json("{\"role\":\"app\",\"children\":[{\"ref\":\"e1\",\"role\":\"button\",\"name\":\"Save\"}]}"));
        assertThat(backend.verbs.calls).containsExactly("snapshot dialog null null 5");
    }

    @Test
    void aFoundTargetReturnsItsNode() throws Exception {
        Node cell = new Node("cell", "Status");
        cell.value = "open";
        backend.verbs.node = cell;
        JsonNode out = json(session.read(json(
                "{\"target\":{\"role\":\"table\",\"row\":{\"index\":2},\"column\":\"Status\"}}")));
        assertThat(out.path("found").asBoolean()).isTrue();
        assertThat(out.path("node").path("value").asText()).isEqualTo("open");
    }

    @Test
    void aMissingTargetIsAnAnswerWithCandidates() throws Exception {
        backend.verbs.failure = new DriverException(Reason.NOT_FOUND, "nothing matches",
                Collections.singletonList("button 'OK' in main"));
        JsonNode out = json(session.read(json("{\"target\":{\"role\":\"button\",\"name\":\"Cancel\"}}")));
        assertThat(out.path("found").asBoolean()).isFalse();
        assertThat(out.path("reason").asText()).isEqualTo("NOT_FOUND");
        assertThat(out.path("candidates").get(0).asText()).isEqualTo("button 'OK' in main");
    }

    @Test
    void aStaleRefIsAToolError() {
        backend.verbs.failure = new DriverException(Reason.BAD_INPUT, "stale ref e3");
        DriverException e = catchThrowableOfType(
                () -> session.read(json("{\"target\":{\"ref\":\"e3\"}}")), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
    }

    @Test
    void readNeedsAWellFormedTarget() {
        assertThat(catchThrowableOfType(() -> session.read(json("{}")), DriverException.class).reason())
                .isEqualTo(Reason.BAD_INPUT);
        assertThat(catchThrowableOfType(() -> session.read(json("{\"target\":{\"role\":\"button\",\"nope\":1}}")),
                DriverException.class).reason()).isEqualTo(Reason.BAD_INPUT);
    }

    @Test
    void noDriverIsACapabilityGap() {
        UiMcpSession plain = new UiMcpSession(new RecordingStepHost(), new UiMcpToolsTest.FakeInventory(), mapper);
        assertThat(catchThrowableOfType(() -> plain.snapshot(json("{}")), DriverException.class).reason())
                .isEqualTo(Reason.CAPABILITY_GAP);
    }

    // ---- the read forms and the chooser wait, over a RecordingHost ----------------------------

    private final RecordingVerbs verbs = new RecordingVerbs();
    private final UiMcpSession recording = new UiMcpSession(new RecordingHost(verbs), null, new ObjectMapper());

    @Test
    void readFormsAll() throws Exception {
        verbs.readAllAnswer = Arrays.asList(new Node("label", "A"), new Node("label", "B"));
        JsonNode r = json(recording.read(json("{\"target\":{\"role\":\"label\"},\"form\":\"all\"}")));
        assertThat(r.get("found").asBoolean()).isTrue();
        assertThat(r.get("nodes")).hasSize(2);
    }

    @Test
    void readFormGeometry() throws Exception {
        Geometry g = new Geometry();
        g.width = 300;
        verbs.geometryAnswer = g;
        JsonNode r = json(recording.read(json("{\"target\":{\"role\":\"table\"},\"form\":\"geometry\"}")));
        assertThat(r.path("geometry").path("width").asInt()).isEqualTo(300);
    }

    @Test
    void readFormCandidatesAndMenuItems() throws Exception {
        verbs.candidates = Arrays.asList("button 'Save'");
        assertThat(json(recording.read(json("{\"target\":{\"role\":\"button\",\"name\":\"Store\"},\"form\":\"candidates\"}")))
                .get("candidates").get(0).asText()).isEqualTo("button 'Save'");
        verbs.menuItemsAnswer = Arrays.asList("New", "Open\u2026");
        JsonNode items = json(recording.read(json("{\"form\":\"menuItems\",\"path\":[\"File\"]}")));
        assertThat(items.get("items").toString()).isEqualTo("[\"New\",\"Open\u2026\"]");
        assertThat(verbs.calls).contains("menuItems [File]");
    }

    @Test
    void menuItemsNeedsAPath() {
        assertThatThrownBy(() -> recording.read(json("{\"form\":\"menuItems\"}")))
                .hasMessage("BAD_INPUT: read form menuItems needs 'path'");
    }

    @Test
    void otherFormsStillNeedATarget() {
        assertThatThrownBy(() -> recording.read(json("{\"form\":\"all\"}")))
                .hasMessage("BAD_INPUT: read needs a 'target' object");
    }

    @Test
    void anUnknownFormIsBadInput() {
        assertThatThrownBy(() -> recording.read(json("{\"target\":{\"role\":\"x\"},\"form\":\"pixels\"}")))
                .hasMessage("BAD_INPUT: unknown read form 'pixels' (known: node, all, geometry, candidates, menuItems)");
    }

    @Test
    void theFileChooserReadWaitsTheGivenTimeout() throws Exception {
        verbs.fileChooserAnswer = new io.github.kormcrete.swingui.driver.FileChooserState("/tmp", "a.txt", false, "Metal");
        recording.readFileChooser(json("{\"timeout\":3000}"));
        assertThat(verbs.calls).contains("fileChooser 3000");
    }

    @Test
    void theFileChooserReadWithoutATimeoutPassesTheDriversDefault() throws Exception {
        verbs.fileChooserAnswer = new io.github.kormcrete.swingui.driver.FileChooserState("/tmp", "a.txt", false, "Metal");
        recording.readFileChooser(json("{}"));
        assertThat(verbs.calls).contains("fileChooser null");
    }

    @Test
    void aZeroTimeoutIsPassedAsZero() throws Exception {
        verbs.fileChooserAnswer = new io.github.kormcrete.swingui.driver.FileChooserState("/tmp", "a.txt", false, "Metal");
        recording.readFileChooser(json("{\"timeout\":0}"));
        assertThat(verbs.calls).contains("fileChooser 0");
    }

    @Test
    void noFileChooserShowingIsAnAnswerNotAToolError() throws Exception {
        JsonNode r = json(recording.readFileChooser(json("{}")));
        assertThat(r.get("found").asBoolean()).isFalse();
        assertThat(r.get("reason").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void screenshotPassesMaxSizeAndScale() throws Exception {
        session.screenshot(json("{\"maxSize\":3000,\"scale\":2}"));
        assertThat(backend.verbs.calls).containsExactly("screenshot null 3000 2.0");
    }

    @Test
    void screenshotTakesAWindowLikeSnapshot() throws Exception {
        session.screenshot(json("{\"window\":\"main\"}"));
        assertThat(backend.verbs.calls).containsExactly("screenshot in main null");
    }

    @Test
    void aScreenshotTargetWinsOverAWindow() throws Exception {
        session.screenshot(json("{\"window\":\"main\",\"target\":{\"role\":\"button\",\"name\":\"OK\"}}"));
        assertThat(backend.verbs.calls).containsExactly("screenshot " + Locator.of("button", "OK") + " null");
    }

    @Test
    void aScaleThatIsNotANumberIsBadInput() {
        DriverException e = catchThrowableOfType(
                () -> session.screenshot(json("{\"scale\":\"2x\"}")), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("scale");
    }
}
