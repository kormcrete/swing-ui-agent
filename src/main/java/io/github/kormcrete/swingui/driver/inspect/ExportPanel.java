package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.widgets.Screenshots;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Graphics;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.BiConsumer;

/**
 * Export…: the person names the JUnit class (and its package) the recording is written to, without an agent. A refused
 * export keeps the panel open with the reason, so they can fix the name and try again.
 */
final class ExportPanel extends JPanel {

/** Not in a screenshot: the person's palette is never in an image. */
@Override
public void paint(Graphics g) {
    if (!Screenshots.capturing()) super.paint(g);
}

    private final JTextField className = new JTextField(22);
    private final JTextField packageName = new JTextField(22);
    private final JLabel message = new JLabel(" ");
    private final JButton export;

    ExportPanel(String suggestedClass, String suggestedPackage, BiConsumer<String, String> onExport, Runnable close) {
        super(new BorderLayout(0, 6));
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Overlay.NAVY, 2),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        setBackground(Color.WHITE);
        JLabel title = new JLabel("Export as a JUnit test");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        add(title, BorderLayout.NORTH);

        className.setText(suggestedClass);
        packageName.setText(suggestedPackage);
        JPanel fields = new JPanel(new GridBagLayout());
        fields.setOpaque(false);
        row(fields, 0, "Class", className);
        row(fields, 1, "Package", packageName);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 0, 0, 0);
        message.setForeground(Overlay.NO);
        fields.add(message, c);
        add(fields, BorderLayout.CENTER);

        export = small("Export", () -> {
            message.setText(" ");
            onExport.accept(className.getText().trim(), packageName.getText().trim());
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.setOpaque(false);
        buttons.add(small("Cancel", close));
        buttons.add(export);
        add(buttons, BorderLayout.SOUTH);
        className.addActionListener(e -> export.doClick());
        packageName.addActionListener(e -> export.doClick());
        SwingUtilities.invokeLater(() -> {
            className.requestFocusInWindow();
            className.selectAll();
        });
    }

    /** What the fields hold now: class, package. */
    String[] names() {
        return new String[] {className.getText(), packageName.getText()};
    }

    /** The message under the fields: blank, or why the last export wrote nothing. */
    String message() {
        return message.getText();
    }

    /** As if the person pressed Export. */
    void submit() {
        export.doClick();
    }

    /** Why the last export wrote nothing. */
    void refused(String why) {
        message.setText("Not exported: " + why);
        revalidate();
    }

    private static void row(JPanel to, int y, String label, JTextField field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 0, 2, 8);
        to.add(new JLabel(label), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(2, 0, 2, 0);
        to.add(field, c);
    }

    private static JButton small(String label, Runnable action) {
        JButton b = new JButton(label);
        b.setFocusable(false);
        b.setMargin(new Insets(2, 6, 2, 6));
        b.addActionListener(e -> action.run());
        return b;
    }
}
