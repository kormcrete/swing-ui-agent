package io.github.kormcrete.swingui.driver.input;

import io.github.kormcrete.swingui.driver.Edt;
import io.github.kormcrete.swingui.driver.focus.FocusEmulator;
import io.github.kormcrete.swingui.driver.platform.Platform;

import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Real-shaped input posted into the application's own event queue. Mouse events go
 * to the target's window, where AWT hit-tests them as it would a real click; key events go to a
 * window, and the focus manager routes them to its focus owner.
 */
public final class Poster {

    public enum Button { LEFT, RIGHT }

    public interface EventSink {
        void post(AWTEvent event);
    }

    private static final int DRAG_STEPS = 6;

    private final EventSink sink;
    private final boolean popupTriggerOnPress;
    private final IntSupplier menuShortcutMask;
    /** The window of the last hover, for {@link #leaveHovered}; weak, so a disposed dialog is not kept. */
    private volatile WeakReference<Window> hovered;

    public Poster(EventSink sink, boolean popupTriggerOnPress, IntSupplier menuShortcutMask) {
        this.sink = sink;
        this.popupTriggerOnPress = popupTriggerOnPress;
        this.menuShortcutMask = menuShortcutMask;
    }

    public static Poster forThisJvm() {
        return new Poster(e -> Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(e),
                popupTriggerOnPress(Platform.current()), KeyChord::menuShortcutMask);
    }

    /** Where the toolkit marks a right click as the popup trigger: on press on macOS and X11, on release on Windows. */
    static boolean popupTriggerOnPress(Platform platform) {
        return platform != Platform.WINDOWS;
    }

    public int menuShortcutMask() {
        return menuShortcutMask.getAsInt();
    }

    /** A click at {@code p}, in target coordinates; AWT decides who receives it. */
    public void click(final Component target, final Point p, Button button, int count, int modifiers) {
        Window window = Edt.call(() -> FocusEmulator.windowOf(target));
        Point at = Edt.call(() -> SwingUtilities.convertPoint(target, p, window));
        postAll(clickEvents(window, at, button, count, modifiers, System.currentTimeMillis(), popupTriggerOnPress));
    }

    /**
     * The pointer arrives at {@code p} (target coordinates) from outside the window, so AWT's dispatcher
     * sends the component MOUSE_ENTERED even when it already counts the pointer as inside it - as it does
     * for the component the last posted click landed on, which a move alone would never re-enter.
     */
    public void move(final Component target, final Point p) {
        Window window = Edt.call(() -> FocusEmulator.windowOf(target));
        Point at = Edt.call(() -> SwingUtilities.convertPoint(target, p, window));
        hovered = new WeakReference<Window>(window);
        postAll(hoverEvents(window, at, System.currentTimeMillis()));
    }

    /** The pointer leaves the window; the dispatcher sends the component under it MOUSE_EXITED. */
    public void leave(final Component target) {
        Window window = Edt.call(() -> FocusEmulator.windowOf(target));
        postAll(Collections.singletonList(leaveEvent(window, System.currentTimeMillis())));
    }

    /**
     * The pointer leaves the window the last {@link #move} was in. Cleanup: nothing is resolved or aimed,
     * and a window that is gone (never hovered, collected or disposed) makes it a no-op.
     */
    public void leaveHovered() {
        WeakReference<Window> ref = hovered;
        final Window window = ref == null ? null : ref.get();
        if (window == null || !Edt.call(window::isDisplayable)) return;
        postAll(Collections.singletonList(leaveEvent(window, System.currentTimeMillis())));
    }

    /** Press, drags and release; a native drag is never started. */
    public void drag(final Component source, final Point from, final Point to) {
        Window window = Edt.call(() -> FocusEmulator.windowOf(source));
        Point a = Edt.call(() -> SwingUtilities.convertPoint(source, from, window));
        Point b = Edt.call(() -> SwingUtilities.convertPoint(source, to, window));
        long now = System.currentTimeMillis();
        List<AWTEvent> events = new ArrayList<AWTEvent>();
        events.add(new MouseEvent(window, MouseEvent.MOUSE_MOVED, now, 0, a.x, a.y, 0, false, MouseEvent.NOBUTTON));
        events.add(new MouseEvent(window, MouseEvent.MOUSE_PRESSED, now, InputEvent.BUTTON1_DOWN_MASK, a.x, a.y, 1, false, MouseEvent.BUTTON1));
        for (int i = 1; i <= DRAG_STEPS; i++) {
            int x = a.x + (b.x - a.x) * i / DRAG_STEPS;
            int y = a.y + (b.y - a.y) * i / DRAG_STEPS;
            events.add(new MouseEvent(window, MouseEvent.MOUSE_DRAGGED, now + i, InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.NOBUTTON));
        }
        events.add(new MouseEvent(window, MouseEvent.MOUSE_RELEASED, now + DRAG_STEPS + 1, 0, b.x, b.y, 1, false, MouseEvent.BUTTON1));
        postAll(events);
    }

