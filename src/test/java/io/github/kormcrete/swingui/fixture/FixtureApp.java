package io.github.kormcrete.swingui.fixture;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;

/**
 * A plain Swing order form with no application framework, for driving through swing-ui-agent in its own JVM
 * (FixtureAppDriveIT): two fields, Save, a New that moves focus itself, a Delete that asks in a modal dialog,
 * an attachments drop target with a context menu, and a status label that tells what happened.
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

            JButton save = new JButton("Save");
            save.setName("save");
            save.addActionListener(e -> status.setText("saved " + customer.getText()));
            JButton fresh = new JButton("New");
            fresh.setName("new");
            fresh.addActionListener(e -> {
                customer.setText("");
                notes.setText("");
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

            JPanel form = new JPanel(new FlowLayout());
            for (Component c : new Component[] {customer, notes, save, fresh, delete, attachments, status}) form.add(c);
            frame.setContentPane(form);
            frame.setSize(760, 110);
            frame.setLocation(40, 40);
            frame.setVisible(true);
        });
    }
}
