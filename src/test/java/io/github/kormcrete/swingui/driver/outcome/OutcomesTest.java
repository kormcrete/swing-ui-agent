package io.github.kormcrete.swingui.driver.outcome;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.Component;
import java.awt.Window;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The outcome of a step: only what changed in the readable UI, keyed by the names a person reads. */
class OutcomesTest {

    private static UiReading withTable(UiReading.Builder b, String... scopes) {
        UiReading.Rows rows = UiReading.builder().table("table", Arrays.asList("Order"),
                Arrays.asList(Arrays.asList("ORD-1640"))).build().tables.get(0).value;
        return b.rows(true, new UiReading.Named<UiReading.Rows>("table", Arrays.asList(scopes), "", rows)).build();
    }

    private static UiReading.Named<String> in(String name, String value, String... scopes) {
        return new UiReading.Named<String>(name, Arrays.asList(scopes), "", value);
    }

    @Test
    void aFilledTextFieldOnTheTablesTabIsNamedAsAPossibleFilter() {
        UiReading r = withTable(UiReading.builder().textField(in("Search", "ORD-1640", "Orders"))
                .textField(in("Note", "x", "Other")).field(in("Status", "All", "Orders")), "Orders");

        assertThat(Outcomes.filterHint(r, "table"))
                .isEqualTo("the table shows 1 row and Search holds \"ORD-1640\": a filter may hide the new row");
    }

    @Test
    void noHintWithoutAFilledTextField() {
        assertThat(Outcomes.filterHint(withTable(UiReading.builder().textField(in("Search", " ", "Orders")), "Orders"), "table")).isNull();
        assertThat(Outcomes.filterHint(withTable(UiReading.builder().field(in("Status", "Cancelled", "Orders")), "Orders"), "table")).isNull();
        assertThat(Outcomes.filterHint(withTable(UiReading.builder().textField(in("Search", "x", "Orders")), "Orders"), "Customers")).isNull();
        assertThat(Outcomes.filterHint(null, "table")).isNull();
    }

    @Test
    void severalFieldsAreNamedNearestFirstAndAtMostThree() {
        UiReading r = withTable(UiReading.builder().textField("Outer", "a").textField(in("Search", "b", "Orders"))
                .textField(in("Mid", "c", "Orders")).textField(in("Far", "d")), "Orders");

        assertThat(Outcomes.filterHint(r, "table")).isEqualTo("the table shows 1 row and Search holds \"b\", "
                + "Mid holds \"c\", Outer holds \"a\": a filter may hide the new row");
    }

    @Test
    void aFilledSearchBesideATableOnTheStartScreenIsNamed() {
        UiReading r = withTable(UiReading.builder().textField(in("Search", "ORD-1640", "Orders")), "Orders");

        assertThat(Outcomes.startHint(r)).isEqualTo("Search holds \"ORD-1640\" next to the table table: a test "
                + "that leaves it filled may fail its next run on this app start; leave the screen as you found it");
    }

    @Test
    void noStartHintWithoutAFilledTextFieldBesideATable() {
        assertThat(Outcomes.startHint(withTable(UiReading.builder().textField(in("Search", "", "Orders")), "Orders"))).isNull();
        assertThat(Outcomes.startHint(UiReading.builder().textField(in("Search", "x", "Orders")).build())).isNull();
        assertThat(Outcomes.startHint(withTable(UiReading.builder().field(in("Status", "Placed", "Orders")), "Orders"))).isNull();
        assertThat(Outcomes.startHint(null)).isNull();
    }

    /** Time stands still: these readings never run out of budget, however slow the machine. */
    private final UiReader reader = new UiReader(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 40);
    private final List<Window> windows = new ArrayList<Window>();

    private final JFrame frame = new JFrame("Order Desk");
    private final JPanel content = new JPanel(null);
    private final JTextField customer = new JTextField("Ada");
    private final DefaultTableModel model = new DefaultTableModel(new Object[][] {
            {"ORD-1640", "Acme", "New"},
            {"ORD-1641", "Acme", "Shipped"},
            {"ORD-1642", "Birch & Co", "Open"}}, new Object[] {"Order", "Customer", "Status"});
    private final JTable orders = new JTable(model);

    OutcomesTest() throws Exception {
        onEdt(() -> {
            JLabel caption = new JLabel("Customer:");
            caption.setLabelFor(customer);
            customer.setName("customer");
            place(caption, 10, 10, 80, 24);
            place(customer, 100, 10, 120, 24);
            JScrollPane scroll = new JScrollPane(orders);
            scroll.setBorder(BorderFactory.createTitledBorder("Orders"));
            place(scroll, 10, 50, 400, 200);
            frame.setContentPane(content);
            windows.add(frame);
            return null;
        });
    }

