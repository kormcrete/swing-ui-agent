package io.github.kormcrete.swingui.client;

import io.github.kormcrete.swingui.agent.InJvmAgent;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.spi.AppHooks;
import io.github.kormcrete.swingui.spi.AppInfo;
import io.github.kormcrete.swingui.spi.PluginParts;
import io.github.kormcrete.swingui.spi.ResetSpec;
import io.github.kormcrete.swingui.testing.RecordingHost;
import io.github.kormcrete.swingui.testing.RecordingVerbs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteAppTest {

    public interface Hooks {
        List<String> openTabTitles();
        Map<String, List<String>> viewTabs();
        int openTabCount();
        boolean navChildColumnShowing();
        void openOrderReportTab(String title, List<Integer> ids);
        void stuck();
    }

    static final class Impl implements Hooks {
        final List<String> calls = new ArrayList<String>();
        @Override public List<String> openTabTitles() { return Arrays.asList("Orders"); }
        @Override public Map<String, List<String>> viewTabs() { return Collections.singletonMap("south", Arrays.asList("Line items")); }
        @Override public int openTabCount() { return 3; }
        @Override public boolean navChildColumnShowing() { return true; }
        @Override public void openOrderReportTab(String title, List<Integer> ids) { calls.add(title + " " + ids); }
        @Override public void stuck() { throw new DriverException(Reason.TIMEOUT, "the nav bar did not settle"); }
    }

    static final class App implements AppHooks {
        final List<String> calls = new ArrayList<String>();
        @Override public void reset(ResetSpec spec) { calls.add("reset " + spec); }
        @Override public void login(ResetSpec who, boolean force) { calls.add("login " + who + " " + force); }
        @Override public AppInfo info() { return new AppInfo("fixture", "1"); }
    }

    private final RecordingVerbs served = new RecordingVerbs();
    private final Impl impl = new Impl();
    private final App app = new App();
    private final InJvmAgent agent = InJvmAgent.start(served,
            new PluginParts(new RecordingHost(served), null, app, Hooks.class, impl, null));
    private final RemoteApp remote = new RemoteApp(new AgentClient("127.0.0.1", agent.port()));

    @AfterEach
    void stop() {
        agent.stop();
    }

    @Test
    void appStepsAndLogins() {
        remote.navigate("Orders");
        remote.closeActiveTab();
        remote.loginAs("customer");
        remote.loginAs("customer1", "pw");
        remote.relogin();
        assertThat(served.calls).containsExactly("hook navigate Orders", "hook closeActiveTab");
        assertThat(app.calls).containsExactly("login customer false", "login customer1 false", "login null true");
    }

    @Test
    void typedHooksRoundTrip() {
        Hooks h = remote.hooks(Hooks.class);
        assertThat(h.openTabTitles()).containsExactly("Orders");
        assertThat(h.viewTabs()).containsEntry("south", Arrays.asList("Line items"));
        assertThat(h.openTabCount()).isEqualTo(3);
        assertThat(h.navChildColumnShowing()).isTrue();
        h.openOrderReportTab("Report", Arrays.asList(3, 4));
        assertThat(impl.calls).containsExactly("Report [3, 4]");
    }

    @Test
    void aHookFailureKeepsItsReasonAndMessage() {
        assertThatThrownBy(() -> remote.hooks(Hooks.class).stuck())
                .isInstanceOf(DriverException.class)
                .hasMessage("TIMEOUT: the nav bar did not settle");
    }
}
