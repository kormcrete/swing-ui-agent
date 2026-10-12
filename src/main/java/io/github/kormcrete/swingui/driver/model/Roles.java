package io.github.kormcrete.swingui.driver.model;

import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.JRootPane;
import javax.swing.JLayeredPane;
import javax.swing.table.JTableHeader;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Window;
import java.awt.event.MouseListener;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Snapshot and locator roles. */
public final class Roles {

    public static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
            "window", "dialog", "button", "toggle", "checkbox", "radio", "text", "password", "combo", "list",
            "table", "tree", "tabs", "tab", "menubar", "menu", "menuitem", "clickable", "label", "section"));

    private static final String[] PLATFORM_PACKAGES = {"javax.swing.", "java.awt.", "sun.", "com.sun.", "com.apple."};

    private Roles() {}

    /** The role of a component, or null for a plain container. */
    public static String of(Component c) {
        if (c instanceof Window) return c instanceof Dialog ? "dialog" : "window";
        if (c instanceof JMenuBar) return "menubar";
        if (c instanceof JMenu || c instanceof JPopupMenu) return "menu";
        if (c instanceof JMenuItem) return "menuitem";
        if (c instanceof JCheckBox) return "checkbox";
        if (c instanceof JRadioButton) return "radio";
        if (c instanceof JToggleButton) return "toggle";
        if (c instanceof AbstractButton) return "button";
        if (c instanceof JPasswordField) return "password";
        if (c instanceof JTextComponent) return "text";
        if (c instanceof JComboBox) return "combo";
        if (c instanceof JList) return "list";
        if (c instanceof JTable) return "table";
        if (c instanceof JTree) return "tree";
        if (c instanceof JTabbedPane) return "tabs";
        if (isPlumbing(c)) return null;
        if (hasAppMouseListener(c)) return "clickable";
        return isStaticText(c) ? "label" : null;
    }

    /**
     * Static text the app painted for the reader, such as a "50 of 407" count strip. Tested after
     * {@link #hasAppMouseListener}, so a label the app made clickable keeps the role that says it can be clicked.
     *
     * <p>A label that already serves as an input's accessible name is dropped later, by the walk -
     * not here - because that depends on the whole window, which a per-component role cannot see.
     */
    private static boolean isStaticText(Component c) {
        return c instanceof JLabel && Names.clean(((JLabel) c).getText()) != null;
    }

    /**
     * Whether a node of this role lists the targets inside it. A text field does: a button mounted inside it (a clear
     * button, a lookup's busy mark) is its child.
     */
    public static boolean descends(String role) {
        return "menubar".equals(role) || "clickable".equals(role) || "text".equals(role);
    }

    private static boolean isPlumbing(Component c) {
        return c instanceof JScrollBar || c instanceof JScrollPane || c instanceof JViewport
                || c instanceof JSplitPane || c instanceof JRootPane || c instanceof JLayeredPane
                || c instanceof JToolBar || c instanceof JTableHeader
                || c.getClass().getName().startsWith("javax.swing.plaf.");
    }

    /** A mouse listener the app added: not the look and feel's, not the tooltip manager's. */
    private static boolean hasAppMouseListener(Component c) {
        String uiPrefix = uiPackagePrefix(c);
        for (MouseListener l : c.getMouseListeners()) {
            String name = l.getClass().getName();
            if (startsWithAny(name, PLATFORM_PACKAGES)) continue;
            if (uiPrefix != null && name.startsWith(uiPrefix)) continue;
            return true;
        }
        return false;
    }

    /** The package of the component's UI delegate, e.g. {@code com.formdev.flatlaf.ui.}; its listeners are the look and feel's. */
    private static String uiPackagePrefix(Component c) {
        try {
            Object ui = c.getClass().getMethod("getUI").invoke(c);
            if (ui == null || ui.getClass().getPackage() == null) return null;
            return ui.getClass().getPackage().getName() + ".";
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean startsWithAny(String s, String[] prefixes) {
        for (String p : prefixes) {
            if (s.startsWith(p)) return true;
        }
        return false;
    }

    /** Components that never contain a target: scroll bars and look-and-feel internals. */
    public static boolean skips(Component c) {
        return c instanceof JScrollBar || c.getClass().getName().startsWith("javax.swing.plaf.");
    }
}
