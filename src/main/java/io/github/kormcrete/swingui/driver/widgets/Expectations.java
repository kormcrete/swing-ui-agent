package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Expectation;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Names;
import io.github.kormcrete.swingui.driver.model.Node;
import io.github.kormcrete.swingui.driver.model.Resolved;
import io.github.kormcrete.swingui.driver.model.Roles;
import io.github.kormcrete.swingui.driver.model.RowSpec;
import io.github.kormcrete.swingui.driver.model.Section;

import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JPasswordField;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reads what {@code expect} compares. Null means the expectation holds. EDT. */
public final class Expectations {

    private Expectations() {}

    public static String check(Resolved r, Expectation e) {
        return check(r, e, null);
    }

    /**
     * {@code liveSection} is the section a registered source currently claims for {@code r.component}, asked
     * fresh by the caller; null for a non-section target. Its {@code state} is added to the derived states,
     * never put in their place, so a claimed button is still judged on {@code disabled} as well.
     */
    public static String check(Resolved r, Expectation e, Section liveSection) {
        if (r.component instanceof JPasswordField && (e.text != null || e.value != null)) {
            throw new DriverException(Reason.BAD_INPUT, "a password field's content is never read");
        }
        if (e.state != null) return state(r, e.state, liveSection);
        if (e.text != null) return compare("text", e.text, text(r));
        if (e.value != null) return compare("value", e.value, value(r));
        if (e.tooltip != null) {
            String actual = tooltip(r);
            return compare("tooltip", e.tooltip, actual == null ? "" : actual);
        }
        if (e.rows != null) {
            int n = rowCount(r);
            return Expectation.compare(n, e.rows.op, e.rows.value)
                    ? null : "expected rows " + e.rows.op + " " + e.rows.value + " but there are " + n;
        }
        if (e.selected != null) {
            List<Integer> actual = selectedRows(r);
            return sameRows(actual, e.selected) ? null
                    : "expected selected rows " + e.selected + " but they were " + actual;
        }
        if (!(r.component instanceof JTable)) {
            throw new DriverException(Reason.BAD_INPUT, "'cell' needs a table target, not " + r.describe());
        }
        JTable table = (JTable) r.component;
        int row = TableWidget.row(table, RowSpec.of(e.cell.row));
        String actual = Cells.table(table, row, TableWidget.column(table, e.cell.column)).text;
        return compare("cell [" + e.cell.column + "]", e.cell.expected, actual);
    }

    private static String state(Resolved r, List<String> wanted, Section liveSection) {
        Node n = new Node(r.role, r.name);
        if (r.part != null) {
            r.part.addStates(n);
        } else if (r.tab != null) {
            JTabbedPane tabs = (JTabbedPane) r.component;
            if (tabs.getSelectedIndex() == r.tab) n.addState("selected");
            if (!tabs.isEnabledAt(r.tab)) n.addState("disabled");
        } else if (r.component instanceof JTable && r.row() != null) {
            cellState((JTable) r.component, r, n);
        } else if (r.component instanceof JTree && r.item() != null) {
            // A tree ROW's own state - the same node Snapshot.describe reads for that item.
            JTree tree = (JTree) r.component;
            TreeWidget.itemState(tree, TreeWidget.row(tree, r.item()), n);
        } else if (liveSection == null || Roles.of(r.component) != null) {
            // A component with a derived role is described by it, as the snapshot does. A claimed one with
            // no derived role is a section node with no derived state: the source's state is all it has.
            Widgets.describe(r.component, r.role, n, 0);
        }
        // Added, never replacing: else `!disabled` would pass on a claimed, disabled button and a claimed
        // table's row would lose its selection.
        if (liveSection != null && liveSection.state != null) {
            for (String s : liveSection.state) n.addState(s);
        }
        for (String s : wanted) {
            boolean absent = s.startsWith("!");
            String name = absent ? s.substring(1) : s;
            if (n.hasState(name) == absent) {
                return "expected state " + s + " but the states were " + (n.state == null ? "[]" : n.state);
            }
        }
        return null;
    }

    private static String text(Resolved r) {
        Component c = r.component;
        if (r.part != null) return r.part.name;
        if (r.tab != null) return Names.tabTitle((JTabbedPane) c, r.tab);
        if (c instanceof JTable && r.row() != null) return cellText((JTable) c, r);
        if (c instanceof JTextComponent) return ((JTextComponent) c).getText();
        if (c instanceof JComboBox) return ComboWidget.selectedText((JComboBox<?>) c);
        String own = Names.clean(Names.ownText(c));
        return own != null ? own : r.name;
    }

