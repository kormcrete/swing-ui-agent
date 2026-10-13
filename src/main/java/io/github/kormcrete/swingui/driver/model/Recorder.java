package io.github.kormcrete.swingui.driver.model;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.window.WindowRoot;
import io.github.kormcrete.swingui.driver.window.WindowRoots;
import io.github.kormcrete.swingui.driver.window.WindowSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a resolved target into the locator a flow stores: the first form that resolves to
 * exactly that element. The nth fallback marks the step fragile. EDT.
 */
public final class Recorder {

    public static final class Recorded {
        public final Locator locator;
        public final boolean fragile;

        public Recorded(Locator locator, boolean fragile) {
            this.locator = locator;
            this.fragile = fragile;
        }
    }

    private final Resolver resolver;
    private final WindowSource windows;

    public Recorder(Resolver resolver, WindowSource windows) {
        this.resolver = resolver;
        this.windows = windows;
    }

    public Recorded record(Resolved target) {
        Locator sub = subTargets(target.locator);
        String window = shortWindow(target.window);
        List<Locator> tries = new ArrayList<Locator>();
        String id = target.tab == null ? target.component.getName() : null;
        if (id != null && !id.isEmpty() && target.component instanceof javax.swing.JComponent) {
            tries.add(with(sub, null, null, null, null, id));
            if (window != null) tries.add(with(sub, window, null, null, null, id));
        }
        // An element without a name (an unlabelled table) is still unique by role inside its scope often enough:
        // try that before falling back to nth.
        tries.add(with(sub, window, null, target.role, target.name, null));
        for (int k = 1; k <= target.trail.size(); k++) {
            List<String> innermost = new ArrayList<String>(target.trail.subList(target.trail.size() - k, target.trail.size()));
            tries.add(with(sub, window, innermost, target.role, target.name, null));
        }
        for (Locator l : tries) {
            List<Resolved> m = resolver.matches(l);
            if (m.size() == 1 && same(m.get(0), target)) return new Recorded(l, false);
        }
        Locator last = with(sub, window, target.trail.isEmpty() ? null : new ArrayList<String>(target.trail),
                target.role, target.name, null);
        List<Resolved> m = resolver.matches(last);
        for (int i = 0; i < m.size(); i++) {
            if (same(m.get(i), target)) {
                last.nth = i;
                return new Recorded(last, true);
            }
        }
        // An element whose window closed under a held ref is reported as gone, as a stale ref is,
        // not as one no locator can address.
        if (!target.component.isShowing()) {
            throw new DriverException(Reason.BAD_INPUT, "the element is no longer on screen: "
                    + target.describe() + " - its window has closed or been replaced since the ref was"
                    + " taken; take a new snapshot");
        }
        throw new DriverException(Reason.BAD_INPUT, "no locator resolves to " + target.describe()
                + " - it cannot be addressed by window, scope, role, name, id or nth");
    }

    /** A bare {@code dialog} when it names the same dialog; the exact title otherwise. */
    private String shortWindow(String token) {
        if (token == null || !token.startsWith("dialog:") || token.endsWith("*")) return token;
        List<WindowRoot> roots = windows.roots();
        List<WindowRoot> bare = WindowRoots.select(WindowRef.parse("dialog"), roots, windows.main());
        List<WindowRoot> exact = WindowRoots.select(WindowRef.parse(token), roots, windows.main());
        return bare.size() == 1 && exact.size() == 1 && bare.get(0) == exact.get(0) ? "dialog" : token;
    }

    private static Locator subTargets(Locator from) {
        Locator copy = from.copy();
        Locator l = new Locator();
        l.row = copy.row;
        l.column = copy.column;
        l.item = copy.item;
        l.point = copy.point;
        return l;
    }

    private static Locator with(Locator sub, String window, List<String> scope, String role, String name, String id) {
        Locator l = sub.copy();
        l.window = window;
        l.scope = scope;
        l.role = role;
        l.name = name;
        l.id = id;
        return l;
    }

    private static boolean same(Resolved a, Resolved b) {
        return a.component == b.component && (a.tab == null ? b.tab == null : a.tab.equals(b.tab));
    }
}
