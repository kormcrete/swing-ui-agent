package io.github.kormcrete.swingui.driver.model;

import io.github.kormcrete.swingui.driver.window.WindowRoot;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A painted component's {@link Part}s - a segmented switch drawn as one component - become child nodes a locator
 * resolves by role and word, inside the component's own id scope, with their selected and disabled states.
 */
class PartsTest {

    /** Two words painted in one clickable component: what a segmented switch is to the walk. */
    static final class Painted extends JComponent {
        int picked = 1;
        boolean enabled = true;

        Painted() {
            setBounds(0, 0, 100, 20);
            addMouseListener(new MouseAdapter() {
            });
        }
    }

    final FakeWindows windows = new FakeWindows();
    final Names names = new Names(Visibility.TREE);
    final Refs refs = new Refs(c -> true);
    final Parts parts = new Parts();
    final Resolver resolver = new Resolver(Visibility.TREE, names, refs, windows, new Sections(), parts);
    final Snapshot snapshot = new Snapshot(Visibility.TREE, names, refs, windows, new Sections(), parts);
    final JPanel main = new JPanel(null);
    final Painted severity = new Painted();

    PartsTest() {
        severity.setName("severity");
        main.add(severity);
        windows.main = main;
        windows.roots.add(new WindowRoot(main, "window", "OrderDesk", false, null, false));
        parts.register(new PartSource() {
            @Override
            public List<Part> parts(Component c) {
                if (!(c instanceof Painted)) return null;
                Painted p = (Painted) c;
                return Arrays.asList(
                        new Part(0, "toggle", "Rejected", p.picked == 0, p.enabled, new Rectangle(0, 0, 50, 20)),
                        new Part(1, "toggle", "Expected", p.picked == 1, p.enabled, new Rectangle(50, 0, 50, 20)));
            }
        });
    }

    @Test
    void aPartResolvesByItsWordAndCarriesItsIndexAndArea() {
        Resolved r = resolver.resolve(Locator.of("toggle", "Rejected"));
        assertThat(r.component).isSameAs(severity);
        assertThat(r.part).isNotNull();
        assertThat(r.tab).isEqualTo(0);
        assertThat(r.part.bounds).isEqualTo(new Rectangle(0, 0, 50, 20));
    }

    @Test
    void thePickedPartIsSelectedAndTheOtherIsNot() {
        Node tree = snapshot.build(null, null, 100, 0);
        assertThat(tree.find("toggle", "Expected").hasState("selected")).isTrue();
        assertThat(tree.find("toggle", "Rejected").hasState("selected")).isFalse();
    }

    @Test
    void aReadOnlySwitchsPartsAreDisabled() {
        severity.enabled = false;
        Node tree = snapshot.build(null, null, 100, 0);
        assertThat(tree.find("toggle", "Expected").hasState("disabled")).isTrue();
    }

    @Test
    void anIdScopeOfTheComponentItselfReachesItsParts() {
        Locator l = Locator.of("toggle", "Expected");
        l.scope = Collections.singletonList("id:severity");
        assertThat(resolver.resolve(l).part.index).isEqualTo(1);
    }

    @Test
    void aPartIsNeverMatchedByTheComponentsId() {
        Locator l = Locator.ofId("severity");
        l.role = "toggle";
        assertThatThrownBy(() -> resolver.resolve(l)).hasMessageContaining("nothing matches");
    }

    @Test
    void aSnapshotRefResolvesBackToThePart() {
        Node tree = snapshot.build(null, null, 100, 0);
        Locator byRef = new Locator();
        byRef.ref = tree.find("toggle", "Expected").ref;
        Resolved r = resolver.resolve(byRef);
        assertThat(r.part).isNotNull();
        assertThat(r.name).isEqualTo("Expected");
    }

    @Test
    void withoutASourceAPaintedComponentHasNoParts() {
        Resolver bare = new Resolver(Visibility.TREE, names, refs, windows);
        assertThatThrownBy(() -> bare.resolve(Locator.of("toggle", "Expected"))).hasMessageContaining("nothing matches");
    }
}
