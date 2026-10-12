package io.github.kormcrete.swingui.driver.model;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;

import java.awt.Component;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Snapshot refs: weak references, valid until the next snapshot. Numbers keep counting
 * across snapshots, so an old ref can never alias a new element.
 */
public final class Refs {

    public static final class Entry {
        private final WeakReference<Component> component;
        public final Integer tab;

        Entry(Component component, Integer tab) {
            this.component = new WeakReference<Component>(component);
            this.tab = tab;
        }

        public Component component() {
            return component.get();
        }
    }

    private final Predicate<Component> alive;
    private final Map<String, Entry> entries = new HashMap<String, Entry>();
    private long next = 1;

    /** {@code alive}: {@code Component::isDisplayable} live; unit tests pass {@code c -> true}. */
    public Refs(Predicate<Component> alive) {
        this.alive = alive;
    }

    public synchronized void clear() {
        entries.clear();
    }

    public synchronized String add(Component component, Integer tab) {
        String ref = "e" + next++;
        entries.put(ref, new Entry(component, tab));
        return ref;
    }

    public synchronized Entry get(String ref) {
        Entry e = entries.get(ref);
        Component c = e == null ? null : e.component();
        if (c == null || !alive.test(c)) {
            throw new DriverException(Reason.BAD_INPUT, "stale ref " + ref + " - take a new snapshot");
        }
        return e;
    }
}
