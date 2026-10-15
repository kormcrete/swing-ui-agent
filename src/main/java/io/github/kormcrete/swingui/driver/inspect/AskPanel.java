package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.widgets.Screenshots;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Insets;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Asks the person for one line in the palette - a step's, a check's or a group's sentence - instead of a modal dialog:
 * it takes the steps panel's place while open. OK hands the trimmed text on; a refusal keeps the panel open with the
 * reason, so they can fix it; Cancel closes it. The palette's own: never seen by the snapshot, the recorder or a
 * screenshot. EDT.
 */
final class AskPanel extends JPanel {

    private final JLabel title = new JLabel();
    private final JTextField text = new JTextField(34);
    private final JLabel message = new JLabel(" ");
    private final JLabel hint = new JLabel();
    private final JButton ok;
    private final Runnable close;
    private final Function<String, CompletableFuture<Inspector.Result>> onOk;

    /**
     * {@code group}: a group's sentence, which empty cannot remove (Ungroup ends the group); else a step's or a
     * check's. {@code onOk} gets the trimmed text and completes with what became of it (any thread); {@code close}
     * closes the panel, after an accepted text too, and then {@code giveBack} gives the keyboard back to the app's
     * component that had it (null: none had it).
     */
    AskPanel(String title, boolean group, String initial, Runnable giveBack,
             Function<String, CompletableFuture<Inspector.Result>> onOk, Runnable close) {
        super(new BorderLayout(0, 6));
        this.close = () -> {
            close.run();
            if (giveBack != null) giveBack.run();
        };
        this.onOk = onOk;
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Overlay.NAVY, 2),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        setBackground(Color.WHITE);
        this.title.setText(title);
        this.title.setFont(this.title.getFont().deriveFont(Font.BOLD));
        add(this.title, BorderLayout.NORTH);
        text.setText(initial == null ? "" : initial);
        hint.setText(group ? "One line, at most 200 characters. A group keeps a sentence: Ungroup ends it."
                : "One line, at most 200 characters. Empty removes the sentence.");
        hint.setForeground(Color.GRAY);
        hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D() - 1f));
        message.setForeground(Overlay.NO);
        JPanel center = new JPanel(new BorderLayout(0, 4));
        center.setOpaque(false);
        center.add(text, BorderLayout.NORTH);
        center.add(hint, BorderLayout.CENTER);
        center.add(message, BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);
        ok = small("OK", this::submit);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.setOpaque(false);
        buttons.add(small("Cancel", this.close));
        buttons.add(ok);
        add(buttons, BorderLayout.SOUTH);
        text.addActionListener(e -> ok.doClick());
        SwingUtilities.invokeLater(() -> {
            text.requestFocusInWindow();
            text.selectAll();
        });
    }

    String title() {
        return title.getText();
    }

    /** The line under the field: what a sentence may be. */
    String hint() {
        return hint.getText();
    }

    /** What the field holds now. */
    String text() {
        return text.getText();
    }

    /** Blank, or why the last OK changed nothing. */
    String message() {
        return message.getText();
    }

    /** As if the person typed {@code s} over what the field held. */
    void type(String s) {
        text.setText(s);
    }

    /** As if the person pressed OK. */
    void ok() {
        ok.doClick();
    }

    /** As if the person pressed Cancel. */
    void cancel() {
        close.run();
    }

    /** OK: the text goes on; while it is being applied OK waits, a refusal shows under the field. */
    private void submit() {
        message.setText(" ");
        CompletableFuture<Inspector.Result> result = onOk.apply(text.getText().trim());
        if (result == null) return;
        ok.setEnabled(false);
        result.thenAccept(r -> SwingUtilities.invokeLater(() -> {
            ok.setEnabled(true);
            if (r.ok) close.run(); else message.setText("Not changed: " + r.line);
        }));
    }

    private static JButton small(String label, Runnable action) {
        JButton b = new JButton(label);
        b.setFocusable(false);
        b.setMargin(new Insets(2, 6, 2, 6));
        b.addActionListener(e -> action.run());
        return b;
    }

    /** Not in a screenshot: a capture leaves the palette out. */
    @Override
    public void paint(Graphics g) {
        if (!Screenshots.capturing()) super.paint(g);
    }
}
