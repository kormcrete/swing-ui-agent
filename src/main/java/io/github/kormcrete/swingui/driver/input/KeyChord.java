package io.github.kormcrete.swingui.driver.input;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;

import javax.swing.KeyStroke;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One key with its modifiers, parsed from "Enter", "Mod+O", "Shift+Tab". {@code Mod} is
 * the platform's menu-shortcut key; any {@code KeyEvent.VK_} name without its prefix, such as "F5", also parses.
 */
public final class KeyChord {

    private static final int COMMAND_KEYS = InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK | InputEvent.ALT_DOWN_MASK;
    private static final int EXTENDED = InputEvent.SHIFT_DOWN_MASK | COMMAND_KEYS | InputEvent.ALT_GRAPH_DOWN_MASK;
    private static final Map<String, Integer> ALIASES = new HashMap<String, Integer>();

    static {
        ALIASES.put("enter", KeyEvent.VK_ENTER);
        ALIASES.put("return", KeyEvent.VK_ENTER);
        ALIASES.put("esc", KeyEvent.VK_ESCAPE);
        ALIASES.put("escape", KeyEvent.VK_ESCAPE);
        ALIASES.put("tab", KeyEvent.VK_TAB);
        ALIASES.put("space", KeyEvent.VK_SPACE);
        ALIASES.put("backspace", KeyEvent.VK_BACK_SPACE);
        ALIASES.put("delete", KeyEvent.VK_DELETE);
        ALIASES.put("del", KeyEvent.VK_DELETE);
        ALIASES.put("up", KeyEvent.VK_UP);
        ALIASES.put("down", KeyEvent.VK_DOWN);
        ALIASES.put("left", KeyEvent.VK_LEFT);
        ALIASES.put("right", KeyEvent.VK_RIGHT);
        ALIASES.put("home", KeyEvent.VK_HOME);
        ALIASES.put("end", KeyEvent.VK_END);
        ALIASES.put("pageup", KeyEvent.VK_PAGE_UP);
        ALIASES.put("pagedown", KeyEvent.VK_PAGE_DOWN);
        ALIASES.put("insert", KeyEvent.VK_INSERT);
        ALIASES.put("plus", KeyEvent.VK_PLUS);
        ALIASES.put("minus", KeyEvent.VK_MINUS);
    }

    public final int keyCode;
    public final int modifiers;
    public final char keyChar;

    private KeyChord(int keyCode, int modifiers, char keyChar) {
        this.keyCode = keyCode;
        this.modifiers = modifiers;
        this.keyChar = keyChar;
    }

    public static KeyChord parse(String spec, int menuShortcutMask) {
        if (spec == null || spec.trim().isEmpty()) throw bad("empty key chord");
        String[] parts = spec.trim().split("\\+");
        int mods = 0;
        for (int i = 0; i < parts.length - 1; i++) {
            mods |= modifier(parts[i].trim(), menuShortcutMask);
        }
        String key = parts[parts.length - 1].trim();
        if (key.isEmpty()) throw bad("no key in '" + spec + "'");
        int code = keyCode(key);
        return new KeyChord(code, mods, typedChar(key, code, mods));
    }

    public static KeyChord of(int keyCode, int modifiers) {
        return new KeyChord(keyCode, modifiers, (modifiers & COMMAND_KEYS) != 0 ? KeyEvent.CHAR_UNDEFINED : namedChar(keyCode));
    }

    public static KeyChord of(KeyStroke stroke) {
        return new KeyChord(stroke.getKeyCode(), stroke.getModifiers() & EXTENDED, KeyEvent.CHAR_UNDEFINED);
    }

    public static int modifierMask(List<String> names, int menuShortcutMask) {
        int mask = 0;
        if (names != null) {
            for (String name : names) mask |= modifier(name.trim(), menuShortcutMask);
        }
        return mask;
    }

    /** The extended mask of Cmd on macOS, Ctrl elsewhere. Starts the toolkit: live use only. */
    @SuppressWarnings("deprecation")
    public static int menuShortcutMask() {
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
        return (mask & InputEvent.META_MASK) != 0 ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
    }

    private static int modifier(String name, int menuShortcutMask) {
        switch (name.toLowerCase(Locale.ROOT)) {
            case "mod": return menuShortcutMask;
            case "shift": return InputEvent.SHIFT_DOWN_MASK;
            case "ctrl": case "control": return InputEvent.CTRL_DOWN_MASK;
            case "alt": case "option": return InputEvent.ALT_DOWN_MASK;
            case "meta": case "cmd": case "command": return InputEvent.META_DOWN_MASK;
            default: throw bad("unknown modifier '" + name + "' (Mod, Shift, Ctrl, Alt, Meta)");
        }
    }

    private static int keyCode(String key) {
        Integer alias = ALIASES.get(key.toLowerCase(Locale.ROOT));
        if (alias != null) return alias;
        if (key.length() == 1) {
            char c = key.charAt(0);
            if (c >= 'a' && c <= 'z') return KeyEvent.VK_A + (c - 'a');
            if (c >= 'A' && c <= 'Z') return KeyEvent.VK_A + (c - 'A');
            if (c >= '0' && c <= '9') return KeyEvent.VK_0 + (c - '0');
            int code = KeyEvent.getExtendedKeyCodeForChar(c);
            if (code != KeyEvent.VK_UNDEFINED) return code;
        }
        try {
            return KeyEvent.class.getField("VK_" + key.toUpperCase(Locale.ROOT)).getInt(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw bad("unknown key '" + key + "'");
        }
    }

    private static char typedChar(String key, int code, int mods) {
        if ((mods & COMMAND_KEYS) != 0) return KeyEvent.CHAR_UNDEFINED;
        if (key.length() == 1) {
            char c = key.charAt(0);
            if (Character.isLetter(c)) {
                return (mods & InputEvent.SHIFT_DOWN_MASK) != 0 ? Character.toUpperCase(c) : Character.toLowerCase(c);
            }
            return c;
        }
        return namedChar(code);
    }

    private static char namedChar(int code) {
        switch (code) {
            case KeyEvent.VK_ENTER: return '\n';
            case KeyEvent.VK_TAB: return '\t';
            case KeyEvent.VK_SPACE: return ' ';
            case KeyEvent.VK_ESCAPE: return (char) 27;
            case KeyEvent.VK_BACK_SPACE: return '\b';
            case KeyEvent.VK_DELETE: return (char) 127;
            default: return KeyEvent.CHAR_UNDEFINED;
        }
    }

    private static DriverException bad(String message) {
        return new DriverException(Reason.BAD_INPUT, message);
    }
}
