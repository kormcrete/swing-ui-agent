package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.FileChooserState;
import io.github.kormcrete.swingui.driver.PendingDialog;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Names;
import io.github.kormcrete.swingui.driver.window.AppWindows;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.LookAndFeel;
import javax.swing.RootPaneContainer;
import javax.swing.UIManager;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Dialogs, option panes and file choosers: what they ask, and their answers. EDT. */
public final class Dialogs {

    private static final int MESSAGE_LABELS = 3;

    private Dialogs() {}

    public static PendingDialog describe(Window dialog, Names names) {
        return describe(AppWindows.titleOf(dialog), content(dialog), names);
    }

    public static PendingDialog describe(String title, Container content, Names names) {
        JOptionPane pane = first(content, JOptionPane.class);
        String message = pane == null ? null : messageText(pane.getMessage());
        if (message == null) message = labelText(content);
        List<String> buttons = new ArrayList<String>();
        collectButtons(content, buttons);
        List<String> fields = new ArrayList<String>();
        collectFields(content, content, names, fields);
        return new PendingDialog(title, message == null ? "" : message, buttons, fields);
    }

    public static AbstractButton button(Window dialog, String label) {
        return button(content(dialog), label);
    }

    public static AbstractButton button(Container content, String label) {
        List<AbstractButton> all = new ArrayList<AbstractButton>();
        collect(content, JButton.class, all);
        List<String> texts = new ArrayList<String>();
        List<AbstractButton> hits = new ArrayList<AbstractButton>();
        for (AbstractButton b : all) {
            String text = Names.clean(b.getText());
            if (text == null) continue;
            texts.add(text);
            if (text.equals(label)) hits.add(b);
        }
        if (hits.isEmpty()) throw new DriverException(Reason.NOT_FOUND, "no button '" + label + "'; buttons are " + texts);
        if (hits.size() > 1) throw new DriverException(Reason.AMBIGUOUS, hits.size() + " buttons read '" + label + "'");
        return hits.get(0);
    }

    /** The innermost showing file chooser, or null. */
    public static JFileChooser showingFileChooser() {
        JFileChooser found = null;
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            List<JFileChooser> choosers = new ArrayList<JFileChooser>();
            collect(w, JFileChooser.class, choosers);
            for (JFileChooser c : choosers) {
                if (c.isShowing()) found = c;
            }
        }
        return found;
    }

    /** The chooser's file-name field, or null for a look and feel without one. */
    public static JTextField fileNameField(JFileChooser chooser) {
        List<JTextField> fields = new ArrayList<JTextField>();
        collect(chooser, JTextField.class, fields);
        for (JTextField f : fields) {
            if (f.isEditable()) return f;
        }
        return null;
    }

    /**
     * {@code chooser}'s folder, file-name field and the look and feel; {@code focusOwner} is the keyboard
     * focus owner, or null.
     */
    public static FileChooserState fileChooserState(JFileChooser chooser, Component focusOwner) {
        File dir = chooser.getCurrentDirectory();
        JTextField name = fileNameField(chooser);
        LookAndFeel laf = UIManager.getLookAndFeel();
        return new FileChooserState(dir == null ? null : dir.getAbsolutePath(), name == null ? null : name.getText(),
                name != null && name == focusOwner, laf == null ? null : laf.getName());
    }

    private static Container content(Window dialog) {
        return dialog instanceof RootPaneContainer ? ((RootPaneContainer) dialog).getContentPane() : dialog;
    }

    private static String messageText(Object message) {
        if (message == null) return null;
        if (message instanceof String) return Names.clean((String) message);
        if (message instanceof Object[]) {
            StringBuilder sb = new StringBuilder();
            for (Object part : (Object[]) message) {
                String t = messageText(part);
                if (t != null) sb.append(sb.length() > 0 ? " " : "").append(t);
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        if (message instanceof Container) return labelText((Container) message);
        return Names.clean(String.valueOf(message));
    }

    private static String labelText(Container content) {
        List<JLabel> labels = new ArrayList<JLabel>();
        collect(content, JLabel.class, labels);
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (JLabel l : labels) {
            String t = Names.clean(l.getText());
            if (t == null) continue;
            sb.append(sb.length() > 0 ? " " : "").append(t);
            if (++used == MESSAGE_LABELS) break;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void collectButtons(Container content, List<String> out) {
        List<JButton> buttons = new ArrayList<JButton>();
        collect(content, JButton.class, buttons);
        for (JButton b : buttons) {
            String t = Names.clean(b.getText());
            if (t != null) out.add(t);
        }
    }

    private static void collectFields(Container root, Container content, Names names, List<String> out) {
        List<JTextComponent> fields = new ArrayList<JTextComponent>();
        collect(content, JTextComponent.class, fields);
        for (JTextComponent f : fields) {
            if (!f.isEditable()) continue;
            String name = names.nameOf(f, root);
            out.add(name == null ? "text" : name);
        }
    }

    private static <T> T first(Container c, Class<T> type) {
        List<T> out = new ArrayList<T>();
        collect(c, type, out);
        return out.isEmpty() ? null : out.get(0);
    }

    private static <T> void collect(Component c, Class<T> type, List<? super T> out) {
        if (!c.isVisible()) return;
        if (type.isInstance(c)) out.add(type.cast(c));
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) collect(child, type, out);
        }
    }
}
