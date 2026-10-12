package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Node;
import io.github.kormcrete.swingui.driver.platform.CapabilityReport;
import io.github.kormcrete.swingui.driver.platform.Watch;
import io.github.kormcrete.swingui.spi.AppHooks;
import io.github.kormcrete.swingui.spi.AppInfo;
import io.github.kormcrete.swingui.spi.ResetSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentResetTest {

    /** A client with {@code modals} modal dialogs stacked; an escape closes one unless {@code stubborn}. */
    static final class Dialogs implements Verbs {
        final List<String> calls = new ArrayList<String>();
        int modals;
        boolean stubborn;
        boolean closeFails;
        /** a stubborn dialog that a window close does get rid of */
        boolean closable;

        @Override public CapabilityReport capabilities() { throw Verbs.missing("capabilities"); }
        @Override public Watch watch() { throw Verbs.missing("watch"); }
        @Override public Node read(Locator target, Integer maxRows) { throw Verbs.missing("read"); }

        @Override public Node snapshot(String window, String ref, Integer maxNodes, Integer maxRows) {
            calls.add("snapshot " + window);
            Node root = new Node("windows", null);
            if (modals > 0) {
                Node d = new Node("dialog", "Error");
                d.modal = Boolean.TRUE;
                root.addChild(d);
            }
            return root;
        }

        @Override public VerbOutcome answer(String button, Long timeout) {
            calls.add("answer " + button + " " + timeout);
            if (modals == 0) throw new DriverException(Reason.NOT_FOUND, "no dialog");
            if (stubborn) throw new DriverException(Reason.NOT_ACTIONABLE, "Escape did not close dialog Error");
            modals--;
            return new VerbOutcome();
        }

        @Override public VerbOutcome closeWindow(String window, Long timeout) {
            calls.add("closeWindow " + window + " " + timeout);
            if (closeFails) throw new DriverException(Reason.NOT_ACTIONABLE, "closing did not close window Error");
            if (modals > 0 && (!stubborn || closable)) modals--;
            return new VerbOutcome();
        }

        @Override public void resetPointer() { calls.add("resetPointer"); }
    }

    static final class App implements AppHooks {
        final List<String> calls = new ArrayList<String>();
        @Override public void reset(ResetSpec spec) { calls.add("reset " + spec.key()); }
        @Override public void login(ResetSpec who, boolean force) { calls.add("login " + who + " " + force); }
        @Override public AppInfo info() { return new AppInfo("fixture", "1"); }
    }

    private final Dialogs verbs = new Dialogs();
    private final App app = new App();

    @Test
    void aCleanClientGetsNoEscape() {
        AgentReset.run(verbs, app, ResetSpec.alias(null));
        assertThat(verbs.calls).containsExactly("resetPointer", "snapshot dialog");
        assertThat(app.calls).containsExactly("reset alias:default");
    }

    @Test
    void withoutAnAppPluginOnlyThePointerAndTheDialogsAreReset() {
        verbs.modals = 1;
        AgentReset.run(verbs, null, null);
        assertThat(verbs.calls).containsExactly("resetPointer", "snapshot dialog", "answer escape 2000",
                "snapshot dialog");
    }

    @Test
    void eachModalGetsOneEscapeThenTheAppResets() {
        verbs.modals = 2;
        AgentReset.run(verbs, app, ResetSpec.alias("alice"));
        assertThat(verbs.calls).containsExactly("resetPointer", "snapshot dialog", "answer escape 2000",
                "snapshot dialog", "answer escape 2000", "snapshot dialog");
        assertThat(app.calls).containsExactly("reset alias:alice");
    }

    @Test
    void stubbornModalFailsNamingIt() {
        verbs.modals = 1;
        verbs.stubborn = true;
        verbs.closeFails = true;
        assertThatThrownBy(() -> AgentReset.run(verbs, app, ResetSpec.alias(null)))
                .isInstanceOf(DriverException.class)
                .hasMessage("APP_ERROR: reset: a modal dialog would not close after Escape and a window close: Error");
        assertThat(verbs.calls).containsExactly("resetPointer", "snapshot dialog", "answer escape 2000",
                "snapshot dialog", "closeWindow dialog:Error 2000", "snapshot dialog");
        assertThat(app.calls).isEmpty();
    }

    @Test
    void aWindowCloseGetsRidOfWhatEscapeCouldNot() {
        verbs.modals = 1;
        verbs.stubborn = true;
        verbs.closable = true;
        AgentReset.run(verbs, app, ResetSpec.alias(null));
        assertThat(verbs.calls).containsExactly("resetPointer", "snapshot dialog", "answer escape 2000",
                "snapshot dialog", "closeWindow dialog:Error 2000", "snapshot dialog");
        assertThat(app.calls).containsExactly("reset alias:default");
    }

    /** Records its hooks into the client's call list, so one list shows the order of both. */
    static final class OrderedApp implements AppHooks {
        final List<String> calls;
        RuntimeException recoverFails;
        OrderedApp(List<String> calls) { this.calls = calls; }
        @Override public void recoverSession() {
            calls.add("app.recoverSession");
            if (recoverFails != null) throw recoverFails;
        }
        @Override public void reset(ResetSpec spec) { calls.add("app.reset " + spec.key()); }
        @Override public void login(ResetSpec who, boolean force) { calls.add("app.login"); }
        @Override public AppInfo info() { return new AppInfo("fixture", "1"); }
    }

    @Test
    void theAppRecoversItsSessionBeforeTheEscapesThenResets() {
        OrderedApp ordered = new OrderedApp(verbs.calls);
        verbs.modals = 1;
        AgentReset.run(verbs, ordered, ResetSpec.alias("alice"));
        // an expired-session dialog that Escape would turn into an exit question gets its sign-in first
        assertThat(verbs.calls).containsExactly("resetPointer", "app.recoverSession", "snapshot dialog",
                "answer escape 2000", "snapshot dialog", "app.reset alias:alice");
    }

    @Test
    void aRecoveryThatFailsFailsTheResetWithItsError() {
        OrderedApp ordered = new OrderedApp(verbs.calls);
        ordered.recoverFails = new DriverException(Reason.APP_ERROR,
                "the session expired for customer9, and the users file has no password for customer9");
        verbs.modals = 1;
        assertThatThrownBy(() -> AgentReset.run(verbs, ordered, ResetSpec.alias(null)))
                .isSameAs(ordered.recoverFails);
        // no escape: on an expired-session dialog it would only raise the exit question
        assertThat(verbs.calls).containsExactly("resetPointer", "app.recoverSession");
    }
}
