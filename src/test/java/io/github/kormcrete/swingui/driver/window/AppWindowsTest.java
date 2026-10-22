package io.github.kormcrete.swingui.driver.window;

import org.junit.jupiter.api.Test;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JWindow;
import java.awt.Window;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppWindowsTest {

    private static <W extends Window> W window(Class<W> kind, boolean showing) {
        W w = mock(kind);
        when(w.isShowing()).thenReturn(showing);
        when(w.getType()).thenReturn(Window.Type.NORMAL);
        return w;
    }

    @Test
    void aShowingDialogWithoutAnyFrameIsAnAppWindow() {
        // a sign-in dialog the app opens before its main frame
        assertThat(AppWindows.anyAppWindowShowing(new Window[]{window(JFrame.class, false), window(JDialog.class, true)}))
                .isTrue();
    }

    @Test
    void aShowingFrameIsAnAppWindow() {
        assertThat(AppWindows.anyAppWindowShowing(new Window[]{window(JFrame.class, true)})).isTrue();
    }

    @Test
    void hiddenWindowsPopupsAndSplashWindowsAreNot() {
        JDialog popup = window(JDialog.class, true);
        when(popup.getType()).thenReturn(Window.Type.POPUP);
        assertThat(AppWindows.anyAppWindowShowing(new Window[]{window(JFrame.class, false), window(JDialog.class, false),
                window(JWindow.class, true), popup})).isFalse();
        assertThat(AppWindows.anyAppWindowShowing(new Window[0])).isFalse();
    }
}
