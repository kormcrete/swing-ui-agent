package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.widgets.Screenshots;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import java.awt.Component;
import java.awt.Graphics;
import java.util.ArrayList;
import java.util.List;

/**
 * The steps list's row menu (right click on a row, or ⋯): describe the step, the check or the steps selected, ungroup,
 * and how a table row is named - this value or by position, the current way marked, a way not known yet greyed. The
 * palette's own: never seen by the snapshot, the recorder or a screenshot. EDT.
 */
final class StepMenu extends JPopupMenu {

    /** Adds {@code label}; {@code enabled} false greys it. */
    StepMenu item(String label, boolean enabled, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        return add(item, enabled, action);
    }

    /** Adds a choice of one of several ways, {@code selected} for the current one. */
    StepMenu choice(String label, boolean selected, boolean enabled, Runnable action) {
        JRadioButtonMenuItem item = new JRadioButtonMenuItem(label, selected);
        return add(item, enabled, action);
    }

    StepMenu separator() {
        if (getComponentCount() > 0) addSeparator();
        return this;
    }

    private StepMenu add(JMenuItem item, boolean enabled, Runnable action) {
        item.setEnabled(enabled);
        item.addActionListener(e -> action.run());
        add(item);
        return this;
    }

    /** The items' labels, in order. */
    List<String> labels() {
        List<String> out = new ArrayList<String>();
        for (Component c : getComponents()) if (c instanceof JMenuItem) out.add(((JMenuItem) c).getText());
        return out;
    }

    boolean enabled(String label) {
        return item(label).isEnabled();
    }

    /** Whether {@code label} is the way chosen now. */
    boolean selected(String label) {
        return item(label).isSelected();
    }

    /** Chooses {@code label}, as the person would: the menu closes, the item's action runs (not for a greyed one). */
    void choose(String label) {
        JMenuItem item = item(label);
        setVisible(false);
        if (item.isEnabled()) item.doClick(0);
    }

    private JMenuItem item(String label) {
        for (Component c : getComponents()) {
            if (c instanceof JMenuItem && label.equals(((JMenuItem) c).getText())) return (JMenuItem) c;
        }
        throw new IllegalArgumentException("no item '" + label + "' in the step menu, only " + labels());
    }

    /** Not in a screenshot: a capture leaves the palette out. */
    @Override
    public void paint(Graphics g) {
        if (!Screenshots.capturing()) super.paint(g);
    }
}
