package io.github.kormcrete.swingui.driver.model;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollBar;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import java.awt.event.MouseAdapter;

import static org.assertj.core.api.Assertions.assertThat;

class RolesTest {

    @Test
    void swingWidgetsHaveTheirRoles() {
        assertThat(Roles.of(new JButton("OK"))).isEqualTo("button");
        assertThat(Roles.of(new JCheckBox("Active"))).isEqualTo("checkbox");
        assertThat(Roles.of(new JToggleButton("An"))).isEqualTo("toggle");
        assertThat(Roles.of(new JPasswordField())).isEqualTo("password");
        assertThat(Roles.of(new JTextField())).isEqualTo("text");
        assertThat(Roles.of(new JTabbedPane())).isEqualTo("tabs");
    }

    @Test
    void anAppMouseListenerMakesAComponentClickable() {
        JPanel folder = new JPanel();
        folder.addMouseListener(new MouseAdapter() {});
        assertThat(Roles.of(folder)).isEqualTo("clickable");
    }

    @Test
    void lookAndFeelAndTooltipListenersDoNot() {
        JLabel tipped = new JLabel("x");
        tipped.setToolTipText("Help");
        // The tooltip manager's listener must not read as the app's: a label stays a label.
        assertThat(Roles.of(tipped)).isEqualTo("label");
        assertThat(Roles.of(new JScrollBar())).isNull();
        assertThat(Roles.of(new JPanel())).isNull();
    }

    /** Non-blank static text, plain or HTML, is a {@code label}; an empty or blank label is not a node. */
    @Test
    void staticTextBecomesALabel() {
        assertThat(Roles.of(new JLabel("50 of 407"))).isEqualTo("label");
        assertThat(Roles.of(new JLabel())).isNull();
        assertThat(Roles.of(new JLabel("   "))).isNull();
        assertThat(Roles.of(new JLabel("<html><b>Total</b></html>"))).isEqualTo("label");
    }

    /** A label the app made clickable keeps the role that says so; 'label' means static text. */
    @Test
    void aLabelTheAppMadeClickableStaysClickable() {
        JLabel link = new JLabel("More...");
        link.addMouseListener(new MouseAdapter() {});
        assertThat(Roles.of(link)).isEqualTo("clickable");
    }

    @Test
    void labelIsAKnownLocatorRole() {
        assertThat(Roles.ALL).contains("label");
    }

    @Test
    void onlyContainersOfTargetsAreDescended() {
        assertThat(Roles.descends("clickable")).isTrue();
        assertThat(Roles.descends("menubar")).isTrue();
        // a button mounted inside a text field (a clear X) is the field's child
        assertThat(Roles.descends("text")).isTrue();
        assertThat(Roles.descends("table")).isFalse();
        assertThat(Roles.descends("button")).isFalse();
    }
}
