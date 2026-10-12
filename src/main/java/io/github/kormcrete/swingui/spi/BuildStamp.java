package io.github.kormcrete.swingui.spi;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Which build a jar or class folder came from: one manifest attribute that every jar of one build shares.
 * A class folder normally has no manifest, so it carries no stamp.
 */
public final class BuildStamp {

    private BuildStamp() {}

    /**
     * The jar or folder a code source names: {@code file:/a.jar}, {@code jar:file:/a.jar!/} or {@code file:/dir/},
     * with spaces encoded or not. Null for a nested jar, a non-file URL or null.
     */
    public static Path location(URL codeSource) {
        if (codeSource == null) return null;
        String s = codeSource.toString();
        if (s.startsWith("jar:")) {
            int bang = s.indexOf("!/");
            if (bang < 0 || bang + 2 < s.length()) return null;      // nested (…!/BOOT-INF/lib/x.jar!/): no file of its own
            s = s.substring("jar:".length(), bang);
        }
        if (!s.startsWith("file:")) return null;
        try {
            return Paths.get(new URI(s));
        } catch (URISyntaxException e) {
            try {
                return Paths.get(new URI(s.replace(" ", "%20")));
            } catch (URISyntaxException | IllegalArgumentException again) {
                return null;
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The attribute in a jar's manifest, or in a folder's {@code META-INF/MANIFEST.MF}; null when absent. */
    public static String read(Path location, String attribute) throws IOException {
        if (location == null || attribute == null) return null;
        Manifest manifest;
        if (Files.isDirectory(location)) {
            Path file = location.resolve(JarFile.MANIFEST_NAME);
            if (!Files.isRegularFile(file)) return null;
            try (InputStream in = Files.newInputStream(file)) {
                manifest = new Manifest(in);
            }
        } else {
            try (JarFile jar = new JarFile(location.toFile())) {
                manifest = jar.getManifest();
            }
        }
        if (manifest == null) return null;
        String value = manifest.getMainAttributes().getValue(attribute);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
