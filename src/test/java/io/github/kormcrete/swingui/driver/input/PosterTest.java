package io.github.kormcrete.swingui.driver.input;

import io.github.kormcrete.swingui.driver.platform.Platform;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PosterTest {

    private final JPanel source = new JPanel();

    @Test
    void aLeftClickIsMovePressReleaseClick() {
        List<MouseEvent> events = Poster.clickEvents(source, new Point(10, 20), Poster.Button.LEFT, 1, 0, 5L, true);
        assertThat(events).extracting(MouseEvent::getID).containsExactly(
                MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED);
        MouseEvent press = events.get(1);
        assertThat(press.getButton()).isEqualTo(MouseEvent.BUTTON1);
        assertThat(press.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK).isNotZero();
        assertThat(press.getPoint()).isEqualTo(new Point(10, 20));
        assertThat(press.getClickCount()).isEqualTo(1);
    }

    @Test
    void aDoubleClickCountsUp() {
        List<MouseEvent> events = Poster.clickEvents(source, new Point(1, 1), Poster.Button.LEFT, 2, 0, 5L, true);
        assertThat(events).hasSize(7);
        assertThat(events.get(4).getID()).isEqualTo(MouseEvent.MOUSE_PRESSED);
        assertThat(events.get(4).getClickCount()).isEqualTo(2);
    }

    @Test
    void theRightButtonIsAPopupTriggerWhereThePlatformSaysSo() {
        List<MouseEvent> mac = Poster.clickEvents(source, new Point(1, 1), Poster.Button.RIGHT, 1, 0, 5L, true);
        assertThat(mac.get(1).isPopupTrigger()).isTrue();
        assertThat(mac.get(2).isPopupTrigger()).isFalse();
        assertThat(mac.get(1).getButton()).isEqualTo(MouseEvent.BUTTON3);
        List<MouseEvent> other = Poster.clickEvents(source, new Point(1, 1), Poster.Button.RIGHT, 1, 0, 5L, false);
        assertThat(other.get(1).isPopupTrigger()).isFalse();
        assertThat(other.get(2).isPopupTrigger()).isTrue();
    }

    @Test
    void onlyWindowsTriggersPopupsOnRelease() {
        assertThat(Poster.popupTriggerOnPress(Platform.MAC)).isTrue();
        assertThat(Poster.popupTriggerOnPress(Platform.LINUX)).isTrue();
        assertThat(Poster.popupTriggerOnPress(Platform.WINDOWS)).isFalse();
    }

    @Test
    void modifiersStayDownButTheButtonMaskOnlyOnPress() {
        List<MouseEvent> events = Poster.clickEvents(source, new Point(1, 1), Poster.Button.LEFT, 1,
                InputEvent.META_DOWN_MASK, 5L, true);
        assertThat(events.get(1).getModifiersEx()).isEqualTo(InputEvent.META_DOWN_MASK | InputEvent.BUTTON1_DOWN_MASK);
        assertThat(events.get(2).getModifiersEx()).isEqualTo(InputEvent.META_DOWN_MASK);
    }

    @Test
    void aKeyWithACharacterAlsoTypesIt() {
        List<KeyEvent> enter = Poster.keyEvents(source, KeyChord.parse("Enter", InputEvent.META_DOWN_MASK), 5L);
        assertThat(enter).extracting(KeyEvent::getID)
                .containsExactly(KeyEvent.KEY_PRESSED, KeyEvent.KEY_TYPED, KeyEvent.KEY_RELEASED);
        assertThat(enter.get(1).getKeyChar()).isEqualTo('\n');
        List<KeyEvent> open = Poster.keyEvents(source, KeyChord.parse("Mod+O", InputEvent.META_DOWN_MASK), 5L);
        assertThat(open).extracting(KeyEvent::getID).containsExactly(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED);
        assertThat(open.get(0).getModifiersEx()).isEqualTo(InputEvent.META_DOWN_MASK);
    }

    @Test
    void textIsTypedCharacterByCharacter() {
        List<KeyEvent> events = Poster.textEvents(source, "Ab-ä", 5L);
        assertThat(events).hasSize(12);
        assertThat(events.get(0).getKeyCode()).isEqualTo(KeyEvent.VK_A);
        assertThat(events.get(0).getModifiersEx()).isEqualTo(InputEvent.SHIFT_DOWN_MASK);
        assertThat(events.get(1).getKeyChar()).isEqualTo('A');
        assertThat(events.get(4).getKeyChar()).isEqualTo('b');
        assertThat(events.get(6).getKeyCode()).isEqualTo(KeyEvent.VK_MINUS);
        assertThat(events.get(10).getID()).isEqualTo(KeyEvent.KEY_TYPED);
        assertThat(events.get(10).getKeyChar()).isEqualTo('ä');
    }

    @Test
    void aMoveIsOneMouseMovedAtThePoint() {
        java.awt.Component window = new javax.swing.JPanel();
        java.awt.event.MouseEvent e = Poster.moveEvent(window, new java.awt.Point(7, 9), 1L);
        assertThat(e.getID()).isEqualTo(java.awt.event.MouseEvent.MOUSE_MOVED);
        assertThat(e.getPoint()).isEqualTo(new java.awt.Point(7, 9));
        assertThat(e.getButton()).isEqualTo(java.awt.event.MouseEvent.NOBUTTON);
    }

    @Test
    void aLeaveIsAMouseExitedOffTheWindow() {
        java.awt.event.MouseEvent e = Poster.leaveEvent(new javax.swing.JPanel(), 1L);
        assertThat(e.getID()).isEqualTo(java.awt.event.MouseEvent.MOUSE_EXITED);
        assertThat(e.getX()).isNegative();
    }

    /** A hover leaves the window first: a move alone never re-enters the component the last posted click landed on. */
    @Test
    void aHoverLeavesTheWindowThenMovesToThePoint() {
        JPanel window = new JPanel();
        List<MouseEvent> events = Poster.hoverEvents(window, new Point(7, 9), 1L);
        assertThat(events).extracting(MouseEvent::getID)
                .containsExactly(MouseEvent.MOUSE_EXITED, MouseEvent.MOUSE_MOVED);
        assertThat(events.get(0).getX()).isNegative();
        assertThat(events.get(1).getPoint()).isEqualTo(new Point(7, 9));
        assertThat(events).allMatch(e -> e.getSource() == window);
    }

    @Test
    void leavingWithNothingHoveredPostsNothing() {
        List<java.awt.AWTEvent> posted = new java.util.ArrayList<java.awt.AWTEvent>();
        new Poster(posted::add, false, () -> 0).leaveHovered();
        assertThat(posted).isEmpty();
    }
}
