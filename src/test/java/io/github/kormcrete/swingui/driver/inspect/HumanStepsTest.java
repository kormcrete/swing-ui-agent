package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.input.KeyChord;
import io.github.kormcrete.swingui.driver.model.Locator;
import org.junit.jupiter.api.Test;

import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What a person does becomes the step the agent would have sent - and what the app does by itself does not. */
class HumanStepsTest {

    private final Scene scene = new Scene();
    private final List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
    /** What the person did that could not be recorded, and why. */
    private final List<String> notRecorded = new ArrayList<String>();
    private final HumanSteps human = new HumanSteps(scene.inspector, steps::add, notRecorded::add);

    @Test
    void aPressAndReleaseOnAButtonIsAClick() {
        click(scene.save, 1);
        human.flush();
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).containsEntry("action", "click");
        assertThat(((Locator) steps.get(0).get("target")).name).isEqualTo("Save");
        assertThat(steps.get(0)).doesNotContainKey("count");
    }

    @Test
    void aDoubleClickIsOneClickWithACount() {
        click(scene.save, 1);
        click(scene.save, 2);
        human.flush();
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).containsEntry("count", 2);
    }

    @Test
    void aTableCellClickNamesTheRowByItsKey() {
        java.awt.Rectangle cell = scene.orders.getCellRect(2, 1, true);
        click(scene.orders, cell.x + 2, cell.y + 2, 1);
        human.flush();
        Locator target = (Locator) steps.get(0).get("target");
        assertThat(target.row).containsEntry("Order", "ORD-1642");
        assertThat(target.column).isEqualTo("Customer");
    }

    @Test
    void aRowClickCarriesItsViewRow() {
        java.awt.Rectangle cell = scene.orders.getCellRect(2, 1, true);
        click(scene.orders, cell.x + 2, cell.y + 2, 1);
        human.flush();
        assertThat(steps.get(0)).containsEntry(Inspector.ROW_INDEX, 2);
        click(scene.save, 1);
        human.flush();
        assertThat(steps.get(1)).doesNotContainKey(Inspector.ROW_INDEX);
    }

    @Test
    void aChoiceFromAButtonsDropdownIsAPickOfItsPath() {
        javax.swing.JPopupMenu dropdown = new javax.swing.JPopupMenu();
        javax.swing.JMenu recent = new javax.swing.JMenu("Recent");
        javax.swing.JMenuItem ada = new javax.swing.JMenuItem("Ada Shop Ltd");
        recent.add(ada);
        dropdown.add(recent);
        dropdown.setInvoker(scene.save);
        click(scene.save, 1);
        click(ada, 1);
        human.flush();
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0)).containsEntry("action", "click");
        assertThat(steps.get(1)).containsEntry("action", "pick")
                .containsEntry("path", java.util.Arrays.asList("Recent", "Ada Shop Ltd"));
    }

    @Test
    void aSuggestionPickInAPopupListIsAClickOnItsItem() throws Exception {
        onEdt(() -> {
            // a field's suggestions: a list in a popup the field opens as the person types (Order Desk's Customer field)
            javax.swing.JList<String> suggestions = new javax.swing.JList<String>(new String[] {"Ada Shop Ltd", "Birch & Co"});
            javax.swing.JPopupMenu popup = new javax.swing.JPopupMenu();
            popup.add(suggestions);
            suggestions.setBounds(0, 0, 200, 40);
            scene.popup(popup, scene.customer);
            focus(scene.customer, FocusEvent.FOCUS_GAINED);
            typeInto(scene.customer, "Ada");

            java.awt.Rectangle ada = suggestions.getCellBounds(0, 0);
            click(suggestions, ada.x + 4, ada.y + 2, 1);
            scene.customer.setText("Ada Shop Ltd");   // the app takes the pick
            human.flush();

            assertThat(steps).hasSize(2);
            assertThat(steps.get(0)).containsEntry("action", "type").containsEntry("text", "Ada");
            assertThat(steps.get(1)).containsEntry("action", "click").doesNotContainKey("count");
            Locator target = (Locator) steps.get(1).get("target");
            Locator expected = Locator.of("list", null);
            expected.window = "popup";
            expected.item = java.util.Collections.singletonList("Ada Shop Ltd");
            assertThat(target).isEqualTo(expected);
            assertThat(((Locator) steps.get(1).get(Inspector.SEEN)).item).containsExactly("Ada Shop Ltd");
        });
    }

    @Test
    void aSuggestionListThatHidesOnThePressStillRecordsThePick() throws Exception {
        onEdt(() -> {
            javax.swing.JList<String> suggestions = new javax.swing.JList<String>(new String[] {"Ada Shop Ltd", "Birch & Co"});
            javax.swing.JPopupMenu popup = new javax.swing.JPopupMenu();
            popup.add(suggestions);
            suggestions.setBounds(0, 0, 200, 40);
            scene.popup(popup, scene.customer);
            java.awt.Rectangle birch = suggestions.getCellBounds(1, 1);

            // the field takes the pick from the selection, which the press changes, and hides its popup at once
            click(suggestions, birch.x + 4, birch.y + 2, 1, () -> scene.roots.remove(scene.roots.size() - 1));
            human.flush();

            assertThat(steps).hasSize(1);
            assertThat(((Locator) steps.get(0).get("target")).item).containsExactly("Birch & Co");
        });
    }

    @Test
    void aListEntryClickIsAClickOnItsItem() throws Exception {
        onEdt(() -> {
            javax.swing.JList<String> customers = new javax.swing.JList<String>(new String[] {"Ada Shop Ltd", "Birch & Co"});
            customers.setBounds(320, 130, 150, 40);
            scene.content.add(customers);
            java.awt.Rectangle birch = customers.getCellBounds(1, 1);

            click(customers, birch.x + 4, birch.y + 2, 1);
            human.flush();
            click(customers, birch.x + 4, birch.y + 2, 1);
            click(customers, birch.x + 4, birch.y + 2, 2);
            human.flush();
            // below the last entry: the list selects the last one, as the closest
            click(customers, 4, customers.getHeight() - 1, 1);
            human.flush();

            assertThat(steps).hasSize(3);
            Locator target = (Locator) steps.get(0).get("target");
            assertThat(target.role).isEqualTo("list");
            assertThat(target.item).containsExactly("Birch & Co");
            assertThat(steps.get(0)).doesNotContainKey("count").doesNotContainKey(Inspector.ROW_INDEX);
            assertThat(steps.get(1)).containsEntry("count", 2);
            assertThat(((Locator) steps.get(1).get("target")).item).containsExactly("Birch & Co");
            assertThat(((Locator) steps.get(2).get("target")).item).containsExactly("Birch & Co");
            assertThat(steps.get(2)).doesNotContainKey("count");

            // a file list selects only what is under the pointer
            customers.putClientProperty("List.isFileList", Boolean.TRUE);
            click(customers, 4, customers.getHeight() - 1, 1);
            human.flush();
            assertThat(steps).hasSize(3);
            assertThat(notRecorded).isEmpty();
        });
    }

    @Test
    void anEntryThatReadsLikeAnotherIsNotRecordedAndSaysWhy() throws Exception {
        onEdt(() -> {
            javax.swing.JList<String> customers = new javax.swing.JList<String>(new String[] {"Ada Shop Ltd", "Ada Shop Ltd"});
            customers.setBounds(320, 130, 150, 40);
            scene.content.add(customers);
            java.awt.Rectangle second = customers.getCellBounds(1, 1);

            click(customers, second.x + 4, second.y + 2, 1);
            human.flush();
            // a double click says it once
            click(customers, second.x + 4, second.y + 2, 2);
            human.flush();

            assertThat(steps).isEmpty();
            assertThat(notRecorded).containsExactly("a click on 'Ada Shop Ltd': more than one entry of the list reads so");
        });
    }

    @Test
    void aTreeRowClickIsAClickOnItsPath() throws Exception {
        onEdt(() -> {
            javax.swing.JTree categories = categories();
            java.awt.Rectangle pens = categories.getRowBounds(2);

            click(categories, pens.x + 2, pens.y + 2, 1);
            human.flush();
            java.awt.Rectangle officeRow = categories.getRowBounds(0);
            click(categories, officeRow.x + 2, officeRow.y + 2, 1);
            click(categories, officeRow.x + 2, officeRow.y + 2, 2);
            human.flush();
            // left of a row, where nothing happened: no step
            click(categories, 0, officeRow.y + 2, 1);
            human.flush();

            assertThat(steps).hasSize(2);
            assertThat(notRecorded).isEmpty();
            Locator target = (Locator) steps.get(0).get("target");
            assertThat(target.role).isEqualTo("tree");
            assertThat(target.item).containsExactly("Office", "Pens");
            assertThat(steps.get(0)).doesNotContainKey("count");
            assertThat(((Locator) steps.get(1).get("target")).item).containsExactly("Office");
            assertThat(steps.get(1)).containsEntry("count", 2);
        });
    }

    @Test
    void aClickOnATreesExpandArrowIsNotRecordedAndSaysWhy() throws Exception {
        onEdt(() -> {
            javax.swing.JTree categories = categories();
            java.awt.Rectangle office = categories.getRowBounds(0);

            // most looks and feels collapse the row on the press on its arrow
            click(categories, 1, office.y + 2, 1, () -> categories.collapseRow(0));
            human.flush();

            assertThat(steps).isEmpty();
            assertThat(notRecorded).containsExactly("the expand arrow of 'Office': double-click the row instead");
        });
    }

    @Test
    void anArrowThatActsOnTheReleaseIsNotRecordedEither() throws Exception {
        javax.swing.JTree categories = onEdt(this::categories);
        java.awt.Rectangle office = onEdt(() -> categories.getRowBounds(0));

        // Aqua's arrow expands or collapses in the tree's own release handler, after the recorder heard the release
        releaseHandledAfter(categories, 1, office.y + 2, 1, () -> categories.collapseRow(0));
        human.flush();

        assertThat(steps).isEmpty();
        assertThat(notRecorded).containsExactly("the expand arrow of 'Office': double-click the row instead");
    }

    /** The release is decided later: when the recorder stopped listening (Done) in between, it adds nothing. */
    @Test
    void aClickDecidedAfterTheRecorderStoppedIsNoStep() throws Exception {
        javax.swing.JTree categories = onEdt(this::categories);
        java.awt.Rectangle pens = onEdt(() -> categories.getRowBounds(2));
        SwingUtilities.invokeAndWait(() -> human.active(true));
        try {
            releaseHandledAfter(categories, pens.x + pens.width + 5, pens.y + 2, 1, () -> {
                human.active(false);
                categories.setSelectionRow(2);
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> human.active(false));
        }
        human.flush();
        assertThat(steps).isEmpty();
        assertThat(notRecorded).isEmpty();
    }

    @Test
    void aDoubleClickInARowsIndentIsADoubleClickOnTheRow() throws Exception {
        onEdt(() -> {
            javax.swing.JTree categories = categories();
            java.awt.Rectangle office = categories.getRowBounds(0);

            // a wide selection: the first press selects the row, the second (count 2) collapses it
            click(categories, 1, office.y + 2, 1, () -> categories.setSelectionRow(0));
            click(categories, 1, office.y + 2, 2, () -> categories.collapseRow(0));
            human.flush();

            assertThat(steps).hasSize(1);
            assertThat(((Locator) steps.get(0).get("target")).item).containsExactly("Office");
            assertThat(steps.get(0)).containsEntry("count", 2);
            assertThat(notRecorded).isEmpty();
        });
    }

    @Test
    void aClickBesideARowThatWasSelectedAlreadyIsNoStep() throws Exception {
        javax.swing.JTree categories = onEdt(this::categories);
        java.awt.Rectangle pens = onEdt(() -> categories.getRowBounds(2));
        onEdt(() -> categories.setSelectionRow(2));

        // the tree did nothing with it: the row stays selected as it was
        releaseHandledAfter(categories, pens.x + pens.width + 5, pens.y + 2, 1, () -> { });
        human.flush();

        assertThat(steps).isEmpty();
        assertThat(notRecorded).isEmpty();
    }

    @Test
    void aClickBesideATreeRowIsAClickOnTheRowWhenItSelectsIt() throws Exception {
        onEdt(() -> {
            javax.swing.JTree categories = categories();
            java.awt.Rectangle pens = categories.getRowBounds(2);

            // a wide selection (FlatLaf) selects the row on a click in its indent, or right of its label
            click(categories, 1, pens.y + 2, 1, () -> categories.setSelectionRow(2));
            human.flush();
            categories.clearSelection();
            click(categories, pens.x + pens.width + 5, pens.y + 2, 1, () -> categories.setSelectionRow(2));
            human.flush();
            // one that selects nothing (Metal, Basic) did nothing: no step
            categories.clearSelection();
            click(categories, pens.x + pens.width + 5, pens.y + 2, 1);
            human.flush();

            assertThat(steps).hasSize(2);
            assertThat(((Locator) steps.get(0).get("target")).item).containsExactly("Office", "Pens");
            assertThat(((Locator) steps.get(1).get("target")).item).containsExactly("Office", "Pens");
            assertThat(notRecorded).isEmpty();
        });
    }

    /** Order Desk's product categories: Office (Paper, Pens) expanded, Coffee; the root hidden. In the scene's window. */
    private javax.swing.JTree categories() {
        javax.swing.tree.DefaultMutableTreeNode products = new javax.swing.tree.DefaultMutableTreeNode("Products");
        javax.swing.tree.DefaultMutableTreeNode office = new javax.swing.tree.DefaultMutableTreeNode("Office");
        office.add(new javax.swing.tree.DefaultMutableTreeNode("Paper"));
        office.add(new javax.swing.tree.DefaultMutableTreeNode("Pens"));
        products.add(office);
        products.add(new javax.swing.tree.DefaultMutableTreeNode("Coffee"));
        javax.swing.JTree categories = new javax.swing.JTree(products);
        categories.setRootVisible(false);
        categories.setShowsRootHandles(true);
        categories.expandRow(0);
        categories.setBounds(320, 130, 150, 100);
        scene.content.add(categories);
        return categories;
    }

    @Test
    void aComboPopupStaysASelect() throws Exception {
        onEdt(() -> {
            javax.swing.JComboBox<String> customer = new javax.swing.JComboBox<String>(new String[] {"Ada Shop Ltd", "Birch & Co"});
            customer.setBounds(320, 130, 150, 24);
            scene.content.add(customer);
            javax.swing.plaf.basic.ComboPopup popup = (javax.swing.plaf.basic.ComboPopup) customer.getUI().getAccessibleChild(customer, 0);
            ((javax.swing.JPopupMenu) popup).setInvoker(customer);   // as showing it does
            javax.swing.JList<?> list = popup.getList();
            list.setSize(150, 40);
            java.awt.Rectangle birch = list.getCellBounds(1, 1);

            click(list, birch.x + 4, birch.y + 2, 1);
            human.flush();

            assertThat(steps).hasSize(1);
            assertThat(steps.get(0)).containsEntry("action", "select").containsEntry("item", "Birch & Co");
        });
    }

    @Test
    void spinnerArrowsInATableCellAreOneTypeOfTheValueWhenTheEditEnds() {
        Spinners cart = new Spinners();
        cart.table.editCellAt(0, 1);
        javax.swing.JSpinner spinner = (javax.swing.JSpinner) cart.table.getEditorComponent();
        Component next = arrow(spinner);
        click(next, 1);
        spinner.setValue(2);
        click(next, 1);
        spinner.setValue(3);
        key(spinner, KeyEvent.KEY_PRESSED, 0, KeyEvent.VK_UP, KeyEvent.CHAR_UNDEFINED);
        assertThat(steps).isEmpty();
        cart.table.getCellEditor().stopCellEditing();
        human.flush();
        assertThat(steps).hasSize(1);
        Map<String, Object> type = steps.get(0);
        assertThat(type).containsEntry("action", "type").containsEntry("text", "3").containsEntry("commit", true);
        Locator cell = (Locator) type.get("target");
        assertThat(cell.row).containsEntry("Product", "A4 paper");
        assertThat(cell.column).isEqualTo("Qty");
    }

    @Test
    void aCancelledSpinnerEditIsNoStep() {
        Spinners cart = new Spinners();
        cart.table.editCellAt(0, 1);
        javax.swing.JSpinner spinner = (javax.swing.JSpinner) cart.table.getEditorComponent();
        click(arrow(spinner), 1);
        spinner.setValue(2);
        cart.table.getCellEditor().cancelCellEditing();
        human.flush();
        assertThat(steps).isEmpty();
    }

    @Test
    void theEnterThatEndsASpinnerEditOnTheTableIsPartOfTheType() {
        Spinners cart = new Spinners();
        cart.table.editCellAt(0, 1);
        javax.swing.JSpinner spinner = (javax.swing.JSpinner) cart.table.getEditorComponent();
        click(arrow(spinner), 1);
        spinner.setValue(2);
        // the arrows take no focus: the table has it, and its Enter ends the edit
        key(cart.table, KeyEvent.KEY_PRESSED, 0, KeyEvent.VK_ENTER, '\n');
        cart.table.getCellEditor().stopCellEditing();
        human.flush();
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).containsEntry("action", "type").containsEntry("text", "2").containsEntry("commit", true);
    }

    @Test
    void aClickOnADisabledButtonIsNoStep() {
        scene.save.setEnabled(false);
        click(scene.save, 1);
        human.flush();
        assertThat(steps).isEmpty();
    }

    @Test
    void aButtonOfAnOptionPaneIsAnAnswerByItsLabel() {
        javax.swing.JOptionPane pane = new javax.swing.JOptionPane("Place this order?", javax.swing.JOptionPane.QUESTION_MESSAGE,
                javax.swing.JOptionPane.YES_NO_OPTION, null, new Object[] {"Place order", "Keep editing"});
        javax.swing.JButton place = findButton(pane, "Place order");
        pane.setBounds(10, 130, 300, 120);
        scene.content.add(pane);
        click(place, 1);
        human.flush();
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).containsEntry("action", "answer").containsEntry("button", "Place order")
                .doesNotContainKey("target");
    }

    private static javax.swing.JButton findButton(java.awt.Container in, String text) {
        for (Component c : in.getComponents()) {
            if (c instanceof javax.swing.JButton && text.equals(((javax.swing.JButton) c).getText())) return (javax.swing.JButton) c;
            if (c instanceof java.awt.Container) {
                javax.swing.JButton b = findButton((java.awt.Container) c, text);
                if (b != null) return b;
            }
        }
        return null;
    }

    @Test
    void aButtonWhoseLabelChangedIsRecordedByItsLabelNow() {
        click(scene.save, 1);
        human.flush();
        scene.save.setText("Place order");
        click(scene.save, 1);
        human.flush();
        assertThat(((Locator) steps.get(1).get("target")).name).isEqualTo("Place order");
    }

    @Test
    void anEnterOnTheTableRightAfterAnEditEndedByItselfBelongsToThatEdit() {
        Spinners cart = new Spinners();
        cart.table.editCellAt(0, 1);
        javax.swing.JSpinner spinner = (javax.swing.JSpinner) cart.table.getEditorComponent();
        click(arrow(spinner), 1);
        spinner.setValue(2);
        cart.table.getCellEditor().stopCellEditing();   // an editor that commits on every change
        key(cart.table, KeyEvent.KEY_PRESSED, 0, KeyEvent.VK_ENTER, '\n');
        human.flush();
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).containsEntry("action", "type");
        // after another step, Enter on the table is a step again
        click(scene.save, 1);
        key(cart.table, KeyEvent.KEY_PRESSED, 0, KeyEvent.VK_ENTER, '\n');
        human.flush();
        assertThat(steps).hasSize(3);
        assertThat(steps.get(2)).containsEntry("action", "press").containsEntry("keys", "Enter");
    }

    /** A cart table whose Qty cells are edited with a spinner, in the scene's window. */
    private final class Spinners {
        final JTable table = new JTable(new javax.swing.table.DefaultTableModel(
                new Object[][] {{"A4 paper", 1}, {"Gel pen", 1}}, new Object[] {"Product", "Qty"}));

        Spinners() {
            table.getColumnModel().getColumn(1).setCellEditor(new SpinnerEditor());
            table.setBounds(10, 130, 300, 40);
            scene.content.add(table);
        }
    }

    private static final class SpinnerEditor extends javax.swing.AbstractCellEditor implements javax.swing.table.TableCellEditor {
        private final javax.swing.JSpinner spinner = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(1, 1, 99, 1));

        @Override public Object getCellEditorValue() { return spinner.getValue(); }
        @Override public Component getTableCellEditorComponent(JTable t, Object value, boolean selected, int row, int column) {
            spinner.setValue(value);
            return spinner;
        }
    }

    private static Component arrow(javax.swing.JSpinner spinner) {
        for (Component c : spinner.getComponents()) if (c instanceof javax.swing.JButton) return c;
        throw new AssertionError("no arrow button in the spinner");
    }

    @Test
    void whatTheAgentDoesWhileThePersonRecordsIsNotRecordedTwice() {
        Inspector.agentActing(true);
        try {
            click(scene.save, 1);
            human.flush();
        } finally {
            Inspector.agentActing(false);
        }
        // the agent's own step is recorded on its side; the person's recorder leaves it alone
        assertThat(steps).isEmpty();
        click(scene.save, 1);
        human.flush();
        assertThat(steps).hasSize(1);
    }

    @Test
    void aCheckBoxIsACheckToTheStateItIsAboutToTake() {
        click(scene.rush, 1);
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).containsEntry("action", "check").containsEntry("checked", true);
    }

    @Test
    void typingIsOneTypeStepWhenTheFieldLosesFocus() {
        focus(scene.customer, FocusEvent.FOCUS_GAINED);
        typeInto(scene.customer, "Acme");
        assertThat(steps).isEmpty();
        // what the person reads: the field as they see it, not its locator (#customer)
        assertThat(human.pending()).isEqualTo("typing “Acme” into text field (recorded when you leave the field)");
        focus(scene.customer, FocusEvent.FOCUS_LOST);
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).containsEntry("action", "type").containsEntry("text", "Acme").doesNotContainKey("commit");
    }

    @Test
    void enterInAFieldCommitsTheTyping() {
        focus(scene.customer, FocusEvent.FOCUS_GAINED);
        typeInto(scene.customer, "Acme");
        key(scene.customer, KeyEvent.KEY_PRESSED, 0, KeyEvent.VK_ENTER, '\n');
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).containsEntry("text", "Acme").containsEntry("commit", true);
    }

    @Test
    void textTheAppSetsByItselfIsNotTyping() {
        focus(scene.customer, FocusEvent.FOCUS_GAINED);
        scene.customer.setText("filled in by the app");
        assertThat(human.pending()).isNull();
        focus(scene.customer, FocusEvent.FOCUS_LOST);
        assertThat(steps).isEmpty();
    }

    @Test
    void aClickIntoATextFieldIsNoStep() {
        click(scene.customer, 1);
        human.flush();
        assertThat(steps).isEmpty();
    }

    @Test
    void escapeIsAPress() {
        key(scene.save, KeyEvent.KEY_PRESSED, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED);
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).containsEntry("action", "press").containsEntry("keys", "Escape");
    }

    @Test
    void aCommandChordIsAPressOnceNoMenuItemClaimedIt() throws Exception {
        int mod = HumanSteps.MAC ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
        key(scene.save, KeyEvent.KEY_PRESSED, mod, KeyEvent.VK_S, KeyEvent.CHAR_UNDEFINED);
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).containsEntry("keys", "Mod+S");
    }

    @Test
    void aChordReadsBackAsThePressVerbParsesIt() {
        int menu = HumanSteps.MAC ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
        String chord = HumanSteps.chord(menu | InputEvent.SHIFT_DOWN_MASK, HumanSteps.keyName(KeyEvent.VK_F5));
        KeyChord parsed = KeyChord.parse(chord, menu);
        assertThat(parsed.keyCode).isEqualTo(KeyEvent.VK_F5);
        assertThat(parsed.modifiers).isEqualTo(menu | InputEvent.SHIFT_DOWN_MASK);
    }

    @Test
    void aClickOnThePaletteIsNoStep() {
        Bar bar = new Bar("swing-ui-agent", "").button("● Record", Overlay.NO, () -> { });
        bar.setBounds(320, 10, 100, 30);
        scene.content.add(bar);
        click(bar, 1);
        human.flush();
        assertThat(steps).isEmpty();
    }

    @Test
    void typingInAPaletteFieldIsNoStep() {
        javax.swing.JTextField field = new javax.swing.JTextField() { };
        field.setBounds(320, 50, 100, 24);
        scene.content.add(field);
        focus(field, FocusEvent.FOCUS_GAINED);
        typeInto(field, "Mine");
        focus(field, FocusEvent.FOCUS_LOST);
        human.flush();
        assertThat(steps).isEmpty();
    }

    @Test
    void focusMovingIntoThePaletteKeepsTheTypingOpen() {
        javax.swing.JTextField describe = new javax.swing.JTextField() { };   // the palette's own field
        focus(scene.customer, FocusEvent.FOCUS_GAINED);
        typeInto(scene.customer, "Ada Shop Ltd");
        human.eventDispatched(new FocusEvent(scene.customer, FocusEvent.FOCUS_LOST, false, describe));
        assertThat(steps).isEmpty();
        assertThat(human.pending()).contains("Ada Shop Ltd");

        // the keyboard comes back, and leaving the field for the app records the typing once
        focus(scene.customer, FocusEvent.FOCUS_GAINED);
        focus(scene.customer, FocusEvent.FOCUS_LOST);
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).containsEntry("action", "type").containsEntry("text", "Ada Shop Ltd");
    }

    /**
     * Runs {@code body} on the event dispatch thread, where the recorder runs, and waits; its assertion errors fail the
     * test as they are. A test that measures list or tree rows must: they cache their row heights, worked out lazily, and
     * the event dispatch thread may rework them between the test's measuring and the recorder's (a click then lands on
     * another row).
     */
    private static void onEdt(EdtBody body) throws Exception {
        onEdt(() -> {
            body.run();
            return null;
        });
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> read) throws Exception {
        java.util.concurrent.atomic.AtomicReference<T> got = new java.util.concurrent.atomic.AtomicReference<T>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    got.set(read.call());
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new java.lang.reflect.UndeclaredThrowableException(e);
                }
            });
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof java.lang.reflect.UndeclaredThrowableException) cause = cause.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
        return got.get();
    }

    private interface EdtBody {
        void run() throws Exception;
    }

    private void click(Component c, int count) {
        click(c, 3, 3, count);
    }

    private void click(Component c, int x, int y, int count) {
        click(c, x, y, count, () -> { });
    }

    /**
     * A click on the EDT whose release the app handles with {@code app} after the recorder heard it, as the component's own
     * release listeners would; then whatever the recorder left for later has run.
     */
    private void releaseHandledAfter(Component c, int x, int y, int count, Runnable app) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            human.eventDispatched(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, 1L, InputEvent.BUTTON1_DOWN_MASK, x, y, count,
                    false, MouseEvent.BUTTON1));
            human.eventDispatched(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, 2L, 0, x, y, count, false, MouseEvent.BUTTON1));
            app.run();
        });
        SwingUtilities.invokeAndWait(() -> { });
    }

    /** A click whose press the app handles with {@code app} before the release comes, as the component's own listeners would. */
    private void click(Component c, int x, int y, int count, Runnable app) {
        human.eventDispatched(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, 1L, InputEvent.BUTTON1_DOWN_MASK, x, y, count,
                false, MouseEvent.BUTTON1));
        app.run();
        human.eventDispatched(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, 2L, 0, x, y, count, false, MouseEvent.BUTTON1));
    }

    private void focus(Component c, int id) {
        human.eventDispatched(new FocusEvent(c, id));
    }

    private void typeInto(javax.swing.JTextField field, String text) {
        for (char ch : text.toCharArray()) key(field, KeyEvent.KEY_TYPED, 0, KeyEvent.VK_UNDEFINED, ch);
        field.setText(text);
    }

    private void key(Component c, int id, int modifiers, int code, char ch) {
        human.eventDispatched(new KeyEvent(c, id, 1L, modifiers, code, ch));
    }
}
