package io.github.kormcrete.swingui.driver.model;

import io.github.kormcrete.swingui.driver.window.WindowRoot;
import org.junit.jupiter.api.Test;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import java.awt.BorderLayout;

import static org.assertj.core.api.Assertions.assertThat;

/** A snapshot limited by depth, and compacted - the cheap reads that avoid a full-tree dump. */
class SnapshotScopeTest {

    private final Refs refs = new Refs(c -> true);
    private final FakeWindows windows = new FakeWindows();
    private final Snapshot snapshot = new Snapshot(Visibility.TREE, new Names(Visibility.TREE), refs, windows);
    private final JPanel form = new JPanel(null);
    private final JPanel header = new JPanel(new BorderLayout());
    private final JTabbedPane tabs = new JTabbedPane();

    SnapshotScopeTest() {
        JButton save = new JButton("Save");
        save.setBounds(0, 0, 100, 20);
        form.add(save);

        Object[][] data = new Object[25][];
        for (int i = 0; i < data.length; i++) data[i] = new Object[] {"26-" + i, "open"};
        JTable table = new JTable(data, new Object[] {"No.", "Status"});
        header.setBorder(BorderFactory.createTitledBorder("Header"));
        header.add(new JScrollPane(table));
        header.setBounds(0, 40, 400, 200);

        JPanel customerTab = new JPanel(new BorderLayout());
        customerTab.add(header);
        JPanel inner = new JPanel();
        inner.add(new JCheckBox("Active", true));
        customerTab.add(inner, BorderLayout.SOUTH);
        tabs.addTab("Customer", customerTab);
        tabs.addTab("Line items", new JPanel());
        customerTab.setVisible(true);
        tabs.setBounds(0, 300, 400, 300);
        form.add(tabs);

        windows.roots.add(new WindowRoot(form, "dialog", "Order", true, null, true));
    }

    @Test
    void depthOneIsTheWindowsAloneMarkedTruncated() {
        Node app = snapshot.build(null, null, 400, 20, 1);
        assertThat(app.children).hasSize(1);
        Node dialog = app.children.get(0);
        assertThat(dialog.children).isNull();
        assertThat(dialog.truncated).isTrue();
    }

    @Test
    void depthCountsNodesNotSwingContainers() {
        Node dialog = snapshot.build(null, null, 400, 20, 3).children.get(0);
        // level 2: the button and the tabs; level 3: the tabs' two tab nodes - but not the Customer tab's content.
        assertThat(dialog.children).extracting(n -> n.role).containsExactly("button", "tabs");
        Node t = dialog.find("tabs", null);
        assertThat(t.children).extracting(n -> n.name).containsExactly("Customer", "Line items");
        Node customer = t.children.get(0);
        assertThat(customer.children).isNull();
        assertThat(customer.truncated).isTrue();
        assertThat(dialog.find("section", "Header")).isNull();
    }

    @Test
    void anUnlimitedDepthIsTheSnapshotWithoutADepth() {
        Node limited = snapshot.build(null, null, 400, 20, Integer.MAX_VALUE);
        Node plain = snapshot.build(null, null, 400, 20);
        assertThat(limited.count()).isEqualTo(plain.count());
        assertThat(limited.find("checkbox", "Active")).isNotNull();
    }

    @Test
    void underAnElementTheDepthStartsAtThatElement() {
        Node app = snapshot.build(null, header, 400, 20, 1);
        assertThat(app.children).hasSize(1);
        Node section = app.children.get(0);
        assertThat(section.role).isEqualTo("section");
        assertThat(section.name).isEqualTo("Header");
        assertThat(section.children).isNull();
        assertThat(section.truncated).isTrue();

        Node deeper = snapshot.build(null, header, 400, 20, 2).children.get(0);
        assertThat(deeper.find("table", null)).isNotNull();
    }

    @Test
    void compactDropsRowsButKeepsWhatATableHolds() {
        Node table = snapshot.build(null, header, 400, 20).compact().find("table", null);
        assertThat(table.rows).isNull();
        assertThat(table.columns).containsExactly("No.", "Status");
        assertThat(table.rowCount).isEqualTo(25);
        assertThat(table.ref).startsWith("e");
    }
}
