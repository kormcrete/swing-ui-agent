package io.github.kormcrete.swingui.flow;

import java.nio.file.Path;

/** The flows folder cannot be created or written to; names the folder and why. */
public final class FlowFolderNotWritable extends IllegalArgumentException {

    private final Path folder;
    private final String why;

    public FlowFolderNotWritable(Path folder, String why, Throwable cause) {
        super("cannot write flows to " + folder + ": " + why, cause);
        this.folder = folder;
        this.why = why;
    }

    /** The absolute folder that could not be written. */
    public Path folder() {
        return folder;
    }

    /** Why it cannot be written, without the folder. */
    public String why() {
        return why;
    }
}
