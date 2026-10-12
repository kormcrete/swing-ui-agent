package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.FileChooserState;
import io.github.kormcrete.swingui.driver.PendingDialog;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Names;
import io.github.kormcrete.swingui.driver.model.Visibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.plaf.metal.MetalLookAndFeel;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class DialogsTest {

    private final Names names = new Names(Visibility.TREE);

    @Test
    void anOptionPaneGivesItsMessageAndAnswers() {
        JPanel content = new JPanel();
        content.add(new JOptionPane("Really delete?", JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION));
        PendingDialog d = Dialogs.describe("Delete", content, names);
        assertThat(d.title).isEqualTo("Delete");
        assertThat(d.message).isEqualTo("Really delete?");
        assertThat(d.buttons).hasSize(2);
        assertThat(d.fields).isEmpty();
    }

    @Test
    void aCustomDialogGivesItsLabelsButtonsAndFields() {
        JPanel content = new JPanel(null);
        JLabel label = new JLabel("Comment:");
        label.setBounds(0, 0, 80, 20);
        JTextField field = new JTextField();
        field.setBounds(90, 0, 150, 20);
        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        content.add(label);
        content.add(field);
        content.add(ok);
        content.add(cancel);
        PendingDialog d = Dialogs.describe("Save", content, names);
        assertThat(d.message).isEqualTo("Comment:");
        assertThat(d.buttons).containsExactly("OK", "Cancel");
        assertThat(d.fields).containsExactly("Comment");
        assertThat(Dialogs.button(content, "Cancel")).isSameAs(cancel);
    }

    @Test
    void anUnknownButtonListsTheButtons() {
        JPanel content = new JPanel();
        content.add(new JButton("OK"));
        DriverException e = catchThrowableOfType(() -> Dialogs.button(content, "Yes"), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.NOT_FOUND);
        assertThat(e.getMessage()).contains("[OK]");
    }

    @Test
    void aFileChooserGivesItsFolderItsFileNameAndWhetherThatHasFocus(@TempDir Path dir) throws Exception {
        File sub = Files.createDirectory(dir.resolve("Imports")).toFile();
        JFileChooser chooser = metalChooser(sub);
        JTextField name = Dialogs.fileNameField(chooser);
        assertThat(name).isNotNull();
        name.setText("orders.xlsx");
        FileChooserState s = Dialogs.fileChooserState(chooser, name);
        assertThat(new File(s.folder).getCanonicalPath()).isEqualTo(sub.getCanonicalPath());
        assertThat(s.name).isEqualTo("orders.xlsx");
        assertThat(s.nameFocused).isTrue();
        assertThat(s.lookAndFeel).isNotEmpty();
    }

    @Test
    void aFileChooserWhoseNameFieldLacksFocusSaysSo(@TempDir Path dir) throws Exception {
        JFileChooser chooser = metalChooser(dir.toFile());
        assertThat(Dialogs.fileChooserState(chooser, chooser).nameFocused).isFalse();
        assertThat(Dialogs.fileChooserState(chooser, null).nameFocused).isFalse();
    }

    /** Built under Metal, whose open dialog has a file-name field; Aqua's, the macOS default, has none. */
    private static JFileChooser metalChooser(File dir) throws UnsupportedLookAndFeelException {
        LookAndFeel before = UIManager.getLookAndFeel();
        UIManager.setLookAndFeel(new MetalLookAndFeel());
        try {
            return new JFileChooser(dir);
        } finally {
            UIManager.setLookAndFeel(before);
        }
    }
}
