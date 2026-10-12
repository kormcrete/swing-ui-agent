package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.model.Names;
import io.github.kormcrete.swingui.driver.model.Node;

import javax.swing.AbstractButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Fills a snapshot node from its component, per kind. EDT. */
public final class Widgets {

    private Widgets() {}

    public static void describe(Component c, String role, Node n, int maxRows) {
        if (!c.isEnabled()) n.addState("disabled");
        if (c.isFocusOwner()) n.addState("focused");
        if (c instanceof AbstractButton && ((AbstractButton) c).isSelected()) {
            if ("toggle".equals(role)) {
                // Both: 'checked' matches what check:true sets, 'selected' is what flows read.
                n.addState("selected");
                n.addState("checked");
            } else if ("checkbox".equals(role) || "radio".equals(role)
                    || c instanceof JCheckBoxMenuItem || c instanceof JRadioButtonMenuItem) {
                n.addState("checked");
            }
        } else if (isSelectedRow(c)) {
            n.addState("selected");
        }
        if (c instanceof AbstractButton && ((AbstractButton) c).getModel().isRollover()) n.addState("rollover");
        if (c instanceof JTextComponent) {
            JTextComponent text = (JTextComponent) c;
            n.addState(text.isEditable() ? "editable" : "readonly");
            if (!"password".equals(role)) n.value = text.getText();
        } else if (c instanceof JComboBox) {
            ComboWidget.describe((JComboBox<?>) c, n, maxRows);
        } else if (c instanceof JList) {
            ListWidget.describe((JList<?>) c, n, maxRows);
        } else if (c instanceof JTable) {
            TableWidget.describe((JTable) c, n, maxRows);
        } else if (c instanceof JTree) {
            TreeWidget.describe((JTree) c, n, maxRows);
        } else if ("label".equals(role) || c instanceof AbstractButton) {
            // A label's name is its component name when it has one - every JOptionPane message label is
            // "OptionPane.label" - so the text the reader sees rides as its value, when it differs. A named
            // button or check box likewise ("orderOption.express" reads "Express delivery").
            String text = Names.clean(Names.ownText(c));
            if (text != null && !text.equals(n.name)) n.value = text;
        }
        n.tooltip = tooltip(c);
        Scrolls.describe(c, n);
    }

    /** A component's own tooltip, HTML stripped; null when it has none. Reading it registers nothing. */
    public static String tooltip(Component c) {
        return c instanceof JComponent ? Names.clean(((JComponent) c).getToolTipText()) : null;
    }

    /**
     * A non-button component's own selection (a custom-painted row, say): a public, no-argument
     * {@code boolean isSelected()}, called reflectively so no app class is needed. Reads only.
     */
    private static boolean isSelectedRow(Component c) {
        try {
            Method m = c.getClass().getMethod("isSelected");
            if (m.getReturnType() != boolean.class) return false;
            return Boolean.TRUE.equals(m.invoke(c));
        } catch (Exception e) {
            return false;
        }
    }

    public static List<Integer> indices(int[] values) {
        List<Integer> out = new ArrayList<Integer>();
        if (values != null) for (int v : values) out.add(v);
        return out;
    }
}
