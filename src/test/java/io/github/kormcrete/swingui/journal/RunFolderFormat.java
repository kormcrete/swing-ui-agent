package io.github.kormcrete.swingui.journal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What a run folder of format 1 must hold, as the run journal reference documents it: a test helper that lists every
 * way a folder breaks the format (empty: it conforms). A consumer can copy it to check folders of their own.
 */
public final class RunFolderFormat {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> KINDS =
            Arrays.asList("test", "action", "check", "read", "section", "checkpoint", "end");
    /** The kinds of the lines that did something and say how it went ({@code ok} or {@code failed}). */
    private static final List<String> STEPS = Arrays.asList("action", "check", "read", "checkpoint");
    private static final List<String> ENDS = Arrays.asList("passed", "failed", "aborted");
    private static final Pattern TIME = Pattern.compile("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z");

    private RunFolderFormat() {}

    /** Every way {@code runFolder} differs from format 1; empty when it conforms. */
    public static List<String> check(Path runFolder) {
        List<String> problems = new ArrayList<String>();
        JsonNode run = read(runFolder.resolve("run.json"), problems);
        boolean finished = false;
        if (run != null) {
            if (run.path("format").asInt() != 1) problems.add("run.json: format is not 1: " + run.path("format"));
            if (!runFolder.getFileName().toString().equals(run.path("runId").asText())) {
                problems.add("run.json: runId " + run.path("runId") + " is not the folder's name " + runFolder.getFileName());
            }
            for (String field : new String[] {"started", "library", "java", "os"}) {
                if (!run.path(field).isTextual() || run.path(field).asText().isEmpty()) {
                    problems.add("run.json: no " + field);
                }
            }
            if (run.path("started").isTextual() && !TIME.matcher(run.path("started").asText()).matches()) {
                problems.add("run.json: started is not a UTC time with milliseconds: " + run.path("started"));
            }
            if (run.has("finished") && !TIME.matcher(run.path("finished").asText()).matches()) {
                problems.add("run.json: finished is not a UTC time with milliseconds: " + run.path("finished"));
            }
            if (!run.path("clients").isArray()) problems.add("run.json: clients is not a list");
            finished = run.has("finished");
        }
        for (Path journal : files(runFolder.resolve("steps"), ".jsonl", problems)) {
            journal(runFolder, journal, finished, problems);
        }
        for (Path sidecar : files(runFolder.resolve("checkpoints"), ".json", problems)) {
            sidecar(runFolder, sidecar, problems);
        }
        return problems;
    }

    private static void journal(Path runFolder, Path file, boolean finished, List<String> problems) {
        String name = runFolder.relativize(file).toString().replace('\\', '/');
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            problems.add(name + ": unreadable: " + e);
            return;
        }
        if (lines.isEmpty()) {
            problems.add(name + ": empty");
            return;
        }
        String last = null;
        for (int i = 0; i < lines.size(); i++) {
            String at = name + ":" + (i + 1);
            JsonNode line;
            try {
                line = JSON.readTree(lines.get(i));
            } catch (IOException e) {
                problems.add(at + ": not JSON: " + e.getMessage());
                continue;
            }
            if (line == null || !line.isObject()) {
                problems.add(at + ": not a JSON object");
                continue;
            }
            String kind = line.path("kind").asText();
            last = kind;
            if (line.path("seq").asInt() != i + 1) problems.add(at + ": seq is " + line.path("seq") + ", not " + (i + 1));
            if (!KINDS.contains(kind)) problems.add(at + ": unknown kind " + line.path("kind"));
            if (!TIME.matcher(line.path("t").asText()).matches()) {
                problems.add(at + ": t is not a UTC time with milliseconds: " + line.path("t"));
            }
            if (i == 0 && !"test".equals(kind)) problems.add(at + ": the first line is " + kind + ", not test");
            if (i > 0 && "test".equals(kind)) problems.add(at + ": a second test line");
            if ("test".equals(kind)) {
                if (line.path("format").asInt() != 1) problems.add(at + ": format is not 1");
                if (!line.path("test").isTextual()) problems.add(at + ": no test");
                if (!runFolder.getFileName().toString().equals(line.path("runId").asText())) {
                    problems.add(at + ": runId " + line.path("runId") + " is not the folder's name");
                }
                if (!line.path("clients").isArray()) problems.add(at + ": clients is not a list");
            }
            if (STEPS.contains(kind) && !Arrays.asList("ok", "failed").contains(line.path("outcome").asText())) {
                problems.add(at + ": outcome is " + line.path("outcome") + ", not ok or failed");
            }
            if ("end".equals(kind) && !ENDS.contains(line.path("outcome").asText())) {
                problems.add(at + ": end outcome is " + line.path("outcome") + ", not passed, failed or aborted");
            }
            if ("end".equals(kind) && i != lines.size() - 1) problems.add(at + ": a line after the end line");
            if ("checkpoint".equals(kind) && "ok".equals(line.path("outcome").asText())) {
                for (String field : new String[] {"png", "json"}) {
                    String path = line.path(field).asText(null);
                    if (path == null || !Files.isRegularFile(runFolder.resolve(path))) {
                        problems.add(at + ": checkpoint " + field + " " + line.path(field) + " is not in the run folder");
                    }
                }
            }
        }
        if (finished && !"end".equals(last)) problems.add(name + ": the last line is " + last + ", not end");
    }

    private static void sidecar(Path runFolder, Path file, List<String> problems) {
        String name = runFolder.relativize(file).toString().replace('\\', '/');
        JsonNode side = read(file, problems);
        if (side == null) return;
        if (side.path("format").asInt() != 1) problems.add(name + ": format is not 1");
        String expected = file.getFileName().toString().replaceAll("\\.json$", "");
        if (!expected.equals(side.path("name").asText())) problems.add(name + ": name is " + side.path("name"));
        if (!side.path("test").isTextual()) problems.add(name + ": no test");
        int w = side.path("width").asInt();
        int h = side.path("height").asInt();
        if (w <= 0 || h <= 0) problems.add(name + ": no width and height");
        if (!Files.isRegularFile(file.resolveSibling(expected + ".png"))) problems.add(name + ": no " + expected + ".png");
        if (!side.path("masks").isArray()) {
            problems.add(name + ": masks is not a list");
            return;
        }
        int i = 0;
        for (JsonNode m : side.path("masks")) {
            i++;
            int x = m.path("x").asInt(-1);
            int y = m.path("y").asInt(-1);
            int mw = m.path("w").asInt(0);
            int mh = m.path("h").asInt(0);
            if (x < 0 || y < 0 || mw <= 0 || mh <= 0 || x + mw > w || y + mh > h) {
                problems.add(name + ": mask " + i + " " + m + " is not inside " + w + "x" + h);
            }
            if (!m.path("clipped").isBoolean()) problems.add(name + ": mask " + i + " has no clipped");
        }
    }

    private static JsonNode read(Path file, List<String> problems) {
        try {
            return JSON.readTree(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (IOException e) {
            problems.add(file.getFileName() + ": unreadable: " + e);
            return null;
        }
    }

    private static List<Path> files(Path dir, String suffix, List<String> problems) {
        if (!Files.isDirectory(dir)) return new ArrayList<Path>();
        try (Stream<Path> all = Files.walk(dir)) {
            return all.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().collect(Collectors.toList());
        } catch (IOException e) {
            problems.add(dir.getFileName() + ": unreadable: " + e);
            return new ArrayList<Path>();
        }
    }
}
