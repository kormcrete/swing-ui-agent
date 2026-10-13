package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.input.KeyChord;
import io.github.kormcrete.swingui.driver.model.Locator;
import org.junit.jupiter.api.Test;

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
