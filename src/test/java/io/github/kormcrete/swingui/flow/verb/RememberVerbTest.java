package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.flow.Action;
import io.github.kormcrete.swingui.mcp.FakeVerbs;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Resolved;
import io.github.kormcrete.swingui.driver.widgets.Expectations;
import io.github.kormcrete.swingui.flow.FlowJson;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTree;
import java.awt.Component;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** The remember step: what it reads from each kind of element, and how it hands the value on. */
class RememberVerbTest {

    private final JTable orders = new JTable(new Object[][] {
            {"ORD-1640", "Open"},
            {"ORD-1641", "Paid"},
            {"ORD-1642", "Open"}
    }, new Object[] {"Number", "Status"});

    private static Resolved on(Component c, Locator l) {
        return new Resolved(c, null, l.role, l.name, "main", Collections.<String>emptyList(), l);
    }

    private static Locator locator(String role, String name) {
        return Locator.of(role, name);
    }

    @Test
    void aFieldsText() {
        Resolved field = on(new JTextField("ORD-1640"), locator("text", "Number"));
        assertThat(Expectations.remembered(field, false)).isEqualTo("ORD-1640");
        assertThat(Expectations.remembered(on(new JLabel("Total"), locator("label", "Total")), false)).isEqualTo("Total");
    }

    @Test
    void aCellFoundByOtherColumns() {
        Locator l = locator("table", "Orders");
        l.row = new LinkedHashMap<String, Object>(Collections.<String, Object>singletonMap("Number", "ORD-1641"));
        l.column = "Status";
        assertThat(Expectations.remembered(on(orders, l), false)).isEqualTo("Paid");
    }

    @Test
    void theSelectedRowsColumn() {
        orders.setRowSelectionInterval(2, 2);
        Locator l = locator("table", "Orders");
        l.column = "Number";
        assertThat(Expectations.remembered(on(orders, l), true)).isEqualTo("ORD-1642");
    }

    @Test
    void aSelectedRowNeedsAColumnAndASelection() {
        Locator noColumn = locator("table", "Orders");
        orders.setRowSelectionInterval(0, 0);
        DriverException e = catchThrowableOfType(() -> Expectations.remembered(on(orders, noColumn), true), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("column");
        orders.clearSelection();
        Locator l = locator("table", "Orders");
        l.column = "Number";
        assertThat(catchThrowableOfType(() -> Expectations.remembered(on(orders, l), true), DriverException.class).getMessage())
                .endsWith("nothing to remember: " + l + " has no selected row");
    }

    @Test
    void theSelectedListItem() {
        JList<String> list = new JList<String>(new String[] {"Ada Shop Ltd", "Brown & Co"});
        list.setSelectedIndex(1);
        assertThat(Expectations.remembered(on(list, locator("list", "Customers")), true)).isEqualTo("Brown & Co");
    }

    @Test
    void theSelectedTabsTitle() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Orders", new JPanel());
        tabs.addTab("ORD-1641", new JPanel());
        tabs.setSelectedIndex(1);
        assertThat(Expectations.remembered(on(tabs, locator("tabs", "Main")), true)).isEqualTo("ORD-1641");
    }

    @Test
    void aComponentWithoutTextNeverFallsBackToTheLocatorsName() {
        Locator l = locator("panel", "Summary");
        DriverException e = catchThrowableOfType(() -> Expectations.remembered(on(new JPanel(), l), false), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.EXPECTATION);
        assertThat(e.getMessage()).endsWith("nothing to remember: " + l + " is empty");
        DriverException table = catchThrowableOfType(
                () -> Expectations.remembered(on(orders, locator("table", "Orders")), false), DriverException.class);
        assertThat(table.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(table.getMessage()).contains("'row'");
    }

    @Test
    void theSelectedTreeItemIsTheLeadRow() {
        JTree tree = new JTree(new String[] {"Paper", "Pens", "Ink"});
        tree.setSelectionRows(new int[] {3, 1, 2});
        tree.setLeadSelectionPath(tree.getPathForRow(1));
        String lead = tree.getPathForRow(1).getLastPathComponent().toString();
        assertThat(Expectations.remembered(on(tree, locator("tree", "Products")), true)).isEqualTo(lead);
    }

    @Test
    void anEmptyValueFails() {
        Locator l = locator("text", "Note");
        DriverException e = catchThrowableOfType(() -> Expectations.remembered(on(new JTextField(""), l), false), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.EXPECTATION);
        assertThat(e.getMessage()).endsWith("nothing to remember: " + l + " is empty");
    }

    @Test
    void theVerbAsksTheDriverAndReportsTheValueAsItsNote() throws Exception {
        RememberVerb v = (RememberVerb) FlowJson.mapper().readValue(
                "{\"action\":\"remember\",\"as\":\"number\",\"selected\":true,\"timeout\":500,"
                        + "\"target\":{\"role\":\"table\",\"name\":\"Orders\",\"column\":\"Number\"}}", Action.class);
        final List<Object> seen = new ArrayList<Object>();
        Verbs verbs = new FakeVerbs() {
            @Override public String remember(Locator target, boolean selected, Long timeout) {
                seen.add(target.column);
                seen.add(selected);
                seen.add(timeout);
                return "ORD-1642";
            }
        };
        VerbOutcome out = v.run(verbs);
        assertThat(out.note).isEqualTo("ORD-1642");
        assertThat(seen).containsExactly("Number", true, 500L);
        assertThat(v.describe()).contains("remember").contains("number");
        assertThat(v.targets()).containsKey("target");
    }

    @Test
    void aDriverWithoutItIsACapabilityGap() {
        DriverException e = catchThrowableOfType(() -> new FakeVerbs().remember(locator("text", "Note"), false, null), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.CAPABILITY_GAP);
    }

    @Test
    void aRememberNeedsATargetAndAName() {
        RememberVerb v = new RememberVerb();
        v.as = "n";
        assertThat(catchThrowableOfType(() -> v.run(new FakeVerbs()), DriverException.class).reason()).isEqualTo(Reason.BAD_INPUT);
        RememberVerb w = new RememberVerb();
        w.target = locator("text", "Note");
        assertThat(catchThrowableOfType(() -> w.run(new FakeVerbs()), DriverException.class).reason()).isEqualTo(Reason.BAD_INPUT);
    }
}
