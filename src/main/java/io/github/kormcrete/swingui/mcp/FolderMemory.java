package io.github.kormcrete.swingui.mcp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.prefs.Preferences;

/**
 * The recorded tests folder the person last used in the palette, offered first next time unless one was set explicitly. Kept in
 * the user's Java {@link Preferences}, one entry per app working directory, so nothing is written into their project.
 * Memory only: whatever goes wrong, it forgets rather than fails.
 */
public interface FolderMemory {

    /** The remembered folder, or null. */
    Path get();

    /** Remembers {@code folder}. */
    void put(Path folder);

    /** The palette's node in the user preferences. */
    String NODE = "io/github/kormcrete/swing-ui-agent/palette";

    /**
     * Remembers in the user preferences, for the app started in {@code workingDir}. Nothing is read or created until
     * the first {@link #get} or {@link #put}.
     */
    static FolderMemory preferences(Path workingDir) {
        String key = key(workingDir);
        return new FolderMemory() {
            @Override
            public Path get() {
                try {
                    Preferences root = Preferences.userRoot();
                    // a read never creates the node
                    if (!root.nodeExists(NODE)) return null;
                    String v = root.node(NODE).get(key, null);
                    return v == null || v.isEmpty() ? null : Paths.get(v);
                } catch (Exception forgotten) {
                    return null;
                }
            }

            @Override
            public void put(Path folder) {
                try {
                    Preferences node = Preferences.userRoot().node(NODE);
                    node.put(key, folder.toAbsolutePath().normalize().toString());
                    node.flush();
                } catch (Exception forgotten) {
                    // memory only: the next palette offers the default folder again
                }
            }
        };
    }

    /** Remembers for as long as this object lives: tests, and nowhere to keep it. */
    static FolderMemory inMemory() {
        return new FolderMemory() {
            private volatile Path folder;

            @Override
            public Path get() {
                return folder;
            }

            @Override
            public void put(Path folder) {
                this.folder = folder;
            }
        };
    }

    /**
     * The preferences key for {@code workingDir}: {@code recorded-} and the SHA-1 of its absolute, normalized path, short
     * enough for {@link Preferences#MAX_KEY_LENGTH} however deep the folder is.
     */
    static String key(Path workingDir) {
        String path = workingDir.toAbsolutePath().normalize().toString();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(path.getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder("recorded-");
            for (byte b : hash) s.append(String.format("%02x", b & 0xFF));
            return s.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // every Java platform has SHA-1
            throw new IllegalStateException(e);
        }
    }
}
