package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.RowSpec;
import org.junit.jupiter.api.Test;

import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class TableWidgetTest {

    private final JTable table = new JTable(new Object[][] {
            {"26-1", "open", Boolean.TRUE},
            {"26-2", "done", Boolean.FALSE},
            {"26-3", "open", Boolean.FALSE}
    }, new Object[] {"No.", "Status", "Apply"}) {
        @Override
        public Class<?> getColumnClass(int column) {
            return column == 2 ? Boolean.class : String.class;
        }
    };

    private static RowSpec key(String column, String value) {
        return RowSpec.of(Collections.<String, Object>singletonMap(column, value));
    }

    @Test
    void rowsAreFoundByTheirRenderedKeyCells() {
        assertThat(TableWidget.row(table, key("No.", "26-3"))).isEqualTo(2);
        assertThat(TableWidget.row(table, RowSpec.of(Collections.<String, Object>singletonMap("index", 1)))).isEqualTo(1);
    }

    @Test
    void aBooleanCellReadsAsItsState() {
        assertThat(Cells.table(table, 0, 2).text).isEqualTo("true");
        assertThat(Cells.table(table, 1, 2).approx).isFalse();
    }

    @Test
    void missingAndRepeatedRowsAreReported() {
        assertThat(catchThrowableOfType(() -> TableWidget.row(table, key("Status", "open")), DriverException.class)
                .reason()).isEqualTo(Reason.AMBIGUOUS);
        assertThat(catchThrowableOfType(() -> TableWidget.row(table, key("No.", "99")), DriverException.class)
                .reason()).isEqualTo(Reason.NOT_FOUND);
        DriverException column = catchThrowableOfType(() -> TableWidget.column(table, "Customer"), DriverException.class);
        assertThat(column.reason()).isEqualTo(Reason.NOT_FOUND);
        assertThat(column.getMessage()).contains("No.").contains("Apply");
        assertThat(catchThrowableOfType(() -> TableWidget.row(table,
                RowSpec.of(Collections.<String, Object>singletonMap("index", 3))), DriverException.class)
                .reason()).isEqualTo(Reason.NOT_FOUND);
    }

    /** A pivot table heads every order column "Quantity"; each column's identifier is its order number. */
    private static JTable grid() {
        JTable grid = new JTable(new Object[][] {{"7.2", "7.4"}, {"0.1", "0.3"}}, new Object[] {"Quantity", "Quantity"});
        grid.getColumnModel().getColumn(0).setIdentifier("ORD-1640");
        grid.getColumnModel().getColumn(1).setIdentifier("ORD-1641");
        return grid;
    }

    @Test
    void columnsThatShareAHeaderAreToldApartByTheirIdentifier() {
        JTable grid = grid();
        assertThat(TableWidget.column(grid, "Quantity@ORD-1640")).isEqualTo(0);
        assertThat(TableWidget.column(grid, "Quantity@ORD-1641")).isEqualTo(1);
        assertThat(TableWidget.identifiesColumn(grid, "Quantity@ORD-1641")).isTrue();
        assertThat(TableWidget.identifiesColumn(grid, "Quantity@ORD-9999")).isFalse();
        assertThat(TableWidget.identifiesColumn(grid, "Quantity")).isFalse();

        DriverException plain = catchThrowableOfType(() -> TableWidget.column(grid, "Quantity"), DriverException.class);
        assertThat(plain.reason()).isEqualTo(Reason.AMBIGUOUS);
        assertThat(plain.getMessage()).as("the failure says how to address one")
                .contains("Quantity@ORD-1640").contains("Quantity@ORD-1641");

        assertThat(catchThrowableOfType(() -> TableWidget.column(grid, "Quantity@ORD-9999"), DriverException.class)
                .reason()).isEqualTo(Reason.NOT_FOUND);
        assertThat(catchThrowableOfType(() -> TableWidget.column(grid, "Status@ORD-1640"), DriverException.class)
                .reason()).isEqualTo(Reason.NOT_FOUND);
    }

    @Test
    void aColumnWithoutAnIdentifierIsNotAddressableByItsHeaderTwice() {
        // getIdentifier() falls back to the header value when none was set; that is no identity.
        assertThat(TableWidget.identifiesColumn(table, "Status@Status")).isFalse();
        assertThat(TableWidget.column(table, "Status")).isEqualTo(1);
    }

    @Test
    void aHeaderThatHoldsAnAtSignStillMatchesAsWritten() {
        JTable mail = new JTable(new Object[][] {{"a@b"}}, new Object[] {"Email @ Customer"});
        assertThat(TableWidget.column(mail, "Email @ Customer")).isEqualTo(0);
    }

    @Test
    void aKeyColumnInTheRowHeaderTableFindsTheMainTablesRow() {
        DefaultTableModel model = new DefaultTableModel(new Object[][] {
                {"Screws", "7.2", "7.4"}, {"Nuts", "0.1", "0.3"}}, new Object[] {"Line items", "Quantity", "Quantity"});
        JTable main = new JTable(model);
        main.removeColumn(main.getColumnModel().getColumn(0));
        main.getColumnModel().getColumn(0).setIdentifier("ORD-1640");
        main.getColumnModel().getColumn(1).setIdentifier("ORD-1641");
        JTable fixed = new JTable(model);
        fixed.removeColumn(fixed.getColumnModel().getColumn(2));
        fixed.removeColumn(fixed.getColumnModel().getColumn(1));
        main.setSelectionModel(fixed.getSelectionModel());
        JScrollPane pane = new JScrollPane(main);
        pane.setRowHeaderView(fixed);

        assertThat(TableWidget.rowHeaderOf(main)).isSameAs(fixed);
        int row = TableWidget.row(main, key("Line items", "Nuts"));
        assertThat(row).isEqualTo(1);
        assertThat(Cells.table(main, row, TableWidget.column(main, "Quantity@ORD-1641")).text).isEqualTo("0.3");

        // Keys may be split across the two tables.
        Map<String, Object> both = new LinkedHashMap<String, Object>();
        both.put("Line items", "Screws");
        both.put("Quantity@ORD-1640", "7.2");
        assertThat(TableWidget.row(main, RowSpec.of(both))).isEqualTo(0);

        assertThat(catchThrowableOfType(() -> TableWidget.row(main, key("Product", "x")), DriverException.class)
                .reason()).isEqualTo(Reason.NOT_FOUND);
        assertThat(catchThrowableOfType(() -> TableWidget.row(main, key("Line items", "Bolts")), DriverException.class)
                .reason()).isEqualTo(Reason.NOT_FOUND);
    }

    @Test
    void aRowHeaderThatShowsOtherRowsIsNotUsed() {
        JTable main = new JTable(new Object[][] {{"7.2"}, {"0.1"}}, new Object[] {"Quantity"});
        JTable unrelated = new JTable(new Object[][] {{"Screws"}}, new Object[] {"Line items"});
        JScrollPane pane = new JScrollPane(main);
        pane.setRowHeaderView(unrelated);
        assertThat(TableWidget.rowHeaderOf(main)).isNull();
        assertThat(catchThrowableOfType(() -> TableWidget.row(main, key("Line items", "Screws")), DriverException.class)
                .reason()).isEqualTo(Reason.NOT_FOUND);
    }

    @Test
    void rowKeyIsTheFirstUniqueColumnElseTheIndex() {
        JTable byCustomer = new JTable(new Object[][] {{"Acme", "ORD-1"}, {"Acme", "ORD-2"}}, new Object[] {"Customer", "Order"});
        assertThat(TableWidget.rowKey(byCustomer, 1)).isEqualTo(Collections.<String, Object>singletonMap("Order", "ORD-2"));
        assertThat(TableWidget.uniqueRowKey(byCustomer, 1)).isEqualTo(Collections.<String, Object>singletonMap("Order", "ORD-2"));
        JTable twins = new JTable(new Object[][] {{"Acme", "Open"}, {"Acme", "Open"}}, new Object[] {"Customer", "Status"});
        assertThat(TableWidget.rowKey(twins, 1)).isEqualTo(Collections.<String, Object>singletonMap("index", 1));
        assertThat(TableWidget.uniqueRowKey(twins, 1)).isNull();
    }
}
