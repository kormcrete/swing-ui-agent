package io.github.kormcrete.swingui.junit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;

/**
 * The test module's {@code target} directory, whatever the runner's working directory. Maven runs a module's tests
 * from the module, but an IDE may run them from the project root, where a bare {@code target/...} lands
 * the run report and the evidence in the wrong place.
 */
final class ModuleTarget {

    private ModuleTarget() {
    }

    /** {@code <module>/target} when {@code testClass} was loaded from {@code <module>/target/<dir>}; else {@code target}. */
    static Path of(Class<?> testClass) {
        try {
            CodeSource cs = testClass == null ? null : testClass.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                Path dir = Paths.get(cs.getLocation().toURI());
                Path parent = dir.getParent();
                if (Files.isDirectory(dir) && parent != null && parent.getFileName() != null
                        && "target".equals(parent.getFileName().toString())) {
                    return parent;
                }
            }
        } catch (Exception | LinkageError e) {
            // no usable code source: fall back to the working directory's target
        }
        return Paths.get("target");
    }

    /** {@link #of(Class)} for a class known only by name, e.g. from a JUnit test source. */
    static Path of(String className) {
        if (className == null) return Paths.get("target");
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try {
            return of(Class.forName(className, false, cl != null ? cl : ModuleTarget.class.getClassLoader()));
        } catch (ClassNotFoundException | LinkageError e) {
            return Paths.get("target");
        }
    }
}
