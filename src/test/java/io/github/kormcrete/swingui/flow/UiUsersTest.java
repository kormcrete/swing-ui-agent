package io.github.kormcrete.swingui.flow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UiUsersTest {

    @TempDir
    Path dir;

    @AfterEach
    void clearProperty() {
        System.clearProperty(UiUsers.FILE_PROPERTY);
    }

    private void users(String... lines) throws IOException {
        Path file = dir.resolve("ui-users.properties");
        Files.write(file, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        System.setProperty(UiUsers.FILE_PROPERTY, file.toString());
    }

    @Test
    void withNoFileTheDefaultAliasFailsNamingTheFile() {
        Path absent = dir.resolve("absent.properties");
        System.setProperty(UiUsers.FILE_PROPERTY, absent.toString());

        assertThatThrownBy(() -> UiUsers.lookup(UiUsers.DEFAULT_ALIAS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'default'")
                .hasMessageContaining(absent.toString())
                .hasMessageContaining("missing or empty");
    }

    @Test
    void anAliasResolvesToUserAndPassword() throws IOException {
        users("customer=aCustomerLogin:s3cret");

        UiUsers.Credentials c = UiUsers.lookup("customer");

        assertThat(c.user).isEqualTo("aCustomerLogin");
        assertThat(c.password).isEqualTo("s3cret");
    }

    @Test
    void theDefaultAliasComesFromTheFile() throws IOException {
        users("default=someoneElse:pw");

        assertThat(UiUsers.lookup(UiUsers.DEFAULT_ALIAS).user).isEqualTo("someoneElse");
    }

    @Test
    void aNullOrBlankAliasMeansTheDefault() throws IOException {
        users("default=someoneElse:pw");

        assertThat(UiUsers.lookup(null).user).isEqualTo("someoneElse");
        assertThat(UiUsers.lookup("   ").user).isEqualTo("someoneElse");
    }

    @Test
    void onlyTheFirstColonSeparates() throws IOException {
        users("odd=user:pass:word");

        assertThat(UiUsers.lookup("odd").password).isEqualTo("pass:word");
    }

    @Test
    void anUnknownAliasFailsLoudlyAndListsWhatExists() throws IOException {
        users("customer=a:b", "clerk=c:d");

        assertThatThrownBy(() -> UiUsers.lookup("ghost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost")
                .hasMessageContaining("customer")
                .hasMessageContaining("clerk");
    }

    @Test
    void aValueWithNoColonFailsLoudly() throws IOException {
        users("broken=nocolonhere");

        assertThatThrownBy(() -> UiUsers.lookup("broken"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user:password");
    }

    @Test
    void theDefaultFileIsFoundInAnAncestorNotJustTheWorkingDirectory() throws IOException {
        // A failsafe fork's working directory is the MODULE, while the repo keeps one .swing-ui-agent/ at
        // its root. Resolving against the cwd alone finds nothing and reports the file as missing.
        Files.createDirectories(dir.resolve(".swing-ui-agent"));
        Path real = dir.resolve(".swing-ui-agent/ui-users.properties");
        Files.write(real, "customer=a:b".getBytes(StandardCharsets.UTF_8));
        Path deep = dir.resolve("module/src/test");
        Files.createDirectories(deep);

        assertThat(UiUsers.resolveDefaultFile(deep)).isEqualTo(real);
    }

    @Test
    void withNoFileAnywhereTheReportedPathIsTheOneTheCallerExpects() throws IOException {
        Path deep = dir.resolve("module");
        Files.createDirectories(deep);

        assertThat(UiUsers.resolveDefaultFile(deep))
                .isEqualTo(deep.resolve(".swing-ui-agent/ui-users.properties"));
    }

    @Test
    void aSecretIsTheAliasDotNameEntry() throws IOException {
        users("default=alice:pw", "default.pin=s3cret", "clerk=c:d");

        assertThat(UiUsers.secret("default", "pin")).isEqualTo("s3cret");
        assertThat(UiUsers.secret(null, "pin")).isEqualTo("s3cret");
    }

    @Test
    void aMissingSecretFailsNamingTheKey() throws IOException {
        users("clerk=c:d", "default.pin=s3cret");

        assertThatThrownBy(() -> UiUsers.secret("clerk", "pin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clerk.pin")
                .hasMessageNotContaining("s3cret");
    }

    @Test
    void aSecretNameWithADotOrBlankIsRefused() throws IOException {
        users("default=alice:pw", "default.a.b=s3cret");

        assertThatThrownBy(() -> UiUsers.secret("default", "a.b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UiUsers.secret("default", " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSecretIsNeverListedAsAnAliasNorLeaksItsValue() throws IOException {
        users("customer=a:b", "default.pin=s3cret");

        assertThatThrownBy(() -> UiUsers.lookup("ghost"))
                .hasMessageContaining("customer")
                .hasMessageNotContaining("pin")
                .hasMessageNotContaining("s3cret");
        assertThatThrownBy(() -> UiUsers.lookup("default.pin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret")
                .hasMessageNotContaining("s3cret");
    }

    @Test
    void aUserNameFindsTheCredentialsOfTheAliasThatLogsItIn() throws IOException {
        users("customer=customer1:secret", "default=admin:pw");

        UiUsers.Credentials c = UiUsers.forUser("customer1");

        assertThat(c.user).isEqualTo("customer1");
        assertThat(c.password).isEqualTo("secret");
    }

    @Test
    void aUserNoAliasLogsInHasNoCredentials() throws IOException {
        users("customer=customer1:secret", "customer9.pin=customer9:x");

        // a secret is never an alias, even when its value reads user:password
        assertThat(UiUsers.forUser("customer9")).isNull();
        assertThat(UiUsers.forUser(null)).isNull();
    }
}
