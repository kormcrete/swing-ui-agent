package io.github.kormcrete.swingui.driver.model;

import io.github.kormcrete.swingui.driver.window.WindowRoot;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/** Recording precedence: id, role+name in the window, plus scope innermost first, plus nth. */
class RecorderTest {

    private final ResolverTest scene = new ResolverTest();
    private final Recorder recorder = new Recorder(scene.resolver, scene.windows);
    private final Snapshot snapshot = new Snapshot(Visibility.TREE, scene.names, scene.refs, scene.windows);

    private Resolved viaRef(String role, String name) {
        String ref = snapshot.build(null, null, 400, 20).find(role, name).ref;
        return scene.resolver.resolve(Locator.ofRef(ref));
    }

    @Test
    void aRowStepNeedsNoColumnToBeUniqueAnyMore() {
        JPanel pane = new JPanel(null);
        JTable products = new JTable(new Object[][] {{"PEN-GEL-BLK", "Gel pen, black"}}, new Object[] {"SKU", "Product"});
        JTable cart = new JTable(new Object[][] {{"A4 paper, 500 sheets", "1"}}, new Object[] {"Product", "Qty"});
        pane.add(cart);
        pane.add(products);
        scene.mainContent.add(pane);
        try {
            Locator l = Locator.of("table", null);
            l.row = Collections.<String, Object>singletonMap("SKU", "PEN-GEL-BLK");
            Recorder.Recorded r = recorder.record(scene.resolver.resolve(l));
            assertThat(r.fragile).isFalse();
            assertThat(r.locator.nth).isNull();
            assertThat(scene.resolver.resolve(r.locator).component).isSameAs(products);
        } finally {
            scene.mainContent.remove(pane);
        }
    }

    @Test
    void anIdComesFirst() {
        Recorder.Recorded r = recorder.record(viaRef("button", "help"));
        assertThat(r.locator).isEqualTo(Locator.ofId("help"));
        assertThat(r.fragile).isFalse();
    }

    @Test
    void roleAndNameInTheBareDialog() {
        Recorder.Recorded r = recorder.record(viaRef("button", "Assign"));
        Locator expected = Locator.of("button", "Assign");
        expected.window = "dialog";
        assertThat(r.locator).isEqualTo(expected);
    }

    @Test
    void scopeIsAddedInnermostFirst() {
        Resolved foot = scene.resolver.resolve(footOk());
        Recorder.Recorded r = recorder.record(foot);
        assertThat(r.locator.scope).containsExactly("section:Footer");
        assertThat(r.fragile).isFalse();
    }

    @Test
    void twinsFallBackToNthAndAreFragile() {
        JPanel twins = ResolverTest.titled("Middle");
        JButton a = new JButton("Next");
        JButton b = new JButton("Next");
        twins.add(a);
        twins.add(b);
        scene.dialog.add(twins);
        Recorder.Recorded r = recorder.record(viaRef("button", "Next"));
        assertThat(r.fragile).isTrue();
        assertThat(r.locator.nth).isEqualTo(0);
        assertThat(scene.resolver.resolve(r.locator).component).isSameAs(a);
    }

    @Test
    void subTargetsAreKeptAndTheRefIsNot() {
        String ref = snapshot.build(null, null, 400, 20).find("table", "Orders").ref;
        Locator l = Locator.ofRef(ref);
        l.row = Collections.<String, Object>singletonMap("index", 2);
        l.column = "B";
        Recorder.Recorded r = recorder.record(scene.resolver.resolve(l));
        assertThat(r.locator.ref).isNull();
        assertThat(r.locator.row).containsEntry("index", 2);
        assertThat(r.locator.column).isEqualTo("B");
        assertThat(r.locator.role).isEqualTo("table");
    }

    @Test
    void aSecondDialogKeepsTheExactTitle() {
        JPanel tools = new JPanel(null);
        JButton apply = new JButton("Apply");
        tools.add(apply);
        scene.windows.roots.add(new WindowRoot(tools, "dialog", "Tools", false, "Order editor – Order 1", false));
        Recorder.Recorded r = recorder.record(viaRef("button", "Apply"));
        assertThat(r.locator.window).isEqualTo("dialog:Tools");
    }

    private static Locator footOk() {
        Locator l = Locator.of("button", "OK");
        l.scope = Arrays.asList("section:Footer");
        return l;
    }

    @Test
    void anElementWithoutANameIsAddressedByRoleAndScopeNotNth() {
        javax.swing.JTable unnamed = new javax.swing.JTable(2, 2);
        JPanel lines = new JPanel();
        lines.add(unnamed);
        javax.swing.JTabbedPane cart = new javax.swing.JTabbedPane();
        cart.addTab("Cart", lines);
        scene.mainContent.add(cart);
        Resolved table = scene.resolver.resolve(Locator.ofRef(scene.refs.add(unnamed, null)));
        assertThat(table.name).isNull();
        Recorder.Recorded r = recorder.record(table);
        assertThat(r.fragile).isFalse();
        assertThat(r.locator.nth).isNull();
        assertThat(r.locator.name).isNull();
        assertThat(scene.resolver.resolve(r.locator).component).isSameAs(unnamed);
    }

