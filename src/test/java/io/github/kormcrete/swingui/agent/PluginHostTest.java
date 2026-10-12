package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.agent.fixture.FixturePlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PluginHostTest {

    @TempDir Path dir;

    private PluginDescriptor descriptor(boolean withService) throws Exception {
        Path props = dir.resolve(PluginDescriptor.RESOURCE);
        Files.createDirectories(props.getParent());
        Files.write(props, "app-class=com.example.orders.AppContext".getBytes(StandardCharsets.ISO_8859_1));
        if (withService) {
            Path services = dir.resolve("META-INF/services/io.github.kormcrete.swingui.spi.UiPlugin");
            Files.createDirectories(services.getParent());
            Files.write(services, FixturePlugin.class.getName().getBytes(StandardCharsets.UTF_8));
        }
        return PluginDescriptor.read(dir);
    }

    @Test
    void waitsUntilTheAppClassLoads() throws Exception {
        PluginSlot slot = new PluginSlot("fixture", dir);
        new PluginHost(null, slot, descriptor(true));
        assertThat(slot.state()).isEqualTo(PluginSlot.State.WAITING);
        assertThat(slot.detail()).contains("com.example.orders.AppContext");
    }

    @Test
    void startsThePluginFromTheServiceFile() throws Exception {
        FixturePlugin.started = false;
        PluginSlot slot = new PluginSlot("fixture", dir);
        new PluginHost(null, slot, descriptor(true)).startNow(getClass().getClassLoader());
        assertThat(FixturePlugin.started).isTrue();
        assertThat(slot.state()).isEqualTo(PluginSlot.State.READY);
        assertThat(slot.detail()).contains(FixturePlugin.class.getName());
    }

    @Test
    void theCallersContextLoaderIsRestoredOnSuccessAndFailure() throws Exception {
        ClassLoader before = Thread.currentThread().getContextClassLoader();
        new PluginHost(null, new PluginSlot("fixture", dir), descriptor(true)).startNow(getClass().getClassLoader());
        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(before);
        ClassLoader isolated = new java.net.URLClassLoader(new java.net.URL[0], ClassLoader.getSystemClassLoader().getParent());
        new PluginHost(null, new PluginSlot("fixture", dir), descriptor(false)).startNow(isolated);
        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(before);
    }

    @Test
    void aFolderWithoutServiceFileFailsWithTheReason() throws Exception {
        PluginSlot slot = new PluginSlot("fixture", dir);
        // a parent that cannot see the test classpath's own service files
        ClassLoader isolated = new java.net.URLClassLoader(new java.net.URL[0], ClassLoader.getSystemClassLoader().getParent());
        new PluginHost(null, slot, descriptor(false)).startNow(isolated);
        assertThat(slot.state()).isEqualTo(PluginSlot.State.FAILED);
        assertThat(slot.detail()).contains("META-INF/services/io.github.kormcrete.swingui.spi.UiPlugin");
    }

    @Test
    void aPluginThreadThatCannotStartFailsWithTheReason() throws Exception {
        PluginSlot slot = new PluginSlot("fixture", dir);
        PluginHost host = new PluginHost(null, slot, descriptor(true)) {
            @Override Thread newThread(Runnable r) {
                // the real case is OutOfMemoryError "unable to create new native thread"; JUnit aborts the run on an OOM
                throw new Error("unable to create new native thread");
            }
        };
        host.appClassLoaded(getClass().getClassLoader());
        assertThat(slot.state()).isEqualTo(PluginSlot.State.FAILED);
        assertThat(slot.detail()).contains("unable to create new native thread");
    }

    @Test
    void thePluginLoadsInAChildLoaderAndStartsWithItAsContextLoader() throws Exception {
        String src = "package probe;\n"
                + "public class ProbePlugin implements io.github.kormcrete.swingui.spi.UiPlugin {\n"
                + "  public io.github.kormcrete.swingui.spi.PluginParts start(io.github.kormcrete.swingui.spi.PluginContext c) {\n"
                + "    ClassLoader own = ProbePlugin.class.getClassLoader();\n"
                + "    System.setProperty(\"probe.own\", String.valueOf(System.identityHashCode(own)));\n"
                + "    System.setProperty(\"probe.parent\", String.valueOf(System.identityHashCode(own.getParent())));\n"
                + "    System.setProperty(\"probe.tccl\", String.valueOf(System.identityHashCode(Thread.currentThread().getContextClassLoader())));\n"
                + "    return new io.github.kormcrete.swingui.spi.PluginParts(null, null);\n"
                + "  }\n"
                + "}\n";
        PluginJars.compile(dir, "probe.ProbePlugin", src);
        PluginJars.write(dir.resolve("META-INF/services/io.github.kormcrete.swingui.spi.UiPlugin"), "probe.ProbePlugin\n");
        PluginJars.write(dir.resolve("META-INF/swing-ui-agent/plugin.properties"), "app-class=x.Y\nname=probe\n");
        ClassLoader appLoader = getClass().getClassLoader();
        PluginSlot slot = new PluginSlot("probe", dir);
        new PluginHost(null, slot, PluginDescriptor.read(dir)).startNow(appLoader);

        assertThat(slot.state()).as(slot.detail()).isEqualTo(PluginSlot.State.READY);
        assertThat(System.getProperty("probe.parent")).isEqualTo(String.valueOf(System.identityHashCode(appLoader)));
        assertThat(System.getProperty("probe.own")).isNotEqualTo(System.getProperty("probe.parent"));
        assertThat(System.getProperty("probe.tccl")).isEqualTo(System.getProperty("probe.own"));
    }

    /** A plugin folder: plugin.properties with {@code props}, the FixturePlugin service file, and a manifest stamp. */
    private PluginDescriptor stampedPlugin(String props, String stamp) throws Exception {
        Path p = dir.resolve("plugin");
        Path file = p.resolve(PluginDescriptor.RESOURCE);
        Files.createDirectories(file.getParent());
        Files.write(file, props.getBytes(StandardCharsets.ISO_8859_1));
        Path services = p.resolve("META-INF/services/io.github.kormcrete.swingui.spi.UiPlugin");
        Files.createDirectories(services.getParent());
        Files.write(services, FixturePlugin.class.getName().getBytes(StandardCharsets.UTF_8));
        if (stamp != null) {
            Files.write(p.resolve("META-INF/MANIFEST.MF"),
                    ("Manifest-Version: 1.0\nBuild-Session: " + stamp + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return PluginDescriptor.read(p);
    }

    /** A loader whose com.example.orders.AppContext comes from a jar stamped {@code stamp}; the test classpath is its parent. */
    private ClassLoader appFromJar(String stamp) throws Exception {
        Path classes = Files.createDirectories(dir.resolve("app-classes"));   // javac -d needs the folder to exist
        PluginJars.compile(classes, "com.example.orders.AppContext", "package com.example.orders; public class AppContext {}");
        Path jar = PluginJars.jar(classes, dir.resolve("app lib/app.jar"), "Build-Session", stamp);
        return new java.net.URLClassLoader(new java.net.URL[] {jar.toUri().toURL()}, getClass().getClassLoader());
    }

    private static final String MATCHED = "app-class=com.example.orders.AppContext\nbuild-match=Build-Session\n";

    @Test
    void aPluginFromTheSameBuildStarts() throws Exception {
        FixturePlugin.started = false;
        PluginSlot slot = new PluginSlot("fixture", dir);
        new PluginHost(null, slot, stampedPlugin(MATCHED, "S1")).startNow(appFromJar("S1"));
        assertThat(slot.state()).isEqualTo(PluginSlot.State.READY);
        assertThat(FixturePlugin.started).isTrue();
    }

    @Test
    void aPluginFromAnotherBuildFailsNamingBothStamps() throws Exception {
        FixturePlugin.started = false;
        PluginSlot slot = new PluginSlot("fixture", dir);
        new PluginHost(null, slot, stampedPlugin(MATCHED, "S2")).startNow(appFromJar("S1"));
        assertThat(slot.state()).isEqualTo(PluginSlot.State.FAILED);
        assertThat(slot.detail()).contains("plugin Build-Session=S2").contains("app Build-Session=S1").contains("app lib");
        assertThat(FixturePlugin.started).isFalse();
    }

    @Test
    void aStampedPluginNextToAnUnstampedAppFails() throws Exception {
        PluginSlot slot = new PluginSlot("fixture", dir);
        // FixturePlugin's own class: loaded from the test-classes folder, which has no manifest
        String props = "app-class=" + FixturePlugin.class.getName() + "\nbuild-match=Build-Session\n";
        new PluginHost(null, slot, stampedPlugin(props, "S1")).startNow(getClass().getClassLoader());
        assertThat(slot.state()).isEqualTo(PluginSlot.State.FAILED);
        assertThat(slot.detail()).contains("app Build-Session=none");
    }

    @Test
    void aMissingAppClassFailsTheCheckNamingIt() throws Exception {
        PluginSlot slot = new PluginSlot("fixture", dir);
        new PluginHost(null, slot, stampedPlugin(MATCHED, "S1")).startNow(getClass().getClassLoader());
        assertThat(slot.state()).isEqualTo(PluginSlot.State.FAILED);
        assertThat(slot.detail()).startsWith("cannot check the build of com.example.orders.AppContext: ");
    }
}
