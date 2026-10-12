package io.github.kormcrete.swingui.driver.model;

import io.github.kormcrete.swingui.driver.window.WindowRoot;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import java.awt.Window;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The snapshot merges what a registered {@link SectionSource} contributes into the walked
 * tree, and the walk continues into the claimed component's children as usual.
 *
 * <p>The walk builds the node, the claim adds its {@code id} and {@code state} to it. A claimed component that
 * derives a role keeps it - a claimed {@code JButton} stays {@code role:"button"} and keeps {@code disabled} - and
 * only a component that derives no role (such as a plain, uncaptioned {@code JPanel}) becomes a {@code section} node
 * named by the section.
 *
 * <p>Headless: synthetic component tree, no window ever shown.
 */
class SnapshotSectionsTest {

    private final Refs refs = new Refs(c -> true);
    private final Names names = new Names(Visibility.TREE);
    private final FakeWindows windows = new FakeWindows();
    private final JPanel form = new JPanel();
    private final JPanel orderPanel = new JPanel();
    private final JButton save = new JButton("Ok");

    SnapshotSectionsTest() {
        orderPanel.add(save);
        form.add(orderPanel);
        windows.roots.add(new WindowRoot(form, "dialog", "Order editor", false, null, false));
    }

    private static Section section(String id, String name, JComponent content, String... state) {
        Section s = new Section();
        s.id = id;
        s.name = name;
        s.content = content;
        for (String st : state) s.addState(st);
        return s;
    }

    /** Returns the same fixed list on every call; the window parameter is unused, as {@link SectionsTest} does. */
    private static final class FixedSource implements SectionSource {
        private final List<Section> sections;

        FixedSource(Section... sections) {
            this.sections = Arrays.asList(sections);
        }

        @Override
        public List<Section> sections(Window window) {
            return sections;
        }
    }

    @Test
    void aSectionNodeAppearsWithIdNameAndStates() {
        Sections sections = new Sections();
        sections.register(new FixedSource(section("order", "Order", orderPanel, "incomplete")));
        Snapshot snapshot = new Snapshot(Visibility.TREE, names, refs, windows, sections);

        Node node = snapshot.build(null, null, 400, 20).find("section", "Order");

        assertThat(node).isNotNull();
        assertThat(node.id).isEqualTo("order");
        assertThat(node.state).containsExactly("incomplete");
    }

    @Test
    void itsChildrenAreStillWalked() {
        Sections sections = new Sections();
        sections.register(new FixedSource(section("order", "Order", orderPanel)));
        Snapshot snapshot = new Snapshot(Visibility.TREE, names, refs, windows, sections);

        Node node = snapshot.build(null, null, 400, 20).find("section", "Order");

        assertThat(node).isNotNull();
        assertThat(node.find("button", "Ok")).isNotNull();
    }

    @Test
    void aNullContentSectionIsAbsent() {
        Sections sections = new Sections();
        sections.register(new FixedSource(section("params", "Line items", null)));
        Snapshot snapshot = new Snapshot(Visibility.TREE, names, refs, windows, sections);

        Node app = snapshot.build(null, null, 400, 20);

        assertThat(app.find("section", "Line items")).isNull();
        // the plain panel still walks through untouched, as it would with no source registered at all
        assertThat(app.find("button", "Ok")).isNotNull();
    }

    @Test
    void aNotShowingSectionIsAbsent() {
        orderPanel.setVisible(false);
        Sections sections = new Sections();
        sections.register(new FixedSource(section("order", "Order", orderPanel)));
        Snapshot snapshot = new Snapshot(Visibility.TREE, names, refs, windows, sections);

        Node app = snapshot.build(null, null, 400, 20);

        assertThat(app.find("section", "Order")).isNull();
        assertThat(app.find("button", "Ok")).isNull();
    }

    // ---- a claimed component that derives a role keeps it ----------------------------------

    /**
     * The claim lookup goes by component IDENTITY, so a source may claim a {@code JButton}: it stays a button node
     * with its own name and derived state, plus the section's {@code id} and contributed state.
     */
    @Test
    void aClaimedButtonKeepsItsRoleItsNameAndItsDerivedState() {
        JButton enterQuantities = new JButton("Enter quantities");
        enterQuantities.setEnabled(false);
        form.add(enterQuantities);
        Sections sections = new Sections();
        sections.register(new FixedSource(
                section("summary.enterQuantities", "Enter quantities (section)", enterQuantities, "readonly")));
        Snapshot snapshot = new Snapshot(Visibility.TREE, names, refs, windows, sections);

        Node app = snapshot.build(null, null, 400, 20);

        Node node = app.find("button", "Enter quantities");
        assertThat(node).as("a claimed button is still a button node").isNotNull();
        assertThat(node.state).as("its own state first, the contributed state after")
                .containsExactly("disabled", "readonly");
        assertThat(node.id).isEqualTo("summary.enterQuantities");
        assertThat(app.find("section", "Enter quantities (section)"))
                .as("claiming it must not turn a button into a section").isNull();
    }

