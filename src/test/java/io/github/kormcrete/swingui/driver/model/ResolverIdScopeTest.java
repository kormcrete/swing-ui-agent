package io.github.kormcrete.swingui.driver.model;

import io.github.kormcrete.swingui.driver.window.WindowRoot;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JPanel;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An {@code id:<name>} scope step narrows to strict descendants of the component named {@code <name>}. */
class ResolverIdScopeTest {

    final FakeWindows windows = new FakeWindows();
    final Names names = new Names(Visibility.TREE);
    final Resolver resolver = new Resolver(Visibility.TREE, names, new Refs(c -> true), windows);
    final JPanel main = new JPanel(null);
    final JPanel column610 = new JPanel(null);
    final JPanel column866 = new JPanel(null);
    final JButton open610 = new JButton("Open");
    final JButton open866 = new JButton("Open");

    ResolverIdScopeTest() {
        column610.setName("summary.column.ORD-1610");
        column866.setName("summary.column.ORD-0866");
        column610.add(open610);
        column866.add(open866);
        main.add(column610);
        main.add(column866);
        windows.main = main;
        windows.roots.add(new WindowRoot(main, "window", "OrderDesk", false, null, false));
    }

    private static Locator inside(String id, String role, String name) {
        Locator l = Locator.of(role, name);
        l.scope = Collections.singletonList("id:" + id);
        return l;
    }

    @Test
    void anIdScopePicksTheElementInsideThatComponent() {
        assertThat(resolver.resolve(inside("summary.column.ORD-1610", "button", "Open")).component).isSameAs(open610);
    }

    @Test
    void withoutTheScopeTheNameIsAmbiguous() {
        assertThatThrownBy(() -> resolver.resolve(Locator.of("button", "Open"))).hasMessageContaining("2 elements match");
    }

    @Test
    void theNamedComponentIsNotInsideItself() {
        open610.setName("self");
        assertThatThrownBy(() -> resolver.resolve(inside("self", "button", "Open"))).hasMessageContaining("nothing matches");
    }

    @Test
    void aSiblingOfTheNamedComponentIsNotInsideIt() {
        // open866's parent is column866, the sibling of the named column - not an ancestor.
        column610.remove(open610);
        assertThatThrownBy(() -> resolver.resolve(inside("summary.column.ORD-1610", "button", "Open")))
                .hasMessageContaining("nothing matches");
    }

    @Test
    void anyDepthBelowTheNamedComponentCounts() {
        column610.remove(open610);
        JPanel wrapper = new JPanel(null);
        wrapper.add(open610);
        column610.add(wrapper);
        assertThat(resolver.resolve(inside("summary.column.ORD-1610", "button", "Open")).component).isSameAs(open610);
    }

    @Test
    void anIdScopeCombinesWithAnIdTarget() {
        open610.setName("open");
        open866.setName("open");
        Locator l = Locator.ofId("open");
        l.scope = Collections.singletonList(ScopeStep.id("summary.column.ORD-0866"));
        assertThat(resolver.resolve(l).component).isSameAs(open866);
    }
}
