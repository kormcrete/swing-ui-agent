package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.testing.RecordingVerbs;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Locator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceTest {

    @TempDir Path dir;
    private static final java.util.function.Supplier<String> STACK = () -> "AWT-EventQueue-0: test stack";

    @Test
    void anActionFailureWritesItsLocatorCandidatesAndAScreenshot() throws Exception {
        RecordingVerbs verbs = new RecordingVerbs();
        verbs.candidates = Collections.singletonList("button 'Save ▾'");
        UiActionFailed f = new UiActionFailed("click", Locator.of("button", "Save"),
                new DriverException(Reason.NOT_FOUND, "no button"), Collections.<String>emptyList());
        Path out = Evidence.write(dir, verbs, f, STACK);
        String text = new String(Files.readAllBytes(out.resolve("evidence.txt")), StandardCharsets.UTF_8);
        assertThat(text).contains("click", "Save", "Save ▾");
        assertThat(Files.list(out).anyMatch(p -> p.toString().endsWith(".png"))).isTrue();
    }

    @Test
    void aTimeoutAlsoWritesTheEventThreadStack() throws Exception {
        UiActionFailed f = new UiActionFailed("click", Locator.of("button", "OK"),
                new DriverException(Reason.TIMEOUT, "still busy"), Collections.<String>emptyList());
        Path out = Evidence.write(dir, new RecordingVerbs(), f, STACK);
        String text = new String(Files.readAllBytes(out.resolve("evidence.txt")), StandardCharsets.UTF_8);
        assertThat(text).contains("AWT-EventQueue-0: test stack");
    }

    @Test
    void anyOtherFailureStillGetsAScreenshotAndItsMessage() throws Exception {
        Path out = Evidence.write(dir, new RecordingVerbs(), new IllegalStateException("boom"), null);
        String text = new String(Files.readAllBytes(out.resolve("evidence.txt")), StandardCharsets.UTF_8);
        assertThat(text).contains("boom");
    }

    @Test
    void aClientLevelFailureHasNoLocatorButStillAScreenshotAndTheEdtStack() throws Exception {
        UiActionFailed f = new UiActionFailed("navigate Orders", null,
                new DriverException(Reason.TIMEOUT, "rows never reached 1"), Collections.<String>emptyList());
        Path out = Evidence.write(dir, new RecordingVerbs(), f, STACK);
        String text = new String(Files.readAllBytes(out.resolve("evidence.txt")), StandardCharsets.UTF_8);
        assertThat(text).contains("navigate Orders", "target: n/a", "AWT-EventQueue-0: test stack").doesNotContain("diagnostics failed");
        assertThat(Files.list(out).anyMatch(p -> p.toString().endsWith(".png"))).isTrue();
    }

    @Test
    void noDriverMeansNoDiagnosticsButStillTheFailureAndItsStack() throws Exception {
        Path out = Evidence.write(dir, null, new IllegalStateException("no client"), STACK);
        String text = new String(Files.readAllBytes(out.resolve("evidence.txt")), StandardCharsets.UTF_8);
        assertThat(text).contains("no client", "Diagnostics: none (no driver)").doesNotContain("diagnostics failed");
    }
}
