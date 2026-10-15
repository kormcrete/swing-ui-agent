package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.model.Locator;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** A recording kept in a list: what the session does, without a session. */
final class FakeSink implements Inspector.Sink {
    final List<Inspector.StepLine> steps = Collections.synchronizedList(new ArrayList<Inspector.StepLine>());
    private int nextId = 1;
    final List<String> exports = Collections.synchronizedList(new ArrayList<String>());
    final List<String> recordedExports = Collections.synchronizedList(new ArrayList<String>());
    volatile String refuse;
    /** The folder the palette offers for a recorded test. */
    volatile Path recordedFolder = Paths.get("").toAbsolutePath().resolve("target/fake-recorded tests").normalize();
    /** The recorded test the recording came from, or null. */
    volatile Path loaded;
    /** A recorded test export fails as a folder problem. */
    volatile boolean refuseFolder;
    /** Runs at the start of {@link #begin}, on the inspector's thread: what happens while the session lock is awaited. */
    volatile Runnable onBegin;
    /** What undo would take back (null: nothing); why an undo is refused (null: it is not). */
    volatile String undoLabel;
    volatile String refuseUndo;
    /**
     * What a run did, in order: "reset", "replay 0", …; the person's edits: "delete <id>", "cut <id>", "undo"; their
     * ● Record: "begin".
     */
    final List<String> log = Collections.synchronizedList(new ArrayList<String>());
    /** The recorded tests Load recorded test… finds, in any folder; and the folders it looked in. */
    volatile List<Inspector.RecordedTestEntry> recordedTests = new ArrayList<Inspector.RecordedTestEntry>();
    final List<Path> listed = Collections.synchronizedList(new ArrayList<Path>());
    /** The steps a loaded recorded test has; why a load is refused (null: it is not). */
    volatile List<String> loadedSteps = new ArrayList<String>();
    volatile String refuseLoad;
    /** What the run's reset does. */
    volatile Inspector.Result reset = new Inspector.Result(true, "reset");
    /** Runs inside the reset, on the inspector's thread: what happens while the app resets. */
    volatile Runnable onReset;

    /** Adds steps the person made, at the end; the agent's; one the agent edited (not replayed since). */
    void person(String... lines) { for (String l : lines) steps.add(line("person", l, false)); }
    void agent(String... lines) { for (String l : lines) steps.add(line("agent", l, false)); }
    void edited(String l) { steps.add(line("agent", l, true)); }

    /** A step the agent put in at {@code index}, as the session would; the palette hears nothing of it. */
    Inspector.StepLine insert(int index, String l) {
        Inspector.StepLine s = line("agent", l, true);
        steps.add(index, s);
        return s;
    }

    /**
     * Adds a record as the session lists it: its step or check {@code label} ("2", "2.1"), sentence, group and row.
     * Returns its id.
     */
    int add(String author, String label, String l, String say, int groupId, String groupSay, Inspector.RowLine row) {
        Inspector.StepLine s;
        synchronized (this) {
            s = new Inspector.StepLine(nextId++, author, l, false, label, say, label.contains("."), groupId, groupSay, row);
        }
        steps.add(s);
        return s.id;
    }

    /** {@link #add} of the person's record without sentence, group or row. */
    int add(String label, String l) {
        return add("person", label, l, null, 0, null, null);
    }

    private synchronized Inspector.StepLine line(String author, String l, boolean edited) {
        return new Inspector.StepLine(nextId++, author, l, edited);
    }

    /** The steps' lines, in order. */
    List<String> lines() {
        List<String> out = new ArrayList<String>();
        for (Inspector.StepLine s : steps()) out.add(s.line);
        return out;
    }

    /** The position (0-based) of the step with {@code id}, -1 when it is gone. */
    private int indexOf(int id) {
        synchronized (steps) {
            for (int i = 0; i < steps.size(); i++) if (steps.get(i).id == id) return i;
        }
        return -1;
    }

    /** Why ● Record is refused (null: it is not), as the session refuses it while the agent's recording runs. */
    volatile String refuseBegin;

