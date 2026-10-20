package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.widgets.Cells;
import io.github.kormcrete.swingui.driver.widgets.TreeWidget;

import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JRootPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.ChangeListener;
import javax.swing.plaf.basic.ComboPopup;
import javax.swing.tree.TreePath;
import javax.swing.text.JTextComponent;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Turns what the person does in the app into the steps the agent would have sent: a press-and-release on one
 * element is a {@code click} (a double click one click with {@code count}; on a list's entry or a tree's row, also a
 * suggestion list in a popup, a click on its {@code item}), a check box a {@code check}, a menu-bar item
 * a {@code menu} (from its action, so the macOS screen menu bar and accelerators count too), a context-menu item a
 * {@code context_menu}, a choice from a button's dropdown a {@code pick}, a combo choice a {@code select}, a text
 * field's new content a {@code type} when focus leaves it (with {@code commit} when Enter left it), a table cell edited
 * with a spinner or another composite editor one {@code type} of its new value when the edit ends, a value a popup
 * opened from a text field writes into it (a calendar's day) one {@code type} of the field's new text when it closes, a
 * command chord or Escape a {@code press}. It listens and never consumes:
 * the app gets every event. EDT.
 */
final class HumanSteps implements AWTEventListener {

    static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    private final Inspector inspector;
    private final Consumer<Map<String, Object>> emit;
    private final Consumer<String> notRecorded;
    private boolean installed;

    private Component pressed;
    private Target pressedTarget;
    /** The list entry or tree row pressed (view index, -1 none) and why it cannot be recorded (null: it can). */
    private int pressedEntry = -1;
    private String pressedRefusal;
    /**
     * For a pressed tree row: whether the press was on its label, whether it was expanded then, the tree's selection then,
     * and its path as the person reads it.
     */
    private boolean pressedOnLabel;
    private boolean pressedExpanded;
    private TreePath[] pressedSelection;
    private String pressedPath;
    /** The last line {@link #refuse} said: a double click's second click does not say it again. */
    private String lastRefused;
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

    /** A table cell the person is editing with a composite editor (a spinner): its value becomes a type at the end. */
    private CellEdit cellEdit;
    /** The table whose cell edit just became a type step, until the next input: an Enter on it belongs to that edit. */
    private JTable endedEdit;

    private final List<JMenuItem> hooked = new ArrayList<JMenuItem>();
    private final ActionListener menuListener = this::menuAction;

    /** The popup the app opened from a text field (a calendar), while it is open; null when none is. */
    private FieldPopup fieldPopup;
    /** The popup open as last heard, so each one is looked at once. */
    private JPopupMenu openPopup;
    /** Hears every popup menu open and close (Swing tells its menu selection manager). */
    private final ChangeListener popups = e -> popupsChanged();

    HumanSteps(Inspector inspector, Consumer<Map<String, Object>> emit, Consumer<String> notRecorded) {
        this.inspector = inspector;
        this.emit = emit;
        this.notRecorded = notRecorded;
    }

    void active(boolean on) {
        if (on && !installed) {
            Toolkit.getDefaultToolkit().addAWTEventListener(this,
                    AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK | AWTEvent.FOCUS_EVENT_MASK);
            hookMenus();
            MenuSelectionManager.defaultManager().addChangeListener(popups);
            installed = true;
            Component focused = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (focused instanceof JTextComponent && !Overlay.ours(focused)) startEditing((JTextComponent) focused);
        } else if (!on && installed) {
            flush();
            if (cellEdit != null) cellEdit.stopWatching();
            cellEdit = null;
            Toolkit.getDefaultToolkit().removeAWTEventListener(this);
            MenuSelectionManager.defaultManager().removeChangeListener(popups);
            fieldPopup = null;
            openPopup = null;
            for (JMenuItem item : hooked) item.removeActionListener(menuListener);
            hooked.clear();
            installed = false;
            editing = null;
        }
    }

    /** Whether it listens to the person's input now. */
    boolean listening() {
        return installed;
    }

    /** What the person is doing that is not a step yet - typing not committed - or null. */
    String pending() {
        if (editing == null || editingTarget == null) return null;
        String now = editing.getText();
        return !typed || now.equals(editingText) ? null : "typing “" + now + "” into " + editingTarget.shown + " (recorded when you leave the field)";
    }

    /** Hands over what is still held back: a click waiting to see whether it becomes a double click, open typing. */
    void flush() {
        flushTyping(false);
        flushClick();
        if (pendingChord != null) {
            out(pendingChord);
            pendingChord = null;
        }
    }

    @Override
    public void eventDispatched(AWTEvent event) {
        if (Inspector.agentIsActing()) return;
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
        JTable edited = Inspector.editingTable(deepest);
        if (edited != null && !(deepest instanceof JTextComponent && deepest.getParent() == edited)) {
            // inside a composite cell editor (a spinner's arrows): no click of its own, the cell's new value is the step
            pressed = null;
            pressedTarget = null;
            watchCellEdit(edited);
            return;
        }
        Point at = SwingUtilities.convertPoint(src, e.getPoint(), deepest);
        AbstractButton answer = optionPaneButton(deepest);
        if (e.getID() == MouseEvent.MOUSE_PRESSED) {
            endedEdit = null;
            if (deepest != editing) flushTyping(false);
            pressed = deepest;
            pressedTarget = null;
            pressedEntry = -1;
            pressedRefusal = null;
            // a disabled element does nothing: no step; an option pane's button is answered by its label on release
            if (!deepest.isEnabled() || answer != null) return;
            if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e)) {
                contextTarget = inspector.snapNow(deepest, at);
                return;
            }
            if (ignoredForClicks(deepest)) return;
            if (deepest instanceof JList || deepest instanceof JTree) pressEntry(deepest, at);
            else pressedTarget = inspector.snapNow(deepest, at);
            return;
        }
        if (e.getID() != MouseEvent.MOUSE_RELEASED) return;
        if (answer != null) {
            if (SwingUtilities.isLeftMouseButton(e) && pressed == deepest && answer.isEnabled() && answer.getText() != null) {
                // what the agent records for a standard dialog: answer by the button's label, not its internal name
                Map<String, Object> step = new LinkedHashMap<String, Object>();
                step.put("action", "answer");
                step.put("button", answer.getText());
                emitStep(step);
            }
            return;
        }
        if (e.isPopupTrigger()) {
            contextTarget = inspector.snapNow(deepest, at);
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
        if (!SwingUtilities.isLeftMouseButton(e) || ignoredForClicks(deepest)) return;
        if (deepest instanceof JList || deepest instanceof JTree) {
            releasedEntry(e, deepest, at);
            return;
        }
        if (pressedTarget == null) return;
        Target t = inspector.snapNow(deepest, at);
        if (t == null || !t.same(pressedTarget)) return;
        clicked(e, t);
    }

    /** A press and release on {@code t}: a check of a check box, else a click, held back to see whether it becomes a double click. */
    private void clicked(MouseEvent e, Target t) {
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
        if (fieldPopup != null && fieldPopup.opener == null && !fieldPopup.stepped && fieldPopup.openedBy(t.component)) {
            // the popup opened on the press: this click is what opened it
            fieldPopup.opener = step("click", t);
            return;
        }
        flushClick();
        pendingClick = step("click", t);
        pendingClickTarget = t;
        pendingTimer = new Timer(450, x -> flushClick());
        pendingTimer.setRepeats(false);
        pendingTimer.start();
    }

    /**
     * The press on list or tree {@code c}: the entry or row the click selects, as its target by {@code item} (taken now:
     * a suggestion popup may hide before the release), or why it cannot be one.
     */
    private void pressEntry(Component c, Point at) {
        if (c instanceof JList) {
            pressedEntry = Inspector.listIndexAt((JList<?>) c, at);
        } else {
            JTree tree = (JTree) c;
            pressedEntry = Inspector.treeRowAt(tree, at);
            if (pressedEntry < 0) return;
            pressedOnLabel = Inspector.onRowLabel(tree, pressedEntry, at);
            pressedExpanded = tree.isExpanded(pressedEntry);
            pressedSelection = tree.getSelectionPaths();
            pressedPath = TreeWidget.pathText(tree, pressedEntry);
        }
        if (pressedEntry < 0) return;
        try {
            pressedTarget = c instanceof JList ? inspector.listItem((JList<?>) c, pressedEntry) : inspector.treeRow((JTree) c, pressedEntry);
        } catch (Inspector.NotRecordable why) {
            pressedRefusal = why.getMessage();
        }
    }

    /**
     * The release on list or tree {@code c}: a click on the pressed entry when it lands on the same entry. Beside a tree
     * row's label (the expand arrow, the indent, right of it) the click counts only for what the tree did with it: a row
     * this click selected is a click on that row, a row it expanded or collapsed is the arrow, which no step can say, and
     * a click that did neither is no step. Most looks and feels act on the press; Aqua's arrow acts on the release, after
     * this listener: when the tree has not acted yet, it is asked again once the release is handled.
     */
    private void releasedEntry(MouseEvent e, Component c, Point at) {
        int entry = c instanceof JList ? Inspector.listIndexAt((JList<?>) c, at) : Inspector.treeRowAt((JTree) c, at);
        if (c != pressed || pressedEntry < 0 || entry != pressedEntry) return;
        Target target = pressedTarget;
        String refusal = pressedRefusal;
        if (!(c instanceof JTree) || pressedOnLabel) {
            entryClicked(e, target, refusal);
            return;
        }
        JTree tree = (JTree) c;
        boolean expanded = pressedExpanded;
        TreePath[] selection = pressedSelection;
        String arrow = "the expand arrow of '" + pressedPath + "': double-click the row instead";
        if (besideRow(e, tree, entry, expanded, selection, target, refusal, arrow)) return;
        boolean listening = installed;
        SwingUtilities.invokeLater(() -> {
            // later, outside eventDispatched: the same guards - nothing once it stopped listening (Done) or during the
            // agent's step, and a throw never reaches the app's EDT handler
            if (installed != listening || Inspector.agentIsActing()) return;
            try {
                besideRow(e, tree, entry, expanded, selection, target, refusal, arrow);
            } catch (RuntimeException failed) {
                System.err.println("swing-ui-agent inspector: " + failed);
            }
        });
    }

    /**
     * Decides a click beside tree row {@code row} by what the tree did since the press ({@code expanded} and
     * {@code selection} were its state then): true when it did something, false when nothing (yet).
     */
    private boolean besideRow(MouseEvent e, JTree tree, int row, boolean expanded, TreePath[] selection, Target target,
                              String refusal, String arrow) {
        boolean toggled = tree.isExpanded(row) != expanded;
        boolean selected = tree.isRowSelected(row) && !Arrays.equals(selection, tree.getSelectionPaths());
        if (!toggled && !selected) return false;
        // a double click in a wide row's indent: its first click selected the row, its second toggles it, as on the label
        boolean doubleOnRow = e.getClickCount() >= 2 && pendingClick != null && target != null && target.same(pendingClickTarget);
        if (toggled && !doubleOnRow) refuse(e, arrow);
        else entryClicked(e, target, refusal);
        return true;
    }

    /** A click on a list entry or tree row: {@code target}, or {@code refusal} when it cannot be recorded. */
    private void entryClicked(MouseEvent e, Target target, String refusal) {
        if (refusal != null) refuse(e, refusal);
        else if (target != null) clicked(e, target);
    }

    /** What the person did and no step can say: shown and listed as not recorded, after a click still held back. */
    private void refuse(MouseEvent e, String line) {
        flushClick();
        if (e.getClickCount() >= 2 && line.equals(lastRefused)) return;
        lastRefused = line;
        notRecorded.accept(line);
    }

    /**
     * Text fields take focus, menus and combos record through their own paths, popups are not clicked into - except a
     * list in one that is no combo's (a field's suggestions): a pick there is a click on its entry.
     */
    private static boolean ignoredForClicks(Component c) {
        if (c instanceof JMenuItem) return true;
        JPopupMenu popup = (JPopupMenu) SwingUtilities.getAncestorOfClass(JPopupMenu.class, c);
        if (popup != null && (!(c instanceof JList) || popup instanceof ComboPopup)) return true;
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
        // keys inside a cell's composite editor (arrows, digits, Enter, Escape) edit the cell; its value is the step
        // an editor that ended the edit by itself (on each spinner change): the Enter that follows on its table is the
        // person's way of committing it, already part of that type step
        if (code == KeyEvent.VK_ENTER && !command && endedEdit != null && e.getComponent() == endedEdit) {
            endedEdit = null;
            return;
        }
        endedEdit = null;
        // (after a spinner's arrows the table itself has the focus: its Enter ends the edit, part of that type step)
        if (!command && cellEdit != null
                && (e.getComponent() == cellEdit.table || Inspector.editingTable(e.getComponent()) == cellEdit.table)) {
            return;
        }
        if (code == KeyEvent.VK_ENTER && inText && !(editing instanceof JTextArea) && !command) {
            flushTyping(true);
            return;
        }
        if (code == KeyEvent.VK_TAB && !command) return;
        // Escape closing a field's popup: the popup is no step, so neither is what closes it
        if (code == KeyEvent.VK_ESCAPE && !command && fieldPopup != null) return;
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
                    out(step);
                }
            });
        } else {
            emitStep(step);
        }
    }

    private void focus(FocusEvent e) {
        Component c = e.getComponent();
        if (e.getID() == FocusEvent.FOCUS_GAINED && c instanceof JTextComponent) {
            JTable edited = Inspector.editingTable(c);
            if (edited != null && c.getParent() != edited) {
                // the text field of a spinner in a cell: the cell's value at the end of the edit is the step
                flushTyping(false);
                editing = null;
                watchCellEdit(edited);
                return;
            }
            if (c != editing) {
                flushTyping(false);
                startEditing((JTextComponent) c);
            }
        } else if (e.getID() == FocusEvent.FOCUS_LOST && c == editing) {
            // into the palette (a sentence typed in the Steps list): the person has not left the app's field, it gets
            // the keyboard back
            if (e.getOppositeComponent() != null && Overlay.ours(e.getOppositeComponent())) return;
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
        out(step);
    }

    private void emitStep(Map<String, Object> step) {
        flushClick();
        out(step);
    }

    /**
     * Hands {@code step} over. While a field's popup is open the step is the popup's doing (a suggestion picked, a
     * menu item chosen): the click that opened the popup goes first, and the field's new text is no step of its own.
     */
    private void out(Map<String, Object> step) {
        if (fieldPopup != null) {
            fieldPopup.stepped = true;
            Map<String, Object> opener = fieldPopup.opener;
            fieldPopup.opener = null;
            if (opener != null) emit.accept(opener);
        }
        emit.accept(step);
    }

    private void menuItemReleased(JMenuItem item) {
        if (inMenuBar(item)) return;
        List<String> path = path(item);
        if (path.isEmpty()) return;
        if (contextTarget == null) {
            // a button's dropdown: the click on the button opened it, the choice is a pick down its path
            Map<String, Object> step = new LinkedHashMap<String, Object>();
            step.put("action", "pick");
            step.put("path", path);
            emitStep(step);
            return;
        }
        Map<String, Object> step = step("context_menu", contextTarget);
        step.put("path", path);
        emitStep(step);
        contextTarget = null;
    }

    /** {@code c} as a button of a standard option pane (a JOptionPane's Yes, OK, or its own options), else null. */
    private static AbstractButton optionPaneButton(Component c) {
        if (!(c instanceof AbstractButton) || c instanceof JMenuItem) return null;
        return SwingUtilities.getAncestorOfClass(javax.swing.JOptionPane.class, c) != null ? (AbstractButton) c : null;
    }

    /** Starts watching the cell {@code table} is editing, unless it already is; a cell before it is done with. */
    private void watchCellEdit(JTable table) {
        int row = table.getEditingRow();
        int column = table.getEditingColumn();
        if (cellEdit != null && cellEdit.table == table && cellEdit.row == row && cellEdit.column == column) return;
        if (cellEdit != null) cellEdit.stopWatching();
        cellEdit = null;
        Target cell = inspector.cell(table, row, column);
        if (cell == null) return;
        flushClick();
        cellEdit = new CellEdit(table, row, column, cell);
    }

    /**
     * One cell under a composite editor. When the table drops its editor, a value that changed (the edit was stopped,
     * not cancelled) becomes a {@code type} with {@code commit}, as the agent records typing into such a cell.
     */
    private final class CellEdit implements java.beans.PropertyChangeListener {
        final JTable table;
        final int row;
        final int column;
        private final Target target;
        private final int modelRow;
        private final int modelColumn;
        private final String before;

        CellEdit(JTable table, int row, int column, Target target) {
            this.table = table;
            this.row = row;
            this.column = column;
            this.target = target;
            this.modelRow = table.convertRowIndexToModel(row);
            this.modelColumn = table.convertColumnIndexToModel(column);
            this.before = value();
            table.addPropertyChangeListener("tableCellEditor", this);
        }

        private String value() {
            return String.valueOf(table.getModel().getValueAt(modelRow, modelColumn));
        }

        @Override
        public void propertyChange(java.beans.PropertyChangeEvent e) {
            if (e.getNewValue() != null) return;
            stopWatching();
            if (cellEdit == this) cellEdit = null;
            String now = value();
            if (now.equals(before)) return;
            Map<String, Object> step = step("type", target);
            step.put("text", now);
            step.put("commit", true);
            emitStep(step);
            endedEdit = table;
        }

        void stopWatching() {
            table.removePropertyChangeListener("tableCellEditor", this);
        }
    }

    /** A popup opened or closed, or what is selected in one changed. */
    private void popupsChanged() {
        MenuElement[] path = MenuSelectionManager.defaultManager().getSelectedPath();
        JPopupMenu top = path.length > 0 && path[0] instanceof JPopupMenu ? (JPopupMenu) path[0] : null;
        if (top == openPopup) return;
        openPopup = top;
        if (fieldPopup != null && fieldPopup.popup != top) fieldPopupClosed();
        if (top != null && fieldPopup == null && !Inspector.agentIsActing()) fieldPopupOpened(top);
    }

    /**
     * {@code popup} opened from a text field, or from a button it holds or that stands beside it alone (a calendar):
     * its text now is what the popup may change. The click that opened it is held back - the field's new value will
     * say what the popup did.
     */
    private void fieldPopupOpened(JPopupMenu popup) {
        Component invoker = popup.getInvoker();
        if (invoker == null || popup instanceof ComboPopup || Overlay.ours(popup) || inMenuBar(popup)) return;
        Container picker = pickerOf(invoker);
        if (picker == null) return;
        JTextComponent field = picker instanceof JTextComponent ? (JTextComponent) picker : onlyField(picker);
        Target target = inspector.component(field);
        if (target == null) return;
        FieldPopup fp = new FieldPopup(popup, invoker, picker, field, target);
        if (pendingClick != null && fp.openedBy(pendingClickTarget.component)) {
            if (pendingTimer != null) pendingTimer.stop();
            fp.opener = pendingClick;
            pendingClick = null;
            pendingClickTarget = null;
        }
        fieldPopup = fp;
    }

    /**
     * The field's popup closed. When the popup made no step of its own and the person typed nothing meanwhile, a field
     * whose text changed gets it as one {@code type}, as if typed, and the click that opened the popup is no step: the
     * replay types the value without opening the popup (whatever month a calendar would show then). When nothing
     * changed, nothing is recorded. An app that sets the value after hiding its popup is looked at again once the event
     * is handled.
     */
    private void fieldPopupClosed() {
        FieldPopup fp = fieldPopup;
        fieldPopup = null;
        if (fp.stepped || (editing == fp.field && typed)) {
            if (fp.opener != null) emit.accept(fp.opener);
            return;
        }
        if (typedByPopup(fp)) return;
        boolean listening = installed;
        SwingUtilities.invokeLater(() -> {
            // the same guards as later work elsewhere: nothing once it stopped listening, nothing during the agent's step
            if (installed != listening || Inspector.agentIsActing()) return;
            try {
                typedByPopup(fp);
            } catch (RuntimeException failed) {
                System.err.println("swing-ui-agent inspector: " + failed);
            }
        });
    }

    /** True when {@code fp}'s field has new text, which then became its {@code type}. */
    private boolean typedByPopup(FieldPopup fp) {
        String now = fp.field.getText();
        if (now.equals(fp.before)) return false;
        Map<String, Object> step = step("type", fp.target);
        step.put("text", now);
        // a formatted field takes typed text as its value on Enter (which, the text being edited, presses no default
        // button); a plain field's text is its value
        if (fp.field instanceof JFormattedTextField) step.put("commit", true);
        emitStep(step);
        if (editing == fp.field) editingText = now;
        return true;
    }

    /**
     * What a popup opened from {@code invoker} belongs to: the text field the invoker is or lies in, else the nearest
     * container around the invoker that holds exactly one field a person can type into (a picker: its field and its
     * button); null when the nearest one holding any holds several (a form), or none does.
     */
    static Container pickerOf(Component invoker) {
        for (Component c = invoker; c != null && !(c instanceof Window) && !(c instanceof JRootPane); c = c.getParent()) {
            if (c instanceof JTextComponent) return typable((JTextComponent) c) ? (Container) c : null;
            int fields = fieldsIn(c);
            if (fields == 1) return (Container) c;
            if (fields > 1) return null;
        }
        return null;
    }

    private static int fieldsIn(Component c) {
        if (c instanceof JTextComponent) return typable((JTextComponent) c) ? 1 : 0;
        if (!(c instanceof Container) || c instanceof JPopupMenu) return 0;
        int n = 0;
        for (Component k : ((Container) c).getComponents()) n += fieldsIn(k);
        return n;
    }

    private static JTextComponent onlyField(Component c) {
        if (c instanceof JTextComponent) return typable((JTextComponent) c) ? (JTextComponent) c : null;
        if (!(c instanceof Container)) return null;
        for (Component k : ((Container) c).getComponents()) {
            JTextComponent f = onlyField(k);
            if (f != null) return f;
        }
        return null;
    }

    private static boolean typable(JTextComponent c) {
        return c.isEditable() && c.isEnabled() && c.isVisible() && !(c instanceof JPasswordField);
    }

    /** A popup the app opened from a text field (or its picker), and that field's text when it opened. */
    private static final class FieldPopup {
        final JPopupMenu popup;
        final Component invoker;
        final Container picker;
        final JTextComponent field;
        final Target target;
        final String before;
        /** The click that opened it, held back; null when none is (or it went out already). */
        Map<String, Object> opener;
        /** Something was recorded while it was open: that is what it did. */
        boolean stepped;

        FieldPopup(JPopupMenu popup, Component invoker, Container picker, JTextComponent field, Target target) {
            this.popup = popup;
            this.invoker = invoker;
            this.picker = picker;
            this.field = field;
            this.target = target;
            this.before = field.getText();
        }

        /** Whether a click on {@code c} is one that opens this popup: on its invoker or within its picker. */
        boolean openedBy(Component c) {
            return c != null && (SwingUtilities.isDescendingFrom(c, invoker) || SwingUtilities.isDescendingFrom(c, picker));
        }
    }

    private void menuAction(ActionEvent e) {
        if (Inspector.agentIsActing() || !(e.getSource() instanceof JMenuItem)) return;
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

    private static boolean inMenuBar(Component item) {
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
        // the person's click on a row: the session keeps the row's position beside its value
        if (t.row >= 0 && t.locator.row != null) step.put(Inspector.ROW_INDEX, t.row);
        // and what the element was, for a vocabulary's patterns: kept inside the recording, never in a file
        if (t.seen != null) step.put(Inspector.SEEN, t.seen);
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
