package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.model.Node;
import org.junit.jupiter.api.Test;

import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;

import static org.assertj.core.api.Assertions.assertThat;

class TreeWidgetTest {

    private static JTree tree() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
        DefaultMutableTreeNode branch = new DefaultMutableTreeNode("A");
        branch.add(new DefaultMutableTreeNode("B"));
        root.add(branch);
        root.add(new DefaultMutableTreeNode("C"));
        JTree t = new JTree(root);
        t.setRootVisible(false);
        return t;
    }

    @Test
    void aCollapsedBranchIsNeitherLeafNorExpanded() {
        JTree t = tree();
        Node n = new Node("item", "A");
        TreeWidget.itemState(t, 0, n);
        assertThat(n.state).isNull();
    }

    @Test
    void anExpandedSelectedBranchAndALeaf() {
        JTree t = tree();
        t.expandRow(0);
        t.setSelectionRow(0);
        Node a = new Node("item", "A");
        TreeWidget.itemState(t, 0, a);
        Node b = new Node("item", "A > B");
        TreeWidget.itemState(t, 1, b);
        assertThat(a.state).containsExactlyInAnyOrder("selected", "expanded");
        assertThat(b.state).containsExactly("leaf");
    }
}
