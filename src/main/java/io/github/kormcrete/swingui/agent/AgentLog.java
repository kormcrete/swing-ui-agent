package io.github.kormcrete.swingui.agent;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The agent's own lines (listening, cannot listen, failed to start, plugin errors): on the console as always, and with
 * {@code log=<file>} also in that file, emptied at start — for an app started without a console, such as a packaged
 * download. A file that cannot be written is said once on stderr; the agent carries on with the console only.
 */
final class AgentLog {

    private final PrintStream file;

    private AgentLog(PrintStream file) {
        this.file = file;
    }

    /** No file: {@code path} null. Creates the parent folders and empties the file. Never throws. */
    static AgentLog open(Path path) {
        if (path == null) return new AgentLog(null);
        try {
            Path absolute = path.toAbsolutePath();
            if (absolute.getParent() != null) Files.createDirectories(absolute.getParent());
            return new AgentLog(new PrintStream(new FileOutputStream(absolute.toFile(), false), true, "UTF-8"));
        } catch (IOException | RuntimeException e) {
            System.err.println("swing-ui-agent: cannot write the log " + path + " (" + e + ")");
            return new AgentLog(null);
        }
    }

    void out(String line) {
        System.out.println(line);
        if (file != null) file.println(line);
    }

    void err(String line) {
        System.err.println(line);
        if (file != null) file.println(line);
    }

    void err(Throwable t) {
        t.printStackTrace();
        if (file != null) t.printStackTrace(file);
    }
}
