package io.github.kormcrete.swingui.driver.model;

import org.junit.jupiter.api.Test;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import java.awt.event.ActionEvent;

import static org.assertj.core.api.Assertions.assertThat;

/** Name order, on unshown trees with explicit bounds. */
class NamesTest {

    private final Names names = new Names(Visibility.TREE);
    private final JPanel root = new JPanel(null);

    private JTextField field(int x, int y) {
        JTextField f = new JTextField();
        f.setBounds(x, y, 120, 20);
        root.add(f);
        return f;
    }

    private JLabel label(String text, int x, int y) {
        JLabel l = new JLabel(text);
        l.setBounds(x, y, 80, 20);
        root.add(l);
        return l;
    }

    @Test
    void anExplicitNameComesFirst() {
        JButton b = new JButton("Log in");
        b.setName("login.button");
        assertThat(names.nameOf(b, root)).isEqualTo("login.button");
    }

    @Test
    void ownTextIsCleaned() {
        assertThat(names.nameOf(new JButton("<html><b>Save</b>&nbsp;</html>"), root)).isEqualTo("Save");
    }

    @Test
    void aLinkedLabelThenTheNearestLabelName() {
        JTextField linked = field(300, 300);
        label("City:", 0, 200).setLabelFor(linked);
        JTextField near = field(90, 0);
        label("Customer:", 0, 0);
        assertThat(names.nameOf(linked, root)).isEqualTo("City");
        assertThat(names.nameOf(near, root)).isEqualTo("Customer");
    }

    @Test
    void aHiddenLabelIsSkippedAndTheTooltipNames() {
        JTextField f = field(90, 0);
        f.setToolTipText("Remark");
        label("Customer:", 0, 0).setVisible(false);
        assertThat(names.nameOf(f, root)).isEqualTo("Remark");
    }

    @Test
    void aTableIsNamedByTheTitledBorderAroundItsScrollPane() {
        JTable table = new JTable(1, 1);
        JPanel box = new JPanel();
        box.setBorder(BorderFactory.createTitledBorder("Orders"));
        box.add(new JScrollPane(table));
        root.add(box);
        assertThat(names.nameOf(table, root)).isEqualTo("Orders");
        assertThat(Names.sectionTitle(box)).isEqualTo("Orders");
    }

    @Test
    void anIconButtonIsNamedByItsAction() {
        JButton b = new JButton(new AbstractAction("Duplicate") {
            @Override public void actionPerformed(ActionEvent e) { }
        });
        b.setHideActionText(true);
        assertThat(b.getText()).isNullOrEmpty();
        assertThat(names.nameOf(b, root)).isEqualTo("Duplicate");
    }

    @Test
    void nothingToNameIsNull() {
        assertThat(names.nameOf(new JPanel(), root)).isNull();
        assertThat(Names.cleanLabel(" Customer : ")).isEqualTo("Customer");
    }

    @Test
    void anUntitledTabIsNamedByItsTooltip() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(null, null, new JPanel(), "Archived Orders");
        tabs.addTab("", null, new JPanel(), " Orders ");
        tabs.addTab("Title", null, new JPanel(), "Tooltip");
        tabs.addTab(null, new JPanel());
        assertThat(Names.tabTitle(tabs, 0)).isEqualTo("Archived Orders");
        assertThat(Names.tabTitle(tabs, 1)).isEqualTo("Orders");
        assertThat(Names.tabTitle(tabs, 2)).as("a real title wins over the tooltip").isEqualTo("Title");
        assertThat(Names.tabTitle(tabs, 3)).isNull();
    }
}
