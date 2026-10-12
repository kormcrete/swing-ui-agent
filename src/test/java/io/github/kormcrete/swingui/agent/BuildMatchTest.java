package io.github.kormcrete.swingui.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

class BuildMatchTest {

    private static final String A = "Build-Session";

    @TempDir Path dir;

    private Path folder(String name, String stamp) throws Exception {
        Path f = dir.resolve(name);
        Files.createDirectories(f);
        if (stamp != null) {
            Path mf = f.resolve(JarFile.MANIFEST_NAME);
            Files.createDirectories(mf.getParent());
            Files.write(mf, ("Manifest-Version: 1.0\n" + A + ": " + stamp + "\n").getBytes("UTF-8"));
        }
        return f;
    }

    @Test
    void noAttributeConfiguredNeverChecks() throws Exception {
        assertThat(BuildMatch.check(null, folder("p", "1"), folder("a", "2"))).isNull();
    }

    @Test
    void bothUnstampedPasses() throws Exception {
        assertThat(BuildMatch.check(A, folder("p", null), folder("a", null))).isNull();
    }

    @Test
    void equalStampsPass() throws Exception {
        assertThat(BuildMatch.check(A, folder("p", "20261008-120000"), folder("a", "20261008-120000"))).isNull();
    }

    @Test
    void differentStampsFailNamingBoth() throws Exception {
        Path p = folder("p", "20261008-120000");
        Path a = folder("a", "20261007-090000");
        assertThat(BuildMatch.check(A, p, a))
                .startsWith("the plugin and the app come from different builds: ")
                .contains("plugin Build-Session=20261008-120000 (" + p + ")")
                .contains("app Build-Session=20261007-090000 (" + a + ")");
    }

    @Test
    void oneSideUnstampedFails() throws Exception {
        assertThat(BuildMatch.check(A, folder("p", "1"), folder("a", null))).contains("app Build-Session=none");
        assertThat(BuildMatch.check(A, folder("p2", null), folder("a2", "1"))).contains("plugin Build-Session=none");
    }

    @Test
    void anUnreadablePluginFailsNamingIt() throws Exception {
        Path gone = dir.resolve("gone.jar");
        assertThat(BuildMatch.check(A, gone, folder("a", "1"))).startsWith("cannot read Build-Session of the plugin at " + gone);
    }
}
