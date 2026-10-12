package io.github.kormcrete.swingui.spi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuildStampTest {

    @TempDir Path dir;

    private Path jar(Path file, String attribute, String value) throws IOException {
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (attribute != null) m.getMainAttributes().putValue(attribute, value);
        Files.createDirectories(file.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file), m)) {
            out.flush();
        }
        return file;
    }

    @Test
    void aFileUrlOfAJarIsThatJar() throws Exception {
        Path j = jar(dir.resolve("a.jar"), null, null);
        assertThat(BuildStamp.location(j.toUri().toURL())).isEqualTo(j);
    }

    @Test
    void aJarUrlIsTheJarItNames() throws Exception {
        Path j = jar(dir.resolve("lib/a.jar"), null, null);
        assertThat(BuildStamp.location(new URL("jar:" + j.toUri() + "!/"))).isEqualTo(j);
    }

    @Test
    void anEncodedSpaceDecodes() throws Exception {
        Path j = jar(dir.resolve("Application Support/lib/a.jar"), null, null);
        URL url = j.toUri().toURL();                       // file:/…/Application%20Support/lib/a.jar
        assertThat(url.toString()).contains("%20");
        assertThat(BuildStamp.location(url)).isEqualTo(j);
        assertThat(BuildStamp.location(new URL("jar:" + url + "!/"))).isEqualTo(j);
    }

    @Test
    void aLiteralSpaceIsAccepted() throws Exception {
        Path j = jar(dir.resolve("Application Support/a.jar"), null, null);
        URL url = new URL("file:" + j.toAbsolutePath());   // some class loaders hand out unencoded paths
        assertThat(BuildStamp.location(url)).isEqualTo(j);
    }

    @Test
    void aFolderUrlIsThatFolder() throws Exception {
        assertThat(BuildStamp.location(dir.toUri().toURL())).isEqualTo(dir);
    }

    @Test
    void aNestedJarIsNoLocation() throws Exception {
        assertThat(BuildStamp.location(new URL("jar:file:/x/app.jar!/BOOT-INF/lib/b.jar!/"))).isNull();
    }

    @Test
    void aNonFileUrlOrNullIsNoLocation() throws Exception {
        assertThat(BuildStamp.location(new URL("http://example.org/a.jar"))).isNull();
        assertThat(BuildStamp.location(null)).isNull();
    }

    @Test
    void readsTheAttributeOfAJar() throws Exception {
        assertThat(BuildStamp.read(jar(dir.resolve("a.jar"), "Build-Session", "20261008-120000"), "Build-Session"))
                .isEqualTo("20261008-120000");
    }

    @Test
    void aJarWithoutTheAttributeReadsNull() throws Exception {
        assertThat(BuildStamp.read(jar(dir.resolve("a.jar"), null, null), "Build-Session")).isNull();
    }

    @Test
    void aFolderReadsItsManifestOrNull() throws Exception {
        assertThat(BuildStamp.read(dir, "Build-Session")).isNull();
        Path mf = dir.resolve(JarFile.MANIFEST_NAME);
        Files.createDirectories(mf.getParent());
        Files.write(mf, "Manifest-Version: 1.0\nBuild-Session: S1\n".getBytes("UTF-8"));
        assertThat(BuildStamp.read(dir, "Build-Session")).isEqualTo("S1");
    }

    @Test
    void aMissingJarThrows() {
        assertThatThrownBy(() -> BuildStamp.read(dir.resolve("gone.jar"), "Build-Session")).isInstanceOf(IOException.class);
    }

    @Test
    void noLocationOrNoAttributeReadsNull() throws Exception {
        assertThat(BuildStamp.read(null, "Build-Session")).isNull();
        assertThat(BuildStamp.read(dir, null)).isNull();
    }
}