    public void chord(Component source, KeyChord chord) {
        postAll(keyEvents(source, chord, System.currentTimeMillis()));
    }

    public void key(Component source, int keyCode, int modifiers) {
        chord(source, KeyChord.of(keyCode, modifiers));
    }

    public void stroke(Component source, KeyStroke stroke) {
        chord(source, KeyChord.of(stroke));
    }

    public void text(Component source, String text) {
        postAll(textEvents(source, text, System.currentTimeMillis()));
    }

    static List<MouseEvent> clickEvents(Component window, Point at, Button button, int count, int modifiers,
                                        long when, boolean popupTriggerOnPress) {
        boolean right = button == Button.RIGHT;
        int awtButton = right ? MouseEvent.BUTTON3 : MouseEvent.BUTTON1;
        int downMask = right ? InputEvent.BUTTON3_DOWN_MASK : InputEvent.BUTTON1_DOWN_MASK;
        List<MouseEvent> out = new ArrayList<MouseEvent>();
        out.add(new MouseEvent(window, MouseEvent.MOUSE_MOVED, when, modifiers, at.x, at.y, 0, false, MouseEvent.NOBUTTON));
        for (int n = 1; n <= count; n++) {
            out.add(new MouseEvent(window, MouseEvent.MOUSE_PRESSED, when, modifiers | downMask, at.x, at.y, n,
                    right && popupTriggerOnPress, awtButton));
            out.add(new MouseEvent(window, MouseEvent.MOUSE_RELEASED, when, modifiers, at.x, at.y, n,
                    right && !popupTriggerOnPress, awtButton));
            out.add(new MouseEvent(window, MouseEvent.MOUSE_CLICKED, when, modifiers, at.x, at.y, n, false, awtButton));
        }
        return out;
    }

    /** A leave, then the move: the dispatcher forgets what it last entered and enters the component at {@code at}. */
    static List<MouseEvent> hoverEvents(Component window, Point at, long when) {
        List<MouseEvent> out = new ArrayList<MouseEvent>();
        out.add(leaveEvent(window, when));
        out.add(moveEvent(window, at, when));
        return out;
    }

    static MouseEvent moveEvent(Component window, Point at, long when) {
        return new MouseEvent(window, MouseEvent.MOUSE_MOVED, when, 0, at.x, at.y, 0, false, MouseEvent.NOBUTTON);
    }

    static MouseEvent leaveEvent(Component window, long when) {
        return new MouseEvent(window, MouseEvent.MOUSE_EXITED, when, 0, -1, -1, 0, false, MouseEvent.NOBUTTON);
    }

    static List<KeyEvent> keyEvents(Component source, KeyChord chord, long when) {
        List<KeyEvent> out = new ArrayList<KeyEvent>();
        out.add(new KeyEvent(source, KeyEvent.KEY_PRESSED, when, chord.modifiers, chord.keyCode, chord.keyChar));
        if (chord.keyChar != KeyEvent.CHAR_UNDEFINED) {
            out.add(new KeyEvent(source, KeyEvent.KEY_TYPED, when, chord.modifiers, KeyEvent.VK_UNDEFINED, chord.keyChar));
        }
        out.add(new KeyEvent(source, KeyEvent.KEY_RELEASED, when, chord.modifiers, chord.keyCode, chord.keyChar));
        return out;
    }

    static List<KeyEvent> textEvents(Component source, String text, long when) {
        List<KeyEvent> out = new ArrayList<KeyEvent>();
        for (char c : text.toCharArray()) {
            int mods = Character.isUpperCase(c) ? InputEvent.SHIFT_DOWN_MASK : 0;
            int code = codeFor(c);
            out.add(new KeyEvent(source, KeyEvent.KEY_PRESSED, when, mods, code, c));
            out.add(new KeyEvent(source, KeyEvent.KEY_TYPED, when, mods, KeyEvent.VK_UNDEFINED, c));
            out.add(new KeyEvent(source, KeyEvent.KEY_RELEASED, when, mods, code, c));
        }
        return out;
    }

    private static int codeFor(char c) {
        char upper = Character.toUpperCase(c);
        if (upper >= 'A' && upper <= 'Z') return KeyEvent.VK_A + (upper - 'A');
        if (c >= '0' && c <= '9') return KeyEvent.VK_0 + (c - '0');
        if (c == ' ') return KeyEvent.VK_SPACE;
        return KeyEvent.getExtendedKeyCodeForChar(c);
    }

    private void postAll(List<? extends AWTEvent> events) {
        for (AWTEvent e : events) sink.post(e);
    }
}
