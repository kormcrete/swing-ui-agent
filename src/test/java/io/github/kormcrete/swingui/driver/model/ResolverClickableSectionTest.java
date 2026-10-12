package io.github.kormcrete.swingui.driver.model;

import io.github.kormcrete.swingui.driver.window.WindowRoot;
import org.junit.jupiter.api.Test;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.event.MouseAdapter;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A titled box that also carries an app mouse listener has the role {@code clickable}, yet its caption still scopes
 * {@code section:<caption>}: of two fields captioned "Product", the one inside the box is reached through it. The
 * box stays clickable by its role and name.
 */
class ResolverClickableSectionTest {

    final Refs refs = new Refs(c -> true);
    final FakeWindows windows = new FakeWindows();
    final Names names = new Names(Visibility.TREE);
    final Resolver resolver = new Resolver(Visibility.TREE, names, refs, windows);
    final JPanel content = new JPanel(null);
    final JPanel shipping = new JPanel();
    final JTextField product = new JTextField();
    final JTextField shippingProduct = new JTextField();

    ResolverClickableSectionTest() {
        content.add(labelled("Product", product));
        // The titled border sits inside a compound one.
        shipping.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(2, 2, 2, 2), BorderFactory.createTitledBorder("Shipping")));
        shipping.addMouseListener(new MouseAdapter() {});
        shipping.add(labelled("Product", shippingProduct));
        content.add(shipping);
        windows.main = content;
        windows.roots.add(new WindowRoot(content, "window", "OrderDesk", false, null, true));
    }

    /** A plain row panel holding a label and the field it names. */
    private static JPanel labelled(String caption, JTextField field) {
        JPanel row = new JPanel();
        JLabel label = new JLabel(caption);
        label.setLabelFor(field);
        row.add(label);
        row.add(field);
        return row;
    }

    private static Locator scoped(String role, String name, String... scope) {
        Locator l = Locator.of(role, name);
        l.scope = Arrays.asList(scope);
        return l;
    }

    @Test
    void theTitledBoxIsClickable() {
        assertThat(Roles.of(shipping)).isEqualTo("clickable");
        Resolved box = resolver.resolve(Locator.of("clickable", "Shipping"));
        assertThat(box.component).isSameAs(shipping);
        assertThat(box.role).isEqualTo("clickable");
    }

    @Test
    void aSectionScopeReachesTheFieldInsideAClickableTitledBox() {
        assertThat(resolver.matches(Locator.of("text", "Product"))).hasSize(2);

        Resolved r = resolver.resolve(scoped("text", "Product", "section:Shipping"));

        assertThat(r.component).isSameAs(shippingProduct);
        assertThat(r.trail).containsExactly("section:Shipping");
    }

    @Test
    void theClickableTitledBoxIsASectionTargetToo() {
        List<Resolved> found = resolver.matches(Locator.of("section", "Shipping"));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).component).isSameAs(shipping);
        assertThat(found.get(0).role).isEqualTo("section");
    }

    @Test
    void theRecordedTrailOfTheInnerFieldCarriesTheSectionStep() {
        assertThat(Trail.of(shippingProduct)).containsExactly("section:Shipping");
        assertThat(Trail.of(product)).isEmpty();
        assertThat(Trail.of(shipping)).isEmpty(); // a box's own caption never scopes the box itself
    }

    @Test
    void theFieldOutsideTheBoxIsNotInItsScope() {
        List<Resolved> found = resolver.matches(scoped("text", "Product", "section:Shipping"));

        assertThat(found).extracting(x -> x.component).containsExactly(shippingProduct);
    }
}
