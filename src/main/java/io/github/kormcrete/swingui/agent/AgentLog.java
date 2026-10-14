package io.github.kormcrete.swingui.agent;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The agent's own lines (listening, cannot listen, failed to start, plugin errors): on the console as always, and with
 * {@code log=<file>} also in that file, emptied at start — for an app started without a console, such as a packaged
 * download. A file that cannot be written is said once on stderr; the agent carries on with the console only.
 */
final class AgentLog {

    private final Path file;

    private AgentLog(Path file) {
        this.file = file;
    }

    /**
     * No file: {@code path} null. Creates the parent folders and empties the file. Never throws. The file is not kept
     * open (each line is appended and closed), so it can be deleted at any time, also on Windows.
     */
    static AgentLog open(Path path) {
        if (path == null) return new AgentLog(null);
        try {
            Path absolute = path.toAbsolutePath();
            if (absolute.getParent() != null) Files.createDirectories(absolute.getParent());
            Files.write(absolute, new byte[0]);
            return new AgentLog(absolute);
        } catch (IOException | RuntimeException e) {
            System.err.println("swing-ui-agent: cannot write the log " + path + " (" + e + ")");
            return new AgentLog(null);
        }
    }

    void out(String line) {
        System.out.println(line);
        append(line);
    }

    void err(String line) {
        System.err.println(line);
        append(line);
    }

    void err(Throwable t) {
        t.printStackTrace();
        StringWriter text = new StringWriter();
        t.printStackTrace(new PrintWriter(text));
        append(text.toString().trim());
    }

    /** A failing write is ignored: the console has the line, and the agent must never throw. */
    private void append(String line) {
        if (file == null) return;
        try {
            Files.write(file, (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            // ignored on purpose
        }
    }
}
