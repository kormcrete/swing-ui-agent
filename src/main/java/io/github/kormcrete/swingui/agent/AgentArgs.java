package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.driver.LaunchMode;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@code -javaagent:swing-ui-agent.jar=port=8765,bind=127.0.0.1,mode=normal,palette=on,flows=uitest,plugins=a.jar:b/classes,log=agent.log}.
 * <p>{@code plugins} is a list separated by {@link File#pathSeparator}; every entry gets its own {@link PluginSlot}.
 * <p>{@code flows} names the folder of flow files ({@code export_flow} writes there, {@code run_flow} reads there); a
 * relative one is under the app's working directory. Without it {@code -Dswingui.flowsDir} decides, else {@code uitest}.
 * <p>{@code log} also writes the agent's own lines to that file (see AgentLog); the path cannot contain a comma.
 * <p>{@code palette} is on by default, for a person at the screen: a small bar in the app window lets them record
 * steps and checks and answer the agent's questions there. {@code palette=off} is for agent-only and CI runs, where
 * nothing should show or listen to input. It needs a screen, so {@code mode=background} defaults it off and refuses
 * {@code palette=on}; a headless JVM never shows one. A test run holding the lease takes it out of the window.
 */
public final class AgentArgs {

    public final int port;
    public final String bind;
    public final String mode;
    public final List<Path> plugins;
    public final boolean palette;
    /** The flows folder as given; null when absent. */
    public final String flows;
    /** The file the agent's own lines also go to; null when absent. */
    public final Path log;

    private AgentArgs(int port, String bind, String mode, List<Path> plugins, boolean palette,
                      String flows, Path log) {
        this.port = port;
        this.bind = bind;
        this.mode = mode;
        this.palette = palette;
        this.flows = flows;
        this.log = log;
        this.plugins = Collections.unmodifiableList(plugins);
    }

    public static AgentArgs parse(String raw) {
        int port = 8765;
        String bind = "127.0.0.1";
        String mode = "normal";
        List<Path> plugins = new ArrayList<Path>();
        Boolean palette = null;
        String flows = null;
        Path log = null;
        if (raw != null && !raw.trim().isEmpty()) {
            for (String pair : raw.split(",")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) throw new IllegalArgumentException("argument '" + pair.trim() + "' is not key=value");
                String key = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                if ("port".equals(key)) {
                    port = port(value);
                } else if ("bind".equals(key)) {
                    bind = bind(value);
                } else if ("mode".equals(key)) {
                    try {
                        LaunchMode.parse(value);
                    } catch (RuntimeException e) {
                        throw new IllegalArgumentException("mode must be normal or background, was '" + value + "'");
                    }
                    mode = value;
                } else if ("palette".equals(key)) {
                    if ("on".equals(value) || "true".equals(value)) {
                        palette = Boolean.TRUE;
                    } else if ("off".equals(value) || "false".equals(value)) {
                        palette = Boolean.FALSE;
                    } else {
                        throw new IllegalArgumentException("palette must be on or off, was '" + value + "'");
                    }
                } else if ("inspector".equals(key)) {
                    throw new IllegalArgumentException("'inspector' is now 'palette' (on by default; palette=off turns it off)");
                } else if ("plugins".equals(key)) {
                    for (String p : value.split(Pattern.quote(File.pathSeparator))) {
                        if (!p.trim().isEmpty()) plugins.add(Paths.get(p.trim()));
                    }
                } else if ("flows".equals(key)) {
                    if (value.isEmpty()) throw new IllegalArgumentException("flows needs a folder, e.g. flows=uitest");
                    flows = value;
                } else if ("log".equals(key)) {
                    if (value.isEmpty()) throw new IllegalArgumentException("log needs a file, for example log=agent.log");
                    log = Paths.get(value);
                } else {
                    throw new IllegalArgumentException("unknown argument '" + key + "' (known: port, bind, mode, palette, flows, plugins, log)");
                }
            }
        }
        // decided after the loop, so the order of mode and palette does not matter
        boolean background = "background".equals(mode);
        if (Boolean.TRUE.equals(palette) && background) {
            throw new IllegalArgumentException("palette=on needs a person at the screen; it cannot run with mode=background");
        }
        return new AgentArgs(port, bind, mode, plugins, palette == null ? !background : palette.booleanValue(), flows, log);
    }

    /**
     * A loopback IP literal or {@code localhost}. A host name is refused rather than resolved: premain must never wait
     * on DNS, and refusing here keeps a bad bind a one-line argument error that starts nothing.
     */
    private static String bind(String value) {
        if (!"localhost".equalsIgnoreCase(value) && !isLoopbackLiteral(value)) {
            throw new IllegalArgumentException("bind must be a loopback address (127.0.0.1, ::1 or localhost), was '" + value + "'");
        }
        return value;
    }

    private static boolean isLoopbackLiteral(String value) {
        try {
            if (value.indexOf(':') >= 0) {
                // an IPv6 literal: InetAddress parses it without a lookup, and refuses a malformed one
                return InetAddress.getByName(value).isLoopbackAddress();
            }
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4) return false;
            byte[] address = new byte[4];
            for (int i = 0; i < 4; i++) {
                if (!parts[i].matches("\\d{1,3}")) return false;
                int octet = Integer.parseInt(parts[i]);
                if (octet > 255) return false;
                address[i] = (byte) octet;
            }
            return InetAddress.getByAddress(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static int port(String value) {
        try {
            int p = Integer.parseInt(value);
            if (p < 0 || p > 65535) throw new NumberFormatException();
            return p;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("port must be 0-65535, was '" + value + "'");
        }
    }
}
