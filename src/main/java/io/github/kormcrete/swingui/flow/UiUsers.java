package io.github.kormcrete.swingui.flow;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.TreeSet;

/**
 * Resolves a recorded test's credential alias to a username and password.
 *
 * <p>Recorded tests name an alias, never a password -- recorded test files are committed, and a committed password
 * is a leaked one. Aliases live in an untracked properties file, one {@code alias=user:password}
 * per line: {@code -Dswingui.users}, else {@code .swing-ui-agent/ui-users.properties} in the working directory
 * or its nearest ancestor that has one.
 *
 * <p>An alias may carry secrets as {@code alias.<name>=value} lines, e.g. {@code default.pin=1234}, read with
 * {@link #secret}. A key with a dot is never an alias: {@link #lookup} refuses it and the list of known aliases
 * leaves it out, so a secret never reaches an error message.
 */
public final class UiUsers {

    /** The identity every recorded test runs as unless a loginAs step says otherwise. */
    public static final String DEFAULT_ALIAS = "default";

    /** System property overriding the credentials file location; mainly for tests. */
    public static final String FILE_PROPERTY = "swingui.users";

    private static final String DEFAULT_FILE = ".swing-ui-agent/ui-users.properties";

    public static final class Credentials {
        public final String user;
        public final String password;

        public Credentials(String user, String password) {
            this.user = user;
            this.password = password;
        }
    }

    private UiUsers() {
    }

    public static Credentials lookup(String alias) {
        String wanted = aliasOrDefault(alias);
        if (wanted.indexOf('.') >= 0) {
            throw new IllegalArgumentException("'" + wanted + "' is a secret's key, not an alias: an alias has no dot.");
        }
        Properties props = load();
        String value = props.getProperty(wanted);
        if (value == null) {
            throw new IllegalArgumentException("No credentials for alias '" + wanted + "' in "
                    + file() + ". Known aliases: " + known(props));
        }
        int colon = value.indexOf(':');
        if (colon < 0) {
            // the value is not shown: with the user name left out, it is the password
            throw new IllegalArgumentException("Alias '" + wanted + "' in " + file()
                    + " must read user:password; its value has no ':'.");
        }
        return new Credentials(value.substring(0, colon), value.substring(colon + 1));
    }

    /**
     * The credentials of the alias that logs {@code user} in, or null when no alias does: the way back from a
     * user name an app shows (a locked sign-in field) to the password a recorded test's alias holds. Never log the password.
     */
    public static Credentials forUser(String user) {
        if (user == null) return null;
        Properties props = load();
        // sorted, so the answer does not hang on the properties' hash order when two aliases share a user
        for (String key : new TreeSet<String>(props.stringPropertyNames())) {
            if (key.indexOf('.') >= 0) continue;
            String value = props.getProperty(key);
            int colon = value.indexOf(':');
            if (colon >= 0 && value.substring(0, colon).equals(user)) {
                return new Credentials(user, value.substring(colon + 1));
            }
        }
        return null;
    }

    /**
     * The secret {@code name} of {@code alias}: the {@code <alias>.<name>} entry (null or blank alias means
     * {@code default}). Never log or print it. A missing or empty entry fails naming the key, not a value.
     */
    public static String secret(String alias, String name) {
        if (name == null || name.trim().isEmpty() || name.indexOf('.') >= 0) {
            throw new IllegalArgumentException("A secret name is non-empty and has no dot, was '" + name + "'");
        }
        String key = aliasOrDefault(alias) + "." + name.trim();
        String value = load().getProperty(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("No " + key + " in " + file()
                    + " -- add it there yourself; a secret is never typed in chat or committed.");
        }
        return value;
    }

    private static String aliasOrDefault(String alias) {
        return (alias == null || alias.trim().isEmpty()) ? DEFAULT_ALIAS : alias.trim();
    }

    /** The aliases, never their secrets: a secret's key ({@code default.pin}) is left out. */
    private static String known(Properties props) {
        TreeSet<String> aliases = new TreeSet<>();
        for (String key : props.stringPropertyNames()) {
            if (key.indexOf('.') < 0) aliases.add(key);
        }
        if (aliases.isEmpty()) {
            return "(none -- the file is missing or empty)";
        }
        return String.join(", ", aliases);
    }

    private static Path file() {
        String override = System.getProperty(FILE_PROPERTY);
        if (override != null) {
            return Paths.get(override);
        }
        return resolveDefaultFile(Paths.get("").toAbsolutePath());
    }

    /**
     * Finds {@value #DEFAULT_FILE} in {@code start} or the nearest ancestor that has one.
     *
     * <p>Searched, not resolved: a repo keeps one {@code .swing-ui-agent/} at its root, but a surefire/failsafe
     * fork runs in the module directory.
     *
     * <p>Returns the cwd-relative candidate when nothing is found, so the error message names a
     * path the caller recognises.
     */
    static Path resolveDefaultFile(Path start) {
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(DEFAULT_FILE);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return start.resolve(DEFAULT_FILE);
    }

    private static Properties load() {
        Properties props = new Properties();
        Path path = file();
        if (Files.isRegularFile(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
            } catch (IOException e) {
                throw new IllegalStateException("Could not read " + path + ": " + e.getMessage(), e);
            }
        }
        return props;
    }
}
