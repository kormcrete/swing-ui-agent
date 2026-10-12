package io.github.kormcrete.swingui.driver.input;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import org.junit.jupiter.api.Test;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class KeyChordTest {

    private static final int CMD = InputEvent.META_DOWN_MASK;

    @Test
    void namedKeysCarryTheirCharacter() {
        KeyChord enter = KeyChord.parse("Enter", CMD);
        assertThat(enter.keyCode).isEqualTo(KeyEvent.VK_ENTER);
        assertThat(enter.modifiers).isZero();
        assertThat(enter.keyChar).isEqualTo('\n');
        assertThat(KeyChord.parse("Escape", CMD).keyChar).isEqualTo((char) 27);
        assertThat(KeyChord.parse("Space", CMD).keyChar).isEqualTo(' ');
    }

    @Test
    void modIsThePlatformMenuShortcutKey() {
        KeyChord mac = KeyChord.parse("Mod+O", CMD);
        assertThat(mac.keyCode).isEqualTo(KeyEvent.VK_O);
        assertThat(mac.modifiers).isEqualTo(InputEvent.META_DOWN_MASK);
        assertThat(mac.keyChar).isEqualTo(KeyEvent.CHAR_UNDEFINED);
        assertThat(KeyChord.parse("Mod+O", InputEvent.CTRL_DOWN_MASK).modifiers).isEqualTo(InputEvent.CTRL_DOWN_MASK);
    }

    /**
     * A grid's paste is bound as the menu-shortcut V; the posted Mod+V must look the same binding up,
     * and type no 'v' that would open a cell editor instead.
     */
    @Test
    @SuppressWarnings("deprecation")
    void modVFindsTheMenuShortcutPasteBinding() {
        for (int mod : new int[] {InputEvent.META_DOWN_MASK, InputEvent.CTRL_DOWN_MASK}) {
            KeyChord paste = KeyChord.parse("Mod+V", mod);
            KeyEvent pressed = new KeyEvent(new javax.swing.JPanel(), KeyEvent.KEY_PRESSED, 0L,
                    paste.modifiers, paste.keyCode, paste.keyChar);
            int strokeMask = mod == InputEvent.META_DOWN_MASK ? InputEvent.META_MASK : InputEvent.CTRL_MASK;
            assertThat(KeyStroke.getKeyStrokeForEvent(pressed)).isEqualTo(KeyStroke.getKeyStroke(KeyEvent.VK_V, strokeMask));
            assertThat(paste.keyChar).isEqualTo(KeyEvent.CHAR_UNDEFINED);
        }
    }

    @Test
    void shiftChangesTheLetterCase() {
        assertThat(KeyChord.parse("a", CMD).keyChar).isEqualTo('a');
        KeyChord upper = KeyChord.parse("Shift+a", CMD);
        assertThat(upper.keyChar).isEqualTo('A');
        assertThat(upper.modifiers).isEqualTo(InputEvent.SHIFT_DOWN_MASK);
        assertThat(KeyChord.parse("Shift+Tab", CMD).keyCode).isEqualTo(KeyEvent.VK_TAB);
    }

    @Test
    void vkConstantNamesParse() {
        assertThat(KeyChord.parse("ESCAPE", CMD).keyCode).isEqualTo(KeyEvent.VK_ESCAPE);
        assertThat(KeyChord.parse("PAGE_DOWN", CMD).keyCode).isEqualTo(KeyEvent.VK_PAGE_DOWN);
        assertThat(KeyChord.parse("F5", CMD).keyCode).isEqualTo(KeyEvent.VK_F5);
    }

    @Test
    void badChordsAreBadInput() {
        assertBad("", "empty");
        assertBad("Hyper+X", "unknown modifier 'Hyper'");
        assertBad("Frobnicate", "unknown key 'Frobnicate'");
    }

    @Test
    void clickModifiersCombine() {
        assertThat(KeyChord.modifierMask(Arrays.asList("Mod", "Shift"), CMD))
                .isEqualTo(InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
    }

    @Test
    void aKeyStrokeKeepsOnlyExtendedModifiers() {
        KeyChord c = KeyChord.of(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.META_DOWN_MASK));
        assertThat(c.keyCode).isEqualTo(KeyEvent.VK_A);
        assertThat(c.modifiers).isEqualTo(InputEvent.META_DOWN_MASK);
    }

    private static void assertBad(String chord, String text) {
        DriverException e = catchThrowableOfType(() -> KeyChord.parse(chord, CMD), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains(text);
    }
}