    private static String value(Resolved r) {
        Component c = r.component;
        if (c instanceof JTable && r.row() != null) return cellText((JTable) c, r);
        if (c instanceof JTextComponent) return ((JTextComponent) c).getText();
        if (c instanceof JComboBox) return ComboWidget.selectedText((JComboBox<?>) c);
        if (c instanceof JToggleButton) return String.valueOf(((JToggleButton) c).isSelected());
        throw new DriverException(Reason.BAD_INPUT, r.describe() + " has no value; expect its text or state");
    }

    /** The tooltip shown on hover: a cell's rendered one, a tab's, else the component's own. Null when none. */
    private static String tooltip(Resolved r) {
        Component c = r.component;
        if (r.tab != null) return Names.clean(((JTabbedPane) c).getToolTipTextAt(r.tab));
        if (c instanceof JTable && r.row() != null) {
            if (r.column() == null) {
                throw new DriverException(Reason.BAD_INPUT, "a table row has no single tooltip; add 'column'");
            }
            JTable table = (JTable) c;
            return Cells.tableTooltip(table, TableWidget.row(table, r.row()), TableWidget.column(table, r.column()));
        }
        return Widgets.tooltip(c);
    }

    private static String cellText(JTable table, Resolved r) {
        if (r.column() == null) {
            throw new DriverException(Reason.BAD_INPUT, "a table row has no single text; add 'column'");
        }
        return Cells.table(table, TableWidget.row(table, r.row()), TableWidget.column(table, r.column())).text;
    }

    /**
     * A cell's own state: {@code checked} and {@code selected} for a true Boolean cell (the vocabulary of a
     * check box, radio or toggle, as the {@code check} verb treats them alike), plus {@code disabled} when
     * the cell is not editable. A row with no column has {@code selected} and {@code focused} (lead) instead.
     */
    private static void cellState(JTable table, Resolved r, Node n) {
        if (r.column() == null) {
            int only = TableWidget.row(table, r.row());
            if (table.isRowSelected(only)) n.addState("selected");
            if (table.getSelectionModel().getLeadSelectionIndex() == only) n.addState("focused");
            return;
        }
        int row = TableWidget.row(table, r.row());
        int column = TableWidget.column(table, r.column());
        Object value = table.getValueAt(row, column);
        boolean booleanCell = value instanceof Boolean || Boolean.class.equals(table.getColumnClass(column));
        if (booleanCell && Boolean.TRUE.equals(value)) {
            n.addState("checked");
            n.addState("selected");
        }
        if (!table.isCellEditable(row, column)) n.addState("disabled");
    }

    /** The selected rows of a table, list or tree, in order. */
    private static List<Integer> selectedRows(Resolved r) {
        Component c = r.component;
        int[] indices;
        if (c instanceof JTable) {
            indices = ((JTable) c).getSelectedRows();
        } else if (c instanceof JList) {
            indices = ((JList<?>) c).getSelectedIndices();
        } else if (c instanceof JTree) {
            int[] rows = ((JTree) c).getSelectionRows();
            indices = rows == null ? new int[0] : rows;
        } else {
            throw new DriverException(Reason.BAD_INPUT, r.describe() + " has no selectable rows");
        }
        List<Integer> out = new ArrayList<Integer>(indices.length);
        for (int i : indices) out.add(i);
        return out;
    }

    private static boolean sameRows(List<Integer> actual, List<Integer> wanted) {
        if (actual.size() != wanted.size()) return false;
        List<Integer> a = new ArrayList<Integer>(actual);
        List<Integer> b = new ArrayList<Integer>(wanted);
        Collections.sort(a);
        Collections.sort(b);
        return a.equals(b);
    }

    private static int rowCount(Resolved r) {
        Component c = r.component;
        if (c instanceof JTable) return ((JTable) c).getRowCount();
        if (c instanceof JList) return ((JList<?>) c).getModel().getSize();
        if (c instanceof JTree) return ((JTree) c).getRowCount();
        if (c instanceof JComboBox) return ((JComboBox<?>) c).getItemCount();
        throw new DriverException(Reason.BAD_INPUT, r.describe() + " has no rows");
    }

    private static String compare(String what, String expected, String actual) {
        return expected.equals(actual) ? null : "expected " + what + " '" + expected + "' but was '" + actual + "'";
    }
}
