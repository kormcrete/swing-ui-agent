package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Expectation;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Resolved;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import java.awt.Component;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ExpectationsTest {

    private static Resolved on(Component c, String role, String name) {
        return new Resolved(c, null, role, name, "main", Collections.<String>emptyList(), Locator.of(role, name));
    }

    private static Expectation value(String v) {
        Expectation e = new Expectation();
        e.value = v;
        return e;
    }

    private static Expectation state(String... s) {
        Expectation e = new Expectation();
        e.state = Arrays.asList(s);
        return e;
    }

    @Test
    void aFieldsValue() {
        Resolved field = on(new JTextField("ORD-1640"), "text", "Order no.");
        assertThat(Expectations.check(field, value("ORD-1640"))).isNull();
        assertThat(Expectations.check(field, value("x"))).isEqualTo("expected value 'x' but was 'ORD-1640'");
    }

    @Test
    void aCheckBoxsState() {
        JCheckBox box = new JCheckBox("Active", true);
        Resolved r = on(box, "checkbox", "Active");
        assertThat(Expectations.check(r, state("checked", "!disabled"))).isNull();
        assertThat(Expectations.check(r, state("!checked"))).contains("expected state !checked");
        box.setEnabled(false);
        assertThat(Expectations.check(r, state("disabled", "checked"))).isNull();
    }

    /** {@code expect state:["checked"]} holds alike for a check box, a radio button, a toggle and a Boolean cell. */
    @Test
    void checkedIsConsistentAcrossCheckboxRadioToggleAndABooleanCell() {
        assertThat(Expectations.check(on(new JCheckBox("Active", true), "checkbox", "Active"),
                state("checked"))).isNull();
        assertThat(Expectations.check(on(new JRadioButton("Active", true), "radio", "Active"),
                state("checked"))).isNull();

        JToggleButton toggle = new JToggleButton("Notes", true);
        Resolved toggleResolved = on(toggle, "toggle", "Notes");
        assertThat(Expectations.check(toggleResolved, state("checked"))).isNull();
        assertThat(Expectations.check(toggleResolved, state("selected"))).isNull(); // a toggle is also "selected"

        JTable table = new JTable(new Object[][] {{"26-1", Boolean.TRUE}, {"26-2", Boolean.FALSE}},
                new Object[] {"No.", "Notes"}) {
            @Override
            public Class<?> getColumnClass(int column) {
                return column == 1 ? Boolean.class : String.class;
            }
        };
        assertThat(Expectations.check(cell(table, 0), state("checked"))).isNull();
        assertThat(Expectations.check(cell(table, 1), state("!checked"))).isNull();
    }

    private static Resolved cell(JTable table, int rowIndex) {
        Locator locator = Locator.of("table", "Orders");
        locator.row = Collections.<String, Object>singletonMap("index", rowIndex);
        locator.column = "Notes";
        return new Resolved(table, null, "table", "Orders", "main", Collections.<String>emptyList(), locator);
    }

    private static Resolved row(JTable table, int rowIndex) {
        Locator locator = Locator.of("table", "Orders");
        locator.row = Collections.<String, Object>singletonMap("index", rowIndex);
        return new Resolved(table, null, "table", "Orders", "main", Collections.<String>emptyList(), locator);
    }

    private static Expectation selected(Integer... rows) {
        Expectation e = new Expectation();
        e.selected = Arrays.asList(rows);
        return e;
    }

    private static JTable threeRows() {
        return new JTable(new Object[][] {{"a"}, {"b"}, {"c"}}, new Object[] {"Order no."});
    }

    // ---- selection: the one thing about a row an author wants to assert ------------------

    @Test
    void theSelectedRowsOfATableAreAssertable() {
        JTable table = threeRows();
        table.setRowSelectionInterval(0, 0);
        table.addRowSelectionInterval(2, 2);

        Resolved r = on(table, "table", "Orders");
        assertThat(Expectations.check(r, selected(0, 2))).isNull();
        assertThat(Expectations.check(r, selected(2, 0))).as("order does not matter").isNull();
        assertThat(Expectations.check(r, selected(0)))
                .isEqualTo("expected selected rows [0] but they were [0, 2]");
    }

    @Test
    void nothingSelectedIsAssertableToo() {
        JTable table = threeRows();

        assertThat(Expectations.check(on(table, "table", "Orders"), selected())).isNull();
    }

    @Test
    void aRowWithNoColumnReportsItsOwnSelection() {
        JTable table = threeRows();
        table.setRowSelectionInterval(1, 1);

        assertThat(Expectations.check(row(table, 1), state("selected"))).isNull();
        assertThat(Expectations.check(row(table, 1), state("focused"))).as("the lead row").isNull();
        assertThat(Expectations.check(row(table, 0), state("!selected"))).isNull();
        assertThat(Expectations.check(row(table, 0), state("selected")))
                .isEqualTo("expected state selected but the states were []");
    }

    @Test
    void selectionNeedsSomethingWithRows() {
        DriverException e = catchThrowableOfType(
                () -> Expectations.check(on(new JButton("Save"), "button", "Save"), selected(0)),
                DriverException.class);

        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("no selectable rows");
    }

    @Test
    void selectedIsOneOfTheChoicesAndNotTwo() {
        Expectation e = selected(0);
        e.text = "also this";

        DriverException thrown = catchThrowableOfType(e::validate, DriverException.class);

        assertThat(thrown.getMessage()).contains("exactly one of").contains("selected");
    }

    @Test
    void aButtonsText() {
        Expectation e = new Expectation();
        e.text = "Save";
        assertThat(Expectations.check(on(new JButton("Save"), "button", "Save"), e)).isNull();
    }

    @Test
    void tableRowsAndCells() {
        JTable table = new JTable(new Object[][] {{"26-1", "open"}, {"26-2", "done"}, {"26-3", "open"}},
                new Object[] {"No.", "Status"});
        Resolved r = on(table, "table", "Orders");
        Expectation rows = new Expectation();
        rows.rows = new Expectation.Count();
        rows.rows.op = "eq";
        rows.rows.value = 2;
        assertThat(Expectations.check(r, rows)).isEqualTo("expected rows eq 2 but there are 3");
        Expectation cell = new Expectation();
        cell.cell = new Expectation.Cell();
        cell.cell.row = Collections.<String, Object>singletonMap("No.", "26-2");
        cell.cell.column = "Status";
        cell.cell.expected = "done";
        assertThat(Expectations.check(r, cell)).isNull();
    }

    @Test
    void valuesThatDoNotExistOrMustNotBeReadAreBadInput() {
        assertThat(catchThrowableOfType(() -> Expectations.check(on(new JLabel("x"), null, "x"), value("x")),
                DriverException.class).reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(catchThrowableOfType(() -> Expectations.check(on(new JPasswordField("secret"), "password", null),
                value("secret")), DriverException.class).getMessage()).contains("password");
    }

    @Test
    void aTreeItemsStateIsItsRowsState() {
        javax.swing.tree.DefaultMutableTreeNode root = new javax.swing.tree.DefaultMutableTreeNode("root");
        javax.swing.tree.DefaultMutableTreeNode a = new javax.swing.tree.DefaultMutableTreeNode("A");
        a.add(new javax.swing.tree.DefaultMutableTreeNode("B"));
        root.add(a);
        javax.swing.JTree tree = new javax.swing.JTree(root);
        tree.setRootVisible(false);
        Locator l = Locator.of("tree", null);
        l.item = java.util.Collections.singletonList("A");
        Resolved r = new Resolved(tree, null, "tree", null, "main", java.util.Collections.<String>emptyList(), l);
        Expectation expanded = new Expectation();
        expanded.state = java.util.Collections.singletonList("expanded");

        assertThat(Expectations.check(r, expanded)).contains("expanded");
        tree.expandRow(0);
        assertThat(Expectations.check(r, expanded)).isNull();
    }

    private static Expectation absent() {
        Expectation e = new Expectation();
        e.absent = true;
        return e;
    }

    private static javax.swing.table.DefaultTableModel cart() {
        return new javax.swing.table.DefaultTableModel(new Object[][] {
                {"A4 paper, 500 sheets", "3"}, {"Gel pen, black", "2"}}, new Object[] {"Product", "Qty"});
    }

    private static Resolved cartLine(JTable table, String product, String column) {
        Locator l = Locator.of("table", "Cart");
        l.row = Collections.<String, Object>singletonMap("Product", product);
        l.column = column;
        return new Resolved(table, null, "table", "Cart", "main", Collections.<String>emptyList(), l);
    }

    /** A row is absent once the table no longer has it, though the table is still there. */
    @Test
    void aRemovedRowIsAbsent() {
        javax.swing.table.DefaultTableModel model = cart();
        JTable table = new JTable(model);
        Resolved line = cartLine(table, "Gel pen, black", null);
        assertThat(Expectations.check(line, absent())).contains("still there");
        model.removeRow(1);
        assertThat(Expectations.check(line, absent())).isNull();
    }

    @Test
    void aCellOfARemovedRowIsAbsent() {
        javax.swing.table.DefaultTableModel model = cart();
        JTable table = new JTable(model);
        Resolved qty = cartLine(table, "Gel pen, black", "Qty");
        assertThat(Expectations.check(qty, absent())).contains("still there");
        model.removeRow(1);
        assertThat(Expectations.check(qty, absent())).isNull();
    }

    @Test
    void twoRowsThatMatchAreNotAbsent() {
        javax.swing.table.DefaultTableModel model = cart();
        model.addRow(new Object[] {"Gel pen, black", "5"});
        Resolved line = cartLine(new JTable(model), "Gel pen, black", null);
        assertThat(Expectations.check(line, absent())).contains("several");
    }

    @Test
    void aRemovedTreeItemIsAbsent() {
        javax.swing.tree.DefaultMutableTreeNode root = new javax.swing.tree.DefaultMutableTreeNode("Products");
        javax.swing.tree.DefaultMutableTreeNode pens = new javax.swing.tree.DefaultMutableTreeNode("Pens");
        root.add(new javax.swing.tree.DefaultMutableTreeNode("Paper"));
        root.add(pens);
        javax.swing.tree.DefaultTreeModel model = new javax.swing.tree.DefaultTreeModel(root);
        javax.swing.JTree tree = new javax.swing.JTree(model);
        tree.setRootVisible(false);
        Locator l = Locator.of("tree", "Categories");
        l.item = Collections.singletonList("Pens");
        Resolved r = new Resolved(tree, null, "tree", "Categories", "main", Collections.<String>emptyList(), l);
        assertThat(Expectations.check(r, absent())).contains("still there");
        model.removeNodeFromParent(pens);
        assertThat(Expectations.check(r, absent())).isNull();
    }

    private static Resolved entry(javax.swing.JList<String> list, String text) {
        Locator l = Locator.of("list", "Customers");
        l.item = Collections.singletonList(text);
        return new Resolved(list, null, "list", "Customers", "main", Collections.<String>emptyList(), l);
    }

    @Test
    void aRemovedListEntryIsAbsent() {
        javax.swing.DefaultListModel<String> model = new javax.swing.DefaultListModel<String>();
        model.addElement("Ada Shop Ltd");
        model.addElement("Birch & Co");
        Resolved birch = entry(new javax.swing.JList<String>(model), "Birch & Co");
        assertThat(Expectations.check(birch, absent())).contains("still there");
        model.removeElement("Birch & Co");
        assertThat(Expectations.check(birch, absent())).isNull();
    }

    /** {@code shown} is {@code state ["!hidden"]}: on a list entry it needs the entry, not only the list. */
    @Test
    void aRemovedListEntryIsNotShown() {
        javax.swing.DefaultListModel<String> model = new javax.swing.DefaultListModel<String>();
        model.addElement("Ada Shop Ltd");
        model.addElement("Birch & Co");
        Resolved birch = entry(new javax.swing.JList<String>(model), "Birch & Co");
        assertThat(Expectations.check(birch, state("!hidden"))).isNull();
        model.removeElement("Birch & Co");
        DriverException gone = catchThrowableOfType(() -> Expectations.check(birch, state("!hidden")), DriverException.class);
        assertThat(gone.reason()).isEqualTo(Reason.NOT_FOUND);
    }

    @Test
    void aRemovedRowIsNotShown() {
        javax.swing.table.DefaultTableModel model = cart();
        JTable table = new JTable(model);
        Resolved line = cartLine(table, "Gel pen, black", null);
        assertThat(Expectations.check(line, state("!hidden"))).isNull();
        model.removeRow(1);
        DriverException gone = catchThrowableOfType(() -> Expectations.check(line, state("!hidden")), DriverException.class);
        assertThat(gone.reason()).isEqualTo(Reason.NOT_FOUND);
    }
}
