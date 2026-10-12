package io.github.kormcrete.swingui.agent;

import java.io.InputStream;
import java.util.Properties;

/** The agent's own version and build time, from the Maven-filtered build.properties. */
public final class AgentInfo {

    public final String version;
    public final String built;

    public AgentInfo(String version, String built) {
        this.version = version;
        this.built = built;
    }

    public static AgentInfo load() {
        Properties p = new Properties();
        try (InputStream in = AgentInfo.class.getResourceAsStream("build.properties")) {
            if (in != null) p.load(in);
        } catch (Exception ignored) {
            // an unreadable stamp is reported as unknown, never fatal
        }
        return new AgentInfo(p.getProperty("version", "unknown"), p.getProperty("built", "unknown"));
    }
}
