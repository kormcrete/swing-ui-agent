package io.github.kormcrete.swingui.driver.model;

import javax.swing.JTabbedPane;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

/** The scope steps above a component, root first: the tabs it sits on and the titled sections around it. */
public final class Trail {

    private Trail() {}

    public static List<String> of(Component c) {
        List<String> out = new ArrayList<String>();
        Component child = c;
        for (Component p = c.getParent(); p != null; child = p, p = p.getParent()) {
            if (p instanceof JTabbedPane) {
                JTabbedPane tabs = (JTabbedPane) p;
                int i = tabs.indexOfComponent(child);
                if (i >= 0 && !isDocumentHost(tabs)) {
                    String title = Names.tabTitle(tabs, i);
                    if (title != null) out.add(0, ScopeStep.tab(title));
                }
            }
            // Whatever its role: a titled box that also carries a mouse listener derives "clickable", and
            // still scopes what lies inside it - the Resolver pushes the same step as it descends.
            String caption = Names.sectionTitle(p);
            if (caption != null) out.add(0, ScopeStep.section(caption));
        }
        return out;
    }

    /**
     * A strip of open documents or screens, not a plain tab group: every one of its tabs installs
     * its own tab-header component (a close button), where a plain tab strip just carries a title
     * string. The selected title of such a strip is instance data picked at runtime (a record
     * number, a name) and can never replay against a different run, so the strip itself
     * contributes no scope step - only its content does.
     */
    private static boolean isDocumentHost(JTabbedPane tabs) {
        int count = tabs.getTabCount();
        if (count == 0) return false;
        for (int i = 0; i < count; i++) {
            if (tabs.getTabComponentAt(i) != null) return true;
        }
        return false;
    }
}
