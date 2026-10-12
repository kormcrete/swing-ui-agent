package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Names;

import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.plaf.basic.ComboPopup;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

/** Popup menus and combo popups. EDT. */
public final class Popups {

    private Popups() {}

    public static JMenuItem item(JPopupMenu popup, String label) {
        List<JMenuItem> hits = new ArrayList<JMenuItem>();
        List<String> items = new ArrayList<String>();
        for (Component c : popup.getComponents()) {
            if (!(c instanceof JMenuItem) || !c.isVisible()) continue;
            String text = Names.clean(((JMenuItem) c).getText());
            items.add(text);
            if (label.equals(text)) hits.add((JMenuItem) c);
        }
        if (hits.isEmpty()) throw new DriverException(Reason.NOT_FOUND, "no item '" + label + "' in the popup; items are " + items);
        if (hits.size() > 1) throw new DriverException(Reason.AMBIGUOUS, hits.size() + " popup items read '" + label + "'");
        return hits.get(0);
    }

    /** The list of the combo's showing popup, or null while it is closed. */
    public static JList<?> comboList(JComboBox<?> combo, List<JPopupMenu> popups) {
        for (JPopupMenu p : popups) {
            if (p.getInvoker() == combo && p instanceof ComboPopup && p.isShowing()) return ((ComboPopup) p).getList();
        }
        return null;
    }
}
