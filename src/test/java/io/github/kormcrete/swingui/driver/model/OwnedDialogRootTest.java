package io.github.kormcrete.swingui.driver.model;

import io.github.kormcrete.swingui.driver.window.WindowRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A dialog's parent in AWT is the window that owns it, so the owner frame "contains" every component of the dialog.
 * A ref to a component in the dialog still belongs to the dialog, whatever order the windows are listed in.
 */
class OwnedDialogRootTest {

    private final JFrame frame = new JFrame("Order Desk");
    private final JDialog dialog = new JDialog(frame, "Checkout", false);
    private final JButton next = new JButton("Next");

    @AfterEach
    void tearDown() {
        dialog.dispose();
        frame.dispose();
    }

    @Test
    void aRefInAnOwnedDialogResolvesToTheDialogNotItsOwner() {
        dialog.getContentPane().add(next);
        FakeWindows windows = new FakeWindows();
        windows.main = frame;
        // as the live window list has them: the focused dialog first, its owner after it
        windows.roots.add(new WindowRoot(dialog, "dialog", "Checkout", true, null, true));
        windows.roots.add(new WindowRoot(frame, "window", "Order Desk", false, "Checkout", false));
        Refs refs = new Refs(c -> true);
        Visibility all = c -> true;
        Resolver resolver = new Resolver(all, new Names(all), refs, windows);

        Resolved r = resolver.resolve(Locator.ofRef(refs.add(next, null)));

        assertThat(r.window).isNotNull().startsWith("dialog");
        Recorder.Recorded recorded = new Recorder(resolver, windows).record(r);
        assertThat(recorded.locator.window).startsWith("dialog");
        assertThat(recorded.locator.name).isEqualTo("Next");
        assertThat(recorded.fragile).isFalse();
    }
}
