package io.github.kormcrete.swingui.driver.verbs;

import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Resolved;
import org.junit.jupiter.api.Test;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.table.JTableHeader;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class TargetsTest {

    private final JTable table = new JTable(new Object[][] {{"a", "b"}, {"c", "d"}}, new Object[] {"Name", "Info"});

    TargetsTest() {
        table.setBounds(0, 0, 200, 32);
        table.getColumnModel().getColumn(0).setWidth(120);
        table.getColumnModel().getColumn(1).setWidth(80);
        table.getTableHeader().setBounds(0, 0, 200, 18);
    }

    private Resolved resolved(Locator l) {
        return new Resolved(table, null, "table", null, "main", Collections.<String>emptyList(), l);
    }

    @Test
    void aColumnWithoutARowAimsAtItsHeaderCell() {
        Locator l = Locator.of("table", null);
        l.column = "Info";
        Resolved r = resolved(l);
        assertThat(Targets.surface(r)).isInstanceOf(JTableHeader.class);
        Rectangle cell = table.getTableHeader().getHeaderRect(1);
        assertThat(Targets.aim(r)).isEqualTo(new Point(cell.x + cell.width / 2, cell.y + cell.height / 2));
        assertThat(Targets.rightBorder(r)).isEqualTo(new Point(cell.x + cell.width - 1, cell.y + cell.height / 2));
    }

    @Test
    void aRowAndAColumnStillAimAtTheCell() {
        Locator l = Locator.of("table", null);
        l.row = Collections.<String, Object>singletonMap("index", 1);
        l.column = "Info";
        Resolved r = resolved(l);
        assertThat(Targets.surface(r)).isSameAs(table);
        Rectangle cell = table.getCellRect(1, 1, false);
        assertThat(Targets.aim(r)).isEqualTo(new Point(cell.x + cell.width / 2, cell.y + cell.height / 2));
    }

    // ---- aiming brings the target into view of every scroll pane around it, as a table row does ----

    /** A view of 50 rows in its own small scroll pane, placed far down a scrolling form; returns the form's pane. */
    private static JScrollPane inScrollingForm(JComponent view, int viewHeight) {
        JScrollPane own = new JScrollPane(view);
        view.setSize(300, viewHeight);
        own.getViewport().setSize(300, 100);
        own.getViewport().setExtentSize(new Dimension(300, 100));
        own.setBounds(0, 1000, 300, 100);
        JPanel form = new JPanel(null);
        form.add(own);
        form.setPreferredSize(new Dimension(300, 1200));
        form.setSize(300, 1200);
        JScrollPane formPane = new JScrollPane(form);
        formPane.getViewport().setSize(300, 400);
        formPane.getViewport().setExtentSize(new Dimension(300, 400));
        formPane.setSize(300, 400);
        return formPane;
    }

    private static void assertShownInForm(Component c, Rectangle area, JScrollPane formPane) {
        Rectangle seen = SwingUtilities.convertRectangle(c, area, formPane.getViewport());
        assertThat(new Rectangle(0, 0, 300, 400).contains(seen)).as("in the form's visible part: " + seen).isTrue();
    }

    private static Resolved resolved(Component c, String role, Locator l) {
        return new Resolved(c, null, role, null, "main", Collections.<String>emptyList(), l);
    }

    @Test
    void aListItemInsideAScrollingFormIsBroughtIntoViewOfTheFormToo() throws Exception {
        DefaultListModel<String> model = new DefaultListModel<String>();
        for (int i = 0; i < 50; i++) model.addElement("Item " + i);
        JList<String> list = new JList<String>(model);
        SwingUtilities.invokeAndWait(() -> {
            JScrollPane formPane = inScrollingForm(list, 50 * list.getCellBounds(0, 0).height);
            Locator l = Locator.of("list", null);
            l.item = Collections.singletonList("Item 40");
            Targets.aim(resolved(list, "list", l));
            assertShownInForm(list, list.getCellBounds(40, 40), formPane);
        });
    }

    @Test
    void aTreeRowInsideAScrollingFormIsBroughtIntoViewOfTheFormToo() throws Exception {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Products");
        for (int i = 0; i < 50; i++) root.add(new DefaultMutableTreeNode("Product " + i));
        JTree tree = new JTree(root);
        SwingUtilities.invokeAndWait(() -> {
            JScrollPane formPane = inScrollingForm(tree, 51 * tree.getRowBounds(0).height);
            Locator l = Locator.of("tree", null);
            l.item = java.util.Arrays.asList("Products", "Product 40");
            Targets.aim(resolved(tree, "tree", l));
            assertShownInForm(tree, tree.getRowBounds(41), formPane);
        });
    }

    @Test
    void aPlainComponentInsideAScrollingFormIsBroughtIntoViewOfTheFormToo() throws Exception {
        JPanel strip = new JPanel(null);
        JButton save = new JButton("Save");
        save.setBounds(0, 900, 300, 30);
        strip.add(save);
        SwingUtilities.invokeAndWait(() -> {
            JScrollPane formPane = inScrollingForm(strip, 1000);
            Targets.aim(resolved(save, "button", Locator.of("button", "Save")));
            assertShownInForm(save, new Rectangle(0, 0, save.getWidth(), save.getHeight()), formPane);
        });
    }

    // ---- a right-click target is selected first, as a user's right-click selects in most apps ----

    @Test
    void aRightClickedTableRowThatIsNotSelectedBecomesTheSelection() throws Exception {
        JTable lines = new JTable(new Object[][] {{"a"}, {"b"}, {"c"}}, new Object[] {"Name"});
        SwingUtilities.invokeAndWait(() -> {
            lines.setRowSelectionInterval(0, 0);
            Locator l = Locator.of("table", null);
            l.row = Collections.<String, Object>singletonMap("Name", "c");
            Targets.selectForContextMenu(resolved(lines, "table", l));
            assertThat(lines.getSelectedRows()).containsExactly(2);
        });
    }

    @Test
    void aRightClickOnARowOfTheSelectionKeepsTheWholeSelection() throws Exception {
        JTable lines = new JTable(new Object[][] {{"a"}, {"b"}, {"c"}}, new Object[] {"Name"});
        SwingUtilities.invokeAndWait(() -> {
            lines.setRowSelectionInterval(0, 1);
            Locator l = Locator.of("table", null);
            l.row = Collections.<String, Object>singletonMap("index", 1);
            Targets.selectForContextMenu(resolved(lines, "table", l));
            assertThat(lines.getSelectedRows()).containsExactly(0, 1);
        });
    }

    @Test
    void aRightClickedListItemAndTreeRowBecomeTheSelection() throws Exception {
        DefaultListModel<String> model = new DefaultListModel<String>();
        model.addElement("Paper");
        model.addElement("Pens");
        JList<String> list = new JList<String>(model);
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Products");
        root.add(new DefaultMutableTreeNode("Paper"));
        root.add(new DefaultMutableTreeNode("Pens"));
        JTree tree = new JTree(root);
        SwingUtilities.invokeAndWait(() -> {
            Locator item = Locator.of("list", null);
            item.item = Collections.singletonList("Pens");
            Targets.selectForContextMenu(resolved(list, "list", item));
            assertThat(list.getSelectedIndices()).containsExactly(1);

            Locator node = Locator.of("tree", null);
            node.item = java.util.Arrays.asList("Products", "Pens");
            Targets.selectForContextMenu(resolved(tree, "tree", node));
            assertThat(tree.getSelectionRows()).containsExactly(2);
        });
    }

    @Test
    void aRightClickOnAPlainComponentChangesNoSelection() throws Exception {
        JTable lines = new JTable(new Object[][] {{"a"}, {"b"}}, new Object[] {"Name"});
        SwingUtilities.invokeAndWait(() -> {
            lines.setRowSelectionInterval(0, 0);
            Targets.selectForContextMenu(resolved(lines, "table", Locator.of("table", null)));
            assertThat(lines.getSelectedRows()).containsExactly(0);
        });
    }
}
