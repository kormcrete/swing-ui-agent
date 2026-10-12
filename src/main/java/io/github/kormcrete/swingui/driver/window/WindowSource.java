package io.github.kormcrete.swingui.driver.window;

import java.awt.Component;
import java.util.List;

/** The app's top-level roots, focused window first and popups last (innermost last). EDT. */
public interface WindowSource {

    List<WindowRoot> roots();

    /** The app's main window, or null before it shows. */
    Component main();
}