    @AfterEach
    void dispose() throws Exception {
        onEdt(() -> {
            for (Window w : windows) w.dispose();
            return null;
        });
    }

    @Test
    void aRowAddedToATableIsTheOnlyChange() throws Exception {
        Map<String, Object> outcome = step(() -> model.addRow(new Object[] {"ORD-1643", "Ada Shop Ltd", "New"}));

        assertThat(at(outcome, "rows", "Orders", "added", 0, "Order")).isEqualTo("ORD-1643");
        assertThat(at(outcome, "rows", "Orders", "added", 0, "Customer")).isEqualTo("Ada Shop Ltd");
        assertThat(at(outcome, "rows", "Orders", "count")).isEqualTo(4);
        assertThat(at(outcome, "rows", "Orders", "removed")).isEqualTo(new ArrayList<Object>());
        assertThat(outcome).containsOnlyKeys("rows");
    }

    @Test
    void aChangedFieldIsKeyedByItsLabel() throws Exception {
        Map<String, Object> outcome = step(() -> customer.setText("Grace"));

        assertThat(at(outcome, "fields", "Customer")).isEqualTo("Grace");
        assertThat(outcome).containsOnlyKeys("fields");
    }

    @Test
    void sameLabelTwiceIsPrefixedWithItsTab() throws Exception {
        final JTextField billing = new JTextField("Ada Lovelace");
        final JTextField shipping = new JTextField("Ada Lovelace");
        onEdt(() -> {
            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Billing", page(billing, true));
            // named by where its label sits, not by setLabelFor: the label search must reach an unselected tab too
            tabs.addTab("Shipping", page(shipping, false));
            place(tabs, 10, 260, 400, 120);
            tabs.doLayout();
            return null;
        });
        UiReading all = read();
        assertThat(Outcomes.diff(UiReading.builder().build(), all).get("fields"))
                .isEqualTo(map("Customer", "Ada", "Billing › Name", "Ada Lovelace", "Shipping › Name", "Ada Lovelace"));

        Map<String, Object> outcome = step(() -> shipping.setText("Grace Hopper"));

        assertThat(outcome.get("fields")).isEqualTo(map("Shipping › Name", "Grace Hopper"));
    }

    @Test
    void listAndTreeItemsAddedAndRemoved() throws Exception {
        final DefaultListModel<String> categories = new DefaultListModel<String>();
        categories.addElement("Pens");
        categories.addElement("Pencils");
        final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Products");
        root.add(new DefaultMutableTreeNode("Pens"));
        final DefaultTreeModel treeModel = new DefaultTreeModel(root);
        onEdt(() -> {
            JScrollPane list = new JScrollPane(new JList<String>(categories));
            list.setBorder(BorderFactory.createTitledBorder("Categories"));
            place(list, 10, 260, 150, 100);
            JScrollPane tree = new JScrollPane(new JTree(treeModel));
            tree.setBorder(BorderFactory.createTitledBorder("Catalog"));
            place(tree, 170, 260, 150, 100);
            return null;
        });

        Map<String, Object> outcome = step(() -> {
            categories.addElement("Gel pens");
            categories.removeElement("Pencils");
            treeModel.insertNodeInto(new DefaultMutableTreeNode("Markers"), root, 1);
        });

        assertThat(at(outcome, "items", "Categories")).isEqualTo(map(
                "added", Arrays.asList("Gel pens"), "removed", Arrays.asList("Pencils")));
        assertThat(at(outcome, "items", "Catalog", "added")).isEqualTo(Arrays.asList("Products > Markers"));
        assertThat(outcome).containsOnlyKeys("items");
    }

    @Test
    void aDialogThatOpenedGivesItsTitleAndMessage() throws Exception {
        Map<String, Object> outcome = step(() -> {
            JDialog dialog = new JOptionPane("Order copied", JOptionPane.INFORMATION_MESSAGE).createDialog(frame, "Copy order");
            windows.add(dialog);
        });

        assertThat(at(outcome, "opened", "dialogs")).isEqualTo(Arrays.asList("Copy order"));
        assertThat(outcome.get("message")).isEqualTo("Order copied");
        assertThat(outcome).containsOnlyKeys("opened", "message");

        Map<String, Object> closed = step(() -> windows.remove(windows.size() - 1).dispose());

        assertThat(at(closed, "closed", "dialogs")).isEqualTo(Arrays.asList("Copy order"));
        assertThat(closed).doesNotContainKey("message");
    }

