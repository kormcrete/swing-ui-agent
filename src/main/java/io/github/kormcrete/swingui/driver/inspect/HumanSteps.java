package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.widgets.Cells;

import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.MenuElement;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.plaf.basic.ComboPopup;
import javax.swing.text.JTextComponent;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Turns what the person does in the app into the steps the agent would have sent: a press-and-release on one
 * element is a {@code click} (a double click one click with {@code count}), a check box a {@code check}, a menu-bar item
 * a {@code menu} (from its action, so the macOS screen menu bar and accelerators count too), a context-menu item a
 * {@code context_menu}, a combo choice a {@code select}, a text field's new content a {@code type} when focus leaves it
 * (with {@code commit} when Enter left it), a command chord or Escape a {@code press}. It listens and never consumes:
 * the app gets every event. EDT.
 */
final class HumanSteps implements AWTEventListener {

    static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    private final Inspector inspector;
    private final Consumer<Map<String, Object>> emit;
    private boolean installed;

    private Component pressed;
    private Target pressedTarget;
    private Target contextTarget;

    private JTextComponent editing;
    private Target editingTarget;
    private String editingText;
    /** The person changed the field since its text was last taken: only that is a type step, not the app's own edits. */
    private boolean typed;

    private Map<String, Object> pendingClick;
    private Target pendingClickTarget;
    private Timer pendingTimer;

    private Map<String, Object> pendingChord;

    private final List<JMenuItem> hooked = new ArrayList<JMenuItem>();
    private final ActionListener menuListener = this::menuAction;

    HumanSteps(Inspector inspector, Consumer<Map<String, Object>> emit) {
        this.inspector = inspector;
        this.emit = emit;
    }

    void active(boolean on) {
        if (on && !installed) {
            Toolkit.getDefaultToolkit().addAWTEventListener(this,
                    AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK | AWTEvent.FOCUS_EVENT_MASK);
            hookMenus();
            installed = true;
            Component focused = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (focused instanceof JTextComponent && !Overlay.ours(focused)) startEditing((JTextComponent) focused);
        } else if (!on && installed) {
            flush();
            Toolkit.getDefaultToolkit().removeAWTEventListener(this);
            for (JMenuItem item : hooked) item.removeActionListener(menuListener);
            hooked.clear();
            installed = false;
            editing = null;
        }
    }

    /** What the person is doing that is not a step yet - typing not committed - or null. */
    String pending() {
        if (editing == null || editingTarget == null) return null;
        String now = editing.getText();
        return !typed || now.equals(editingText) ? null : "typing “" + now + "” into " + editingTarget.element + " (recorded when you leave the field)";
    }

    /** Hands over what is still held back: a click waiting to see whether it becomes a double click, open typing. */
    void flush() {
        flushTyping(false);
        flushClick();
        if (pendingChord != null) {
            emit.accept(pendingChord);
            pendingChord = null;
        }
    }

    @Override
    public void eventDispatched(AWTEvent event) {
        Object source = event.getSource();
        if (!(source instanceof Component) || Overlay.ours((Component) source)) return;
        try {
            if (event instanceof MouseEvent) mouse((MouseEvent) event);
            else if (event instanceof KeyEvent) key((KeyEvent) event);
            else if (event instanceof FocusEvent) focus((FocusEvent) event);
        } catch (RuntimeException e) {
            // recording must never break the app's own event handling
            System.err.println("swing-ui-agent inspector: " + e);
        }
    }

