package io.github.kormcrete.swingui.driver.outcome;

import io.github.kormcrete.swingui.driver.inspect.PaletteParts;
import org.junit.jupiter.api.Test;

import javax.accessibility.AccessibleContext;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.DefaultListSelectionModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.JWindow;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableColumnModel;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.AbstractDocument;
import java.awt.Component;
import java.awt.Point;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.kormcrete.swingui.driver.outcome.OutcomesTest.onEdt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reading the UI: within its time, never the palette, and without changing the app. */
class UiReaderTest {

    private static final Clock STILL = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    @Test
    void aHugeTableFallsBackToViewAndSelectionWithinBudget() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final AbstractTableModel model = new AbstractTableModel() {
            @Override public int getRowCount() { return 40000; }
            @Override public int getColumnCount() { return 2; }
            @Override public String getColumnName(int column) { return column == 0 ? "Order" : "Status"; }
            @Override public Object getValueAt(int row, int column) {
                calls.incrementAndGet();
                return column == 0 ? "ORD-" + row : "Open";
            }
        };
        // a cell is read twice (rendered, then its value): 1,000 rows of 2 cells = 4,000 calls, then 41 ms have passed
        Clock slow = new Clock() {
            @Override public long millis() { return calls.get() >= 4000 ? 41 : 0; }
            @Override public Instant instant() { return Instant.ofEpochMilli(millis()); }
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
        };
        final JFrame frame = new JFrame("Order Desk");
        final JTable table = new JTable(model);
        UiReading reading = onEdt(() -> {
            JScrollPane scroll = new JScrollPane(table);
            scroll.setName("Orders");
            scroll.setBounds(0, 0, 300, 200);
            JPanel content = new JPanel(null);
            content.add(scroll);
            frame.setContentPane(content);
            // sized and laid out as showing would: the view is what the frame's panes leave visible
            frame.setSize(400, 300);
            frame.doLayout();
            frame.getRootPane().doLayout();
            scroll.doLayout();
            JViewport viewport = scroll.getViewport();
            viewport.doLayout();
            viewport.setViewPosition(new Point(0, 100 * table.getRowHeight()));
            table.setRowSelectionInterval(30000, 30000);
            calls.set(0);
            return new UiReader(slow, 40).read(Collections.singletonList(frame));
        });
        onEdt(() -> {
            frame.dispose();
            return null;
        });