    @Test
    void aNewTabIsOpened() throws Exception {
        final JTabbedPane tabs = new JTabbedPane();
        onEdt(() -> {
            tabs.addTab("Orders", new JPanel());
            place(tabs, 10, 260, 400, 120);
            return null;
        });

        Map<String, Object> outcome = step(() -> {
            JPanel order = new JPanel(null);
            JTextField number = new JTextField("ORD-1641");
            JLabel caption = new JLabel("Number:");
            caption.setLabelFor(number);
            order.add(caption);
            order.add(number);
            tabs.addTab("ORD-1641", order);
            tabs.setSelectedIndex(1);
        });

        assertThat(at(outcome, "opened", "tabs")).isEqualTo(Arrays.asList("ORD-1641"));
        assertThat(at(outcome, "fields", "Number")).isEqualTo("ORD-1641");
    }

    @Test
    void changedRowHasBeforeAndAfter() throws Exception {
        Map<String, Object> outcome = step(() -> model.setValueAt("Delivered", 1, 2));

        assertThat(at(outcome, "rows", "Orders", "changed", 0, "before", "Status")).isEqualTo("Shipped");
        assertThat(at(outcome, "rows", "Orders", "changed", 0, "after", "Status")).isEqualTo("Delivered");
        assertThat(at(outcome, "rows", "Orders", "changed", 0, "after", "Order")).isEqualTo("ORD-1641");
        assertThat(at(outcome, "rows", "Orders", "added")).isEqualTo(new ArrayList<Object>());
        assertThat(at(outcome, "rows", "Orders", "removed")).isEqualTo(new ArrayList<Object>());
    }

    @Test
    void aSelectedRowIsShownAsSelected() throws Exception {
        Map<String, Object> outcome = step(() -> orders.setRowSelectionInterval(2, 2));

        assertThat(at(outcome, "selected", "Orders", 0, "Order")).isEqualTo("ORD-1642");
        assertThat(outcome).containsOnlyKeys("selected");
    }

    @Test
    void listsAreCappedAtFiftyWithTheTrueCount() throws Exception {
        Map<String, Object> outcome = step(() -> {
            for (int i = 0; i < 200; i++) model.addRow(new Object[] {"ORD-" + (2000 + i), "Acme", "New"});
        });

        assertThat((List<?>) at(outcome, "rows", "Orders", "added")).hasSize(50);
        assertThat(at(outcome, "rows", "Orders", "added", 49, "Order")).isEqualTo("ORD-2049");
        assertThat(at(outcome, "rows", "Orders", "count")).isEqualTo(203);
    }

    @Test
    void longTextsAreCut() throws Exception {
        final StringBuilder note = new StringBuilder();
        for (int i = 0; i < 700; i++) note.append('x');
        Map<String, Object> outcome = step(() -> customer.setText(note.toString()));

        assertThat((String) at(outcome, "fields", "Customer")).hasSize(500);
    }

    @Test
    void newValuesAreOnlyThoseAbsentBefore() throws Exception {
        final JTextField number = new JTextField();
        onEdt(() -> {
            JLabel caption = new JLabel("Number:");
            caption.setLabelFor(number);
            place(caption, 240, 10, 60, 24);
            place(number, 300, 10, 100, 24);
            return null;
        });
        UiReading before = read();
        onEdt(() -> {
            model.addRow(new Object[] {"ORD-1643", "Ada Shop Ltd", "New"});
            model.addRow(new Object[] {"ORD-1644", "Acme", "New"});
            number.setText("ORD-1644");
            return null;
        });
        Map<String, Object> outcome = Outcomes.diff(before, read());

        Map<String, String> values = Outcomes.newValues(before, outcome);

        // the field wins over the row that shows the same value; "New" and "Acme" were on screen already
        assertThat(new ArrayList<String>(values.keySet())).containsExactly("ORD-1644", "ORD-1643", "Ada Shop Ltd");
        assertThat(values.get("ORD-1644")).isEqualTo(".fields.Number");
        assertThat(values.get("ORD-1643")).isEqualTo(".rows.Orders.added[0].Order");
        assertThat(values.get("Ada Shop Ltd")).isEqualTo(".rows.Orders.added[0].Customer");
    }

