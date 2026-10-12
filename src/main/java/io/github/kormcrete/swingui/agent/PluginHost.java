package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.driver.Driver;
import io.github.kormcrete.swingui.spi.BuildStamp;
import io.github.kormcrete.swingui.spi.PluginContext;
import io.github.kormcrete.swingui.spi.PluginParts;
import io.github.kormcrete.swingui.spi.UiPlugin;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Iterator;
import java.util.ServiceLoader;

/** Loads one plugin next to the app — in a child of the app's own class loader — and starts it off the loading thread. */
public class PluginHost {

    private static final String SERVICE_FILE = "META-INF/services/" + UiPlugin.class.getName();

    private final Driver driver;
    private final PluginSlot slot;
    private final PluginDescriptor descriptor;

    public PluginHost(Driver driver, PluginSlot slot, PluginDescriptor descriptor) {
        this.driver = driver;
        this.slot = slot;
        this.descriptor = descriptor;
        slot.waiting("waiting for " + descriptor.appClass + " to load");
    }

    /** Called from class loading: returns at once; the plugin starts on its own daemon thread. */
    public void appClassLoaded(final ClassLoader appLoader) {
        try {
            slot.starting("loading " + descriptor.location + " next to " + appLoader);
            Thread t = newThread(() -> startNow(appLoader));
            t.setDaemon(true);
            t.start();
        } catch (Throwable e) {
            // the slot must never stay STARTING without a reason (e.g. "unable to create new native thread")
            slot.failed(descriptor.location + ": cannot start the plugin thread: " + e);
            System.err.println("swing-ui-agent: plugin failed - " + slot.detail());
        }
    }

    Thread newThread(Runnable r) {
        return new Thread(r, "swing-ui-agent-plugin");
    }

    void startNow(ClassLoader appLoader) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            URL url = descriptor.location.toUri().toURL();
            if (descriptor.buildMatch != null) {
                String why = buildMismatch(appLoader);
                if (why != null) {
                    slot.failed(why);
                    System.err.println("swing-ui-agent: plugin failed - " + why);
                    return;
                }
            }
            URLClassLoader loader = new URLClassLoader(new URL[] {url}, appLoader);
            Thread.currentThread().setContextClassLoader(loader);
            Iterator<UiPlugin> plugins = ServiceLoader.load(UiPlugin.class, loader).iterator();
            if (!plugins.hasNext()) {
                slot.failed(descriptor.location + " has no " + SERVICE_FILE + " naming a plugin");
                return;
            }
            UiPlugin plugin = plugins.next();
            PluginParts parts = plugin.start(new PluginContext() {
                @Override public Driver driver() { return driver; }
                @Override public String pluginName() { return descriptor.name; }
            });
            slot.ready(parts, plugin.getClass().getName() + " from " + descriptor.location);
            System.out.println("swing-ui-agent: plugin ready - " + slot.detail());
        } catch (Throwable e) {
            slot.failed(descriptor.location + ": " + e);
            System.err.println("swing-ui-agent: plugin failed - " + slot.detail());
            e.printStackTrace();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** Null when the plugin and the app come from one build, else why they do not or cannot be compared. */
    String buildMismatch(ClassLoader appLoader) {
        URL source;
        try {
            Class<?> app = Class.forName(descriptor.appClass, false, appLoader);
            CodeSource cs = app.getProtectionDomain().getCodeSource();
            source = cs == null ? null : cs.getLocation();
        } catch (ClassNotFoundException | LinkageError | SecurityException e) {
            return "cannot check the build of " + descriptor.appClass + ": " + e;
        }
        Path app = BuildStamp.location(source);
        if (app == null) {
            return "cannot check the build of " + descriptor.appClass + ": its code source " + source + " is no jar or folder";
        }
        return BuildMatch.check(descriptor.buildMatch, descriptor.location, app);
    }
}
