package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.driver.FileChooserState;

/** The showing file chooser, read once ({@link Client#fileChooser()}): its folder and file-name field. Immutable. */
public final class FileChooser {
    private final String folder, name, lookAndFeel;
    private final boolean nameFocused;

    private FileChooser(FileChooserState s) {
        folder = s.folder; name = s.name; nameFocused = s.nameFocused; lookAndFeel = s.lookAndFeel;
    }

    static FileChooser of(FileChooserState s) { return new FileChooser(s); }

    /** The absolute path of the folder it shows; null when it has none. */
    public String folder() { return folder; }

    /** The file-name field's text; null for a look and feel without one. */
    public String name() { return name; }

    /** Whether the file-name field owns keyboard focus, so {@link Client#press} lands in it. */
    public boolean nameFocused() { return nameFocused; }

    /** The installed look and feel's name; the chooser's key bindings depend on it. */
    public String lookAndFeel() { return lookAndFeel; }

    @Override
    public String toString() {
        return "FileChooser{folder=" + folder + ", name=" + name + ", nameFocused=" + nameFocused
                + ", lookAndFeel=" + lookAndFeel + "}";
    }
}
