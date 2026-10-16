package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.flow.UiUsers;
import io.github.kormcrete.swingui.testing.RecordingApp;
import io.github.kormcrete.swingui.testing.RecordingVerbs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** A test's secret comes from ui-users.properties, with or without an app side. */
class ClientSecretTest {

    @TempDir
    Path dir;

    @AfterEach
    void clearProperty() {
        System.clearProperty(UiUsers.FILE_PROPERTY);
    }

    @Test
    void aSecretIsReadFromTheUsersFile() throws IOException {
        Path file = dir.resolve("ui-users.properties");
        Files.write(file, "clerk=c:d\nclerk.pin=1234".getBytes(StandardCharsets.UTF_8));
        System.setProperty(UiUsers.FILE_PROPERTY, file.toString());
        RecordingVerbs verbs = new RecordingVerbs();

        assertThat(new Client(verbs, new RecordingApp(verbs.calls), "run-1").secret("clerk", "pin")).isEqualTo("1234");
        assertThat(new Client(verbs, null, "run-1").secret("clerk", "pin")).isEqualTo("1234");
    }
}
