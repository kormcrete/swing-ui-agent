package io.github.kormcrete.swingui.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginDescriptorTest {

    @TempDir Path dir;

    @Test
    void readsAClassFolder() throws Exception {
        Path props = dir.resolve(PluginDescriptor.RESOURCE);
        Files.createDirectories(props.getParent());
        Files.write(props, "app-class = com.example.orders.AppContext\n".getBytes(StandardCharsets.ISO_8859_1));
        PluginDescriptor d = PluginDescriptor.read(dir);
        assertThat(d.appClass).isEqualTo("com.example.orders.AppContext");
        assertThat(d.location).isEqualTo(dir);
    }

    @Test
    void readsAJar() throws Exception {
        Path jar = dir.resolve("p.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(PluginDescriptor.RESOURCE));
            out.write("app-class=com.example.orders.AppContext".getBytes(StandardCharsets.ISO_8859_1));
            out.closeEntry();
        }
        assertThat(PluginDescriptor.read(jar).appClass).isEqualTo("com.example.orders.AppContext");
    }

    @Test
    void aMissingLocationSaysSo() {
        assertThatThrownBy(() -> PluginDescriptor.read(dir.resolve("nope.jar")))
                .isInstanceOf(IOException.class).hasMessageContaining("no plugin at").hasMessageContaining("nope.jar");
    }

    @Test
    void aJarWithoutDescriptorSaysWhichFileIsMissing() throws Exception {
        Path jar = dir.resolve("empty.jar");
        try (OutputStream raw = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(raw)) {
            out.putNextEntry(new JarEntry("x.txt"));
            out.closeEntry();
        }
        assertThatThrownBy(() -> PluginDescriptor.read(jar)).hasMessageContaining(PluginDescriptor.RESOURCE);
    }

    @Test
    void aDescriptorWithoutAppClassIsRefused() throws Exception {
        Path props = dir.resolve(PluginDescriptor.RESOURCE);
        Files.createDirectories(props.getParent());
        Files.write(props, "other=1\n".getBytes(StandardCharsets.ISO_8859_1));
        assertThatThrownBy(() -> PluginDescriptor.read(dir)).hasMessageContaining("app-class");
    }

    @Test
    void nameComesFromThePropertyElseFromTheLocation() throws Exception {
        Path folder = dir.resolve("my-plugin/target/classes");
        writeDescriptor(folder, "app-class=a.B\n");
        assertThat(PluginDescriptor.read(folder).name).isEqualTo("my-plugin");
        Path named = dir.resolve("named");
        writeDescriptor(named, "app-class=a.B\nname=demo\n");
        assertThat(PluginDescriptor.read(named).name).isEqualTo("demo");
        Path other = dir.resolve("some-folder");
        writeDescriptor(other, "app-class=a.B\n");
        assertThat(PluginDescriptor.read(other).name).isEqualTo("some-folder");
    }

    @Test
    void aJarIsNamedAfterItsFileName() throws Exception {
        Path jar = dir.resolve("my-plug.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(PluginDescriptor.RESOURCE));
            out.write("app-class=a.B".getBytes(StandardCharsets.ISO_8859_1));
            out.closeEntry();
        }
        assertThat(PluginDescriptor.read(jar).name).isEqualTo("my-plug");
    }

    private static void writeDescriptor(Path folder, String content) throws IOException {
        Path props = folder.resolve(PluginDescriptor.RESOURCE);
        Files.createDirectories(props.getParent());
        Files.write(props, content.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void buildMatchIsReadAndAbsentIsNull(@TempDir Path dir) throws Exception {
        Path props = dir.resolve(PluginDescriptor.RESOURCE);
        Files.createDirectories(props.getParent());
        Files.write(props, "app-class=com.example.orders.AppContext\nbuild-match=Build-Session\n".getBytes(StandardCharsets.ISO_8859_1));
        assertThat(PluginDescriptor.read(dir).buildMatch).isEqualTo("Build-Session");
        Files.write(props, "app-class=com.example.orders.AppContext\n".getBytes(StandardCharsets.ISO_8859_1));
        assertThat(PluginDescriptor.read(dir).buildMatch).isNull();
    }
}
