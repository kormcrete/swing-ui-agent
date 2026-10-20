package io.github.kormcrete.swingui.driver.model;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.window.WindowRoot;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A text field's clear X: id {@code clear} reaches an app's own X named {@code clear} and FlatLaf's built-in one
 * ({@code TextField.clearButton}), and a {@code text:<name>} scope step reaches what lies strictly inside the text
 * field of that name, so a field found by its caption can hold the X. The step is checked while walking, but it is
 * not part of what a match records: a recorded step keeps the scope it always had.
 */
class ResolverClearButtonTest {

    private final Refs refs = new Refs(c -> true);
    private final Names names = new Names(Visibility.TREE);
    private final FakeWindows windows = new FakeWindows();
    private final JPanel root = new JPanel(null);
    private final Resolver resolver = new Resolver(Visibility.TREE, names, refs, windows);

    ResolverClearButtonTest() {
        windows.roots.add(new WindowRoot(root, "main", "Orders", false, null, true));
    }

    /** A captioned field with buttons mounted inside it, as FlatLaf and an app's own clearable field do. */
    private JTextField field(String caption, int y, JButton... inside) {
        JLabel label = new JLabel(caption);
        label.setBounds(0, y, 80, 20);
        JTextField field = new JTextField();
        field.setLayout(null);
        field.setBounds(90, y, 200, 20);
        label.setLabelFor(field);
        for (JButton b : inside) {
            b.setBounds(170, 0, 20, 20);
            field.add(b);
        }
        root.add(label);
        root.add(field);
        return field;
    }

    private static JButton named(String name) {
        JButton b = new JButton();
        b.setName(name);
        return b;
    }

    private static Locator clearIn(String fieldCaption) {
        Locator l = Locator.ofId("clear");
        l.scope = Collections.singletonList("text:" + fieldCaption);
        return l;
    }

    @Test
    void idClearInsideAFieldFindsFlatLafsOwnClearButton() {
        JButton flat = named("TextField.clearButton");
        field("Search", 0, flat);

        assertThat(resolver.resolve(clearIn("Search")).component).isSameAs(flat);
    }

    @Test
    void idClearInsideAFieldFindsTheAppsOwnXAndNotAnotherButtonBesideIt() {
        JButton x = named("clear");
        JButton calendar = new JButton();
        calendar.setToolTipText("Calendar");
        field("Delivery date", 0, x, calendar);

        assertThat(resolver.resolve(clearIn("Delivery date")).component).isSameAs(x);
    }

    @Test
    void aHiddenXIsNotFoundEvenWhenAnotherButtonShowsInTheField() {
        JButton x = named("clear");
        x.setVisible(false);
        JButton calendar = new JButton();
        calendar.setToolTipText("Calendar");
        field("Delivery date", 0, x, calendar);

        DriverException e = catchThrowableOfType(() -> resolver.resolve(clearIn("Delivery date")), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.NOT_FOUND);
    }

    @Test
    void aTextScopeTakesOnlyWhatLiesInsideThatField() {
        JButton searchX = named("TextField.clearButton");
        JButton customerX = named("TextField.clearButton");
        field("Search", 0, searchX);
        field("Customer", 30, customerX);

        assertThat(resolver.resolve(clearIn("Customer")).component).isSameAs(customerX);
        assertThat(resolver.resolve(clearIn("Search")).component).isSameAs(searchX);
    }

    @Test
    void theTextStepIsNotPartOfWhatAMatchRecords() {
        field("Search", 0, named("TextField.clearButton"));

        assertThat(resolver.resolve(clearIn("Search")).trail).doesNotContain("text:Search");
    }

    @Test
    void aTextScopeStepParses() {
        ScopeStep step = ScopeStep.parse("text:Search");
        assertThat(step.kind).isEqualTo(ScopeStep.Kind.TEXT);
        assertThat(step.title).isEqualTo("Search");
        assertThat(ScopeStep.text("Search")).isEqualTo("text:Search");
    }
}
