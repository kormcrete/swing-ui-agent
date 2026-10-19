package io.github.kormcrete.swingui.spi;

import io.github.kormcrete.swingui.driver.Edt;
import io.github.kormcrete.swingui.driver.PendingDialog;
import io.github.kormcrete.swingui.driver.Verbs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Dialog;
import java.awt.Frame;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link StepHost#openDialog()}'s default, on real modal dialogs. */
class StepHostOpenDialogTest {

    private final StepHost host = new StepHost() {
        @Override public Verbs verbs() { return null; }
        @Override public void navigate(String target) { }
        @Override public void closeActiveTab() { }
        @Override public void loginAs(String alias) { }
    };

    private JDialog shown;

    @AfterEach
    void close() {
        if (shown != null) Edt.run(shown::dispose);
    }

    @Test
    void nothingWaitsWhileNoModalDialogShows() {
        assertThat(host.openDialog()).isNull();
    }

    @Test
    void aShowingOptionPaneIsTheDialogThatWaits() throws Exception {
        show(Edt.call(() -> new JOptionPane("Discard the cart?", JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION)
                .createDialog(null, "New order")));
        PendingDialog d = host.openDialog();
        assertThat(d).isNotNull();
        assertThat(d.title).isEqualTo("New order");
        assertThat(d.message).isEqualTo("Discard the cart?");
        assertThat(d.buttons).hasSize(2);
    }

    @Test
    void aModalDialogOfTheAppsOwnIsNoQuestion() throws Exception {
        show(Edt.call(() -> {
            JDialog checkout = new JDialog((Frame) null, "Checkout", Dialog.ModalityType.APPLICATION_MODAL);
            checkout.getContentPane().add(new JButton("Next"));
            checkout.pack();
            return checkout;
        }));
        assertThat(host.openDialog()).isNull();
    }

    private void show(JDialog dialog) throws InterruptedException {
        shown = dialog;
        SwingUtilities.invokeLater(() -> dialog.setVisible(true));
        long deadline = System.currentTimeMillis() + 5000;
        while (!Edt.call(dialog::isShowing)) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("the dialog never showed");
            Thread.sleep(20);
        }
    }
}