    /** A claimed table still reports its columns, rows and row count. */
    @Test
    void aClaimedTableStillReportsWhatATableReports() {
        JTable grid = new JTable(new Object[][] {{"26-1", "open"}, {"26-2", "done"}},
                new Object[] {"No.", "Status"});
        grid.setName("orderGrid");
        form.add(grid);
        Sections sections = new Sections();
        sections.register(new FixedSource(section("summary.orders", "Summary", grid, "editable")));
        Snapshot snapshot = new Snapshot(Visibility.TREE, names, refs, windows, sections);

        Node node = snapshot.build(null, null, 400, 20).find("table", "orderGrid");

        assertThat(node).isNotNull();
        assertThat(node.columns).containsExactly("No.", "Status");
        assertThat(node.rowCount).isEqualTo(2);
        assertThat(node.rows).containsExactly(Arrays.asList("26-1", "open"), Arrays.asList("26-2", "done"));
        assertThat(node.id).isEqualTo("summary.orders");
        assertThat(node.state).containsExactly("editable");
    }

    /**
     * A claim adds an {@code id} and states to ONE node and changes nothing else in the tree - not that node's role,
     * name, ref or value, and not the shape of the walk below it.
     */
    @Test
    void claimingAComponentChangesOnlyThatNodesIdAndState() {
        JButton enterQuantities = new JButton("Enter quantities");
        enterQuantities.setEnabled(false);
        orderPanel.add(enterQuantities);

        Snapshot unclaimed = new Snapshot(Visibility.TREE, names, new Refs(c -> true), windows);
        String before = structure(unclaimed.build(null, null, 400, 20));

        Sections sections = new Sections();
        sections.register(new FixedSource(section("summary.enterQuantities", "Summary", enterQuantities, "readonly")));
        Snapshot claimed = new Snapshot(Visibility.TREE, names, new Refs(c -> true), windows, sections);
        Node app = claimed.build(null, null, 400, 20);

        assertThat(structure(app)).isEqualTo(before);
        Node node = app.find("button", "Enter quantities");
        assertThat(node.id).isEqualTo("summary.enterQuantities");
        assertThat(node.state).containsExactly("disabled", "readonly");
    }

    /** A registered source whose sections claim nothing in this window must not perturb a single byte. */
    @Test
    void aSourceThatClaimsNothingHereLeavesTheTreeByteForByte() {
        Snapshot withoutSource = new Snapshot(Visibility.TREE, names, new Refs(c -> true), windows);
        String before = dump(withoutSource.build(null, null, 400, 20));

        Sections sections = new Sections();
        sections.register(new FixedSource(section("elsewhere", "Elsewhere", new JPanel(), "incomplete")));
        Snapshot withSource = new Snapshot(Visibility.TREE, names, new Refs(c -> true), windows, sections);
        String after = dump(withSource.build(null, null, 400, 20));

        assertThat(after).isEqualTo(before);
    }

    /** Everything a node carries. */
    private static String dump(Node n) {
        StringBuilder sb = new StringBuilder();
        dump(n, sb);
        return sb.toString();
    }

    private static void dump(Node n, StringBuilder sb) {
        sb.append('[').append(n.ref).append('|').append(n.role).append('|').append(n.name).append('|')
                .append(n.id).append('|').append(n.value).append('|').append(n.state).append('|')
                .append(n.modal).append('|').append(n.blockedBy).append('|').append(n.columns).append('|')
                .append(n.rows).append('|').append(n.rowCount).append('|').append(n.selected).append('|')
                .append(n.items).append('|').append(n.expanded).append('|').append(n.approx).append('|')
                .append(n.truncated).append(']');
        if (n.children != null) {
            for (Node c : n.children) dump(c, sb);
        }
    }

    /** Everything a node carries EXCEPT the two fields a claim is allowed to touch. */
    private static String structure(Node n) {
        StringBuilder sb = new StringBuilder();
        structure(n, sb);
        return sb.toString();
    }

    private static void structure(Node n, StringBuilder sb) {
        sb.append('[').append(n.ref).append('|').append(n.role).append('|').append(n.name).append('|')
                .append(n.value).append('|').append(n.modal).append('|').append(n.blockedBy).append('|')
                .append(n.columns).append('|').append(n.rows).append('|').append(n.rowCount).append('|')
                .append(n.selected).append('|').append(n.items).append('|').append(n.expanded).append('|')
                .append(n.approx).append('|').append(n.truncated).append(']');
        if (n.children != null) {
            for (Node c : n.children) structure(c, sb);
        }
    }
}
