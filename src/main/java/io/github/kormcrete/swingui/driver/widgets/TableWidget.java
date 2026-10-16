package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Names;
import io.github.kormcrete.swingui.driver.model.Node;
import io.github.kormcrete.swingui.driver.model.RowSpec;

import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JViewport;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tables: description, key-column row lookup, cell geometry (view indices throughout). EDT. */
public final class TableWidget {

    private TableWidget() {}

    public static void describe(JTable table, Node n, int maxRows) {
        n.columns = headers(table);
        n.rowCount = table.getRowCount();
        int shown = Math.min(maxRows, table.getRowCount());
        List<List<String>> rows = new ArrayList<List<String>>();
        boolean approx = false;
        for (int r = 0; r < shown; r++) {
            List<String> row = new ArrayList<String>();
            for (int c = 0; c < table.getColumnCount(); c++) {
                Cells.Text text = Cells.table(table, r, c);
                approx |= text.approx;
                row.add(text.text);
            }
            rows.add(row);
        }
        n.rows = rows;
        if (shown < table.getRowCount()) n.truncated = Boolean.TRUE;
        if (approx) n.approx = Boolean.TRUE;
        n.selected = Widgets.indices(table.getSelectedRows());
        if (table.isEditing()) n.addState("editing");
    }

    public static List<String> headers(JTable table) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < table.getColumnCount(); i++) {
            Object header = table.getColumnModel().getColumn(i).getHeaderValue();
            String text = header == null ? null : Names.clean(String.valueOf(header));
            out.add(text != null ? text : table.getColumnName(i));
        }
        return out;
    }

    public static List<String> rowTexts(JTable table, int row) {
        List<String> out = new ArrayList<String>();
        for (int c = 0; c < table.getColumnCount(); c++) out.add(Cells.table(table, row, c).text);
        return out;
    }

    /**
     * A column by its header text - or, where several columns share one header (every order column of a
     * pivot table is headed "Quantity"), by {@code "<header>@<identifier>"}: the header text and the
     * column's {@code TableColumn} identifier, both matched exactly. A column without its own identifier is
     * addressed by header alone.
     */
    public static int column(JTable table, String name) {
        List<String> headers = headers(table);
        int first = headers.indexOf(name);
        if (first < 0) {
            List<Integer> identified = byIdentity(table, headers, name);
            if (identified.size() == 1) return identified.get(0);
            if (identified.size() > 1) {
                throw new DriverException(Reason.AMBIGUOUS, identified.size() + " columns are '" + name + "'");
            }
            throw new DriverException(Reason.NOT_FOUND, "no column '" + name + "'; columns are " + headers
                    + identifiedHint(table, headers, null));
        }
        if (headers.lastIndexOf(name) != first) {
            throw new DriverException(Reason.AMBIGUOUS, "two columns are headed '" + name + "'"
                    + identifiedHint(table, headers, name));
        }
        return first;
    }

    /** True when {@code name} is a {@code "<header>@<identifier>"} that names at least one of the table's columns. */
    public static boolean identifiesColumn(JTable table, String name) {
        return !byIdentity(table, headers(table), name).isEmpty();
    }

    /** The columns {@code "<header>@<identifier>"} names; empty for a name without an {@code @}. */
    private static List<Integer> byIdentity(JTable table, List<String> headers, String name) {
        List<Integer> out = new ArrayList<Integer>();
        int at = name.lastIndexOf('@');
        if (at <= 0) return out;
        String header = name.substring(0, at);
        String identity = name.substring(at + 1);
        for (int i = 0; i < headers.size(); i++) {
            if (header.equals(headers.get(i)) && identity.equals(identity(table, i))) out.add(i);
        }
        return out;
    }

    /**
     * The column's own identifier, trimmed, or null. {@code TableColumn.getIdentifier()} falls back to
     * the header value when none was set, which is no identity: the header already says that.
     */
    private static String identity(JTable table, int column) {
        TableColumn c = table.getColumnModel().getColumn(column);
        Object id = c.getIdentifier();
        if (id == null || id == c.getHeaderValue()) return null;
        String text = String.valueOf(id).trim();
        return text.isEmpty() ? null : text;
    }

    /** The {@code "<header>@<identifier>"} names a failure can offer - for {@code header} only, when given. */
    private static String identifiedHint(JTable table, List<String> headers, String header) {
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < headers.size() && names.size() < 10; i++) {
            if (header != null && !header.equals(headers.get(i))) continue;
            String identity = identity(table, i);
            if (identity != null && !identity.equals(headers.get(i))) names.add(headers.get(i) + "@" + identity);
        }
        return names.isEmpty() ? "" : "; address one as '<header>@<identifier>', e.g. " + names;
    }

    /**
     * The row by its first column whose text is non-empty and unique in the table - an order number, not an index -
     * so a step still finds the row after sorting or a new row; by index only when no column is unique.
     */
    public static Map<String, Object> rowKey(JTable table, int row) {
        Map<String, Object> key = uniqueRowKey(table, row);
        if (key != null) return key;
        key = new LinkedHashMap<String, Object>();
        key.put("index", row);
        return key;
    }

    /** As {@link #rowKey}, but null when no column tells this row from the others. */
    public static Map<String, Object> uniqueRowKey(JTable table, int row) {
        List<String> headers = headers(table);
        for (int c = 0; c < table.getColumnCount(); c++) {
            String v = Cells.table(table, row, c).text;
            if (v == null || v.trim().isEmpty()) continue;
            boolean unique = true;
            for (int r = 0; r < table.getRowCount() && unique; r++) {
                if (r != row && v.equals(Cells.table(table, r, c).text)) unique = false;
            }
            if (!unique || headers.indexOf(headers.get(c)) != c) continue;
            Map<String, Object> key = new LinkedHashMap<String, Object>();
            key.put(headers.get(c), v);
            // the spec must resolve back to this row
            try {
                if (row(table, RowSpec.of(key)) == row) return key;
            } catch (DriverException ignored) {
                // try the next column
            }
        }
        return null;
    }

    /**
     * The row a spec names. A key column the table does not have is looked up in the table beside it
     * that serves as its row header - the {@code JScrollPane}'s row-header view, the shape a frozen
     * key column takes (a data grid's "Item" column): that table shows the same rows, so its
     * row {@code r} is this table's row {@code r}.
     */
    public static int row(JTable table, RowSpec spec) {
        if (spec.index != null) {
            if (spec.index < 0 || spec.index >= table.getRowCount()) {
                throw new DriverException(Reason.NOT_FOUND, "row index " + spec.index + " is out of range; the table has "
                        + table.getRowCount() + " rows");
            }
            return spec.index;
        }
        List<JTable> keyTables = new ArrayList<JTable>();
        List<Integer> keyColumns = new ArrayList<Integer>();
        List<String> keyValues = new ArrayList<String>();
        for (Map.Entry<String, String> key : spec.keys.entrySet()) {
            JTable owner = table;
            int column;
            try {
                column = column(table, key.getKey());
            } catch (DriverException e) {
                JTable header = e.reason() == Reason.NOT_FOUND ? rowHeaderOf(table) : null;
                if (header == null || !headers(header).contains(key.getKey())) throw e;
                owner = header;
                column = column(header, key.getKey());
            }
            keyTables.add(owner);
            keyColumns.add(column);
            keyValues.add(key.getValue());
        }
        List<Integer> hits = new ArrayList<Integer>();
        for (int r = 0; r < table.getRowCount(); r++) {
            boolean all = true;
            for (int k = 0; k < keyColumns.size(); k++) {
                if (!keyValues.get(k).equals(Cells.table(keyTables.get(k), r, keyColumns.get(k)).text)) {
                    all = false;
                    break;
                }
            }
            if (all) hits.add(r);
        }
        if (hits.isEmpty()) throw new DriverException(Reason.NOT_FOUND, "no row with " + spec.keys);
        if (hits.size() > 1) {
            List<String> rows = new ArrayList<String>();
            for (Integer h : hits.subList(0, Math.min(10, hits.size()))) rows.add("row " + h + ": " + rowTexts(table, h));
            throw new DriverException(Reason.AMBIGUOUS, hits.size() + " rows match " + spec.keys, rows);
        }
        return hits.get(0);
    }

    /**
     * True when the table has every key column of {@code spec} - itself or in its row-header table, as
     * {@link #row} looks them up. An index row needs no column. A key several columns share counts as present.
     */
    public static boolean hasKeyColumns(JTable table, RowSpec spec) {
        if (spec.index != null) return true;
        for (String key : spec.keys.keySet()) {
            try {
                column(table, key);
            } catch (DriverException e) {
                if (e.reason() == Reason.AMBIGUOUS) continue;
                JTable header = rowHeaderOf(table);
                if (header == null || !headers(header).contains(key)) return false;
            }
        }
        return true;
    }

    /** True when {@link #row} finds at least one row for {@code spec}: found, or several rows match. */
    public static boolean holdsRow(JTable table, RowSpec spec) {
        try {
            row(table, spec);
            return true;
        } catch (DriverException e) {
            return e.reason() == Reason.AMBIGUOUS;
        }
    }

    /**
     * The table in {@code table}'s enclosing scroll pane's row-header viewport, when it shows the same
     * rows: the same row count, and the same selection model or table model. Null otherwise.
     */
    static JTable rowHeaderOf(JTable table) {
        Container viewport = table.getParent();
        if (!(viewport instanceof JViewport) || !(viewport.getParent() instanceof JScrollPane)) return null;
        JViewport rowHeader = ((JScrollPane) viewport.getParent()).getRowHeader();
        JTable header = rowHeader == null ? null : firstTable(rowHeader.getView());
        if (header == null || header == table || header.getRowCount() != table.getRowCount()) return null;
        return header.getSelectionModel() == table.getSelectionModel() || header.getModel() == table.getModel()
                ? header : null;
    }

    private static JTable firstTable(Component c) {
        if (c instanceof JTable) return (JTable) c;
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                JTable hit = firstTable(child);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /** The header cell of column {@code name}, in the header's coordinates. */
    public static Rectangle headerRect(JTable table, String name) {
        JTableHeader header = table.getTableHeader();
        if (header == null) throw new DriverException(Reason.BAD_INPUT, "the table has no header for column '" + name + "'");
        return header.getHeaderRect(column(table, name));
    }

    /** A cell's rectangle; column -1 means the row's first visible column. */
    public static Rectangle bounds(JTable table, int row, int column) {
        int c = column;
        if (c < 0) {
            int visible = table.columnAtPoint(table.getVisibleRect().getLocation());
            c = visible < 0 ? 0 : visible;
        }
        return table.getCellRect(row, c, false);
    }
}
