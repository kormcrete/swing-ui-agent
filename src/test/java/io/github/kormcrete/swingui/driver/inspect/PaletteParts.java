package io.github.kormcrete.swingui.driver.inspect;

import javax.swing.JComponent;
import javax.swing.JWindow;
import java.awt.Window;

/** The palette's own parts, for tests outside its package that must show they never read them. */
public final class PaletteParts {

    private PaletteParts() {}

    /** The palette's bar with one button. EDT. */
    public static JComponent bar(String button) {
        return new Bar("swing-ui-agent", "").button(button, Overlay.NO, () -> { });
    }

    /** The palette's own window beside {@code dialog}; dispose it after. EDT. */
    public static JWindow window(Window dialog) {
        return new PaletteWindow(dialog);
    }
}
