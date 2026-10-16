package io.github.kormcrete.swingui.driver.model;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.window.WindowRoot;
import org.junit.jupiter.api.Test;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class SnapshotTest {

    private final Refs refs = new Refs(c -> true);
    private final FakeWindows windows = new FakeWindows();
    private final Snapshot snapshot = new Snapshot(Visibility.TREE, new Names(Visibility.TREE), refs, windows);
    private final JPanel form = new JPanel(null);
    private final JPanel orders = new JPanel(new BorderLayout());
    private final JTable table;
    private final JTabbedPane tabs = new JTabbedPane();

    SnapshotTest() {
        JLabel customer = new JLabel("Customer:");
        customer.setBounds(0, 0, 80, 20);
        form.add(customer);
        JTextField field = new JTextField("Test Customer");
        field.setBounds(90, 0, 120, 20);
        form.add(field);
        JButton save = new JButton("Save");
        save.setBounds(0, 40, 100, 20);
        form.add(save);
        JButton hidden = new JButton("Hidden");
        hidden.setVisible(false);
        form.add(hidden);
        JPasswordField password = new JPasswordField("secret");
        password.setBounds(300, 520, 100, 20);
        form.add(password);

        Object[][] data = new Object[25][];
        for (int i = 0; i < data.length; i++) data[i] = new Object[] {"26-" + i, i % 2 == 0 ? "open" : "done"};
        table = new JTable(data, new Object[] {"No.", "Status"});
        orders.setBorder(BorderFactory.createTitledBorder("Orders"));
        orders.add(new JScrollPane(table));
        orders.setBounds(0, 80, 400, 200);
        form.add(orders);

        JPanel customerTab = new JPanel();
        customerTab.add(new JCheckBox("Active", true));
        tabs.addTab("Customer", customerTab);
        tabs.addTab("Line items", new JPanel());
        customerTab.setVisible(true); // a laid-out tabbed pane shows its selected tab
        tabs.setBounds(0, 300, 280, 200);
        form.add(tabs);

        JPanel folder = new JPanel();
        folder.setName("folder:orders");
        folder.addMouseListener(new MouseAdapter() {});
        folder.setBounds(500, 0, 100, 30);
        form.add(folder);

        windows.roots.add(new WindowRoot(form, "dialog", "Order editor – Order ORD-1640", true, null, true));
    }

    private Node build() {
        return snapshot.build(null, null, 400, 20);
    }

    @Test
    void theWindowRootCarriesTitleModalityAndFocus() {
        Node dialog = build().children.get(0);
        assertThat(dialog.role).isEqualTo("dialog");
        assertThat(dialog.name).isEqualTo("Order editor – Order ORD-1640");
        assertThat(dialog.modal).isTrue();
        assertThat(dialog.state).contains("focused");
        assertThat(dialog.ref).startsWith("e");
    }

    @Test
    void fieldsAndButtonsAreNamedAndReffed() {
        Node app = build();
        Node field = app.find("text", "Customer");
        assertThat(field.value).isEqualTo("Test Customer");
        assertThat(field.state).contains("editable");
        assertThat(app.find("button", "Save").ref).matches("e\\d+");
        assertThat(app.find("button", "Hidden")).isNull();
        Node password = app.find("password", null);
        assertThat(password.value).isNull();
    }

    @Test
    void aTitledSectionHoldsItsCappedTable() {
        Node section = build().find("section", "Orders");
        Node t = section.find("table", "Orders");
        assertThat(t.columns).containsExactly("No.", "Status");
        assertThat(t.rows).hasSize(20);
        assertThat(t.rows.get(2)).containsExactly("26-2", "open");
        assertThat(t.rowCount).isEqualTo(25);
        assertThat(t.truncated).isTrue();
        assertThat(t.approx).isNull();
    }

    @Test
    void tabsListEveryTitleAndOnlyTheSelectedContent() {
        Node t = build().find("tabs", null);
        assertThat(t.children).extracting(n -> n.name).containsExactly("Customer", "Line items");
        assertThat(t.children.get(0).state).contains("selected");
        assertThat(t.children.get(0).find("checkbox", "Active").state).contains("checked");
        assertThat(t.children.get(1).children).isNull();
    }

    @Test
    void aPanelWithAnAppMouseListenerIsClickable() {
        assertThat(build().find("clickable", "folder:orders")).isNotNull();
    }

    @Test
    void theNodeBudgetTruncates() {
        Node app = snapshot.build(null, null, 3, 20);
        assertThat(app.count() - 1).isLessThanOrEqualTo(3);
        assertThat(anyTruncated(app)).isTrue();
    }

    @Test
    void aNewSnapshotMakesOldRefsStale() {
        String old = build().find("button", "Save").ref;
        build();
        DriverException e = catchThrowableOfType(() -> refs.get(old), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("stale ref " + old);
    }

    /**
     * A window filter that matches no root builds no tree, so it must not cost a caller the refs it still holds:
     * refs stay valid until the next snapshot that actually builds a tree.
     */
    @Test
    void anEmptySnapshotLeavesExistingRefsValid() {
        String old = build().find("button", "Save").ref;

        Node empty = snapshot.build("main", null, 400, 20); // no "main" root in this fixture

        assertThat(empty.children).isNull();
        refs.get(old); // must not throw "stale ref"
    }

    @Test
    void theWindowFilterAndTheSubtreeNarrowTheSnapshot() {
        assertThat(snapshot.build("dialog", null, 400, 20).children).hasSize(1);
        assertThat(snapshot.build("main", null, 400, 20).children).isNull();
        Node sub = snapshot.build(null, orders, 400, 20);
        assertThat(sub.children).extracting(n -> n.role).containsExactly("section");
    }

    @Test
    void describeReadsACellOrATab() {
        Node cell = snapshot.describe(table, null, RowSpec.of(Collections.<String, Object>singletonMap("index", 2)), "Status", 20);
        assertThat(cell.role).isEqualTo("cell");
        assertThat(cell.value).isEqualTo("open");
        Node tab = snapshot.describe(tabs, 1, null, null, 20);
        assertThat(tab.role).isEqualTo("tab");
        assertThat(tab.name).isEqualTo("Line items");
    }

    @Test
    void describeReadsATreeItemAsItsRowWithTheRowsStates() {
        javax.swing.tree.DefaultMutableTreeNode root = new javax.swing.tree.DefaultMutableTreeNode("root");
        javax.swing.tree.DefaultMutableTreeNode a = new javax.swing.tree.DefaultMutableTreeNode("A");
        a.add(new javax.swing.tree.DefaultMutableTreeNode("B"));
        root.add(a);
        javax.swing.JTree tree = new javax.swing.JTree(root);
        tree.setRootVisible(false);
        tree.expandRow(0);
        tree.setSelectionRow(0);

        Node branch = snapshot.describe(tree, null, null, null, Collections.singletonList("A"), 20);
        assertThat(branch.role).isEqualTo("item");
        assertThat(branch.name).isEqualTo("A");
        assertThat(branch.state).containsExactlyInAnyOrder("selected", "expanded");
        Node leaf = snapshot.describe(tree, null, null, null, java.util.Arrays.asList("A", "B"), 20);
        assertThat(leaf.role).isEqualTo("item");
        assertThat(leaf.name).isEqualTo("A > B");
        assertThat(leaf.state).containsExactly("leaf");
        // Without an item the same tree reads as a whole: the item argument is what narrows it.
        assertThat(snapshot.describe(tree, null, null, null, null, 20).role).isEqualTo("tree");
        assertThat(snapshot.describe(tree, null, null, null, 20).items).containsExactly("A", "A > B");
    }

    private static boolean anyTruncated(Node n) {
        if (Boolean.TRUE.equals(n.truncated)) return true;
        if (n.children != null) {
            for (Node c : n.children) if (anyTruncated(c)) return true;
        }
        return false;
    }

    /**
     * "Customer:" names the field beside it, so it is not a node of its own - the field
     * already carries that name and a second node would only repeat it. Text that names nothing is.
     */
    @Test
    void staticTextIsANodeOnlyWhenItNamesNothing() {
        JLabel counter = new JLabel("50 of 407");
        counter.setBounds(0, 560, 120, 20);
        form.add(counter);

        Node app = snapshot.build(null, null, 400, 20);

        assertThat(app.find("label", "50 of 407")).as("the count strip").isNotNull();
        assertThat(app.find("label", "Customer")).as("names the field, so not its own node").isNull();
        assertThat(app.find("label", "Customer:")).isNull();
        assertThat(app.find("text", "Customer").value).isEqualTo("Test Customer");
    }

    /**
     * A named label keeps its component name as the node's name and carries the text the reader sees as its
     * value - how a JOptionPane's message, whose label is named "OptionPane.label", stays readable. A label named by
     * its own text carries no value.
     */
    @Test
    void aNamedLabelsTextIsItsValue() {
        JLabel message = new JLabel("The record is locked");
        message.setName("OptionPane.label");
        message.setBounds(0, 580, 200, 20);
        form.add(message);
        JLabel counter = new JLabel("50 of 407");
        counter.setBounds(0, 560, 120, 20);
        form.add(counter);

        Node app = snapshot.build(null, null, 400, 20);

        assertThat(app.find("label", "OptionPane.label").value).isEqualTo("The record is locked");
        assertThat(app.find("label", "50 of 407").value).isNull();
    }

    /**
     * A value label whose caption sits beside it (Total, €7.32) is one node, named by the caption and reading its
     * text as the value: the caption would only repeat that name. Two rows of one totals panel stay two nodes.
     */
    @Test
    void aValueLabelIsOneNodeNamedByItsCaption() {
        JPanel totals = new JPanel(null);
        totals.setBounds(0, 600, 200, 50);
        JLabel subtotalCaption = new JLabel("Subtotal");
        subtotalCaption.setBounds(0, 0, 80, 20);
        JLabel subtotal = new JLabel("€6.10");
        subtotal.setBounds(100, 0, 80, 20);
        subtotalCaption.setLabelFor(subtotal);
        JLabel totalCaption = new JLabel("Total");
        totalCaption.setBounds(0, 25, 80, 20);
        JLabel total = new JLabel("€7.32");
        total.setBounds(100, 25, 80, 20);
        totalCaption.setLabelFor(total);
        for (JLabel l : new JLabel[] {subtotalCaption, subtotal, totalCaption, total}) totals.add(l);
        form.add(totals);

        Node app = snapshot.build(null, null, 400, 20);

        assertThat(app.find("label", "Total").value).isEqualTo("€7.32");
        assertThat(app.find("label", "Subtotal").value).isEqualTo("€6.10");
        assertThat(named(app, "Total")).as("the caption is no node of its own").isEqualTo(1);
        assertThat(named(app, "Subtotal")).isEqualTo(1);
        assertThat(app.find("label", "€7.32")).isNull();
    }

    private static int named(Node n, String name) {
        int count = name.equals(n.name) ? 1 : 0;
        if (n.children != null) {
            for (Node c : n.children) count += named(c, name);
        }
        return count;
    }
}
