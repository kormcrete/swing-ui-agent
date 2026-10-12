package io.github.kormcrete.swingui.driver.platform;

import java.awt.Component;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Java 9+: exports and opens the {@code java.desktop} packages the seam reaches into, to the module this class is in,
 * through the agent's Instrumentation, so the app needs no {@code --add-opens} flags. Does nothing on Java 8.
 * Reflective because the build targets Java 8, which has no {@code java.lang.Module}.
 *
 * <p>A JVM that runs the driver without the agent (the unit tests) passes the same packages as flags instead:
 * {@code --add-opens java.desktop/<package>=ALL-UNNAMED}, which at run time also grants what an export would.</p>
 */
public final class ModuleAccess {

    /** What the seams use. Packages this JDK does not have (the macOS ones elsewhere) are skipped. */
    static final String[] PACKAGES = {
        "java.awt", "java.awt.peer", "java.awt.dnd", "java.awt.dnd.peer", "sun.awt", "sun.lwawt", "sun.lwawt.macosx"
    };

    private ModuleAccess() {}

    /** Returns null when done or not needed (Java 8), else why it failed. Never throws. */
    @SuppressWarnings("unchecked")
    public static String open(Instrumentation inst) {
        Method getModule;
        try {
            getModule = Class.class.getMethod("getModule");
        } catch (NoSuchMethodException java8) {
            return null;
        }
        try {
            Object desktop = getModule.invoke(Component.class);
            Object self = getModule.invoke(ModuleAccess.class);
            Set<String> present = (Set<String>) desktop.getClass().getMethod("getPackages").invoke(desktop);
            Map<String, Set<?>> grants = new LinkedHashMap<String, Set<?>>();
            for (String p : PACKAGES) {
                if (present.contains(p)) grants.put(p, Collections.singleton(self));
            }
            Class<?> module = Class.forName("java.lang.Module");
            Instrumentation.class.getMethod("redefineModule", module, Set.class, Map.class, Map.class, Set.class,
                    Map.class).invoke(inst, desktop, Collections.emptySet(), grants, grants, Collections.emptySet(),
                    Collections.emptyMap());
            return null;
        } catch (Exception | LinkageError e) {
            return "cannot open java.desktop to swing-ui-agent: " + AwtSeam.describe(e);
        }
    }
}
