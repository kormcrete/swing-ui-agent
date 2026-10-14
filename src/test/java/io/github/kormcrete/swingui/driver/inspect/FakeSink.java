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

    @Override public void begin() { if (onBegin != null) onBegin.run(); steps.clear(); }
    @Override public Inspector.Result done(Map<String, Object> step) { steps.add(String.valueOf(step.get("action"))); return new Inspector.Result(true, "ok"); }
    @Override public Inspector.Result check(Map<String, Object> step) { return done(step); }
    @Override public List<String> steps() { return new ArrayList<String>(steps); }
    @Override public Locator target(int index) { return null; }
    @Override public Inspector.Result replay(int index) { return new Inspector.Result(true, "ok"); }
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
}