    @Test
    void aKeyThatIsNoPlainWordIsQuotedInItsPath() {
        UiReading before = UiReading.builder().build();
        Map<String, Object> outcome = Outcomes.diff(before, UiReading.builder().field("Billing › Name", "Grace Hopper")
                .field("Customer's note", "It's urgent").build());

        Map<String, String> values = Outcomes.newValues(before, outcome);

        assertThat(values.get("Grace Hopper")).isEqualTo(".fields['Billing › Name']");
        assertThat(values.get("It's urgent")).isEqualTo(".fields['Customer\\'s note']");
    }

    @Test
    void aPartialReadingMarksTheOutcome() {
        UiReading before = UiReading.builder().field("Customer", "Ada").build();
        UiReading after = UiReading.builder().field("Customer", "Grace").partial(true).build();

        assertThat(Outcomes.diff(before, after).get("partial")).isEqualTo(Boolean.TRUE);
        assertThat(Outcomes.diff(before, before)).isEmpty();
    }

    @Test
    void aSecondSameNamedFieldAppearsAndOnlyItIsInTheOutcome() throws Exception {
        final JTabbedPane tabs = new JTabbedPane();
        onEdt(() -> {
            tabs.addTab("Billing", page(new JTextField("Ada Lovelace"), true));
            place(tabs, 10, 260, 400, 120);
            tabs.doLayout();
            return null;
        });

        // alone, the billing field is "Name"; with a second one it is "Billing › Name" - on both sides alike
        Map<String, Object> outcome = step(() -> tabs.addTab("Shipping", page(new JTextField("Grace Hopper"), true)));

        assertThat(outcome.get("fields")).isEqualTo(map("Shipping › Name", "Grace Hopper"));
    }

    @Test
    void aSecondOrderTabKeepsTheFirstOnesKeys() throws Exception {
        final JTabbedPane documents = new JTabbedPane();
        onEdt(() -> {
            document(documents, "ORD-1640", "Ada");
            place(documents, 10, 260, 400, 120);
            return null;
        });

        // an order tab's title is run data: the Customer fields (the form's and each order's) are told apart by their
        // order, the same on both sides, so the first two are no change
        Map<String, Object> outcome = step(() -> document(documents, "ORD-1641", "Grace"));

        assertThat(outcome.get("fields")).isEqualTo(map("Customer #3", "Grace"));
    }

    @Test
    void aSelectionMovedToAnUnreadRegionGivesNoAddedOrRemovedRows() {
        UiReading before = partial(40000, range(100, 110));
        UiReading after = partial(40000, range(100, 110), range(29995, 30005), 30000);

        Map<String, Object> outcome = Outcomes.diff(before, after);

        assertThat(outcome).doesNotContainKey("rows");
        assertThat(at(outcome, "selected", "Orders", 0, "Order")).isEqualTo("ORD-30000");
        assertThat(outcome.get("partial")).isEqualTo(Boolean.TRUE);
        assertThat(Outcomes.newValues(before, outcome)).isEmpty();
    }

    @Test
    void anInsertionAboveTheViewGivesNoAddedOrRemovedRows() {
        UiReading before = partial(40000, range(100, 110));
        // one row inserted at the top: the view shows the rows that were at 99..109
        UiReading after = shifted(40001, range(100, 110), -1);

        Map<String, Object> outcome = Outcomes.diff(before, after);

        assertThat(at(outcome, "rows", "Orders")).isEqualTo(map("added", new ArrayList<Object>(),
                "removed", new ArrayList<Object>(), "changed", new ArrayList<Object>(), "count", 40001));
        assertThat(Outcomes.newValues(before, outcome)).isEmpty();
    }

    @Test
    void aRowAddedInTheViewOfAPartialTableIsAdded() {
        UiReading before = partial(40000, range(100, 110));
        List<Integer> read = range(100, 111);
        List<List<String>> rows = new ArrayList<List<String>>();
        for (int r : read) rows.add(order(r < 105 ? r : r == 105 ? 99999 : r - 1));
        UiReading after = rows(40001, read, rows);

        Map<String, Object> outcome = Outcomes.diff(before, after);

        assertThat(at(outcome, "rows", "Orders", "added", 0, "Order")).isEqualTo("ORD-99999");
        assertThat((List<?>) at(outcome, "rows", "Orders", "added")).hasSize(1);
        assertThat((List<?>) at(outcome, "rows", "Orders", "removed")).isEmpty();
        assertThat(Outcomes.newValues(before, outcome)).containsOnlyKeys("ORD-99999");
    }

