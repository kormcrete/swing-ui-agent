package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.model.Names;
import io.github.kormcrete.swingui.driver.model.Recorder;
import io.github.kormcrete.swingui.driver.model.Refs;
import io.github.kormcrete.swingui.driver.model.Resolver;
import io.github.kormcrete.swingui.driver.model.Visibility;
import io.github.kormcrete.swingui.driver.window.WindowRoot;
import io.github.kormcrete.swingui.driver.window.WindowSource;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.RootPaneContainer;
import javax.swing.JTextField;
import javax.swing.table.JTableHeader;
import java.awt.Component;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

/** An unshown order form - a field, a button, a check box, an orders table with its header - and an inspector over it. */
final class Scene implements WindowSource {

    final List<WindowRoot> roots = new ArrayList<WindowRoot>();
    final Refs refs = new Refs(c -> true);
    final Names names = new Names(Visibility.TREE);
    final Resolver resolver = new Resolver(Visibility.TREE, names, refs, this);
    final Recorder recorder = new Recorder(resolver, this);
    final Inspector inspector = new Inspector(refs, resolver, recorder);

    final JPanel content = new JPanel(null);
    final JTextField customer = new JTextField();
    final JButton save = new JButton("Save");
    final JCheckBox rush = new JCheckBox("Rush");
    final JTable orders = new JTable(new Object[][] {
            {"ORD-1640", "Acme", "Open"},
            {"ORD-1641", "Acme", "Shipped"},
            {"ORD-1642", "Birch & Co", "Open"}}, new Object[] {"Order", "Customer", "Status"});
    final JTableHeader header = orders.getTableHeader();

    Scene() {
        customer.setName("customer");
        place(customer, 10, 10, 120, 24);
        place(save, 140, 10, 80, 24);
        place(rush, 230, 10, 80, 24);
        place(header, 10, 50, 300, 20);
        place(orders, 10, 70, 300, 48);
        orders.doLayout();
        roots.add(new WindowRoot(content, "window", "Orders", false, null, true));
    }

    /** Sizes an unshown window and lays out its root pane, as showing it would. EDT. */
    static void size(Window w, int width, int height) {
        w.setSize(width, height);
        w.doLayout();
        ((RootPaneContainer) w).getRootPane().doLayout();
    }

    private void place(Component c, int x, int y, int w, int h) {
        c.setBounds(x, y, w, h);
        content.add(c);
    }

    @Override public List<WindowRoot> roots() { return roots; }
    @Override public Component main() { return content; }
}
