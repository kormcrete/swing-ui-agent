package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.spi.PluginParts;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginCallsTest {

    public interface Hooks {
        List<String> openTabTitles();
        void openOrderReportTab(String title, List<Integer> ids);
        Map<String, List<String>> viewTabs();
        int openTabCount();
        boolean navChildColumnShowing();
        void fail();
        void twice(String a);
        void twice(int a);
    }

    static final class Impl implements Hooks {
        String last;
        @Override public List<String> openTabTitles() { return Arrays.asList("Orders", "Customers"); }
        @Override public void openOrderReportTab(String title, List<Integer> ids) { last = title + " " + ids; }
        @Override public Map<String, List<String>> viewTabs() {
            Map<String, List<String>> m = new LinkedHashMap<String, List<String>>();
            m.put("south", Collections.singletonList("Line items"));
            return m;
        }
        @Override public int openTabCount() { return 2; }
        @Override public boolean navChildColumnShowing() { return true; }
        @Override public void fail() { throw new DriverException(Reason.TIMEOUT, "nav did not settle"); }
        @Override public void twice(String a) {}
        @Override public void twice(int a) {}
    }

    private final ObjectMapper m = new ObjectMapper();
    private final Impl impl = new Impl();
    private final Plugins plugins = new Plugins(null);

    PluginCallsTest() {
        plugins.add("demo", Paths.get("x")).ready(
                new PluginParts(null, null, null, Hooks.class, impl, null), "ok");
    }

    @Test
    void returnsTheValueAsJson() throws Exception {
        assertThat(PluginCalls.call(plugins, null, "openTabTitles", null, m).toString())
                .isEqualTo("{\"value\":[\"Orders\",\"Customers\"]}");
        assertThat(PluginCalls.call(plugins, "demo", "viewTabs", m.createArrayNode(), m).toString())
                .isEqualTo("{\"value\":{\"south\":[\"Line items\"]}}");
        assertThat(PluginCalls.call(plugins, null, "openTabCount", null, m).toString()).isEqualTo("{\"value\":2}");
    }

    @Test
    void convertsArgumentsToTheParameterTypes() throws Exception {
        assertThat(PluginCalls.call(plugins, null, "openOrderReportTab",
                m.readTree("[\"Report\",[3,4]]"), m).toString()).isEqualTo("{\"value\":null}");
        assertThat(impl.last).isEqualTo("Report [3, 4]");
    }

    @Test
    void aDriverFailureInTheHookKeepsItsReason() {
        assertThatThrownBy(() -> PluginCalls.call(plugins, null, "fail", null, m))
                .isInstanceOf(DriverException.class)
                .hasMessage("TIMEOUT: nav did not settle");
    }

    @Test
    void badCallsAreBadInput() {
        assertThatThrownBy(() -> PluginCalls.call(plugins, null, "nope", null, m))
                .hasMessageStartingWith("BAD_INPUT: demo has no hook 'nope' (known: ");
        assertThatThrownBy(() -> PluginCalls.call(plugins, null, "twice", m.readTree("[\"a\"]"), m))
                .hasMessage("BAD_INPUT: hook 'twice' is overloaded; plugin_call needs one method per name");
        assertThatThrownBy(() -> PluginCalls.call(plugins, null, "openOrderReportTab", m.readTree("[\"a\"]"), m))
                .hasMessage("BAD_INPUT: hook 'openOrderReportTab' takes 2 arguments, got 1");
    }
}
