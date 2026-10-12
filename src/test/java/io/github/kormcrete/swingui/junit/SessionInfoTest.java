package io.github.kormcrete.swingui.junit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionInfoTest {

    private static final String INFO = "{\"agent\":{\"version\":\"2.0\",\"built\":\"2026-10-07T08:00:00Z\","
            + "\"jar\":\"/r/swing-ui-agent.jar\",\"jarTime\":\"2026-10-07T08:00:01Z\"},"
            + "\"endpoint\":{\"bind\":\"127.0.0.1\",\"port\":8765},\"mode\":\"normal\","
            + "\"app\":{\"startedAt\":\"2026-10-07T09:00:00Z\",\"startedAtMillis\":1791363600000,\"name\":\"OrderDesk\",\"version\":null,\"classpath\":[\"/r/a/target/classes\",\"/r/b/target/classes\"]},"
            + "\"lease\":null,\"plugin\":{\"state\":\"ready\",\"detail\":\"DemoUiPlugin from x\"},"
            + "\"plugins\":[{\"name\":\"demo\",\"state\":\"ready\",\"detail\":\"ok\",\"location\":\"/r/p/target/classes\","
            + "\"locationTime\":\"2026-10-07T08:30:00Z\"}]}";

    @Test
    void parsesTheFieldsTheGuardsNeed() throws Exception {
        SessionInfo i = SessionInfo.parse(new ObjectMapper().readTree(INFO));
        assertThat(i.appStartedAtMillis).isEqualTo(1791363600000L);
        assertThat(i.appName).isEqualTo("OrderDesk");
        assertThat(i.appVersion).isNull();
        assertThat(i.agentJarTimeMillis).isEqualTo(java.time.Instant.parse("2026-10-07T08:00:01Z").toEpochMilli());
        assertThat(i.appPluginReady()).isTrue();
        assertThat(i.plugins.get(0).locationTimeMillis).isEqualTo(java.time.Instant.parse("2026-10-07T08:30:00Z").toEpochMilli());
        assertThat(i.headline()).isEqualTo("agent 2.0 built 2026-10-07T08:00:00Z; OrderDesk started 2026-10-07T09:00:00Z; plugin ready");
    }

    @Test
    void parsesTheClientClasspathDirectoriesAndDefaultsToEmpty() throws Exception {
        SessionInfo i = SessionInfo.parse(new ObjectMapper().readTree(INFO));
        assertThat(i.appClasspath).containsExactly("/r/a/target/classes", "/r/b/target/classes");
        assertThat(SessionInfo.parse(new ObjectMapper().readTree("{}")).appClasspath).isEmpty();
    }

    @Test
    void aMissingPluginIsNotReady() throws Exception {
        SessionInfo i = SessionInfo.parse(new ObjectMapper().readTree("{\"plugin\":{\"state\":\"waiting\",\"detail\":\"w\"}}"));
        assertThat(i.appPluginReady()).isFalse();
        assertThat(i.appPluginUsable()).isFalse();
    }

    @Test
    void noPluginAtAllIsUsable() throws Exception {
        SessionInfo i = SessionInfo.parse(new ObjectMapper().readTree("{\"plugin\":{\"state\":\"none\",\"detail\":\"d\"}}"));
        assertThat(i.appPluginReady()).isFalse();
        assertThat(i.appPluginUsable()).isTrue();
    }
}