    private void mouse(MouseEvent e) {
        Component src = e.getComponent();
        Component deepest = SwingUtilities.getDeepestComponentAt(src, e.getX(), e.getY());
        if (deepest == null) deepest = src;
        if (Overlay.ours(deepest)) return;
        Point at = SwingUtilities.convertPoint(src, e.getPoint(), deepest);
        if (e.getID() == MouseEvent.MOUSE_PRESSED) {
            if (deepest != editing) flushTyping(false);
            pressed = deepest;
            pressedTarget = null;
            if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e)) {
                contextTarget = inspector.snap(deepest, at);
                return;
            }
            if (ignoredForClicks(deepest)) return;
            pressedTarget = inspector.snap(deepest, at);
            return;
        }
        if (e.getID() != MouseEvent.MOUSE_RELEASED) return;
        if (e.isPopupTrigger()) {
            contextTarget = inspector.snap(deepest, at);
            return;
        }
        if (deepest instanceof JMenuItem && !(deepest instanceof JMenu)) {
            menuItemReleased((JMenuItem) deepest);
            return;
        }
        JList list = (JList) SwingUtilities.getAncestorOfClass(JList.class, deepest);
        if (deepest instanceof JList) list = (JList) deepest;
        JPopupMenu popup = (JPopupMenu) SwingUtilities.getAncestorOfClass(JPopupMenu.class, deepest);
        if (list != null && popup instanceof ComboPopup && popup.getInvoker() instanceof JComboBox) {
            JComboBox combo = (JComboBox) popup.getInvoker();
            int index = list.locationToIndex(SwingUtilities.convertPoint(deepest, at, list));
            Target t = inspector.component(combo);
            if (t != null && index >= 0) {
                String item = Cells.combo(combo, new JList(), index).text;
                Map<String, Object> step = step("select", t);
                step.put("item", item);
                emitStep(step);
            }
            return;
        }
        if (!SwingUtilities.isLeftMouseButton(e) || pressedTarget == null || ignoredForClicks(deepest)) return;
        Target t = inspector.snap(deepest, at);
        if (t == null || !t.same(pressedTarget)) return;
        if (t.component instanceof JCheckBox
                || (t.component instanceof JToggleButton && !(t.component instanceof JRadioButton))) {
            Map<String, Object> step = step("check", t);
            // the listener runs before the button toggles
            step.put("checked", !((AbstractButton) t.component).isSelected());
            emitStep(step);
            return;
        }
        if (e.getClickCount() >= 2 && pendingClick != null && t.same(pendingClickTarget)) {
            pendingClick.put("count", Math.min(3, e.getClickCount()));
            pendingTimer.restart();
            return;
        }
        flushClick();
        pendingClick = step("click", t);
        pendingClickTarget = t;
        pendingTimer = new Timer(450, x -> flushClick());
        pendingTimer.setRepeats(false);
        pendingTimer.start();
    }

    /** Text fields take focus, menus and combos record through their own paths, popups are not clicked into. */
    private static boolean ignoredForClicks(Component c) {
        if (c instanceof JMenuItem || SwingUtilities.getAncestorOfClass(JPopupMenu.class, c) != null) return true;
        if (c instanceof JComboBox || SwingUtilities.getAncestorOfClass(JComboBox.class, c) != null) return true;
        return c instanceof JTextComponent && !(c.getParent() instanceof JTable);
    }

    private void key(KeyEvent e) {
        if (editing != null && e.getComponent() == editing) {
            if (e.getID() == KeyEvent.KEY_TYPED && !e.isMetaDown() && !e.isControlDown()) typed = true;
            if (e.getID() == KeyEvent.KEY_PRESSED && (e.getKeyCode() == KeyEvent.VK_BACK_SPACE
                    || e.getKeyCode() == KeyEvent.VK_DELETE
                    || ((e.isMetaDown() || e.isControlDown()) && (e.getKeyCode() == KeyEvent.VK_V || e.getKeyCode() == KeyEvent.VK_X)))) {
                typed = true;
            }
        }
        if (e.getID() != KeyEvent.KEY_PRESSED) return;
        int code = e.getKeyCode();
        if (code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_ALT
                || code == KeyEvent.VK_META || code == KeyEvent.VK_ALT_GRAPH || code == KeyEvent.VK_UNDEFINED) {
            return;
        }
        boolean inText = editing != null && e.getComponent() == editing;
        int mods = e.getModifiersEx();
        boolean command = (mods & (InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK | InputEvent.ALT_DOWN_MASK)) != 0;
        if (code == KeyEvent.VK_ENTER && inText && !(editing instanceof JTextArea) && !command) {
            flushTyping(true);
            return;
        }
        if (code == KeyEvent.VK_TAB && !command) return;
        if (inText && !command && code != KeyEvent.VK_ESCAPE) return;
        if (inText && command && editingKey(code)) return;
        String name = keyName(code);
        if (name == null) return;
        if (!command && !inText && !navigationKey(code)) return;
        flushTyping(false);
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("action", "press");
        step.put("keys", chord(mods, name));
        if (command) {
            // an accelerator also fires its menu item; that item's action replaces this chord
            flushClick();
            pendingChord = step;
            SwingUtilities.invokeLater(() -> {
                if (pendingChord == step) {
                    pendingChord = null;
                    emit.accept(step);
                }
            });
        } else {
            emitStep(step);
        }
    }

    private void focus(FocusEvent e) {
        Component c = e.getComponent();
        if (e.getID() == FocusEvent.FOCUS_GAINED && c instanceof JTextComponent) {
            if (c != editing) {
                flushTyping(false);
                startEditing((JTextComponent) c);
            }
        } else if (e.getID() == FocusEvent.FOCUS_LOST && c == editing) {
            flushTyping(false);
            editing = null;
        }
    }

    private void startEditing(JTextComponent c) {
        if (!c.isEditable() || c instanceof JPasswordField) {
            editing = null;
            return;
        }
        editing = c;
        if (c.getParent() instanceof JTable && ((JTable) c.getParent()).isEditing()) {
            JTable table = (JTable) c.getParent();
            editingTarget = inspector.cell(table, table.getEditingRow(), table.getEditingColumn());
        } else {
            editingTarget = inspector.component(c);
        }
        editingText = c.getText();
        typed = false;
    }

    private void flushTyping(boolean commit) {
        if (editing == null || editingTarget == null) return;
        String now = editing.getText();
        if (!typed) {
            // the app changed the text itself (a reset, a lookup): nothing the person did
            editingText = now;
            if (commit) {
                Map<String, Object> enter = step("press", editingTarget);
                enter.put("keys", "Enter");
                emitStep(enter);
            }
            return;
        }
        if (now.equals(editingText) && !commit) return;
        typed = false;
        Map<String, Object> step = step("type", editingTarget);
        step.put("text", now);
        if (commit) step.put("commit", true);
        editingText = now;
        emitStep(step);
    }

    private void flushClick() {
        if (pendingTimer != null) pendingTimer.stop();
        if (pendingClick == null) return;
        Map<String, Object> step = pendingClick;
        pendingClick = null;
        pendingClickTarget = null;
        emit.accept(step);
    }

    private void emitStep(Map<String, Object> step) {
        flushClick();
        emit.accept(step);
    }

    private void menuItemReleased(JMenuItem item) {
        if (inMenuBar(item)) return;
        List<String> path = path(item);
        if (contextTarget == null || path.isEmpty()) return;
        Map<String, Object> step = step("context_menu", contextTarget);
        step.put("path", path);
        emitStep(step);
        contextTarget = null;
    }

    private void menuAction(ActionEvent e) {
        if (!(e.getSource() instanceof JMenuItem)) return;
        pendingChord = null;
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("action", "menu");
        step.put("path", path((JMenuItem) e.getSource()));
        emitStep(step);
    }

    private void hookMenus() {
        for (Window w : Window.getWindows()) {
            if (w instanceof JFrame && w.isShowing() && ((JFrame) w).getJMenuBar() != null) {
                for (MenuElement m : ((JFrame) w).getJMenuBar().getSubElements()) hook(m);
            }
        }
    }

    private void hook(MenuElement m) {
        if (m instanceof JMenuItem && !(m instanceof JMenu)) {
            ((JMenuItem) m).addActionListener(menuListener);
            hooked.add((JMenuItem) m);
        }
        for (MenuElement child : m.getSubElements()) hook(child);
    }

    private static boolean inMenuBar(JMenuItem item) {
        Component c = item;
        while (c != null) {
            if (c instanceof JMenuBar) return true;
            c = c instanceof JPopupMenu ? ((JPopupMenu) c).getInvoker() : c.getParent();
        }
        return false;
    }

    /** The labels from the top menu (or the context menu) down to {@code item}. */
    private static List<String> path(JMenuItem item) {
        List<String> out = new ArrayList<String>();
        Component c = item;
        while (c instanceof JMenuItem) {
            out.add(((JMenuItem) c).getText());
            Component parent = c.getParent();
            c = parent instanceof JPopupMenu ? ((JPopupMenu) parent).getInvoker() : null;
        }
        Collections.reverse(out);
        return out;
    }

    private static Map<String, Object> step(String action, Target t) {
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("action", action);
        step.put("target", t.locator);
        return step;
    }

    private static boolean editingKey(int code) {
        return code == KeyEvent.VK_A || code == KeyEvent.VK_C || code == KeyEvent.VK_V || code == KeyEvent.VK_X
                || code == KeyEvent.VK_Z || code == KeyEvent.VK_Y || code == KeyEvent.VK_LEFT || code == KeyEvent.VK_RIGHT;
    }

    private static boolean navigationKey(int code) {
        return code == KeyEvent.VK_ENTER || code == KeyEvent.VK_ESCAPE || code == KeyEvent.VK_DELETE
                || code == KeyEvent.VK_UP || code == KeyEvent.VK_DOWN || code == KeyEvent.VK_HOME || code == KeyEvent.VK_END
                || code == KeyEvent.VK_PAGE_UP || code == KeyEvent.VK_PAGE_DOWN
                || (code >= KeyEvent.VK_F1 && code <= KeyEvent.VK_F12);
    }

    static String keyName(int code) {
        if (code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z) return String.valueOf((char) ('A' + code - KeyEvent.VK_A));
        if (code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9) return String.valueOf((char) ('0' + code - KeyEvent.VK_0));
        if (code >= KeyEvent.VK_F1 && code <= KeyEvent.VK_F12) return "F" + (code - KeyEvent.VK_F1 + 1);
        switch (code) {
            case KeyEvent.VK_ENTER: return "Enter";
            case KeyEvent.VK_ESCAPE: return "Escape";
            case KeyEvent.VK_DELETE: return "Delete";
            case KeyEvent.VK_BACK_SPACE: return "Backspace";
            case KeyEvent.VK_SPACE: return "Space";
            case KeyEvent.VK_UP: return "Up";
            case KeyEvent.VK_DOWN: return "Down";
            case KeyEvent.VK_LEFT: return "Left";
            case KeyEvent.VK_RIGHT: return "Right";
            case KeyEvent.VK_HOME: return "Home";
            case KeyEvent.VK_END: return "End";
            case KeyEvent.VK_PAGE_UP: return "PageUp";
            case KeyEvent.VK_PAGE_DOWN: return "PageDown";
            default: return null;
        }
    }

    /** {@code Mod} is Cmd on macOS and Ctrl elsewhere, as the press verb reads it. */
    static String chord(int mods, String key) {
        StringBuilder s = new StringBuilder();
        boolean meta = (mods & InputEvent.META_DOWN_MASK) != 0;
        boolean ctrl = (mods & InputEvent.CTRL_DOWN_MASK) != 0;
        if (MAC ? meta : ctrl) s.append("Mod+");
        if (MAC ? ctrl : meta) s.append(MAC ? "Ctrl+" : "Meta+");
        if ((mods & InputEvent.ALT_DOWN_MASK) != 0) s.append("Alt+");
        if ((mods & InputEvent.SHIFT_DOWN_MASK) != 0) s.append("Shift+");
        return s.append(key).toString();
    }
}
