package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.FileChooserState;
import io.github.kormcrete.swingui.driver.PendingDialog;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Names;
import io.github.kormcrete.swingui.driver.model.Roles;
import io.github.kormcrete.swingui.driver.window.AppWindows;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.LookAndFeel;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
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

    /**
     * What {@code dialog} asks: its message, one line per line it shows (a label, a line of a text, a part of an
     * option pane's message), and its answers: the buttons, but not those inside an option pane's message, which do
     * something else than answer it ("Details…").
     */
    public static PendingDialog describe(Window dialog, Names names) {
        return describe(AppWindows.titleOf(dialog), content(dialog), names);
    }

    public static PendingDialog describe(String title, Container content, Names names) {
        JOptionPane pane = first(content, JOptionPane.class);
        String message = pane == null ? null : messageText(pane.getMessage());
        if (message == null) message = labelText(content);
        List<String> buttons = new ArrayList<String>();
        collectButtons(content, pane, buttons);
        List<String> fields = new ArrayList<String>();
        collectFields(content, content, names, fields);
        return new PendingDialog(title, message == null ? "" : message, buttons, fields);
    }

    /**
     * The message or confirmation {@code dialog} shows as an option pane, read as {@link #describe(Window, Names)} does;
     * null when {@code dialog} is null or shows none.
     */
    public static PendingDialog optionPane(Window dialog, Names names) {
        if (dialog == null || first(content(dialog), JOptionPane.class) == null) return null;
        return describe(dialog, names);
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
        if (hits.size() > 1) {
            // an option pane's answer wins over a button of the same label inside its message
            JOptionPane pane = first(content, JOptionPane.class);
            List<AbstractButton> answers = new ArrayList<AbstractButton>();
            for (AbstractButton b : hits) {
                if (!inMessage(b, pane)) answers.add(b);
            }
            if (answers.size() == 1) return answers.get(0);
        }
        if (hits.size() > 1) throw new DriverException(Reason.AMBIGUOUS, hits.size() + " buttons read '" + label + "'");
        return hits.get(0);
    }

    /** The innermost showing file chooser of the app, or null. */
    public static JFileChooser showingFileChooser() {
        JFileChooser found = null;
        for (Window w : Window.getWindows()) {
            if (!w.isShowing() || !appChooserWindow(w)) continue;
            List<JFileChooser> choosers = new ArrayList<JFileChooser>();
            collect(w, JFileChooser.class, choosers);
            for (JFileChooser c : choosers) {
                if (c.isShowing()) found = c;
            }
        }
        return found;
    }

    /** Whether a file chooser in {@code w} may be the app's: never one in the palette's own folder dialog. */
    public static boolean appChooserWindow(Window w) {
        return !Roles.agentOwn(w);
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

    /** The lines of an option pane's message: a text's lines, a component's labels and read-only texts, in order. */
    private static String messageText(Object message) {
        List<String> lines = new ArrayList<String>();
        addLines(message, lines);
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    private static void addLines(Object message, List<String> lines) {
        if (message == null) return;
        if (message instanceof Object[]) {
            for (Object part : (Object[]) message) addLines(part, lines);
        } else if (message instanceof Component) {
            addComponentLines((Component) message, lines);
        } else {
            addTextLines(String.valueOf(message), lines);
        }
    }

    private static void addComponentLines(Component c, List<String> lines) {
        if (!c.isVisible()) return;
        if (c instanceof JLabel) {
            addTextLines(((JLabel) c).getText(), lines);
        } else if (c instanceof JTextComponent) {
            // an editable one is a field the dialog asks for, not part of what it says
            if (!((JTextComponent) c).isEditable()) addTextLines(((JTextComponent) c).getText(), lines);
        } else if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) addComponentLines(child, lines);
        }
    }

    /** {@code text}'s non-blank lines, cleaned; an HTML text breaks at {@code <br>}, paragraphs, divs and list items. */
    private static void addTextLines(String text, List<String> lines) {
        if (text == null) return;
        boolean html = text.regionMatches(true, 0, "<html>", 0, 6);
        String t = html ? text.replaceAll("(?i)<br\\s*/?>|</?(p|div|li)\\b[^>]*>", "\n") : text;
        for (String line : t.split("\\r?\\n")) {
            String clean = Names.clean(html ? "<html>" + line : line);
            if (clean != null) lines.add(clean);
        }
    }

    /** The first {@link #MESSAGE_LABELS} labels' lines: what a dialog without an option pane most likely says. */
    private static String labelText(Container content) {
        List<JLabel> labels = new ArrayList<JLabel>();
        collect(content, JLabel.class, labels);
        List<String> lines = new ArrayList<String>();
        int used = 0;
        for (JLabel l : labels) {
            if (Names.clean(l.getText()) == null) continue;
            addTextLines(l.getText(), lines);
            if (++used == MESSAGE_LABELS) break;
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    private static void collectButtons(Container content, JOptionPane pane, List<String> out) {
        List<JButton> buttons = new ArrayList<JButton>();
        collect(content, JButton.class, buttons);
        for (JButton b : buttons) {
            String t = Names.clean(b.getText());
            if (t != null && !inMessage(b, pane)) out.add(t);
        }
    }

    /** Whether {@code c} sits inside {@code pane}'s message (a component, or a component part of it); false without a pane. */
    private static boolean inMessage(Component c, JOptionPane pane) {
        if (pane == null) return false;
        Object message = pane.getMessage();
        Object[] parts = message instanceof Object[] ? (Object[]) message : new Object[] {message};
        for (Object part : parts) {
            if (part instanceof Component && SwingUtilities.isDescendingFrom(c, (Component) part)) return true;
        }
        return false;
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
