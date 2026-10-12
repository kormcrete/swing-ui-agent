package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.FileChooserState;
import io.github.kormcrete.swingui.driver.PendingDialog;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Recorder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JPanel;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class UiMcpVerbToolsTest {

    @TempDir
    Path testsDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final UiMcpReadToolsTest.DriverHost backend = new UiMcpReadToolsTest.DriverHost();
    private final UiMcpSession session = new UiMcpSession(backend, new UiMcpToolsTest.FakeInventory(), mapper);

    @BeforeEach
    void exportToTemp() {
        System.setProperty(UiMcpSession.TESTS_DIR_PROP, testsDir.toString());
        session.startRecording(true);
    }

    @AfterEach
    void clear() {
        System.clearProperty(UiMcpSession.TESTS_DIR_PROP);
    }

    private JsonNode verb(String action, String args) throws Exception {
        return mapper.readTree(session.verb(action, mapper.readTree(args)));
    }

    @Test
    void aRefTargetIsRecordedAsItsLocator() throws Exception {
        backend.verbs.outcome.recorded.put("target", new Recorder.Recorded(Locator.of("button", "Save"), false));
        JsonNode r = verb("click", "{\"target\":{\"ref\":\"e3\"}}");
        assertThat(r.path("passed").asBoolean()).isTrue();
        JsonNode recorded = r.path("recorded");
        assertThat(recorded.path("action").asText()).isEqualTo("click");
        assertThat(recorded.path("target")).isEqualTo(mapper.readTree("{\"role\":\"button\",\"name\":\"Save\"}"));
        assertThat(recorded.has("via")).isFalse();
        JsonNode exported = mapper.readTree(session.exportTest("WalkUiIT", "walk", "p", true));
        assertThat(exported.path("steps").asInt()).isEqualTo(1);
        assertThat(exported.path("source").asText()).doesNotContain("e3");
    }

    @Test
    void anApiPathAndAnNthTargetAreMarked() throws Exception {
        Locator twin = Locator.of("button", "Next");
        twin.nth = 1;
        backend.verbs.outcome.recorded.put("target", new Recorder.Recorded(twin, true));
        backend.verbs.outcome.via = "api";
        JsonNode recorded = verb("click", "{\"target\":{\"ref\":\"e9\"}}").path("recorded");
        assertThat(recorded.path("via").asText()).isEqualTo("api");
        assertThat(recorded.path("fragile").asBoolean()).isTrue();
        assertThat(recorded.path("target").path("nth").asInt()).isEqualTo(1);
    }

    @Test
    void aCapabilityGapIsThrownOnceTyped() {
        backend.verbs.failure = new DriverException(Reason.CAPABILITY_GAP, "the popup did not open");
        DriverException e = catchThrowableOfType(() -> verb("select",
                "{\"target\":{\"role\":\"combo\"},\"item\":\"A\"}"), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.CAPABILITY_GAP);
        assertThat(e.getMessage()).isEqualTo("CAPABILITY_GAP: the popup did not open");
    }

    @Test
    void aFailedStepIsAnAnswerWithItsReasonAndIsNotRecorded() throws Exception {
        backend.verbs.failure = new DriverException(Reason.NOT_ACTIONABLE, "covered by blocker");
        JsonNode r = verb("click", "{\"target\":{\"role\":\"button\",\"name\":\"OK\"}}");
        assertThat(r.path("passed").asBoolean()).isFalse();
        assertThat(r.path("reason").asText()).isEqualTo("NOT_ACTIONABLE");
        assertThat(r.has("recorded")).isFalse();
        // nothing was recorded, so there is nothing for exportTest to write
        assertThat(catchThrowableOfType(() -> session.exportTest("XUiIT", "x", "p", true), ExportRefused.class).problems())
                .containsExactly("nothing was recorded");
        assertThat(testsDir.resolve("p/XUiIT.java")).doesNotExist();
    }

    @Test
    void pendingDialogsAndRealInputAreReported() throws Exception {
        backend.verbs.outcome.pending = new PendingDialog("Delete", "Really?", Arrays.asList("Yes", "No"),
                Collections.<String>emptyList());
        backend.verbs.during = () -> {
            backend.verbs.seam.systemGenerated = true;
            backend.verbs.watch.onEvent(new KeyEvent(new JPanel(), KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_A, 'a'));
        };
        JsonNode r = verb("answer", "{\"button\":\"escape\"}");
        assertThat(r.path("pending").path("title").asText()).isEqualTo("Delete");
        assertThat(r.path("realInput")).hasSize(1);
        assertThat(r.has("activations")).isFalse();
    }

    @Test
    void snapshotTrueAddsASnapshotButIsNotRecorded() throws Exception {
        JsonNode r = verb("press", "{\"keys\":\"Escape\",\"snapshot\":true}");
        assertThat(r.path("snapshot").path("role").asText()).isEqualTo("app");
        assertThat(r.path("recorded").has("snapshot")).isFalse();
        assertThat(backend.verbs.calls).containsExactly("press Escape null", "snapshot null null null null");
    }

    @Test
    void theFileChooserIsReadAndItsAbsenceIsNotAToolError() throws Exception {
        JsonNode none = mapper.readTree(session.readFileChooser());
        assertThat(none.path("found").asBoolean()).isFalse();
        assertThat(none.path("reason").asText()).isEqualTo("NOT_FOUND");
        backend.verbs.fileChooserState = new FileChooserState("/tmp/import", "", true, "Metal Look and Feel");
        JsonNode r = mapper.readTree(session.readFileChooser());
        assertThat(r.path("found").asBoolean()).isTrue();
        assertThat(r.path("folder").asText()).isEqualTo("/tmp/import");
        assertThat(r.path("nameFocused").asBoolean()).isTrue();
        assertThat(r.path("lookAndFeel").asText()).contains("Metal");
    }

    @Test
    void aClipboardBlockIsSetRecordedAndReadBack() throws Exception {
        JsonNode r = verb("setClipboard", "{\"text\":\"7.2\\t<0.5\\n12\\tn/a\"}");
        assertThat(r.path("passed").asBoolean()).isTrue();
        assertThat(r.path("recorded").path("action").asText()).isEqualTo("setClipboard");
        assertThat(r.path("recorded").path("text").asText()).isEqualTo("7.2\t<0.5\n12\tn/a");
        assertThat(mapper.readTree(session.readClipboard()).path("text").asText()).isEqualTo("7.2\t<0.5\n12\tn/a");
        JsonNode exported = mapper.readTree(session.exportTest("PasteUiIT", "paste", "p", true));
        assertThat(exported.path("steps").asInt()).isEqualTo(1);
    }

    @Test
    void anUnknownArgumentIsBadInput() {
        DriverException e = catchThrowableOfType(() -> verb("click",
                "{\"target\":{\"role\":\"button\"},\"bogus\":1}"), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
    }

    @Test
    void oneActionRunsAtATime() throws Exception {
        final AtomicInteger inside = new AtomicInteger();
        final AtomicInteger most = new AtomicInteger();
        final CountDownLatch firstIn = new CountDownLatch(1);
        backend.verbs.during = () -> {
            most.accumulateAndGet(inside.incrementAndGet(), Math::max);
            firstIn.countDown();
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            inside.decrementAndGet();
        };
        Runnable call = () -> {
            try {
                verb("press", "{\"keys\":\"Escape\"}");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        Thread a = new Thread(call);
        Thread b = new Thread(call);
        a.start();
        assertThat(firstIn.await(5, TimeUnit.SECONDS)).isTrue();
        b.start();
        a.join(5_000);
        b.join(5_000);
        assertThat(most.get()).isEqualTo(1);
        assertThat(backend.verbs.calls).hasSize(2);
    }
}
