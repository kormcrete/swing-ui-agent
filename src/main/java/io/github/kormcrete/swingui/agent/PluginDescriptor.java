package io.github.kormcrete.swingui.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** A plugin's location (jar or class folder) and the app class whose loading starts it. */
public class PluginDescriptor {

    public static final String RESOURCE = "META-INF/swing-ui-agent/plugin.properties";

    public final Path location;
    public final String appClass;
    /** The plugin's slot name: the {@code name} property, else derived from the location. */
    public final String name;
    /**
     * Whether {@link #name} is the {@code name} property: recorded tests store {@code <name>:<step>}, so a plugin with a
     * vocabulary needs one that does not change with its jar or folder ({@link PluginSlot#ready}).
     */
    public final boolean named;

    /** The manifest attribute both the plugin and the app's jar must agree on, or null for no check. */
    public final String buildMatch;

    PluginDescriptor(Path location, String appClass, String name) {
        this(location, appClass, name, null);
    }

    PluginDescriptor(Path location, String appClass, String name, String buildMatch) {
        this(location, appClass, name, true, buildMatch);
    }

    private PluginDescriptor(Path location, String appClass, String name, boolean named, String buildMatch) {
        this.location = location;
        this.appClass = appClass;
        this.name = name;
        this.named = named;
        this.buildMatch = buildMatch;
    }

    /** {@code target/classes} is named after its module folder, a jar after its file name, anything else after itself. */
    static String defaultName(Path location) {
        Path abs = location.toAbsolutePath().normalize();
        Path file = abs.getFileName();
        String f = file == null ? "plugin" : file.toString();
        if (f.endsWith(".jar")) return f.substring(0, f.length() - 4);
        Path parent = abs.getParent();
        Path module = parent == null ? null : parent.getParent();
        if (f.equals("classes") && parent != null && parent.getFileName() != null
                && parent.getFileName().toString().equals("target") && module != null && module.getFileName() != null) {
            return module.getFileName().toString();
        }
        return f;
    }

    public static PluginDescriptor read(Path location) throws IOException {
        if (!Files.exists(location)) throw new IOException("no plugin at " + location.toAbsolutePath());
        Properties p = new Properties();
        if (Files.isDirectory(location)) {
            Path file = location.resolve(RESOURCE);
            if (!Files.isRegularFile(file)) throw new IOException(location + " has no " + RESOURCE);
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            }
        } else {
            try (JarFile jar = new JarFile(location.toFile())) {
                JarEntry entry = jar.getJarEntry(RESOURCE);
                if (entry == null) throw new IOException(location + " has no " + RESOURCE);
                try (InputStream in = jar.getInputStream(entry)) {
                    p.load(in);
                }
            }
        }
        String app = p.getProperty("app-class", "").trim();
        if (app.isEmpty()) throw new IOException(location + ": " + RESOURCE + " names no app-class");
        String name = p.getProperty("name", "").trim();
        String match = p.getProperty("build-match", "").trim();
        return new PluginDescriptor(location, app, name.isEmpty() ? defaultName(location) : name, !name.isEmpty(),
                match.isEmpty() ? null : match);
    }
}
