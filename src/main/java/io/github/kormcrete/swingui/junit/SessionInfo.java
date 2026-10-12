package io.github.kormcrete.swingui.junit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The agent's {@code session info}, parsed: what a {@link UiRunGuard} checks before a run. Missing parts are null or 0. */
public final class SessionInfo {

    public final String agentVersion;
    public final String agentBuilt;
    public final String agentJar;
    public final long agentJarTimeMillis;
    public final String appName;
    public final String appVersion;
    public final long appStartedAtMillis;
    /** The client JVM's class-path entries that are directories (absolute); empty when the agent reports none. */
    public final List<String> appClasspath;
    public final String pluginState;
    public final String pluginDetail;
    public final String leaseOwner;
    public final List<Plugin> plugins;
    private final String appStartedAt;

    /** One plugin slot. */
    public static final class Plugin {
        public final String name;
        public final String state;
        public final String detail;
        public final String location;
        public final long locationTimeMillis;

        Plugin(JsonNode n) {
            name = text(n, "name");
            state = text(n, "state");
            detail = text(n, "detail");
            location = text(n, "location");
            locationTimeMillis = millis(text(n, "locationTime"));
        }
    }

    private SessionInfo(JsonNode i) {
        JsonNode agent = i.path("agent");
        agentVersion = text(agent, "version");
        agentBuilt = text(agent, "built");
        agentJar = text(agent, "jar");
        agentJarTimeMillis = millis(text(agent, "jarTime"));
        JsonNode app = i.path("app");
        appName = text(app, "name");
        appVersion = text(app, "version");
        appStartedAt = text(app, "startedAt");
        appStartedAtMillis = app.path("startedAtMillis").asLong(0);
        List<String> cp = new ArrayList<String>();
        for (JsonNode c : app.path("classpath")) cp.add(c.asText());
        appClasspath = Collections.unmodifiableList(cp);
        pluginState = text(i.path("plugin"), "state");
        pluginDetail = text(i.path("plugin"), "detail");
        leaseOwner = text(i.path("lease"), "owner");
        List<Plugin> list = new ArrayList<Plugin>();
        for (JsonNode p : i.path("plugins")) list.add(new Plugin(p));
        plugins = Collections.unmodifiableList(list);
    }

    public static SessionInfo parse(JsonNode info) {
        return new SessionInfo(info);
    }

    public boolean appPluginReady() {
        return "ready".equals(pluginState);
    }

    /** No app plugin configured, or it is ready: either way tests can run. */
    public boolean appPluginUsable() {
        return appPluginReady() || "none".equals(pluginState);
    }

    public String headline() {
        return "agent " + agentVersion + " built " + agentBuilt + "; " + (appName == null ? "app" : appName)
                + " started " + appStartedAt + "; plugin " + pluginState;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static long millis(String iso) {
        return iso == null ? 0 : Instant.parse(iso).toEpochMilli();
    }
}
