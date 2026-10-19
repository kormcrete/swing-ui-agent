package io.github.kormcrete.swingui.driver.settle;

import org.junit.jupiter.api.Test;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import static org.assertj.core.api.Assertions.assertThat;

/** The error-dialog rules, on dialogs that are built but never shown. */
class ErrorDialogsTest {

    private final ErrorDialogs rules = new ErrorDialogs();

    @Test
    void withoutRulesNoDialogIsAnError() {
        assertThat(rules.isError(optionPane(JOptionPane.ERROR_MESSAGE))).isFalse();
    }

    @Test
    void theReadyMadeRuleTakesAnErrorTypeOptionPaneOnly() {
        assertThat(ErrorDialogs.ERROR_OPTION_PANE.isError(optionPane(JOptionPane.ERROR_MESSAGE))).isTrue();
        assertThat(ErrorDialogs.ERROR_OPTION_PANE.isError(optionPane(JOptionPane.QUESTION_MESSAGE))).isFalse();
        assertThat(ErrorDialogs.ERROR_OPTION_PANE.isError(optionPane(JOptionPane.WARNING_MESSAGE))).isFalse();
        assertThat(ErrorDialogs.ERROR_OPTION_PANE.isError(new JDialog())).isFalse();
    }

    @Test
    void anOptionPaneDeepInACustomDialogCounts() {
        JDialog dialog = new JDialog();
        JPanel outer = new JPanel();
        JPanel inner = new JPanel();
        inner.add(new JOptionPane("Disk full", JOptionPane.ERROR_MESSAGE));
        outer.add(inner);
        dialog.getContentPane().add(outer);
        assertThat(ErrorDialogs.ERROR_OPTION_PANE.isError(dialog)).isTrue();
    }

    @Test
    void addedRulesAddUp() {
        final JDialog crash = new JDialog();
        final JDialog report = new JDialog();
        rules.add(dialog -> dialog == crash);
        rules.add(dialog -> dialog == report);
        rules.add(null);
        assertThat(rules.isError(crash)).isTrue();
        assertThat(rules.isError(report)).isTrue();
        assertThat(rules.isError(new JDialog())).isFalse();
    }

    private static JDialog optionPane(int messageType) {
        return new JOptionPane("Something", messageType).createDialog(null, "Title");
    }
}
