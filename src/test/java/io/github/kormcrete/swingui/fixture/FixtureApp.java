package io.github.kormcrete.swingui.fixture;

import javax.swing.AbstractCellEditor;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.CellEditorListener;
import javax.swing.event.ChangeEvent;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * A plain Swing order form with no application framework, for driving through swing-ui-agent in its own JVM
 * (FixtureAppDriveIT): two fields, Save, a New that moves focus itself, a Delete that asks in a modal dialog,
 * an attachments drop target with a context menu, a lines table, a status label that tells what happened, a Total
 * caption beside the value label it names (Save sets the value, New clears it) and an
 * Archive button bottom right, with a button beside it that drags the person's palette over Archive (as a person
 * would: the palette's default spot covers no button). That button is enabled once the palette is in the window.
 * The lines table edits Qty in a plain spinner, and Code and Ref in validating editors that refuse anything but
 * digits: Code silently, Ref with a message dialog.
 */
public final class FixtureApp {

    private FixtureApp() {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Orders");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            JTextField customer = new JTextField(10);
            customer.setName("customer");
            JTextField notes = new JTextField(10);
            notes.setName("notes");
            JLabel status = new JLabel("idle");
            status.setName("status");
            JLabel totalCaption = new JLabel("Total");
            JLabel total = new JLabel("€0.00");
            totalCaption.setLabelFor(total);

            JButton save = new JButton("Save");
            save.setName("save");
            save.addActionListener(e -> {
                status.setText("saved " + customer.getText());
                total.setText("€7.32");
            });
            JButton fresh = new JButton("New");
            fresh.setName("new");
            fresh.addActionListener(e -> {
                customer.setText("");
                notes.setText("");
                total.setText("€0.00");
                customer.requestFocusInWindow();
            });
            JButton delete = new JButton("Delete");
            delete.setName("delete");
            delete.addActionListener(e -> {
                int answer = JOptionPane.showConfirmDialog(frame, "Delete ORD-1640?", "Confirm", JOptionPane.YES_NO_OPTION);
                status.setText(answer == JOptionPane.YES_OPTION ? "deleted" : "kept");
            });

            JLabel attachments = new JLabel("[attachments]");
            attachments.setName("attachments");
            new DropTarget(attachments, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
                @Override
                public void drop(DropTargetDropEvent e) {
                    e.acceptDrop(DnDConstants.ACTION_COPY);
                    try {
                        status.setText("attached " + e.getTransferable().getTransferData(DataFlavor.stringFlavor));
                        e.dropComplete(true);
                    } catch (Exception ex) {
                        e.dropComplete(false);
                    }
                }
            });
            JPopupMenu menu = new JPopupMenu();
            JMenuItem clear = new JMenuItem("Clear attachments");
            clear.addActionListener(e -> status.setText("attachments cleared"));
            menu.add(clear);
            attachments.setComponentPopupMenu(menu);

            // Qty edits in a plain spinner: no key forwarding and no commit of its own, so Enter leaves it open
            DefaultTableModel lines = new DefaultTableModel(new Object[][] {{"Paper", 1, "10", "20"}},
                    new Object[] {"Product", "Qty", "Code", "Ref"}) {
                @Override public Class<?> getColumnClass(int column) { return column == 1 ? Integer.class : String.class; }
                @Override public boolean isCellEditable(int row, int column) { return column >= 1; }
            };
            JTable linesTable = new JTable(lines);
            linesTable.setName("lines");
            linesTable.getColumnModel().getColumn(1).setCellEditor(new SpinnerCellEditor());
            DigitsCellEditor digits = new DigitsCellEditor(null);
            digits.addCellEditorListener(new CellEditorListener() {
                @Override public void editingStopped(ChangeEvent e) { }
                @Override public void editingCanceled(ChangeEvent e) { status.setText("code cancelled"); }
            });
            linesTable.getColumnModel().getColumn(2).setCellEditor(digits);
            int[] refusals = {0};
            linesTable.getColumnModel().getColumn(3).setCellEditor(new DigitsCellEditor(field -> {
                status.setText("ref refused " + ++refusals[0]);
                JOptionPane.showMessageDialog(field, "Digits only", "Ref", JOptionPane.ERROR_MESSAGE);
            }));
            lines.addTableModelListener(e -> status.setText("qty " + lines.getValueAt(0, 1) + " code " + lines.getValueAt(0, 2)));
            JScrollPane linesPane = new JScrollPane(linesTable);
            linesPane.setPreferredSize(new Dimension(260, 60));

