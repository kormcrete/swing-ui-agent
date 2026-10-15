package io.github.kormcrete.swingui.driver.model;

import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JViewport;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.TitledBorder;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Name computation, first hit wins. Never goes through the accessibility API. EDT. */
public final class Names {

    private final Visibility visibility;
    private final Map<Container, Map<Component, JLabel>> labelIndexes = new IdentityHashMap<Container, Map<Component, JLabel>>();
    private final Map<Container, Set<JLabel>> captions = new IdentityHashMap<Container, Set<JLabel>>();

    public Names(Visibility visibility) {
        this.visibility = visibility;
    }

    /** Drops cached label indexes; call before each snapshot or resolution. */
    public void reset() {
        labelIndexes.clear();
        captions.clear();
    }

    /** {@code root} bounds the label search: the window, or the top of an unshown tree. */
    public String nameOf(Component c, Container root) {
        String n = clean(explicitName(c));
        return n != null ? n : seenName(c, root);
    }

    /**
     * The name a person reads on screen: {@link #nameOf} without the component's code name ({@code setName}), which
     * names it for a locator but shows nowhere. Null when nothing on screen names it.
     */
    public String seenName(Component c, Container root) {
        String n = clean(ownText(c));
        if (n == null && root != null) {
            JLabel label = labelIndex(root).get(c);
            if (label != null) n = cleanLabel(label.getText());
        }
        if (n == null && root != null && c instanceof JTable) {
            JLabel label = enclosingLabel(c, labelIndex(root));
            if (label != null) n = cleanLabel(label.getText());
        }
        if (n == null) n = clean(titledBorder(c));
        if (n == null && c instanceof JComponent) n = clean(((JComponent) c).getToolTipText());
        if (n == null) n = clean(actionName(c));
        return n;
    }

    /** The caption a container's own titled border shows: a {@code section:} scope step. */
    public static String sectionTitle(Component c) {
        return c instanceof JComponent ? clean(borderTitle(((JComponent) c).getBorder())) : null;
    }

    public static String ownText(Component c) {
        if (c instanceof AbstractButton) return ((AbstractButton) c).getText();
        if (c instanceof JLabel) return ((JLabel) c).getText();
        return null;
    }

    public static String clean(String s) {
        if (s == null) return null;
        String t = s;
        if (t.regionMatches(true, 0, "<html>", 0, 6)) {
            t = t.replaceAll("<[^>]*>", " ").replace("&nbsp;", " ").replace("&amp;", "&")
                    .replace("&lt;", "<").replace("&gt;", ">");
        }
        t = t.replace(' ', ' ').replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? null : t;
    }

    public static String cleanLabel(String s) {
        String t = clean(s);
        if (t != null && t.endsWith(":")) t = clean(t.substring(0, t.length() - 1));
        return t;
    }

    /**
     * Whether this label is already serving as an input's name. Such a label would
     * only repeat what the input node already carries, so the walk drops it and the tree grows by a
     * node only for text that names nothing - a count strip, a status line, a heading.
     *
     * <p>{@code root} is the window the label index is built over; without one nothing can be
     * decided, so the label is kept.
     */
    public boolean namesAnInput(Component c, Container root) {
        return c instanceof JLabel && root != null && captions(root).contains(c);
    }

    private Set<JLabel> captions(Container root) {
        Set<JLabel> set = captions.get(root);
        if (set == null) {
            set = LabelProximity.captionLabels(root, visibility);
            captions.put(root, set);
        }
        return set;
    }

    private Map<Component, JLabel> labelIndex(Container root) {
        Map<Component, JLabel> index = labelIndexes.get(root);
        if (index == null) {
            index = LabelProximity.labelIndex(root, visibility);
            labelIndexes.put(root, index);
        }
        return index;
    }

    /**
     * The label a {@code setLabelFor} links to a container around {@code table} — a panel wrapping the table with a
     * header or a filter —, when that container holds no other table: the
     * caption the label gives the container is the table's. Null when the nearest labelled ancestor holds several.
     */
    static JLabel enclosingLabel(Component table, Map<Component, JLabel> index) {
        for (Container p = table.getParent(); p != null && !(p instanceof Window); p = p.getParent()) {
            JLabel label = index.get(p);
            if (label != null) return tables(p) == 1 ? label : null;
        }
        return null;
    }

    private static int tables(Container parent) {
        int n = 0;
        for (Component c : parent.getComponents()) {
            if (c instanceof JTable) n++;
            else if (c instanceof Container) n += tables((Container) c);
        }
        return n;
    }

    /** Only Swing components: AWT windows and heavyweights invent names such as "frame0". */
    private static String explicitName(Component c) {
        return c instanceof JComponent ? c.getName() : null;
    }

    private static String titledBorder(Component c) {
        String t = borderTitleOf(c);
        if (t != null) return t;
        Component parent = c.getParent();
        if (parent instanceof JViewport) {
            Component scrollPane = parent.getParent();
            t = borderTitleOf(scrollPane);
            if (t != null) return t;
            parent = scrollPane == null ? null : scrollPane.getParent();
        }
        return borderTitleOf(parent);
    }

    private static String borderTitleOf(Component c) {
        return c instanceof JComponent ? borderTitle(((JComponent) c).getBorder()) : null;
    }

    private static String borderTitle(Border border) {
        if (border instanceof TitledBorder) return ((TitledBorder) border).getTitle();
        if (border instanceof CompoundBorder) {
            String outside = borderTitle(((CompoundBorder) border).getOutsideBorder());
            return outside != null ? outside : borderTitle(((CompoundBorder) border).getInsideBorder());
        }
        return null;
    }

    private static String actionName(Component c) {
        if (!(c instanceof AbstractButton)) return null;
        Action action = ((AbstractButton) c).getAction();
        Object name = action == null ? null : action.getValue(Action.NAME);
        return name == null ? null : name.toString();
    }

    /**
     * A tab's title, or the text of its custom tab component, or — for a tab whose title is painted by its icon (a
     * vertical tab strip's text icons carry no title) — its tooltip.
     */
    public static String tabTitle(JTabbedPane tabs, int index) {
        String title = clean(tabs.getTitleAt(index));
        if (title != null) return title;
        Component custom = tabs.getTabComponentAt(index);
        String text = custom instanceof Container ? firstText((Container) custom) : null;
        return text != null ? text : clean(tabs.getToolTipTextAt(index));
    }

    private static String firstText(Container c) {
        String own = clean(ownText(c));
        if (own != null) return own;
        for (Component child : c.getComponents()) {
            String t = child instanceof Container ? firstText((Container) child) : clean(ownText(child));
            if (t != null) return t;
        }
        return null;
    }
}
