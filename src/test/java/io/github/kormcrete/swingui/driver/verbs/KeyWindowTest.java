package io.github.kormcrete.swingui.driver.verbs;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import org.junit.jupiter.api.Test;

import javax.swing.JDialog;
import javax.swing.JFrame;
import java.awt.GraphicsEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The window a screenshot or a target-less key goes to. The driver pins focus itself, so the focused window can be one
 * that was closed a moment ago (the confirm dialog after Place order): that one is skipped. No window is shown here.
 */
class KeyWindowTest {

    @Test
    void aFocusedWindowThatIsNoLongerShowingIsSkippedForTheMainWindow() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        JDialog closed = new JDialog();   // never shown: as a disposed confirm dialog
        closed.setSize(270, 99);
        JFrame main = new JFrame();
        try {
            assertThat(LiveVerbs.keyWindowOf(null, closed, main)).isSameAs(main);
        } finally {
            closed.dispose();
            main.dispose();
        }
    }

    @Test
    void theTopModalDialogWins() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        JDialog top = new JDialog();
        JFrame main = new JFrame();
        try {
            assertThat(LiveVerbs.keyWindowOf(top, null, main)).isSameAs(top);
        } finally {
            top.dispose();
            main.dispose();
        }
    }

    @Test
    void noWindowAtAllIsNotFound() {
        DriverException e = catchThrowableOfType(() -> LiveVerbs.keyWindowOf(null, null, null), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.NOT_FOUND);
    }
}
