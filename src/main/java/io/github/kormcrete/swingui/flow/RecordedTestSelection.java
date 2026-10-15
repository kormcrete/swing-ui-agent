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
 * Which recorded test files a runner runs: the one named by {@code -Drecorded}, else those listed in the folder's
 * {@code gate.txt}, else all of them. It never returns an empty list: a run that selects nothing must fail, not pass.
 */
public final class RecordedTestSelection {

    /** The system property that names one recorded test to run. */
    public static final String PROPERTY = "recorded";
    /** The file in the recorded tests folder that lists the recorded tests to run, one name per line, in order. */
    public static final String GATE_FILE = "gate.txt";

    private RecordedTestSelection() {
    }

    /** The files to run, in order; {@code requested} is the value of {@code -Drecorded} (blank: none). */
    public static List<Path> select(Path dir, String requested) {
        Path abs = dir.toAbsolutePath();
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("no recorded tests folder " + abs + ": export one (export_recorded_test, or Export... in "
                    + "the palette) or point -D" + RecordedTestFiles.DIR_PROP + " at yours");
        }
        List<Path> out = new ArrayList<Path>();
        if (requested != null && !requested.trim().isEmpty()) {
            Path file = RecordedTestFiles.file(dir, requested);
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("no recorded test '" + requested.trim() + "' (named by -D" + PROPERTY
                        + ") in " + abs + "; the recorded tests there: " + names(RecordedTestFiles.list(dir)));
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
                Path file = RecordedTestFiles.file(dir, line);
                if (!Files.isRegularFile(file)) {
                    throw new IllegalArgumentException(GATE_FILE + " line " + (i + 1) + ": no recorded test '" + line + "' in "
                            + abs + "; the recorded tests there: " + names(RecordedTestFiles.list(dir)));
                }
                if (!seen.add(file.getFileName().toString())) {
                    throw new IllegalArgumentException(GATE_FILE + " line " + (i + 1) + ": '" + line
                            + "' is listed twice");
                }
                out.add(file);
            }
            if (out.isEmpty()) {
                throw new IllegalArgumentException(GATE_FILE + " lists no recorded tests: name at least one, or delete it to run all");
            }
            return out;
        }
        out.addAll(RecordedTestFiles.list(dir));
        if (out.isEmpty()) throw new IllegalArgumentException("no recorded test files in " + abs);
        return out;
    }

    private static String names(List<Path> files) {
        StringBuilder sb = new StringBuilder();
        for (Path p : files) {
            String n = p.getFileName().toString();
            if (n.endsWith(RecordedTestFiles.SUFFIX)) n = n.substring(0, n.length() - RecordedTestFiles.SUFFIX.length());
            if (sb.length() > 0) sb.append(", ");
            sb.append(n);
        }
        return sb.toString();
    }
}
