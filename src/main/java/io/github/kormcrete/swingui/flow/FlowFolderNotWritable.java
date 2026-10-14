package io.github.kormcrete.swingui.flow;

import java.nio.file.Path;

/** The flows folder cannot be created or written to; names the folder and why. */
public final class FlowFolderNotWritable extends IllegalArgumentException {

    private final Path folder;

    public FlowFolderNotWritable(Path folder, String why, Throwable cause) {
        super("cannot write flows to " + folder + ": " + why, cause);
        this.folder = folder;
    }

    /** The absolute folder that could not be written. */
    public Path folder() {
        return folder;
    }
}
