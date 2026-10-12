package io.github.kormcrete.swingui.driver.window;

import io.github.kormcrete.swingui.driver.model.WindowRef;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

/** Picks the roots a window token names, and writes the token for a root. */
public final class WindowRoots {

    private WindowRoots() {}

    public static List<WindowRoot> select(WindowRef ref, List<WindowRoot> roots, Component main) {
        List<WindowRoot> out = new ArrayList<WindowRoot>();
        switch (ref.kind) {
            case MAIN:
                for (WindowRoot r : roots) if (main != null && r.content == main) out.add(r);
                break;
            case POPUP:
                WindowRoot innermost = null;
                for (WindowRoot r : roots) if ("menu".equals(r.role)) innermost = r;
                if (innermost != null) out.add(innermost);
                break;
            case WINDOW:
                for (WindowRoot r : roots) if ("window".equals(r.role) && ref.titleMatches(r.title)) out.add(r);
                break;
            case DIALOG:
                if (ref.title != null) {
                    for (WindowRoot r : roots) if ("dialog".equals(r.role) && ref.titleMatches(r.title)) out.add(r);
                    break;
                }
                WindowRoot top = null;
                List<WindowRoot> dialogs = new ArrayList<WindowRoot>();
                for (WindowRoot r : roots) {
                    if (!"dialog".equals(r.role)) continue;
                    dialogs.add(r);
                    if (r.modal && r.blockedBy == null) top = r;
                }
                if (top != null) out.add(top);
                else if (dialogs.size() == 1) out.add(dialogs.get(0));
                break;
            default:
                break;
        }
        return out;
    }

    public static String token(WindowRoot root, Component main) {
        if (main != null && root.content == main) return "main";
        if ("menu".equals(root.role)) return "popup";
        return ("dialog".equals(root.role) ? "dialog:" : "window:") + root.title;
    }
}