    @Override public Inspector.Result begin() {
        log.add("begin");
        if (onBegin != null) onBegin.run();
        if (refuseBegin != null) return new Inspector.Result(false, refuseBegin);
        steps.clear();
        loaded = null;
        return new Inspector.Result(true, "recording");
    }
    @Override public void resume() { log.add("resume"); }
    @Override public void end() { log.add("end"); }
    @Override public Inspector.Result done(Map<String, Object> step) { person(String.valueOf(step.get("action"))); return new Inspector.Result(true, "ok"); }
    @Override public Inspector.Result check(Map<String, Object> step) { return done(step); }
    @Override public List<Inspector.StepLine> steps() {
        synchronized (steps) {
            return new ArrayList<Inspector.StepLine>(steps);
        }
    }
    /** Each record's target by its position, as the session reads it from the step; none: a step without one. */
    final Map<Integer, Locator> targets = new java.util.concurrent.ConcurrentHashMap<Integer, Locator>();
    @Override public Locator target(int index) { return targets.get(index); }
    /** Thrown by {@link #replay}, when set: a replay that breaks. */
    volatile RuntimeException replayThrows;
    /** The step (0-based) whose replay fails, -1 none. */
    volatile int failStep = -1;
    /** Runs at the start of {@link #replay}, on the inspector's thread: what the palette shows while a step runs. */
    volatile Runnable onReplay;
    @Override public Inspector.Result replay(int index) {
        log.add("replay " + index);
        if (onReplay != null) onReplay.run();
        if (replayThrows != null) throw replayThrows;
        if (index == failStep) return new Inspector.Result(false, "no button 'b'");
        return new Inspector.Result(true, "ok");
    }
    @Override public Inspector.Result delete(int id) {
        log.add("delete " + id);
        int i = indexOf(id);
        if (i < 0) return new Inspector.Result(false, "that step is gone");
        steps.remove(i);
        return new Inspector.Result(true, "deleted step " + (i + 1));
    }
    @Override public Inspector.Result cutFrom(int id) {
        log.add("cut " + id);
        int i = indexOf(id);
        if (i < 0) return new Inspector.Result(false, "that step is gone");
        while (steps.size() > i) steps.remove(steps.size() - 1);
        return new Inspector.Result(true, "cut from step " + (i + 1));
    }
    /** Why a describe is refused (null: it is not). */
    volatile String refuseDescribe;
    /** Runs in an accepted {@link #describe}, on the inspector's thread: what the session changes in the recording. */
    volatile Runnable onDescribe;
    @Override public Inspector.Result describe(int fromId, int toId, String say) {
        log.add("describe " + fromId + "-" + toId + " " + say);
        if (refuseDescribe != null) return new Inspector.Result(false, refuseDescribe);
        if (onDescribe != null) onDescribe.run();
        return new Inspector.Result(true, "described");
    }
    @Override public Inspector.Result ungroup(int id) {
        log.add("ungroup " + id);
        return new Inspector.Result(true, "ungrouped");
    }
    @Override public Inspector.Result rowBy(int id, String by) {
        log.add("row " + id + " " + by);
        return new Inspector.Result(true, "row by " + by);
    }
    @Override public Inspector.Result undo() {
        log.add("undo");
        if (refuseUndo != null) return new Inspector.Result(false, refuseUndo);
        String label = undoLabel;
        if (label == null) return new Inspector.Result(false, "nothing to undo");
        undoLabel = null;
        return new Inspector.Result(true, label);
    }
    @Override public String undoLabel() { return undoLabel; }
    @Override public String exportPackage() { return "com.example"; }
    @Override public Inspector.Result export(String className, String pkg) {
        if (refuse != null) return new Inspector.Result(false, refuse);
        exports.add(pkg + "." + className);
        return new Inspector.Result(true, "wrote src/test/java/" + pkg.replace('.', '/') + "/" + className + ".java");
    }
    @Override public Path recordedFolder() { return recordedFolder; }
    @Override public Path loadedTest() { return loaded; }
    @Override public Inspector.Result exportRecordedTest(String name, Path folder) {
        if (refuseFolder) return new Inspector.Result(false, "Cannot write to " + folder + ": not a folder", true);
        if (refuse != null) return new Inspector.Result(false, refuse);
        recordedExports.add(folder.resolve(name + ".json").toString());
        return new Inspector.Result(true, "wrote uitest/" + name + ".json");
    }
    @Override public List<Inspector.RecordedTestEntry> recordedTests(Path folder) { listed.add(folder); return new ArrayList<Inspector.RecordedTestEntry>(recordedTests); }
    @Override public Inspector.Result loadRecordedTest(Path file) {
        if (refuseLoad != null) return new Inspector.Result(false, refuseLoad);
        steps.clear();
        for (String l : loadedSteps) steps.add(line("loaded", l, false));
        loaded = file;
        String name = file.getFileName().toString();
        return new Inspector.Result(true, name.substring(0, name.length() - ".json".length()));
    }
    @Override public Inspector.Result reset() {
        log.add("reset");
        if (onReset != null) onReset.run();
        return reset;
    }
}