        assertThat(reading.partial()).isTrue();
        UiReading.Rows rows = reading.tables().values().iterator().next();
        assertThat(rows.full).isFalse();
        assertThat(rows.count).isEqualTo(40000);
        TreeSet<Integer> allowed = new TreeSet<Integer>();
        for (int r = 100; r <= 112; r++) allowed.add(r);    // in view, a row cut at either edge included
        for (int r = 29995; r <= 30005; r++) allowed.add(r);
        assertThat(rows.indices).isSubsetOf(allowed).contains(100, 105, 29995, 30000, 30005);
        assertThat(rows.selectedValues()).hasSize(1);
        assertThat(((Map<?, ?>) rows.selectedValues().get(0)).get("Order")).isEqualTo("ORD-30000");
        // the first 1,000 rows, then only what is kept: never the 40,000
        assertThat(calls.get()).isLessThan(4000 + 4 * 40);
    }

    @Test
    void readsNothingOfThePalette() throws Exception {
        final JFrame frame = new JFrame("Order Desk");
        final List<JWindow> palette = new ArrayList<JWindow>();
        UiReading reading = onEdt(() -> {
            JPanel content = new JPanel(null);
            JLabel status = new JLabel("3 orders");
            status.setBounds(10, 10, 100, 20);
            content.add(status);
            JComponent bar = PaletteParts.bar("● Record");
            bar.setBounds(10, 40, 200, 30);
            content.add(bar);
            frame.setContentPane(content);
            frame.getLayeredPane().add(PaletteParts.bar("Done"), Integer.valueOf(300));
            JWindow own = PaletteParts.window(frame);
            own.getContentPane().add(new JLabel("Replay speed"));
            palette.add(own);
            return UiReader.standard().read(Arrays.<java.awt.Window>asList(frame, own));
        });
        onEdt(() -> {
            palette.get(0).dispose();
            frame.dispose();
            return null;
        });

        assertThat(reading.texts()).containsValue("3 orders");
        assertThat(reading.values()).doesNotContain("● Record", "Done", "Replay speed", "swing-ui-agent");
        assertThat(reading.windows).containsExactly("Order Desk");
    }

    @Test
    void addsNoListenersToTheApp() throws Exception {
        final AtomicBoolean asked = new AtomicBoolean();
        final DefaultTableModel model = new DefaultTableModel(new Object[][] {
                {"ORD-1640", "Acme", "Open"}, {"ORD-1641", "Acme", "Shipped"}}, new Object[] {"Order", "Customer", "Status"});
        final JTable table = new JTable(model) {
            @Override public AccessibleContext getAccessibleContext() {
                asked.set(true);
                return super.getAccessibleContext();
            }
        };
        final JTextField customer = new JTextField("Ada");
        final JComboBox<String> status = new JComboBox<String>(new DefaultComboBoxModel<String>(new String[] {"Open", "Shipped"}));
        final DefaultListModel<String> categories = new DefaultListModel<String>();
        categories.addElement("Pens");
        final JList<String> list = new JList<String>(categories);
        final JFrame frame = new JFrame("Order Desk");
        onEdt(() -> {
            JPanel content = new JPanel(null);
            JLabel caption = new JLabel("Customer:");
            caption.setLabelFor(customer);
            for (Component c : new Component[] {caption, customer, status, new JScrollPane(table), new JScrollPane(list)}) {
                c.setBounds(10, 10 + content.getComponentCount() * 40, 200, 30);
                content.add(c);
            }
            frame.setContentPane(content);
            table.setRowSelectionInterval(1, 1);
            return null;
        });
        List<Integer> before = onEdt(() -> listeners(table, model, customer, status, list, categories));

        onEdt(() -> UiReader.standard().read(Collections.<java.awt.Window>singletonList(frame)));

        assertThat(onEdt(() -> listeners(table, model, customer, status, list, categories))).isEqualTo(before);
        assertThat(asked.get()).as("getAccessibleContext() called").isFalse();
        onEdt(() -> {
            frame.dispose();
            return null;
        });
    }

    @Test
    void anErrorDialogTheAppsRulesNameGivesItsMessage() throws Exception {
        final JFrame frame = new JFrame("Order Desk");
        final JDialog error = new JDialog(frame, "Problem");
        List<UiReading> readings = onEdt(() -> {
            JLabel says = new JLabel("Could not save ORD-1640");
            says.setBounds(10, 10, 200, 20);
            JPanel content = new JPanel(null);
            content.add(says);
            error.setContentPane(content);
            List<java.awt.Window> windows = Arrays.<java.awt.Window>asList(frame, error);
            // the app's rule, as the driver's error-dialog rules would answer it
            UiReader withRules = new UiReader(STILL, 40, w -> w == error ? "Could not save ORD-1640" : null);
            return Arrays.asList(withRules.read(windows), new UiReader(STILL, 40).read(windows));
        });
        onEdt(() -> {
            error.dispose();
            frame.dispose();
            return null;
        });

        assertThat(readings.get(0).message).isEqualTo("Could not save ORD-1640");
        assertThat(readings.get(0).texts()).isEmpty();
        assertThat(readings.get(0).dialogs).containsExactly("Problem");
        // without the rule it is a dialog like any other: its label is a text
        assertThat(readings.get(1).message).isNull();
        assertThat(readings.get(1).texts()).containsValue("Could not save ORD-1640");
    }

    @Test
    void readsOnTheEventDispatchThreadOnly() {
        assertThatThrownBy(() -> new UiReader(STILL, 40).read(Collections.<java.awt.Window>emptyList()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static List<Integer> listeners(JTable table, DefaultTableModel model, JTextField field, JComboBox<String> combo,
                                           JList<String> list, DefaultListModel<String> items) {
        return Arrays.asList(
                table.getPropertyChangeListeners().length,
                table.getMouseListeners().length,
                table.getFocusListeners().length,
                model.getTableModelListeners().length,
                ((DefaultListSelectionModel) table.getSelectionModel()).getListSelectionListeners().length,
                ((DefaultTableColumnModel) table.getColumnModel()).getColumnModelListeners().length,
                field.getPropertyChangeListeners().length,
                field.getFocusListeners().length,
                ((AbstractDocument) field.getDocument()).getDocumentListeners().length,
                combo.getPropertyChangeListeners().length,
                combo.getActionListeners().length,
                combo.getItemListeners().length,
                ((DefaultComboBoxModel<String>) combo.getModel()).getListDataListeners().length,
                list.getPropertyChangeListeners().length,
                list.getListSelectionListeners().length,
                items.getListDataListeners().length);
    }
}
