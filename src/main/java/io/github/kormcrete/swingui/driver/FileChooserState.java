package io.github.kormcrete.swingui.driver;

/**
 * What a showing file chooser holds right now: the folder it shows and its file-name field. A read
 * for checks such as "Backspace in the file-name field stays in the folder".
 */
public final class FileChooserState {

    /** The current directory's absolute path; null when the chooser has none. */
    public final String folder;
    /** The file-name field's text; null for a look and feel without one. */
    public final String name;
    /** True when the file-name field owns keyboard focus, so a key press with no target lands in it. */
    public final boolean nameFocused;
    /**
     * The installed look and feel's name. The chooser's key bindings are the look and feel's, so a check of
     * them proves something only under the one the app actually runs with.
     */
    public final String lookAndFeel;

    public FileChooserState(String folder, String name, boolean nameFocused, String lookAndFeel) {
        this.folder = folder;
        this.name = name;
        this.nameFocused = nameFocused;
        this.lookAndFeel = lookAndFeel;
    }
}
