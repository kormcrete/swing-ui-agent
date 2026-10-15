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
    private final HumanSteps human = new HumanSteps(scene.inspector, steps::add);

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
        assertThat(human.pending()).contains("Acme");
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

    private void click(Component c, int count) {
        click(c, 3, 3, count);
    }

    private void click(Component c, int x, int y, int count) {
        human.eventDispatched(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, 1L, InputEvent.BUTTON1_DOWN_MASK, x, y, count,
                false, MouseEvent.BUTTON1));
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
