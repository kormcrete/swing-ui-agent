package io.github.kormcrete.swingui.flow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which flow files a runner runs: the one named by {@code -Dflow}, else those listed in the folder's
 * {@code gate.txt}, else all of them. It never returns an empty list: a run that selects nothing must fail, not pass.
 */
public final class FlowSelection {

    /** The system property that names one flow to run. */
    public static final String FLOW_PROPERTY = "flow";
    /** The file in the flows folder that lists the flows to run, one name per line, in order. */
    public static final String GATE_FILE = "gate.txt";

    private FlowSelection() {
    }

    /** The files to run, in order; {@code requested} is the value of {@code -Dflow} (blank: none). */
    public static List<Path> select(Path dir, String requested) {
        Path abs = dir.toAbsolutePath();
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("no flows folder " + abs + ": export one (export_flow, or Export... in "
                    + "the palette) or point -D" + FlowFiles.DIR_PROP + " at yours");
        }
        List<Path> out = new ArrayList<Path>();
        if (requested != null && !requested.trim().isEmpty()) {
            Path file = FlowFiles.file(dir, requested);
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("no flow '" + requested.trim() + "' (named by -D" + FLOW_PROPERTY
                        + ") in " + abs + "; the flows there: " + names(FlowFiles.list(dir)));
            }
            out.add(file);
            return out;
        }
        Path gate = dir.resolve(GATE_FILE);
        if (Files.isRegularFile(gate)) {
            List<String> lines;
            try {
                lines = Files.readAllLines(gate, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalArgumentException("cannot read " + gate + ": " + e);
            }
            Set<String> seen = new HashSet<String>();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int hash = line.indexOf('#');
                if (hash >= 0) line = line.substring(0, hash);
                line = line.trim();
                if (line.isEmpty()) continue;
                Path file = FlowFiles.file(dir, line);
                if (!Files.isRegularFile(file)) {
                    throw new IllegalArgumentException(GATE_FILE + " line " + (i + 1) + ": no flow '" + line + "' in "
                            + abs + "; the flows there: " + names(FlowFiles.list(dir)));
                }
                if (!seen.add(file.getFileName().toString())) {
                    throw new IllegalArgumentException(GATE_FILE + " line " + (i + 1) + ": '" + line
                            + "' is listed twice");
                }
                out.add(file);
            }
            if (out.isEmpty()) {
                throw new IllegalArgumentException(GATE_FILE + " lists no flows: name at least one, or delete it to run all");
            }
            return out;
        }
        out.addAll(FlowFiles.list(dir));
        if (out.isEmpty()) throw new IllegalArgumentException("no flow files in " + abs);
        return out;
    }

    private static String names(List<Path> files) {
        StringBuilder sb = new StringBuilder();
        for (Path p : files) {
            String n = p.getFileName().toString();
            if (n.endsWith(FlowFiles.SUFFIX)) n = n.substring(0, n.length() - FlowFiles.SUFFIX.length());
            if (sb.length() > 0) sb.append(", ");
            sb.append(n);
        }
        return sb.toString();
    }
}
