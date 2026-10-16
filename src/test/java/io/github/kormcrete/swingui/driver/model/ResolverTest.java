package io.github.kormcrete.swingui.driver.model;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.window.WindowRoot;
import org.junit.jupiter.api.Test;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ResolverTest {

    final Refs refs = new Refs(c -> true);
    final FakeWindows windows = new FakeWindows();
    final Names names = new Names(Visibility.TREE);
    final Resolver resolver = new Resolver(Visibility.TREE, names, refs, windows);
    final JPanel mainContent = new JPanel(null);
    final JPanel dialog = new JPanel(null);
    final JButton save = new JButton("Save");
    final JButton help = new JButton("Help");
    final JButton okHead = new JButton("OK");
    final JButton okFoot = new JButton("OK");
    final JButton distribute = new JButton("Assign");
    final JTabbedPane tabs = new JTabbedPane();
    final JTable table = new JTable(3, 2);

    ResolverTest() {
        mainContent.add(save);
        help.setName("help");
        mainContent.add(help);
        JPanel head = titled("Header");
        head.add(okHead);
        dialog.add(head);
        JPanel foot = titled("Footer");
        foot.add(okFoot);
        dialog.add(foot);
        JButton hidden = new JButton("OK");
        hidden.setVisible(false);
        dialog.add(hidden);
        dialog.add(distribute);
        JPanel params = titled("Orders");
        params.add(new JScrollPane(table));
        tabs.addTab("Customer", new JPanel());
        tabs.addTab("Line items", params);
        tabs.setSelectedIndex(1);
        params.setVisible(true); // a laid-out tabbed pane shows its selected tab
        dialog.add(tabs);
        windows.main = mainContent;
        windows.roots.add(new WindowRoot(dialog, "dialog", "Order editor – Order 1", true, null, true));
        windows.roots.add(new WindowRoot(mainContent, "window", "OrderDesk", false, "Order editor – Order 1", false));
    }

    static JPanel titled(String title) {
        JPanel p = new JPanel();
        p.setBorder(BorderFactory.createTitledBorder(title));
        return p;
    }

    // ---- a named column narrows which table can match --------------------------------------

    private JTable withColumns(String... headers) {
        return new JTable(new Object[][] {{"a", "b", "c", "d"}}, headers);
    }

    /** A cell locator: row 0 of the given column. */
    private static Locator cellOf(String role, String column) {
        Locator l = Locator.of(role, null);
        l.row = java.util.Collections.<String, Object>singletonMap("index", 0);
        l.column = column;
        return l;
    }

    @Test
    void aNamedColumnPicksTheTableThatHasIt() {
        // A grid beside its row-header table, neither named: on role alone both match.
        JPanel pane = new JPanel(null);
        JTable grid = withColumns("Quantity");
        JTable rowHeader = withColumns("ID", "Line items", "Product", "Unit");
        pane.add(grid);
        pane.add(rowHeader);
        mainContent.add(pane);
        try {
            assertThat(resolver.matches(Locator.of("table", null))).hasSizeGreaterThan(1);

            assertThat(resolver.resolve(cellOf("table", "Quantity")).component).isSameAs(grid);
            assertThat(resolver.resolve(cellOf("table", "Product")).component).isSameAs(rowHeader);
            assertThat(resolver.matches(cellOf("table", "Not present"))).isEmpty();
        } finally {
            mainContent.remove(pane);
        }
    }

    @Test
    void aColumnAloneNarrowsToTheTableThatHasItWithNoRow() {
        // Same fixture as aNamedColumnPicksTheTableThatHasIt, but the locator carries no row at all.
        JPanel pane = new JPanel(null);
        JTable grid = withColumns("Quantity");
        JTable rowHeader = withColumns("ID", "Line items", "Product", "Unit");
        pane.add(grid);
        pane.add(rowHeader);
        mainContent.add(pane);
        try {
            Locator byGrid = Locator.of("table", null);
            byGrid.column = "Quantity";
            byGrid.validate();
            assertThat(resolver.resolve(byGrid).component).isSameAs(grid);

            Locator byRowHeader = Locator.of("table", null);
            byRowHeader.column = "Product";
            assertThat(resolver.resolve(byRowHeader).component).isSameAs(rowHeader);
        } finally {
            mainContent.remove(pane);
        }
    }

    @Test
    void aColumnOnSomethingThatIsNotATableChangesNothing() {
        Locator l = cellOf("button", "Quantity");
        l.name = "Save";

        assertThat(resolver.resolve(l).component).isSameAs(save);
    }

    @Test
    void aUniqueRoleAndNameResolvesWithItsWindow() {
        Resolved r = resolver.resolve(Locator.of("button", "Save"));
        assertThat(r.component).isSameAs(save);
        assertThat(r.window).isEqualTo("main");
        assertThat(resolver.resolve(Locator.ofId("help")).component).isSameAs(help);
        // getName() comes first, so an id'ed component is also named by its id.
        assertThat(resolver.resolve(Locator.of("button", "help")).component).isSameAs(help);
        // A name locator also accepts the component's own visible text, so "help" (id
        // "help", text "Help") resolves either way.
        assertThat(resolver.resolve(Locator.of("button", "Help")).component).isSameAs(help);
    }

    // ---- a button mounted inside a text field is reached through the field ------------------------

    @Test
    void aButtonInsideATextFieldResolvesInsideIt() {
        // a clear X, named "clear", mounted as a child of the field itself
        javax.swing.JTextField field = new javax.swing.JTextField("Nuts");
        field.setName("lineItem.product");
        JButton clear = new JButton();
        clear.setName("clear");
        field.add(clear);
        mainContent.add(field);
        try {
            Locator inField = Locator.ofId("clear");
            inField.scope = new java.util.ArrayList<String>(java.util.Collections.singletonList(ScopeStep.id("lineItem.product")));
            assertThat(resolver.resolve(inField).component).isSameAs(clear);
            assertThat(resolver.resolve(Locator.ofId("lineItem.product")).component).isSameAs(field);
        } finally {
            mainContent.remove(field);
        }
    }

    // ---- a name locator also matches a setName'd component's own visible text ---------------------

    @Test
    void aSetNamedButtonAlsoResolvesByTheTextAUserReads() {
        // A button with a setName id and a localized visible label.
        JButton addOrders = new JButton("Add orders");
        addOrders.setName("orderList.addOrders");
        mainContent.add(addOrders);
        try {
            // Resolves by the text a user reads ...
            assertThat(resolver.resolve(Locator.of("button", "Add orders")).component).isSameAs(addOrders);
            // ... and by getName().
            assertThat(resolver.resolve(Locator.of("button", "orderList.addOrders")).component).isSameAs(addOrders);
            assertThat(resolver.resolve(Locator.ofId("orderList.addOrders")).component).isSameAs(addOrders);
            // One component, one match - not reported twice.
            assertThat(resolver.matches(Locator.of("button", "Add orders"))).hasSize(1);
            // The reported name - and so what the snapshot prints - is the setName id, never the label.
            assertThat(resolver.resolve(Locator.of("button", "Add orders")).name)
                    .isEqualTo("orderList.addOrders");
            Snapshot snapshot = new Snapshot(Visibility.TREE, names, refs, windows);
            assertThat(snapshot.build(null, null, 400, 20).find("button", "orderList.addOrders")).isNotNull();
        } finally {
            mainContent.remove(addOrders);
        }
    }

    @Test
    void anHtmlWrappedOwnTextStillMatchesThePlainText() {
        JButton addOrdersHtml = new JButton("<html>Add orders</html>");
        addOrdersHtml.setName("orderList.addOrdersHtml");
        mainContent.add(addOrdersHtml);
        try {
            assertThat(resolver.resolve(Locator.of("button", "Add orders")).component).isSameAs(addOrdersHtml);
        } finally {
            mainContent.remove(addOrdersHtml);
        }
    }

    @Test
    void aComponentWithNoSetNameIsUnaffected() {
        // save carries no id: it resolves by its own text, as a single match.
        assertThat(resolver.resolve(Locator.of("button", "Save")).component).isSameAs(save);
        assertThat(resolver.matches(Locator.of("button", "Save"))).hasSize(1);
    }

    @Test
    void twoVisibleMatchesAreAmbiguousAndListed() {
        DriverException e = catchThrowableOfType(() -> resolver.resolve(Locator.of("button", "OK")), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.AMBIGUOUS);
        assertThat(e.details()).hasSize(2);
        assertThat(e.details().get(0)).contains("section:Header");
    }

    @Test
    void scopeAndNthPickOne() {
        Locator foot = Locator.of("button", "OK");
        foot.scope = Arrays.asList("section:Footer");
        assertThat(resolver.resolve(foot).component).isSameAs(okFoot);
        Locator second = Locator.of("button", "OK");
        second.nth = 1;
        assertThat(resolver.resolve(second).component).isSameAs(okFoot);
    }

    @Test
    void nothingFoundListsNearMisses() {
        DriverException e = catchThrowableOfType(() -> resolver.resolve(Locator.of("button", "Cancel")), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.NOT_FOUND);
        assertThat(e.details()).isNotEmpty().hasSizeLessThanOrEqualTo(Resolver.MAX_CANDIDATES);
        assertThat(e.details()).anyMatch(d -> d.contains("button 'Assign'"));
    }

    @Test
    void tabsTablesAndSectionsResolve() {
        Resolved tab = resolver.resolve(Locator.of("tab", "Line items"));
        assertThat(tab.component).isSameAs(tabs);
        assertThat(tab.tab).isEqualTo(1);
        Locator t = Locator.of("table", null);
        t.scope = Arrays.asList("tab:Line items");
        Resolved r = resolver.resolve(t);
        assertThat(r.component).isSameAs(table);
        assertThat(r.name).isEqualTo("Orders");
        assertThat(r.trail).containsExactly("tab:Line items", "section:Orders");
        assertThat(resolver.matches(Locator.of(null, "Header"))).isEmpty();
        assertThat(resolver.resolve(Locator.of("section", "Header")).role).isEqualTo("section");
    }

    /** A tab whose title leads with what it is and ends with data, e.g. the customer of a new order. */
    private JButton newOrderTab() {
        JTabbedPane orders = new JTabbedPane();
        JPanel cart = new JPanel();
        JButton add = new JButton("Add to cart");
        cart.add(add);
        orders.addTab("New order · Ada Shop Ltd", cart);
        mainContent.add(orders);
        return add;
    }

    @Test
    void aTabScopeStepEndingInAStarMatchesThePrefix() {
        JButton add = newOrderTab();
        Locator l = Locator.of("button", "Add to cart");
        l.scope = Arrays.asList("tab:New order*");
        assertThat(resolver.resolve(l).component).isSameAs(add);
        Locator section = Locator.of("table", null);
        section.scope = Arrays.asList("tab:Line*", "section:Ord*");
        assertThat(resolver.resolve(section).component).isSameAs(table);
        Locator other = Locator.of("button", "Add to cart");
        other.scope = Arrays.asList("tab:Orders*");
        assertThat(catchThrowableOfType(() -> resolver.resolve(other), DriverException.class).reason())
                .isEqualTo(Reason.NOT_FOUND);
    }

    @Test
    void withoutAStarItStaysExact() {
        JButton add = newOrderTab();
        Locator prefix = Locator.of("button", "Add to cart");
        prefix.scope = Arrays.asList("tab:New order");
        assertThat(catchThrowableOfType(() -> resolver.resolve(prefix), DriverException.class).reason())
                .isEqualTo(Reason.NOT_FOUND);
        Locator exact = Locator.of("button", "Add to cart");
        exact.scope = Arrays.asList("tab:New order · Ada Shop Ltd");
        assertThat(resolver.resolve(exact).component).isSameAs(add);
        // a star leading or inside the title is text, as is a star on an id step
        Locator lead = Locator.of("button", "Add to cart");
        lead.scope = Arrays.asList("tab:*Ada Shop Ltd");
        assertThat(resolver.matches(lead)).isEmpty();
    }

    @Test
    void windowTokensNarrowTheSearch() {
        Locator bare = Locator.of("button", "Assign");
        bare.window = "dialog";
        assertThat(resolver.resolve(bare).window).isEqualTo("dialog:Order editor – Order 1");
        Locator glob = Locator.of("button", "Assign");
        glob.window = "dialog:Order editor*";
        assertThat(resolver.resolve(glob).component).isSameAs(distribute);
        Locator wrong = Locator.of("button", "Assign");
        wrong.window = "main";
        assertThat(catchThrowableOfType(() -> resolver.resolve(wrong), DriverException.class).reason())
                .isEqualTo(Reason.NOT_FOUND);
    }

    @Test
    void containsMatchesPartOfTheName() {
        Locator l = Locator.of("button", "Sav");
        l.match = "contains";
        assertThat(resolver.resolve(l).component).isSameAs(save);
    }

    @Test
    void aSnapshotRefResolvesWithItsSubTarget() {
        Snapshot snapshot = new Snapshot(Visibility.TREE, names, refs, windows);
        String ref = snapshot.build(null, null, 400, 20).find("table", "Orders").ref;
        Locator l = Locator.ofRef(ref);
        l.row = java.util.Collections.<String, Object>singletonMap("index", 1);
        Resolved r = resolver.resolve(l);
        assertThat(r.component).isSameAs(table);
        assertThat(r.row().index).isEqualTo(1);
        assertThat(r.window).isEqualTo("dialog:Order editor – Order 1");
        assertThat(r.trail).containsExactly("tab:Line items", "section:Orders");
    }

    @Test
    void aMalformedLocatorIsBadInput() {
        assertThat(catchThrowableOfType(() -> resolver.resolve(Locator.of("knob", "OK")), DriverException.class)
                .reason()).isEqualTo(Reason.BAD_INPUT);
    }

    // ---- a value label is addressed by its caption, and by the text it shows -----------------

    @Test
    void aValueLabelResolvesByItsCaptionAndByItsOwnText() {
        JPanel totals = new JPanel(null);
        JLabel caption = new JLabel("Total");
        JLabel value = new JLabel("€7.32");
        caption.setLabelFor(value);
        totals.add(caption);
        totals.add(value);
        mainContent.add(totals);

        assertThat(resolver.matches(Locator.of("label", "Total"))).hasSize(1);
        assertThat(resolver.resolve(Locator.of("label", "Total")).component).isSameAs(value);
        assertThat(resolver.matches(Locator.of("label", "€7.32"))).hasSize(1);
        assertThat(resolver.resolve(Locator.of("label", "€7.32")).component).isSameAs(value);
    }
}
