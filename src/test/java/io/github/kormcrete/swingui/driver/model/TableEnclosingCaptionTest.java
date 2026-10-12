package io.github.kormcrete.swingui.driver.model;

import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import java.awt.Component;
import java.util.IdentityHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A label linked by {@code setLabelFor} to a panel around a table, not to the table inside, captions that table,
 * unless the panel holds another table.
 */
class TableEnclosingCaptionTest {

    @Test
    void theLabelOfAPanelAroundOneTableCaptionsIt() {
        JTable table = new JTable(1, 1);
        JPanel panel = new JPanel();
        panel.add(new JScrollPane(table));
        JLabel title = new JLabel("Changes");
        title.setLabelFor(panel);

        assertThat(Names.enclosingLabel(table, index(panel, title))).isSameAs(title);
    }

    @Test
    void aPanelAroundTwoTablesCaptionsNeither() {
        JTable table = new JTable(1, 1);
        JPanel panel = new JPanel();
        panel.add(new JScrollPane(table));
        panel.add(new JScrollPane(new JTable(1, 1)));
        JLabel title = new JLabel("Changes");

        assertThat(Names.enclosingLabel(table, index(panel, title))).isNull();
        assertThat(Names.enclosingLabel(table, new IdentityHashMap<Component, JLabel>())).isNull();
    }

    private static Map<Component, JLabel> index(Component labelled, JLabel label) {
        Map<Component, JLabel> index = new IdentityHashMap<Component, JLabel>();
        index.put(labelled, label);
        return index;
    }
}
