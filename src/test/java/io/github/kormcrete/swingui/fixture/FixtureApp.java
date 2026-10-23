package io.github.kormcrete.swingui.fixture;

import javax.swing.AbstractCellEditor;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.DropMode;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.event.CellEditorListener;
import javax.swing.event.ChangeEvent;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DragSource;
import java.awt.dnd.DragSourceAdapter;
import java.awt.dnd.DragSourceDropEvent;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * A plain Swing order form with no application framework, for driving through swing-ui-agent in its own JVM
 * (FixtureAppDriveIT): two fields (Notes with a plain border and a caret that does not blink, so a picture of it
 * shows whether its caret was painted), Save, a New that moves focus itself, a Delete that asks in a modal dialog,
 * an attachments drop target with a context menu that lights up while the pointer is over it, a lines table, a status label that tells what happened, a Total
 * caption beside the value label it names (Save sets the value, New sets it back to €0.00) and an
 * Archive button bottom right, with a button beside it that drags the person's palette over Archive (as a person
 * would: the palette's default spot covers no button). That button is enabled once the palette is in the window.
 * The lines table edits Qty in a plain spinner, and Code and Ref in validating editors that refuse anything but
 * digits: Code silently, Ref with a message dialog.
 * <p>Its one menu, {@code Window › Register}, opens the Register window ({@link #showRegister}): records the agent
 * copies, so that each run of a recorded test makes a record with a new number, and opens in a window of their own,
 * titled with their number. {@code Window › Line order} opens the Line order window ({@link #showLineOrder}): order
 * lines a person reorders by dragging a row onto another, with AWT drag and drop.
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
            // a plain border and a caret that does not blink: focused, Notes differs from unfocused by its caret alone
            notes.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Color.GRAY),
                    BorderFactory.createEmptyBorder(2, 3, 2, 3)));
            notes.getCaret().setBlinkRate(0);
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
            // the drop zone lights up while the pointer is over it
            final Color attachmentsColor = attachments.getForeground();
            attachments.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { attachments.setForeground(new Color(0x1E5AA8)); }
                @Override public void mouseExited(MouseEvent e) { attachments.setForeground(attachmentsColor); }
            });
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
            // its one item acts on the selected row, and is enabled only while there is one
            JPopupMenu lineMenu = new JPopupMenu();
            JMenuItem copyProduct = new JMenuItem("Copy product");
            copyProduct.addActionListener(e -> status.setText("copied " + lines.getValueAt(linesTable.getSelectedRow(), 0)));
            lineMenu.add(copyProduct);
            lineMenu.addPopupMenuListener(new PopupMenuListener() {
                @Override public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                    copyProduct.setEnabled(linesTable.getSelectedRow() >= 0);
                }
                @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { }
                @Override public void popupMenuCanceled(PopupMenuEvent e) { }
            });
            linesTable.setComponentPopupMenu(lineMenu);
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
            // a tab the order can close, for the checks that something is gone
            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("ORD-1640", new JLabel("receipt"));
            tabs.setName("orderTabs");
            tabs.setPreferredSize(new Dimension(110, 44));
            JButton closeTab = new JButton("Close tab");
            closeTab.setName("closeTab");
            closeTab.addActionListener(e -> {
                if (tabs.getTabCount() > 0) tabs.removeTabAt(0);
                status.setText("tab closed");
            });
            south.add(tabs);
            south.add(closeTab);
            south.add(paletteOver);
            south.add(archive);
            JPanel content = new JPanel(new BorderLayout());
            content.add(form, BorderLayout.CENTER);
            content.add(south, BorderLayout.SOUTH);
            frame.setContentPane(content);
            JMenuBar menuBar = new JMenuBar();
            JMenu windowMenu = new JMenu("Window");
            JMenuItem registerItem = new JMenuItem("Register");
            registerItem.addActionListener(e -> showRegister());
            windowMenu.add(registerItem);
            JMenuItem lineOrderItem = new JMenuItem("Line order");
            lineOrderItem.addActionListener(e -> showLineOrder());
            windowMenu.add(lineOrderItem);
            menuBar.add(windowMenu);
            frame.setJMenuBar(menuBar);
            // the menu bar takes its own height: the content keeps the size the other cases were written against
            frame.setSize(760, 260 + menuBar.getPreferredSize().height);
            frame.setLocation(40, 40);
            frame.setVisible(true);
        });
    }

    /** The Register window: made the first time {@code Window › Register} is chosen, shown again after that. EDT. */
    private static JFrame register;

    /**
     * Opens the Register window, or brings it back as it was: its records stay, so a second run begins with the ones
     * the first made. Copy copies the selected record under the next number, late (a timer, as an app saving in the
     * background does); Save note writes the Note field into the selected record; Open shows the selected record in a
     * new window titled with its number, with a tab of that name (a label showing the number) and a History tab. Add
     * tag and New folder add an entry with the next number to the Tags list and the Folders tree.
     */
    static void showRegister() {
        if (register == null) register = registerFrame();
        register.setVisible(true);
        register.toFront();
    }

    private static JFrame registerFrame() {
        JFrame frame = new JFrame("Register");
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        DefaultTableModel records = new DefaultTableModel(new Object[][] {{"REG-1001", "Paper order"},
                {"REG-1002", "Pens order"}}, new Object[] {"Number", "Note"}) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        JTable table = new JTable(records);
        table.setName("Register");
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        int[] lastNumber = {1002};
        JButton copy = new JButton("Copy");
        copy.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) return;
            Object[] copied = {"REG-" + ++lastNumber[0], records.getValueAt(row, 1)};
            Timer later = new Timer(150, done -> records.addRow(copied));
            later.setRepeats(false);
            later.start();
        });
        JLabel noteCaption = new JLabel("Note");
        JTextField note = new JTextField(14);
        noteCaption.setLabelFor(note);
        JButton saveNote = new JButton("Save note");
        saveNote.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) records.setValueAt(note.getText(), row, 1);
        });
        JButton open = new JButton("Open");
        open.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) return;
            String number = String.valueOf(records.getValueAt(row, 0));
            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab(number, new JLabel(number));
            tabs.addTab("History", new JLabel("No changes yet"));
            JFrame record = new JFrame(number);
            record.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            record.setContentPane(tabs);
            record.setSize(320, 200);
            record.setLocation(620, 340);
            record.setVisible(true);
        });

        DefaultListModel<String> tagModel = new DefaultListModel<String>();
        tagModel.addElement("TAG-1");
        tagModel.addElement("TAG-2");
        JList<String> tags = new JList<String>(tagModel);
        tags.setName("Tags");
        JButton addTag = new JButton("Add tag");
        addTag.addActionListener(e -> tagModel.addElement("TAG-" + (tagModel.getSize() + 1)));

        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Folders");
        root.add(new DefaultMutableTreeNode("Folder 1"));
        root.add(new DefaultMutableTreeNode("Folder 2"));
        DefaultTreeModel folderModel = new DefaultTreeModel(root);
        JTree folders = new JTree(folderModel);
        folders.setName("Folders");
        JButton newFolder = new JButton("New folder");
        newFolder.addActionListener(e -> {
            folderModel.insertNodeInto(new DefaultMutableTreeNode("Folder " + (root.getChildCount() + 1)), root,
                    root.getChildCount());
            folders.expandPath(new TreePath(root));
        });

        JPanel recordButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (Component c : new Component[] {copy, noteCaption, note, saveNote, open}) recordButtons.add(c);
        JPanel recordsPanel = new JPanel(new BorderLayout());
        recordsPanel.add(new JScrollPane(table), BorderLayout.CENTER);
        recordsPanel.add(recordButtons, BorderLayout.SOUTH);
        JPanel tagsPanel = new JPanel(new BorderLayout());
        tagsPanel.add(new JScrollPane(tags), BorderLayout.CENTER);
        tagsPanel.add(addTag, BorderLayout.SOUTH);
        JPanel foldersPanel = new JPanel(new BorderLayout());
        foldersPanel.add(new JScrollPane(folders), BorderLayout.CENTER);
        foldersPanel.add(newFolder, BorderLayout.SOUTH);
        JPanel entries = new JPanel(new GridLayout(1, 2));
        entries.add(tagsPanel);
        entries.add(foldersPanel);
        entries.setPreferredSize(new Dimension(520, 150));
        JPanel content = new JPanel(new BorderLayout());
        content.add(recordsPanel, BorderLayout.CENTER);
        content.add(entries, BorderLayout.SOUTH);
        frame.setContentPane(content);
        frame.setSize(560, 420);
        frame.setLocation(40, 340);
        return frame;
    }

    /** The Line order window: made the first time {@code Window › Line order} is chosen, shown again after that. EDT. */
    private static JFrame lineOrder;

    /**
     * Opens the Line order window, three lists whose entries move by dragging one onto another, and a status line that
     * says what moved:
     * <ul>
     * <li>Order lines (Coffee, Tea, Milk), with AWT drag and drop as an app wires it by hand: a drag gesture recognizer
     * on the table notes the row the drag starts on and starts the drag with the product's name, and the table's drop
     * target accepts the drop and moves that row to the row it is dropped on;</li>
     * <li>Packing lines (Bread, Butter, Jam), the same, except that its drop asks first ("Move Jam to line 1?") and
     * moves the row without telling the toolkit it took the drop (no acceptDrop, no dropComplete), as some apps do;</li>
     * <li>a Pick list (Apples, Pears, Plums) with Swing's own drag and drop: drag enabled, and a TransferHandler that
     * moves the dragged entry to the entry it is dropped on.</li>
     * </ul>
     */
    static void showLineOrder() {
        if (lineOrder == null) lineOrder = lineOrderFrame();
        lineOrder.setVisible(true);
        lineOrder.toFront();
    }

    private static JFrame lineOrderFrame() {
        JFrame frame = new JFrame("Line order");
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        DefaultTableModel model = new DefaultTableModel(new Object[][] {{"Coffee", 2}, {"Tea", 1}, {"Milk", 3}},
                new Object[] {"Product", "Qty"}) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        JTable table = new JTable(model);
        table.setName("orderLines");
        JLabel moved = new JLabel("no line moved");
        moved.setName("lineStatus");
        reorderByDragAndDrop(table, model, moved, false);
        DefaultTableModel packing = new DefaultTableModel(new Object[][] {{"Bread", 1}, {"Butter", 2}, {"Jam", 1}},
                new Object[] {"Product", "Qty"}) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        JTable packTable = new JTable(packing);
        packTable.setName("packLines");
        reorderByDragAndDrop(packTable, packing, moved, true);

        DefaultListModel<String> picks = new DefaultListModel<String>();
        for (String s : new String[] {"Apples", "Pears", "Plums"}) picks.addElement(s);
        JList<String> pickList = new JList<String>(picks);
        pickList.setName("pickList");
        pickList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        pickList.setDragEnabled(true);
        pickList.setDropMode(DropMode.ON);
        pickList.setTransferHandler(new TransferHandler() {
            private static final long serialVersionUID = 1L;
            private int from = -1;

            @Override public int getSourceActions(JComponent c) { return MOVE; }

            @Override protected Transferable createTransferable(JComponent c) {
                from = pickList.getSelectedIndex();
                return new StringSelection(pickList.getSelectedValue());
            }

            @Override public boolean canImport(TransferSupport support) {
                return support.isDrop() && support.isDataFlavorSupported(DataFlavor.stringFlavor);
            }

            @Override public boolean importData(TransferSupport support) {
                int to = ((JList.DropLocation) support.getDropLocation()).getIndex();
                if (!canImport(support) || from < 0 || to < 0) return false;
                String pick = picks.remove(from);
                picks.add(to, pick);
                moved.setText("moved " + pick + " to pick " + (to + 1));
                return true;
            }
        });

        JPanel lists = new JPanel(new GridLayout(1, 3));
        lists.add(new JScrollPane(table));
        lists.add(new JScrollPane(packTable));
        lists.add(new JScrollPane(pickList));
        JPanel content = new JPanel(new BorderLayout());
        content.add(lists, BorderLayout.CENTER);
        content.add(moved, BorderLayout.SOUTH);
        frame.setContentPane(content);
        frame.setSize(600, 180);
        frame.setLocation(400, 300);
        return frame;
    }

    /**
     * AWT drag and drop that moves a row of {@code table} onto another, wired by hand. With {@code asks}, the drop asks
     * first and moves the row without acceptDrop or dropComplete.
     */
    private static void reorderByDragAndDrop(JTable table, DefaultTableModel model, JLabel moved, boolean asks) {
        int[] dragRow = {-1};
        DragSource.getDefaultDragSource().createDefaultDragGestureRecognizer(table, DnDConstants.ACTION_MOVE, gesture -> {
            int row = table.rowAtPoint(gesture.getDragOrigin());
            if (row < 0) return;
            dragRow[0] = row;
            gesture.startDrag(DragSource.DefaultMoveDrop, new StringSelection(String.valueOf(model.getValueAt(row, 0))),
                    new DragSourceAdapter() {
                        @Override public void dragDropEnd(DragSourceDropEvent e) { dragRow[0] = -1; }
                    });
        });
        new DropTarget(table, DnDConstants.ACTION_MOVE, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent e) {
                int to = table.rowAtPoint(e.getLocation());
                int from = dragRow[0];
                if (from < 0 || to < 0 || !e.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    e.rejectDrop();
                    return;
                }
                Object product = model.getValueAt(from, 0);
                if (asks) {
                    int answer = JOptionPane.showConfirmDialog(table, "Move " + product + " to line " + (to + 1) + "?",
                            "Move line", JOptionPane.YES_NO_OPTION);
                    if (answer == JOptionPane.YES_OPTION) {
                        model.moveRow(from, from, to);
                        moved.setText("moved " + product + " to line " + (to + 1));
                    }
                    return;
                }
                e.acceptDrop(DnDConstants.ACTION_MOVE);
                try {
                    Object data = e.getTransferable().getTransferData(DataFlavor.stringFlavor);
                    model.moveRow(from, from, to);
                    moved.setText("moved " + data + " to line " + (to + 1));
                    e.dropComplete(true);
                } catch (Exception ex) {
                    e.dropComplete(false);
                }
            }
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
