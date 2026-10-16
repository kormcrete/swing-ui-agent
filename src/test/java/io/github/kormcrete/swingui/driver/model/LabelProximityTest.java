package io.github.kormcrete.swingui.driver.model;

import org.junit.jupiter.api.Test;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.util.Map;

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

    // ---- a value label is named by the label that captions it --------------------------------

    @Test
    void aColonCaptionNamesTheLabelToItsRight() {
        JPanel p = new JPanel(null);
        JLabel status = label("Status:", 0, 0);
        JLabel open = label("Open", 90, 0);
        JLabel below = label("Shipped", 90, 40);
        p.add(status); p.add(open); p.add(below);

        Map<JLabel, JLabel> captions = LabelProximity.valueCaptions(p, Visibility.TREE);
        assertThat(captions).containsOnlyKeys(open).containsEntry(open, status);
        assertThat(LabelProximity.captionLabels(p, Visibility.TREE)).containsExactly(status);
    }

    @Test
    void twoLabelsOnARowWithoutAColonAreNotPaired() {
        JPanel p = new JPanel(null);
        p.add(label("Orders", 0, 0));
        p.add(label("12 shown", 90, 0));

        assertThat(LabelProximity.valueCaptions(p, Visibility.TREE)).isEmpty();
        assertThat(LabelProximity.captionLabels(p, Visibility.TREE)).isEmpty();
    }

    @Test
    void aColonLabelInAnotherParentIsNotACaption() {
        JPanel p = new JPanel(null);
        JPanel left = new JPanel(null);
        left.setBounds(0, 0, 90, 20);
        left.add(label("Status:", 0, 0));
        JPanel right = new JPanel(null);
        right.setBounds(90, 0, 90, 20);
        right.add(label("Open", 0, 0));
        p.add(left); p.add(right);

        assertThat(LabelProximity.valueCaptions(p, Visibility.TREE)).isEmpty();
    }

    @Test
    void aValueEndingInAColonIsNotAValue() {
        JPanel p = new JPanel(null);
        p.add(label("Name:", 0, 0));
        p.add(label("Address:", 90, 0));

        assertThat(LabelProximity.valueCaptions(p, Visibility.TREE)).isEmpty();
        assertThat(LabelProximity.captionLabels(p, Visibility.TREE)).isEmpty();
    }

    @Test
    void anEmptyValueIsNotSkipped() {
        JPanel p = new JPanel(null);
        p.add(label("Total:", 0, 0));
        p.add(label("", 90, 0));   // the value, not set yet: a column with a minimum width keeps it showing
        p.add(label("EUR", 180, 0));

        assertThat(LabelProximity.valueCaptions(p, Visibility.TREE)).as("EUR is not the total").isEmpty();
    }

    @Test
    void aHiddenValueLeavesItsCaptionANode() {
        JPanel p = new JPanel(null);
        JLabel total = label("Total", 0, 0);
        JLabel amount = label("€7.32", 90, 0);
        total.setLabelFor(amount);
        amount.setVisible(false);
        JLabel status = label("Status:", 0, 40);
        JLabel open = label("Open", 90, 40);
        open.setVisible(false);
        p.add(total); p.add(amount); p.add(status); p.add(open);

        assertThat(LabelProximity.valueCaptions(p, Visibility.TREE)).isEmpty();
        assertThat(LabelProximity.captionLabels(p, Visibility.TREE)).isEmpty();
    }

    @Test
    void aLabelForLinkWinsOverTheColonRule() {
        JPanel p = new JPanel(null);
        JLabel status = label("Status:", 0, 0);
        JLabel open = label("Open", 90, 0);
        JLabel state = label("State", 0, 100);
        state.setLabelFor(open);
        JLabel total = label("Total:", 0, 40);
        JLabel draft = label("Draft", 90, 40);
        JLabel amount = label("€7.32", 300, 300);   // far away: only getLabelFor links them
        total.setLabelFor(amount);
        p.add(status); p.add(open); p.add(state); p.add(total); p.add(draft); p.add(amount);

        Map<JLabel, JLabel> captions = LabelProximity.valueCaptions(p, Visibility.TREE);
        assertThat(captions).containsOnlyKeys(open, amount);
        assertThat(captions.get(open)).isSameAs(state);
        assertThat(captions.get(amount)).isSameAs(total);
    }

    @Test
    void aColonCaptionOfAnInputCaptionsNoLabel() {
        JPanel p = new JPanel(null);
        p.add(label("Customer:", 0, 0));
        p.add(field("customer", 90, 0));
        p.add(label("(optional)", 220, 0));

        assertThat(LabelProximity.valueCaptions(p, Visibility.TREE)).isEmpty();
    }

    @Test
    void theClosestOfTwoColonCaptionsWins() {
        JPanel p = new JPanel(null);
        JLabel far = label("Order:", 0, 0);
        JLabel near = label("Status:", 50, 40);
        JLabel open = label("<html>Open<br>since today</html>", 90, 0);
        open.setSize(80, 60);   // two lines tall: both captions sit on its row, neither on the other's
        p.add(far); p.add(near); p.add(open);

        Map<JLabel, JLabel> captions = LabelProximity.valueCaptions(p, Visibility.TREE);
        assertThat(captions).containsOnlyKeys(open);
        assertThat(captions.get(open)).isSameAs(near);
    }
}