    @Test
    void aTextKeyedByItselfIsNoNewValue() {
        UiReading before = UiReading.builder().text("Total", "€7.32").build();
        UiReading after = UiReading.builder().text("Total", "€9.10").text("Saved ORD-1643", "Saved ORD-1643").build();

        Map<String, String> values = Outcomes.newValues(before, Outcomes.diff(before, after));

        assertThat(values).containsOnlyKeys("€9.10");
        assertThat(values.get("€9.10")).isEqualTo(".texts.Total");
    }

    // ------------------------------------------------------------------ helpers

    /** An open order as a strip of documents shows it: its tab has a close button of its own. */
    private static void document(JTabbedPane documents, String number, String customerName) {
        JPanel order = new JPanel(null);
        JTextField field = new JTextField(customerName);
        JLabel caption = new JLabel("Customer:");
        caption.setLabelFor(field);
        order.add(caption);
        order.add(field);
        documents.addTab(number, order);
        documents.setTabComponentAt(documents.getTabCount() - 1, new JLabel(number));
    }

    private static List<Integer> range(int first, int last) {
        List<Integer> out = new ArrayList<Integer>();
        for (int i = first; i <= last; i++) out.add(i);
        return out;
    }

    private static List<String> order(int n) {
        return Arrays.asList("ORD-" + n, "Open");
    }

    /** An Orders table of {@code count} rows (row r is ORD-r) read only at {@code read} and {@code more}. */
    private static UiReading partial(int count, List<Integer> read, List<Integer> more, Integer... selected) {
        List<Integer> all = new ArrayList<Integer>(read);
        all.addAll(more);
        List<List<String>> rows = new ArrayList<List<String>>();
        for (int r : all) rows.add(order(r));
        return rows(count, all, rows, selected);
    }

    private static UiReading partial(int count, List<Integer> read) {
        return partial(count, read, new ArrayList<Integer>());
    }

    /** As {@link #partial}, the row at r showing what was at r + {@code by}. */
    private static UiReading shifted(int count, List<Integer> read, int by) {
        List<List<String>> rows = new ArrayList<List<String>>();
        for (int r : read) rows.add(order(r + by));
        return rows(count, read, rows);
    }

    private static UiReading rows(int count, List<Integer> read, List<List<String>> rows, Integer... selected) {
        List<String> headers = Arrays.asList("Order", "Status");
        List<Object> values = new ArrayList<Object>();
        List<Integer> hashes = new ArrayList<Integer>();
        for (List<String> row : rows) {
            String[] cells = row.toArray(new String[0]);
            values.add(UiReading.row(headers, cells));
            hashes.add(Arrays.hashCode(cells));
        }
        UiReading.Rows table = new UiReading.Rows(headers, read, values, hashes, Arrays.asList(selected), count, false);
        return UiReading.builder().rows(true, UiReading.Named.plain("Orders", table)).partial(true).build();
    }


    /** The outcome of {@code change}, run on the EDT between a reading before and one after. */
    private Map<String, Object> step(Runnable change) throws Exception {
        UiReading before = read();
        onEdt(() -> {
            change.run();
            return null;
        });
        return Outcomes.diff(before, read());
    }

    private UiReading read() throws Exception {
        return onEdt(() -> reader.read(windows));
    }

    private JPanel page(JTextField field, boolean linked) {
        JPanel page = new JPanel(null);
        JLabel caption = new JLabel("Name:");
        if (linked) caption.setLabelFor(field);
        caption.setBounds(10, 10, 60, 24);
        field.setBounds(80, 10, 150, 24);
        page.add(caption);
        page.add(field);
        return page;
    }

    private void place(Component c, int x, int y, int w, int h) {
        c.setBounds(x, y, w, h);
        content.add(c);
        c.doLayout();
    }

    /** The value at {@code path} (keys and list indices) in a JSON-shaped map. */
    static Object at(Object root, Object... path) {
        Object at = root;
        for (Object step : path) {
            if (step instanceof Integer) {
                assertThat(at).as("a list before [" + step + "] in " + Arrays.toString(path)).isInstanceOf(List.class);
                at = ((List<?>) at).get((Integer) step);
            } else {
                assertThat(at).as("an object before ." + step + " in " + Arrays.toString(path)).isInstanceOf(Map.class);
                at = ((Map<?, ?>) at).get(step);
            }
        }
        return at;
    }

    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < keysAndValues.length; i += 2) out.put((String) keysAndValues[i], keysAndValues[i + 1]);
        return out;
    }

    static <T> T onEdt(Callable<T> body) throws Exception {
        AtomicReference<T> result = new AtomicReference<T>();
        AtomicReference<Exception> failure = new AtomicReference<Exception>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(body.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) throw failure.get();
        return result.get();
    }
}
