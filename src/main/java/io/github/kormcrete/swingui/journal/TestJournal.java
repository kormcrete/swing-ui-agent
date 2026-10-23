package io.github.kormcrete.swingui.journal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.kormcrete.swingui.client.StepCallVerbs;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.StepCall;
import io.github.kormcrete.swingui.driver.model.Locator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One test's journal: {@code <run>/steps/<test>.jsonl}, one JSON object per line, appended and flushed per line. The
 * {@code test} line comes with the first other line, so a test that writes nothing makes no file. Every line gets the
 * next {@code seq} and is masked: each secret seen becomes {@value JournalLines#MASK}. Writing never fails a test: an
 * IO failure turns the run's steps journal off ({@link RunFolder#broken}). Checkpoint names and their folder work
 * also while the steps journal is off.
 */
public final class TestJournal {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    /** Every secret any journal of this JVM was given, longest first: the run's report.txt masks them too. */
    private static final List<String> SEEN = new CopyOnWriteArrayList<String>();
    /** The fields of a line that may hold what the app or the test said, and so a secret. */
    private static final List<String> MASKED =
            Arrays.asList("target", "locator", "value", "expected", "step", "message", "text");

    private final RunFolder folder;
    private final String test;
    private final long startedAt = System.currentTimeMillis();
    /** Secrets, longest first; shared with this journal's children. */
    private final List<String> secrets;
    /** The checkpoint names claimed, by their lower case form (a disk may ignore case). */
    private final Map<String, String> checkpoints = new HashMap<String, String>();
    private volatile List<ObjectNode> clients = Collections.emptyList();
    /** The file name base, taken from the folder on first need. */
    private String base;
    private int seq;
    private boolean headerWritten;
    private volatile boolean discarded;

    TestJournal(RunFolder folder, String test) {
        this(folder, test, new CopyOnWriteArrayList<String>());
    }

    private TestJournal(RunFolder folder, String test, List<String> secrets) {
        this.folder = folder;
        this.test = test;
        this.secrets = secrets;
    }

    /** The test's name, as the author's code names it ({@code <Class>#<method>}). */
    public String test() {
        return test;
    }

    /** True while this journal writes lines: the run's steps journal is on and this one was not discarded. */
    public boolean stepsOn() {
        return !discarded && folder.stepsOn();
    }

    /**
     * The clients the test line lists; with more than one, each line says which one it came from. Set them before any
     * line is written and before {@link #child} is called: the test line is written with the first line, and a child
     * keeps the list it was made with.
     */
    public void clients(List<ObjectNode> clients) {
        this.clients = clients == null ? Collections.<ObjectNode>emptyList() : new ArrayList<ObjectNode>(clients);
    }

    /**
     * Writes {@code entry} as the next line, masked, with its {@code seq} (and {@code "client": client} when the test has
     * more than one client); the {@code test} line goes first when this is the first. Returns the line's seq, or 0
     * when nothing was written.
     */
    public int append(ObjectNode entry, int client) {
        if (entry == null || !stepsOn()) return 0;
        synchronized (this) {
            if (!stepsOn()) return 0;
            try {
                Path file = file();
                StringBuilder text = new StringBuilder();
                int next = seq;
                if (!headerWritten) text.append(JSON.writeValueAsString(header(++next))).append('\n');
                ObjectNode line = NODES.objectNode();
                line.put("seq", ++next);
                if (client > 0 && clients.size() > 1) line.put("client", client);
                for (Iterator<Map.Entry<String, JsonNode>> it = entry.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> f = it.next();
                    if ("seq".equals(f.getKey()) || "client".equals(f.getKey())) continue;
                    line.set(f.getKey(), MASKED.contains(f.getKey()) ? mask(f.getValue()) : f.getValue());
                }
                text.append(JSON.writeValueAsString(line)).append('\n');
                Files.write(file, text.toString().getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
                headerWritten = true;
                seq = next;
                return seq;
            } catch (IOException | RuntimeException e) {
                folder.broken(e);
                return 0;
            }
        }
    }

    /** Where each step of client {@code client} (1-based) goes as it ends, and each secret it reads. */
    public StepCallVerbs.Sink sink(final int client) {
        return new StepCallVerbs.Sink() {
            @Override public void ended(StepCall call, long startedAtMillis) {
                if (!stepsOn()) return;
                JsonNode step;
                try {
                    step = JSON.readTree(call.json);
                } catch (IOException | RuntimeException unreadable) {
                    step = NODES.objectNode().put("action", call.action);
                }
                String password = typedPassword(step);
                if (password != null) secret(password);   // later lines (a check, the end) must not echo it either
                String message = call.reason == null ? call.message : DriverException.stripReason(call.message, call.reason);
                append(JournalLines.step(step, call.passed, message, call.reason == null ? null : call.reason.name(),
                        call.ms, startedAtMillis), client);
            }

            @Override public void secret(String value) {
                TestJournal.this.secret(value);
            }
        };
    }

    /** The text a {@code type} step types into a {@code password} target; null for any other step or a masked text. */
    private static String typedPassword(JsonNode step) {
        if (!"type".equals(step.path("action").asText(null))) return null;
        if (!"password".equals(step.path("target").path("role").asText(null))) return null;
        String text = step.path("text").asText(null);
        return text == null || text.isEmpty() || JournalLines.MASK.equals(text) ? null : text;
    }

    /** A line that starts a section of the test, named by its author. */
    public void section(String text) {
        if (!stepsOn()) return;
        append(JournalLines.section(text, System.currentTimeMillis()), 0);
    }

    /** A read of client {@code client} that started at {@code startedAt}: its value, or how it failed. */
    public void read(int client, String verb, Locator target, Object value, long startedAt, Throwable failure) {
        if (!stepsOn()) return;
        long now = System.currentTimeMillis();
        String message = failure == null ? null : failure.getMessage() != null ? failure.getMessage() : failure.toString();
        Reason why = failure instanceof DriverException ? ((DriverException) failure).reason() : null;
        if (why != null) message = DriverException.stripReason(message, why);
        String reason = why == null ? null : why.name();
        append(JournalLines.read(verb, target, failure == null ? value : null, failure == null, message, reason,
                Math.max(0, now - startedAt), startedAt), client);
    }

    /**
     * The last line: {@code outcome} is {@code passed}, {@code failed} or {@code aborted}; {@code message} (its first
     * line, without a leading {@code REASON: } of a driver failure) and {@code evidence} only when given.
     */
    public void end(String outcome, String message, String evidence) {
        if (!stepsOn()) return;
        long now = System.currentTimeMillis();
        ObjectNode line = NODES.objectNode();
        line.put("t", JournalLines.time(now));
        line.put("kind", "end");
        line.put("outcome", outcome);
        line.put("ms", Math.max(0, now - startedAt));
        if (message != null) line.put("message", withoutReason(message.split("\\r\\n|\\r|\\n", 2)[0]));
        if (evidence != null) line.put("evidence", evidence);
        append(line, 0);
    }

    /** {@code message} without the {@code REASON: } a driver failure starts with. */
    private static String withoutReason(String message) {
        for (Reason r : Reason.values()) {
            if (message.startsWith(r.name() + ": ")) return DriverException.stripReason(message, r);
        }
        return message;
    }

    /**
     * A journal for a test this one runs as its own, e.g. {@code child("recorded[place-order]")} gives
     * {@code <class part>#recorded[place-order]}: same run folder, clients and secrets.
     */
    public TestJournal child(String methodPart) {
        int hash = test.indexOf('#');
        TestJournal child = new TestJournal(folder, (hash < 0 ? test : test.substring(0, hash)) + "#" + methodPart, secrets);
        child.clients = clients;
        return child;
    }

    /** This journal writes nothing more; when it wrote nothing yet, it never makes a file. */
    public void discard() {
        discarded = true;
    }

    /**
     * Masks {@code value} in every line from now on, also in this journal's children, and in every line of a run's
     * report.txt ({@link #maskSeen}).
     */
    public void secret(String value) {
        if (value == null || value.isEmpty()) return;
        add(secrets, value);
        add(SEEN, value);
    }

    private static void add(List<String> secrets, String value) {
        synchronized (secrets) {
            if (secrets.contains(value)) return;
            secrets.add(value);
            secrets.sort(Comparator.comparingInt(String::length).reversed());
        }
    }

    /** {@code text} with each secret seen replaced by {@value JournalLines#MASK}. */
    public String mask(String text) {
        return mask(secrets, text);
    }

    /** {@code text} with each secret any journal of this JVM was given replaced by {@value JournalLines#MASK}. */
    static String maskSeen(String text) {
        return mask(SEEN, text);
    }

    private static String mask(List<String> secrets, String text) {
        if (text == null) return null;
        String s = text;
        for (String secret : secrets) s = s.replace(secret, JournalLines.MASK);
        return s;
    }

    /**
     * Claims {@code name} for a checkpoint of this test: it must use only {@code A-Z a-z 0-9 . _ -} and be new in this
     * test (case aside, as a disk may ignore case).
     */
    public void claimCheckpoint(String name) {
        if (!RunIds.isSafeName(name)) {
            throw new IllegalArgumentException("checkpoint name '" + name + "' may use only A-Z a-z 0-9 . _ - (not . or ..)");
        }
        synchronized (checkpoints) {
            String before = checkpoints.get(name.toLowerCase(Locale.ROOT));
            if (before == null) {
                checkpoints.put(name.toLowerCase(Locale.ROOT), name);
            } else if (before.equals(name)) {
                throw new IllegalStateException("checkpoint name '" + name + "' is used twice in " + test);
            } else {
                throw new IllegalStateException("checkpoint names '" + before + "' and '" + name + "' in " + test
                        + " differ only in case, which a disk may ignore: give them names that differ in letters");
            }
        }
    }

    /** This test's checkpoint folder, {@code <run>/checkpoints/<test>/}, made when missing. */
    public Path checkpointDir() {
        Path dir = folder.create().resolve("checkpoints").resolve(base());
        try {
            return Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot make the checkpoint folder " + dir + ": " + e.getMessage(), e);
        }
    }

    /** {@code file} relative to the run folder, with {@code /} between names on every OS. */
    public String relative(Path file) {
        return folder.create().toAbsolutePath().relativize(file.toAbsolutePath()).toString().replace('\\', '/');
    }

    private synchronized String base() {
        if (base == null) base = folder.reserve(test);
        return base;
    }

    private Path file() throws IOException {
        Path steps = Files.createDirectories(folder.create().resolve("steps"));
        return steps.resolve(base() + ".jsonl");
    }

    private ObjectNode header(int seq) {
        ObjectNode line = NODES.objectNode();
        line.put("seq", seq);
        line.put("t", JournalLines.time(startedAt));
        line.put("kind", "test");
        line.put("format", 1);
        line.put("test", test);
        line.put("runId", folder.create().getFileName().toString());
        ArrayNode list = line.putArray("clients");
        for (ObjectNode c : clients) list.add(c);
        return line;
    }

    /** {@code node} with each secret masked in its texts. */
    private JsonNode mask(JsonNode node) {
        if (node == null || secrets.isEmpty()) return node;
        if (node.isTextual()) return TextNode.valueOf(mask(node.asText()));
        if (node.isObject()) {
            ObjectNode out = NODES.objectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> f = it.next();
                out.set(f.getKey(), mask(f.getValue()));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = NODES.arrayNode();
            for (JsonNode e : node) out.add(mask(e));
            return out;
        }
        return node;
    }
}
