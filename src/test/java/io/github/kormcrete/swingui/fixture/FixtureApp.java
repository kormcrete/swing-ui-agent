package io.github.kormcrete.swingui.fixture;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.FlowLayout;

/** A plain Swing app with no application framework, for driving through swing-ui-agent: a button and the label it changes. */
public final class FixtureApp {

    private FixtureApp() {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("swing-ui-agent fixture");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.getContentPane().setLayout(new FlowLayout());
            JLabel label = new JLabel("idle");
            label.setName("fixture.label");
            JButton button = new JButton("Press");
            button.setName("fixture.button");
            button.addActionListener(e -> label.setText("clicked"));
            frame.getContentPane().add(button);
            frame.getContentPane().add(label);
            frame.setSize(320, 120);
            frame.setVisible(true);
        });
    }
}
