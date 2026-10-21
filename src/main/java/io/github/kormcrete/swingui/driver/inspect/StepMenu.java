package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.widgets.Screenshots;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import java.awt.Component;
import java.awt.Graphics;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The steps list's row menu (right click on a row, or Shift+F10 / the context-menu key): describe the step, the check or the steps selected, ungroup,
 * and how a table row is named - this value or by position, the current way marked, a way not known yet greyed. For a
 * domain step: Rename… (its sentence), Unfold (a fold: back into the steps it stands for) and Delete. For one record:
 * a value from an earlier step (a submenu), the exact value, and for a typed text unique per run (a submenu). The
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

    /**
     * Adds the submenu {@code label} with {@code items}; choosing the one at index i runs {@code action} with i. Greyed
     * when it has none, or not {@code enabled}.
     */
    StepMenu submenu(String label, boolean enabled, List<String> items, IntConsumer action) {
        return submenu(label, enabled, items, -1, action);
    }

    /** {@link #submenu} of ways to choose from, the one at {@code selected} marked as the current one. */
    StepMenu choices(String label, List<String> items, int selected, IntConsumer action) {
        return submenu(label, true, items, selected, action);
    }

    private StepMenu submenu(String label, boolean enabled, List<String> items, int selected, IntConsumer action) {
        Sub sub = new Sub(label);
        for (int i = 0; i < items.size(); i++) {
            int at = i;
            JMenuItem item = selected < 0 ? new JMenuItem(items.get(i)) : new JRadioButtonMenuItem(items.get(i), i == selected);
            item.addActionListener(e -> action.accept(at));
            sub.add(item);
        }
        sub.setEnabled(enabled && !items.isEmpty());
        add(sub);
        return this;
    }

    /** A submenu: in the palette's package, so that its popup counts as the palette's own. */
    private static final class Sub extends JMenu {
        Sub(String label) {
            super(label);
        }
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

    /** The items of submenu {@code label}, in order. */
    List<String> labels(String label) {
        List<String> out = new ArrayList<String>();
        for (Component c : sub(label).getMenuComponents()) if (c instanceof JMenuItem) out.add(((JMenuItem) c).getText());
        return out;
    }

    /** Whether {@code item} of submenu {@code label} is the way chosen now. */
    boolean selected(String label, String item) {
        return item(sub(label), item).isSelected();
    }

    /** Chooses {@code item} in submenu {@code label}, as the person would: the menu closes, the item's action runs. */
    void choose(String label, String item) {
        JMenu sub = sub(label);
        JMenuItem chosen = item(sub, item);
        setVisible(false);
        if (sub.isEnabled() && chosen.isEnabled()) chosen.doClick(0);
    }

    private JMenu sub(String label) {
        JMenuItem item = item(label);
        if (!(item instanceof JMenu)) throw new IllegalArgumentException("'" + label + "' is no submenu");
        return (JMenu) item;
    }

    private static JMenuItem item(JMenu sub, String label) {
        for (Component c : sub.getMenuComponents()) {
            if (c instanceof JMenuItem && label.equals(((JMenuItem) c).getText())) return (JMenuItem) c;
        }
        throw new IllegalArgumentException("no item '" + label + "' in '" + sub.getText() + "'");
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
