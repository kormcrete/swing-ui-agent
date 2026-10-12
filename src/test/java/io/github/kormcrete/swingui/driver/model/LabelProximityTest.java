package io.github.kormcrete.swingui.driver.model;

import org.junit.jupiter.api.Test;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.text.JTextComponent;
import java.awt.Component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LabelProximityTest {

    private JLabel label(String text, int x, int y) {
        JLabel l = new JLabel(text);
        l.setBounds(x, y, 80, 20);
        return l;
    }

    private JTextField field(String name, int x, int y) {
        JTextField f = new JTextField();
        f.setName(name); // test-only marker so the test can identify which field was picked
        f.setBounds(x, y, 120, 20);
        return f;
    }

    @Test
    void usesLabelForWhenSet() {
        JPanel p = new JPanel(null);
        JLabel l = label("Origin:", 0, 0);
        JTextField wanted = field("wanted", 200, 200); // far away — only getLabelFor links them
        JTextField near = field("near", 90, 0);
        l.setLabelFor(wanted);
        p.add(l); p.add(wanted); p.add(near);

        JTextComponent got = LabelProximity.findTextInputFor(p, "Origin", Visibility.TREE);
        assertThat(got.getName()).isEqualTo("wanted");
    }

    @Test
    void picksNearestToTheRightWhenNoLabelFor() {
        JPanel p = new JPanel(null);
        JLabel l = label("Name:", 0, 0);
        JTextField right = field("right", 90, 0);   // same row, just right
        JTextField below = field("below", 0, 40);   // below
        p.add(l); p.add(right); p.add(below);

        JTextComponent got = LabelProximity.findTextInputFor(p, "Name", Visibility.TREE);
        assertThat(got.getName()).isEqualTo("right");
    }

    @Test
    void colonAndWhitespaceTolerant() {
        JPanel p = new JPanel(null);
        p.add(label("  Status :", 0, 0));
        JTextField f = field("status", 90, 0);
        p.add(f);
        assertThat(LabelProximity.findTextInputFor(p, "Status", Visibility.TREE).getName()).isEqualTo("status");
    }

    @Test
    void throwsWhenLabelMissing() {
        JPanel p = new JPanel(null);
        p.add(label("Name:", 0, 0));
        p.add(field("f", 90, 0));
        assertThatThrownBy(() -> LabelProximity.findTextInputFor(p, "Nonexistent", Visibility.TREE))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("no field labeled 'Nonexistent'");
    }

    @Test
    void throwsAmbiguousWhenLabelDuplicated() {
        JPanel p = new JPanel(null);
        p.add(label("Date:", 0, 0));
        p.add(field("a", 90, 0));
        p.add(label("Date:", 0, 40));
        p.add(field("b", 90, 40));
        assertThatThrownBy(() -> LabelProximity.findTextInputFor(p, "Date", Visibility.TREE))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("ambiguous label 'Date'")
                .hasMessageContaining("setLabelFor");
    }

    @Test
    void resolvesDropdownAsInput() {
        JPanel p = new JPanel(null);
        JLabel l = label("Type:", 0, 0);
        JComboBox<String> combo = new JComboBox<String>();
        combo.setBounds(90, 0, 120, 20);
        l.setLabelFor(combo);
        p.add(l); p.add(combo);

        Component got = LabelProximity.findInputFor(p, "Type", Visibility.TREE);
        assertThat(got).isSameAs(combo);
    }

    @Test
    void findTextInputForRejectsDropdown() {
        JPanel p = new JPanel(null);
        JLabel l = label("Type:", 0, 0);
        JComboBox<String> combo = new JComboBox<String>();
        combo.setBounds(90, 0, 120, 20);
        l.setLabelFor(combo);
        p.add(l); p.add(combo);

        assertThatThrownBy(() -> LabelProximity.findTextInputFor(p, "Type", Visibility.TREE))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not a text input")
                .hasMessageContaining("JComboBox");
    }

    @Test
    void throwsUnsupportedWhenLabelForIsNonInputWidget() {
        JPanel p = new JPanel(null);
        JLabel l = label("Active:", 0, 0);
        JCheckBox check = new JCheckBox();
        check.setBounds(90, 0, 120, 20);
        l.setLabelFor(check);
        p.add(l); p.add(check);

        assertThatThrownBy(() -> LabelProximity.findInputFor(p, "Active", Visibility.TREE))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("unsupported field type")
                .hasMessageContaining("JCheckBox");
    }

    @Test
    void aHiddenLabelIsNotAField() {
        JPanel p = new JPanel(null);
        JLabel hidden = label("Customer:", 0, 0);
        hidden.setVisible(false);
        p.add(hidden);
        p.add(field("customer", 90, 0));
        assertThatThrownBy(() -> LabelProximity.findTextInputFor(p, "Customer", Visibility.TREE))
                .hasMessageContaining("no field labeled 'Customer'");
    }

    @Test
    void theIndexNamesEachInputByItsClosestLabel() {
        JPanel p = new JPanel(null);
        JLabel heading = label("Master data", 0, 0);
        JLabel customer = label("Customer:", 0, 30);
        JTextField field = field("customer", 90, 30);
        JLabel linked = label("City:", 0, 200);
        JTextField far = field("city", 300, 300);
        linked.setLabelFor(far);
        p.add(heading); p.add(customer); p.add(field); p.add(linked); p.add(far);
        java.util.Map<java.awt.Component, JLabel> index = LabelProximity.labelIndex(p, Visibility.TREE);
        assertThat(index.get(field)).isSameAs(customer);
        assertThat(index.get(far)).isSameAs(linked);
    }

    /**
     * A grid of figure cells, each a panel with its caption above its value: each value is named by its own cell's
     * caption, even where it starts a pixel left of that caption and the same-row rule would hand it to a neighbour.
     */
    @Test
    void aCaptionStackedAboveTheOnlyInputOfItsCellNamesThatInput() {
        JPanel grid = new JPanel(null);
        JLabel n = label("n", 11, 5);
        JTextField nValue = field("n", 10, 23);
        JLabel below = label("of which < min", 111, 5);
        JTextField belowValue = field("below", 110, 23);
        n.setSize(40, 16);
        below.setSize(80, 16);
        nValue.setSize(90, 22);
        belowValue.setSize(90, 22);
        JPanel left = cell(0, n, nValue);
        JPanel right = cell(100, below, belowValue);
        grid.add(left); grid.add(right);

        java.util.Map<java.awt.Component, JLabel> index = LabelProximity.labelIndex(grid, Visibility.TREE);
        assertThat(index.get(nValue)).as("n's value").isSameAs(n);
        assertThat(index.get(belowValue)).as("the neighbour's value").isSameAs(below);
        assertThat(LabelProximity.findTextInputFor(grid, "n", Visibility.TREE)).isSameAs(nValue);
        assertThat(LabelProximity.captionLabels(grid, Visibility.TREE)).contains(n, below);
    }

    @Test
    void aCellWithTwoInputsIsNotStacked() {
        JPanel grid = new JPanel(null);
        JLabel caption = label("Period", 10, 0);
        caption.setSize(60, 16);
        JTextField from = field("from", 10, 20);
        JTextField to = field("to", 10, 50);
        JPanel cell = new JPanel(null);
        cell.setBounds(0, 0, 200, 80);
        cell.add(caption); cell.add(from); cell.add(to);
        grid.add(cell);
        assertThat(LabelProximity.captionAbove(caption, Visibility.TREE)).isNull();
    }

    private static JPanel cell(int x, JLabel caption, JTextField value) {
        JPanel cell = new JPanel(null);
        cell.setBounds(x, 0, 100, 50);
        cell.add(caption);
        cell.add(value);
        return cell;
    }
}
