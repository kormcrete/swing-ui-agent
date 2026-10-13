package io.github.kormcrete.swingui.driver.model;

import io.github.kormcrete.swingui.driver.window.WindowRoot;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JPanel;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/** Recording precedence: id, role+name in the window, plus scope innermost first, plus nth. */
class RecorderTest {

    private final ResolverTest scene = new ResolverTest();
    private final Recorder recorder = new Recorder(scene.resolver, scene.windows);
    private final Snapshot snapshot = new Snapshot(Visibility.TREE, scene.names, scene.refs, scene.windows);

    private Resolved viaRef(String role, String name) {
        String ref = snapshot.build(null, null, 400, 20).find(role, name).ref;
        return scene.resolver.resolve(Locator.ofRef(ref));
    }

    @Test
    void anIdComesFirst() {
        Recorder.Recorded r = recorder.record(viaRef("button", "help"));
        assertThat(r.locator).isEqualTo(Locator.ofId("help"));
        assertThat(r.fragile).isFalse();
    }

    @Test
    void roleAndNameInTheBareDialog() {
        Recorder.Recorded r = recorder.record(viaRef("button", "Assign"));
        Locator expected = Locator.of("button", "Assign");
        expected.window = "dialog";
        assertThat(r.locator).isEqualTo(expected);
    }

    @Test
    void scopeIsAddedInnermostFirst() {
        Resolved foot = scene.resolver.resolve(footOk());
        Recorder.Recorded r = recorder.record(foot);
        assertThat(r.locator.scope).containsExactly("section:Footer");
        assertThat(r.fragile).isFalse();
    }

    @Test
    void twinsFallBackToNthAndAreFragile() {
        JPanel twins = ResolverTest.titled("Middle");
        JButton a = new JButton("Next");
        JButton b = new JButton("Next");
        twins.add(a);
        twins.add(b);
        scene.dialog.add(twins);
        Recorder.Recorded r = recorder.record(viaRef("button", "Next"));
        assertThat(r.fragile).isTrue();
        assertThat(r.locator.nth).isEqualTo(0);
        assertThat(scene.resolver.resolve(r.locator).component).isSameAs(a);
    }

    @Test
    void subTargetsAreKeptAndTheRefIsNot() {
        String ref = snapshot.build(null, null, 400, 20).find("table", "Orders").ref;
        Locator l = Locator.ofRef(ref);
        l.row = Collections.<String, Object>singletonMap("index", 2);
        l.column = "B";
        Recorder.Recorded r = recorder.record(scene.resolver.resolve(l));
        assertThat(r.locator.ref).isNull();
        assertThat(r.locator.row).containsEntry("index", 2);
        assertThat(r.locator.column).isEqualTo("B");
        assertThat(r.locator.role).isEqualTo("table");
    }

    @Test
    void aSecondDialogKeepsTheExactTitle() {
        JPanel tools = new JPanel(null);
        JButton apply = new JButton("Apply");
        tools.add(apply);
        scene.windows.roots.add(new WindowRoot(tools, "dialog", "Tools", false, "Order editor – Order 1", false));
        Recorder.Recorded r = recorder.record(viaRef("button", "Apply"));
        assertThat(r.locator.window).isEqualTo("dialog:Tools");
    }

    private static Locator footOk() {
        Locator l = Locator.of("button", "OK");
        l.scope = Arrays.asList("section:Footer");
        return l;
    }

    @Test
    void anElementWithoutANameIsAddressedByRoleAndScopeNotNth() {
        javax.swing.JTable unnamed = new javax.swing.JTable(2, 2);
        JPanel lines = new JPanel();
        lines.add(unnamed);
        javax.swing.JTabbedPane cart = new javax.swing.JTabbedPane();
        cart.addTab("Cart", lines);
        scene.mainContent.add(cart);
        Resolved table = scene.resolver.resolve(Locator.ofRef(scene.refs.add(unnamed, null)));
        assertThat(table.name).isNull();
        Recorder.Recorded r = recorder.record(table);
        assertThat(r.fragile).isFalse();
        assertThat(r.locator.nth).isNull();
        assertThat(r.locator.name).isNull();
        assertThat(scene.resolver.resolve(r.locator).component).isSameAs(unnamed);
    }
}
