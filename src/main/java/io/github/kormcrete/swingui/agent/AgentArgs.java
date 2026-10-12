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
 * {@code -javaagent:swing-ui-agent.jar=port=8765,bind=127.0.0.1,mode=normal,plugins=a.jar:b/classes}.
 * <p>{@code plugins} is a list separated by {@link File#pathSeparator}; every entry gets its own {@link PluginSlot}.
 */
public final class AgentArgs {

    public final int port;
    public final String bind;
    public final String mode;
    public final List<Path> plugins;

    private AgentArgs(int port, String bind, String mode, List<Path> plugins) {
        this.port = port;
        this.bind = bind;
        this.mode = mode;
        this.plugins = Collections.unmodifiableList(plugins);
    }

    public static AgentArgs parse(String raw) {
        int port = 8765;
        String bind = "127.0.0.1";
        String mode = "normal";
        List<Path> plugins = new ArrayList<Path>();
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
                } else if ("plugins".equals(key)) {
                    for (String p : value.split(Pattern.quote(File.pathSeparator))) {
                        if (!p.trim().isEmpty()) plugins.add(Paths.get(p.trim()));
                    }
                } else {
                    throw new IllegalArgumentException("unknown argument '" + key + "' (known: port, bind, mode, plugins)");
                }
            }
        }
        return new AgentArgs(port, bind, mode, plugins);
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
