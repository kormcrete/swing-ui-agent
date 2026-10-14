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
    final List<String> steps = Collections.synchronizedList(new ArrayList<String>());
    final List<String> exports = Collections.synchronizedList(new ArrayList<String>());
    final List<String> flowExports = Collections.synchronizedList(new ArrayList<String>());
    volatile String refuse;
    /** The folder the palette offers for a flow. */
    volatile Path flowsFolder = Paths.get("").toAbsolutePath().resolve("target/fake-flows").normalize();
    /** The flow the recording came from, or null. */
    volatile Path loaded;
    /** A flow export fails as a folder problem. */
    volatile boolean refuseFolder;
    /** Runs at the start of {@link #begin}, on the inspector's thread: what happens while the session lock is awaited. */
    volatile Runnable onBegin;
    /** What a run did, in order: "reset", "replay 0", … */
    final List<String> log = Collections.synchronizedList(new ArrayList<String>());
    /** The flows Load flow… finds, in any folder; and the folders it looked in. */
    volatile List<Inspector.FlowEntry> flows = new ArrayList<Inspector.FlowEntry>();
    final List<Path> listed = Collections.synchronizedList(new ArrayList<Path>());
    /** The steps a loaded flow has; why a load is refused (null: it is not). */
    volatile List<String> flowSteps = new ArrayList<String>();
    volatile String refuseLoad;
    /** What the run's reset does. */
    volatile Inspector.Result reset = new Inspector.Result(true, "reset");

    @Override public void begin() { if (onBegin != null) onBegin.run(); steps.clear(); loaded = null; }
    @Override public void end() { log.add("end"); }
    @Override public Inspector.Result done(Map<String, Object> step) { steps.add(String.valueOf(step.get("action"))); return new Inspector.Result(true, "ok"); }
    @Override public Inspector.Result check(Map<String, Object> step) { return done(step); }
    @Override public List<String> steps() { return new ArrayList<String>(steps); }
    @Override public Locator target(int index) { return null; }
    /** Thrown by {@link #replay}, when set: a replay that breaks. */
    volatile RuntimeException replayThrows;
    @Override public Inspector.Result replay(int index) {
        log.add("replay " + index);
        if (replayThrows != null) throw replayThrows;
        return new Inspector.Result(true, "ok");
    }
    @Override public void truncate(int keep) { while (steps.size() > keep) steps.remove(steps.size() - 1); }
    @Override public void remove(int index) { steps.remove(index); }
    @Override public String exportPackage() { return "com.example"; }
    @Override public Inspector.Result export(String className, String pkg) {
        if (refuse != null) return new Inspector.Result(false, refuse);
        exports.add(pkg + "." + className);
        return new Inspector.Result(true, "wrote src/test/java/" + pkg.replace('.', '/') + "/" + className + ".java");
    }
    @Override public Path flowsFolder() { return flowsFolder; }
    @Override public Path loadedFlow() { return loaded; }
    @Override public Inspector.Result exportFlow(String name, Path folder) {
        if (refuseFolder) return new Inspector.Result(false, "Cannot write to " + folder + ": not a folder", true);
        if (refuse != null) return new Inspector.Result(false, refuse);
        flowExports.add(folder.resolve(name + ".json").toString());
        return new Inspector.Result(true, "wrote uitest/" + name + ".json");
    }
    @Override public List<Inspector.FlowEntry> flows(Path folder) { listed.add(folder); return new ArrayList<Inspector.FlowEntry>(flows); }
    @Override public Inspector.Result loadFlow(Path file) {
        if (refuseLoad != null) return new Inspector.Result(false, refuseLoad);
        steps.clear();
        steps.addAll(flowSteps);
        loaded = file;
        String name = file.getFileName().toString();
        return new Inspector.Result(true, name.substring(0, name.length() - ".json".length()));
    }
    @Override public Inspector.Result reset() { log.add("reset"); return reset; }
}
