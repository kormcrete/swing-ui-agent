package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Names;

import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.RootPaneContainer;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

/** The menu bar's model, walked by label. EDT. */
public final class MenuWidget {

    private MenuWidget() {}

    public static JMenu topMenu(JMenuBar bar, String label) {
        List<JMenu> hits = new ArrayList<JMenu>();
        List<String> menus = new ArrayList<String>();
        for (int i = 0; i < bar.getMenuCount(); i++) {
            JMenu menu = bar.getMenu(i);
            if (menu == null || !menu.isVisible()) continue;
            String text = Names.clean(menu.getText());
            menus.add(text);
            if (label.equals(text)) hits.add(menu);
        }
        if (hits.isEmpty()) throw new DriverException(Reason.NOT_FOUND, "no menu '" + label + "'; menus are " + menus);
        if (hits.size() > 1) throw new DriverException(Reason.AMBIGUOUS, hits.size() + " menus read '" + label + "'");
        return hits.get(0);
    }

    /**
     * Follows {@code rest} through submenus; the last label must be a plain item. Checks {@code current}
     * enabled before searching it, so a disabled top menu or a disabled submenu along the path is caught
     * before the leaf, and the leaf item's own enabled state before returning it.
     */
    public static JMenuItem walk(JMenu top, List<String> rest) {
        if (rest.isEmpty()) throw new DriverException(Reason.BAD_INPUT, "a menu path needs an item after the menu");
        JMenu current = top;
        for (int i = 0; i < rest.size(); i++) {
            if (!current.isEnabled()) {
                throw new DriverException(Reason.NOT_ACTIONABLE, "disabled: menu '" + Names.clean(current.getText()) + "'");
            }
            String label = rest.get(i);
            JMenuItem item = child(current, label);
            boolean last = i == rest.size() - 1;
            if (last) {
                if (item instanceof JMenu) {
                    throw new DriverException(Reason.NOT_ACTIONABLE, "'" + label + "' opens a submenu - add the item to the path");
                }
                if (!item.isEnabled()) {
                    throw new DriverException(Reason.NOT_ACTIONABLE, "disabled: menu item '" + label + "'");
                }
                return item;
            }
            if (!(item instanceof JMenu)) {
                throw new DriverException(Reason.NOT_ACTIONABLE, "'" + label + "' has no submenu");
            }
            current = (JMenu) item;
        }
        throw new IllegalStateException("unreachable");
    }

    /** The visible item labels of the menu {@code rest} leads to from {@code top}; a submenu is listed by its label. */
    public static List<String> items(JMenu top, List<String> rest) {
        JMenu current = top;
        for (String label : rest) {
            JMenuItem item = child(current, label);
            if (!(item instanceof JMenu)) {
                throw new DriverException(Reason.NOT_ACTIONABLE, "'" + label + "' has no submenu");
            }
            current = (JMenu) item;
        }
        List<String> out = new ArrayList<String>();
        for (Component c : current.getMenuComponents()) {
            if (c instanceof JMenuItem && c.isVisible()) out.add(Names.clean(((JMenuItem) c).getText()));
        }
        return out;
    }

    /**
     * Every leaf path of {@code window}'s menu bar, depth first ("File > Export > Orders"), as the {@code menu} verb
     * takes it. A leaf that cannot be chosen (disabled, or under a disabled menu) ends in " [disabled]"; a submenu with
     * no items is listed as a leaf (an app may fill it as it opens). Hidden menus and items, and items without text,
     * are left out. Empty without a window or a menu bar.
     */
    public static List<String> leafPaths(Component window) {
        List<String> out = new ArrayList<String>();
        if (!(window instanceof RootPaneContainer)) return out;
        JMenuBar bar = ((RootPaneContainer) window).getRootPane().getJMenuBar();
        if (bar == null) return out;
        for (int i = 0; i < bar.getMenuCount(); i++) {
            JMenu menu = bar.getMenu(i);
            if (menu != null) addLeaves(menu, "", true, out);
        }
        return out;
    }

    private static void addLeaves(JMenuItem item, String parent, boolean parentEnabled, List<String> out) {
        String text = Names.clean(item.getText());
        if (!item.isVisible() || text == null) return;
        String path = parent.isEmpty() ? text : parent + " > " + text;
        boolean enabled = parentEnabled && item.isEnabled();
        List<JMenuItem> children = new ArrayList<JMenuItem>();
        if (item instanceof JMenu) {
            for (Component c : ((JMenu) item).getMenuComponents()) {
                if (c instanceof JMenuItem && c.isVisible()) children.add((JMenuItem) c);
            }
        }
        if (children.isEmpty()) {
            out.add(enabled ? path : path + " [disabled]");
            return;
        }
        for (JMenuItem child : children) addLeaves(child, path, enabled, out);
    }

    private static JMenuItem child(JMenu menu, String label) {
        List<JMenuItem> hits = new ArrayList<JMenuItem>();
        List<String> items = new ArrayList<String>();
        for (Component c : menu.getMenuComponents()) {
            if (!(c instanceof JMenuItem) || !c.isVisible()) continue;
            String text = Names.clean(((JMenuItem) c).getText());
            items.add(text);
            if (label.equals(text)) hits.add((JMenuItem) c);
        }
        if (hits.isEmpty()) {
            throw new DriverException(Reason.NOT_FOUND, "no item '" + label + "' in menu '" + Names.clean(menu.getText())
                    + "'; items are " + items);
        }
        if (hits.size() > 1) {
            throw new DriverException(Reason.AMBIGUOUS, hits.size() + " items in menu '" + Names.clean(menu.getText())
                    + "' read '" + label + "'");
        }
        return hits.get(0);
    }
}