    @Test
    void theElementAsSeenHasItsWholeTrailAndEveryCellOfItsRow() {
        for (int r = 0; r < 3; r++) {
            scene.table.setValueAt("ORD-" + (r + 1), r, 0);
            scene.table.setValueAt("open", r, 1);
        }
        String ref = snapshot.build(null, null, 400, 20).find("table", "Orders").ref;
        Locator l = Locator.ofRef(ref);
        l.row = Collections.<String, Object>singletonMap("A", "ORD-2");
        l.column = "B";

        Recorder.Recorded r = recorder.record(scene.resolver.resolve(l));

        // the recorder writes the shortest locator; the element as seen keeps all there was
        assertThat(r.locator.scope).isNull();
        assertThat(r.seen.scope).containsExactly("tab:Line items", "section:Orders");
        assertThat(r.seen.role).isEqualTo("table");
        assertThat(r.seen.name).isEqualTo("Orders");
        java.util.Map<String, Object> cells = new java.util.LinkedHashMap<String, Object>();
        cells.put("A", "ORD-2");
        cells.put("B", "open");
        assertThat(r.seen.row).isEqualTo(cells);
        assertThat(r.seen.column).isEqualTo("B");
        assertThat(r.seen.ref).isNull();
        // a locator given as it is (not a ref) is seen the same way
        Locator given = Locator.of("table", "Orders");
        given.row = Collections.<String, Object>singletonMap("index", 1);
        assertThat(Recorder.seen(scene.resolver.resolve(given)).row).isEqualTo(cells);
    }

    @Test
    void aRowWhoseCellsCannotBeReadIsSeenAsTheLocatorNamesIt() {
        scene.table.setValueAt("ORD-1", 0, 0);
        String ref = snapshot.build(null, null, 400, 20).find("table", "Orders").ref;
        Locator l = Locator.ofRef(ref);
        l.row = Collections.<String, Object>singletonMap("index", 0);
        Resolved cell = scene.resolver.resolve(l);
        scene.table.setDefaultRenderer(Object.class, (table, value, selected, focus, row, column) -> {
            throw new IllegalStateException("the app's renderer failed");
        });

        Recorder.Recorded r = recorder.record(cell);

        assertThat(r.locator.row).containsEntry("index", 0);
        assertThat(r.seen.row).isEqualTo(Collections.<String, Object>singletonMap("index", 0));
        assertThat(r.seen.role).isEqualTo("table");
    }

    @Test
    void rowChoiceHasThePositionAndTheValue() {
        for (int r = 0; r < 3; r++) {
            scene.table.setValueAt("ORD-" + (r + 1), r, 0);
            scene.table.setValueAt("open", r, 1);
        }
        String ref = snapshot.build(null, null, 400, 20).find("table", "Orders").ref;
        Locator l = Locator.ofRef(ref);
        l.row = Collections.<String, Object>singletonMap("index", 1);
        l.column = "A";
        RowChoice choice = Recorder.rowChoice(scene.resolver.resolve(l));
        assertThat(choice.index).isEqualTo(1);
        assertThat(choice.byPosition()).isEqualTo(Collections.<String, Object>singletonMap("index", 1));
        assertThat(choice.byValue()).isEqualTo(Collections.<String, Object>singletonMap("A", "ORD-2"));

        // no column tells the rows apart: no "this value", only the position
        for (int r = 0; r < 3; r++) scene.table.setValueAt("same", r, 0);
        for (int r = 0; r < 3; r++) scene.table.setValueAt("same", r, 1);
        RowChoice twins = Recorder.rowChoice(scene.resolver.resolve(l));
        assertThat(twins.index).isEqualTo(1);
        assertThat(twins.byValue()).isNull();

        // a locator without a row has none
        assertThat(Recorder.rowChoice(scene.resolver.resolve(Locator.ofRef(ref)))).isNull();
    }

    @Test
    void aValueLabelIsRecordedByItsCaption() {
        JPanel totals = new JPanel(null);
        JLabel caption = new JLabel("Total");
        JLabel value = new JLabel("€7.32");
        caption.setLabelFor(value);
        totals.add(caption);
        totals.add(value);
        scene.mainContent.add(totals);

        Recorder.Recorded r = recorder.record(viaRef("label", "Total"));
        assertThat(r.locator.role).isEqualTo("label");
        assertThat(r.locator.name).isEqualTo("Total");
        assertThat(r.fragile).isFalse();
        assertThat(scene.resolver.resolve(r.locator).component).isSameAs(value);
    }
}
