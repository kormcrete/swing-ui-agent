package io.github.kormcrete.swingui.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Watch-only: learns which class loader defines each plugin's app class, and changes no bytes. Unregisters once
 * every watched class has been seen.
 */
public final class PluginWatch implements ClassFileTransformer {

    private final Instrumentation inst;
    private final Map<String, List<PluginHost>> byInternalName = new ConcurrentHashMap<String, List<PluginHost>>();

    public PluginWatch(Instrumentation inst) {
        this.inst = inst;
    }

    public void watch(String appClass, PluginHost host) {
        byInternalName.computeIfAbsent(appClass.replace('.', '/'), k -> new CopyOnWriteArrayList<PluginHost>()).add(host);
    }

    public boolean isEmpty() {
        return byInternalName.isEmpty();
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain domain,
                            byte[] classfileBuffer) {
        if (className != null && redefined == null) fire(className, loader);
        return null;
    }

    /** For classes the app loaded before the watch was registered. */
    public void scanLoaded() {
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (isEmpty()) return;
            fire(c.getName().replace('.', '/'), c.getClassLoader());
        }
    }

    void fire(String internalName, ClassLoader loader) {
        List<PluginHost> hosts = byInternalName.remove(internalName);
        if (hosts == null) return;
        ClassLoader appLoader = loader == null ? ClassLoader.getSystemClassLoader() : loader;
        for (PluginHost h : hosts) h.appClassLoaded(appLoader);
        if (byInternalName.isEmpty()) inst.removeTransformer(this);
    }
}