            JPanel form = new JPanel(new FlowLayout());
            for (Component c : new Component[] {customer, notes, save, fresh, delete, attachments, status, totalCaption, total,
                    linesPane}) form.add(c);
            JButton archive = new JButton("Archive");
            archive.setName("archive");
            archive.addActionListener(e -> status.setText("archived"));
            JButton paletteOver = new JButton("Palette over Archive");
            paletteOver.setName("paletteOverArchive");
            paletteOver.addActionListener(e -> status.setText(dragPaletteOver(archive) ? "palette over archive" : "no palette"));
            // the palette comes into the window between the agent's steps, never during one: a test waits for it here
            paletteOver.setEnabled(false);
            frame.getLayeredPane().addContainerListener(new ContainerAdapter() {
                @Override public void componentAdded(ContainerEvent e) {
                    if (isBar(e.getChild())) paletteOver.setEnabled(true);
                }
            });
            JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            south.add(paletteOver);
            south.add(archive);
            JPanel content = new JPanel(new BorderLayout());
            content.add(form, BorderLayout.CENTER);
            content.add(south, BorderLayout.SOUTH);
            frame.setContentPane(content);
            frame.setSize(760, 230);
            frame.setLocation(40, 40);
            frame.setVisible(true);
        });
    }

    /**
     * Drags the person's palette (the swing-ui-agent bar in the window's layered pane) over {@code target} the way a
     * person does: press on its grip, drag, release. True when it then covers {@code target}.
     */
    static boolean dragPaletteOver(JComponent target) {
        JLayeredPane layers = target.getRootPane().getLayeredPane();
        for (Component bar : layers.getComponents()) {
            if (!isBar(bar)) continue;
            Point to = SwingUtilities.convertPoint(target.getParent(), target.getLocation(), layers);
            int x = 5 + to.x - bar.getX();
            int y = 5 + to.y - bar.getY();
            long now = System.currentTimeMillis();
            bar.dispatchEvent(new MouseEvent(bar, MouseEvent.MOUSE_PRESSED, now, InputEvent.BUTTON1_DOWN_MASK, 5, 5, 1, false,
                    MouseEvent.BUTTON1));
            bar.dispatchEvent(new MouseEvent(bar, MouseEvent.MOUSE_DRAGGED, now, InputEvent.BUTTON1_DOWN_MASK, x, y, 0, false,
                    MouseEvent.BUTTON1));
            // the bar has moved with the drag: the grip is under the pointer again
            bar.dispatchEvent(new MouseEvent(bar, MouseEvent.MOUSE_RELEASED, now, 0, 5, 5, 1, false, MouseEvent.BUTTON1));
            return SwingUtilities.convertRectangle(target.getParent(), target.getBounds(), layers).intersects(bar.getBounds());
        }
        return false;
    }

    /** {@code c} is the person's palette (or the agent's bar in its place). */
    private static boolean isBar(Component c) {
        return c.getClass().getName().endsWith(".inspect.Bar");
    }

    /** A spinner cell editor as an app would write it in a hurry: the value is the spinner's, nothing more. */
    static final class SpinnerCellEditor extends AbstractCellEditor implements TableCellEditor {
        private final JSpinner spinner = new JSpinner(new SpinnerNumberModel(1, 1, 999, 1));

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean selected, int row, int column) {
            spinner.setValue(value);
            return spinner;
        }

        @Override
        public Object getCellEditorValue() { return spinner.getValue(); }
    }

    /**
     * A validating editor: stopping is refused unless the text is all digits. Its field has no action of its own,
     * so Enter reaches the table's selectNextRowCell, which asks the editor to stop: a refusal leaves the edit open.
     * A {@code refusal} explains it to the user (a message dialog), as an app validating on Enter does.
     */
    static final class DigitsCellEditor extends AbstractCellEditor implements TableCellEditor {
        private final JTextField field = new JTextField();
        private final Consumer<JTextField> refusal;

        DigitsCellEditor(Consumer<JTextField> refusal) {
            this.refusal = refusal;
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean selected, int row, int column) {
            field.setText(String.valueOf(value));
            return field;
        }

        @Override
        public Object getCellEditorValue() { return field.getText(); }

        @Override
        public boolean stopCellEditing() {
            if (field.getText().matches("\\d+")) return super.stopCellEditing();
            if (refusal != null) refusal.accept(field);
            return false;
        }
    }
}
