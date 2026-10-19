package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.widgets.Screenshots;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

/**
 * The recording as the person sees it: steps and their checks, numbered {@code 7} and {@code 7.1}, each with what its
 * last replay did (● recorded, ✎ changed since and not replayed, ▶ running, ✓ replayed, ✗ failed), the agent's marked
 * "agent: ", a sentence in place of the technical line (which shows on hover), and a group as a header row above its
 * indented steps. Below, the buttons to replay all from a reset, as the exported test runs (for a loaded recorded test:
 * run it), replay from a step, redo from a step (cut the recording there, replay up to it from a reset, record on),
 * delete a step or a check, or undo the last edit - the agent's or the person's; the row menu (right click, or ⋯)
 * describes, ungroups and says how a table row is named. A step of the app plugin's vocabulary (a {@code do} step) is
 * one row, "◆" and its sentence with its values in bold; a fold - one folding made of recorded steps - has a ▸ that
 * shows those steps under it, greyed (▾ hides them), and its row menu renames it, unfolds it into those steps or deletes
 * it. Under the list, what the last steps could become. A double click on a row, or F2 or Enter, names it in place: a
 * field over the row's text holds its sentence (Enter saves, Esc cancels, a click elsewhere saves a change); several
 * steps get a header row of their own for a group's sentence. Rows know their record's id: what the person deletes,
 * redoes or describes is what they saw, even when the agent changed the recording since. The callbacks get flat record
 * indexes (the session's order), never rows: a header row is no record.
 */
final class StepsPanel extends JPanel {

    /** The row menu's edits and the sentences typed in place; ids are the records the person saw. */
    interface Edits {
        /**
         * The person starts naming a row: null when they may not now (a replay runs), else what gives the keyboard back
         * to the app's component when the field ends - Enter, Esc, a click on the palette, the panel going, its records
         * gone - and says whether that component took it.
         */
        BooleanSupplier naming();

        /**
         * Sets the sentence of records {@code fromId}-{@code toId} (one check, one step, or the steps of a group);
         * completes with what became of it, on any thread.
         */
        CompletableFuture<Inspector.Result> describe(int fromId, int toId, String say);

        void ungroup(int id);

        /** {@code by}: "value" or "position". */
        void rowBy(int id, String by);

        /** Puts the steps of the fold with record {@code id} back in its place. */
        void unfold(int id);
    }

    /** One row: a record (step or check), a group's header, or what the person is still doing (pending). */
    private static final class Row {
        /** The record's flat index; -1 for a header or the pending row. */
        final int record;
        final Inspector.StepLine line;
        /** "7" or "7.1"; null for a header or the pending row. */
        final String label;
        final String mark;
        /** For a header: its group. */
        final int group;
        /** The header row a group's sentence is typed in before the group exists; no header otherwise. */
        final boolean fresh;
        /** One of the steps a fold stands for, shown under it (greyed): no record of its own. */
        final boolean raw;

        Row(int record, Inspector.StepLine line, String label, String mark, int group) {
            this(record, line, label, mark, group, false, false);
        }

        Row(int record, Inspector.StepLine line, String label, String mark, int group, boolean fresh) {
            this(record, line, label, mark, group, fresh, false);
        }

        private Row(int record, Inspector.StepLine line, String label, String mark, int group, boolean fresh, boolean raw) {
            this.record = record;
            this.line = line;
            this.label = label;
            this.mark = mark;
            this.group = group;
            this.fresh = fresh;
            this.raw = raw;
        }

        /** One of a fold's steps, under it; in the fold's group so that the group's rows stay one run. */
        static Row raw(int group) {
            return new Row(-1, null, null, null, group, false, true);
        }

        boolean header() {
            return line == null && group != 0 && !raw;
        }

        int id() {
            return line == null ? -1 : line.id;
        }

        /** The step's number: "7" for "7" and "7.1". */
        String step() {
            return stepOf(label);
        }
    }

    /** What naming a selection names: its sentence's records, where the field shows and what it starts with. */
    private static final class Spec {
        /** The row menu's item for it. */
        final String item;
        final boolean group;
        final int fromId;
        final int toId;
        /** The record whose row holds the field; for a group, the first record under its header. */
        final int hostId;
        /** A group that exists: its header holds the field; 0 for a record or a new group (a header row of its own). */
        final int groupId;
        final String initial;
        /** What the empty field shows greyed: the row's technical line, or a new group's steps. */
        final String hint;

        Spec(String item, boolean group, int fromId, int toId, int hostId, int groupId, String initial, String hint) {
            this.item = item;
            this.group = group;
            this.fromId = fromId;
            this.toId = toId;
            this.hostId = hostId;
            this.groupId = groupId;
            this.initial = initial == null ? "" : initial;
            this.hint = hint;
        }

        boolean fresh() {
            return group && groupId == 0;
        }

        /** Whether {@code o} names the same records in the same row. */
        boolean same(Spec o) {
            return group == o.group && fromId == o.fromId && toId == o.toId && hostId == o.hostId && groupId == o.groupId;
        }
    }

    /** A sentence being typed in place of a row's text. */
    private static final class Naming {
        final Spec spec;
        final BooleanSupplier giveBack;
        final SentenceField field;
        /** The row the field covers, as last shown. */
        int row = -1;
        /** Enter or a click elsewhere handed the sentence on and its result is not back yet. */
        boolean saving;

        Naming(Spec spec, BooleanSupplier giveBack, SentenceField field) {
            this.spec = spec;
            this.giveBack = giveBack;
            this.field = field;
        }

        boolean changed() {
            return !field.getText().trim().equals(spec.initial.trim());
        }
    }

    /** One line over a row, the row's technical line greyed while it is empty. */
    private static final class SentenceField extends JTextField {
        private final String hint;

        SentenceField(String text, String hint) {
            super(text);
            this.hint = hint;
        }

        String hint() {
            return hint;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (!getText().isEmpty() || hint == null) return;
            g.setColor(Color.GRAY);
            g.setFont(getFont().deriveFont(Font.ITALIC));
            FontMetrics fm = g.getFontMetrics();
            Insets in = getInsets();
            g.drawString(hint, in.left, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
        }
    }

    private final DefaultListModel<String> model = new DefaultListModel<String>();
    private final JList<String> list = new JList<String>(model) {
        @Override
        protected void processMouseEvent(MouseEvent e) {
            // seen before the list's own handling collapses it: a double click on a selected row names all of them
            if (e.getID() == MouseEvent.MOUSE_PRESSED && e.getClickCount() == 1) {
                int extend = InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK;
                pressedOn = (e.getModifiersEx() & extend) == 0 && getMinSelectionIndex() >= 0
                        ? new int[] {getMinSelectionIndex(), getMaxSelectionIndex(), shownRows} : null;
            }
            super.processMouseEvent(e);
        }
    };
    private final JLabel title = new JLabel("Steps");
    /** Under the list: what the last steps could become; hidden when nothing. */
    private final JLabel hint = new JLabel();
    /** The folds whose steps are shown under them (▾), by record id. */
    private final Set<Integer> opened = new LinkedHashSet<Integer>();
    /** ✕ Delete's callback, also the row menu's Delete. */
    private final IntConsumer deleteRecord;
    /** As last shown: the rows, and the records and their labels in the session's order. */
    private final List<Row> rows = new ArrayList<Row>();
    private List<Inspector.StepLine> records = new ArrayList<Inspector.StepLine>();
    private List<String> labels = new ArrayList<String>();
    private final JButton undo;
    private final javax.swing.JComboBox<String> speed =
            new javax.swing.JComboBox<String>(new String[] {"Fast", "Normal", "Slow", "Step by step"});
    private final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JButton more;
    private final JButton delete;
    private Edits edits = new Edits() {
        @Override public BooleanSupplier naming() { return () -> false; }
        @Override public CompletableFuture<Inspector.Result> describe(int fromId, int toId, String say) {
            return CompletableFuture.completedFuture(new Inspector.Result(true, "described"));
        }
        @Override public void ungroup(int id) { }
        @Override public void rowBy(int id, String by) { }
        @Override public void unfold(int id) { }
    };
    /** The row menu opened last (shown when the list is). */
    private StepMenu menu;
    /** While a replay or run goes on the steps are only shown: nothing here changes them or starts another. */
    private boolean running;
    /** Set while a header's selection is widened to its steps, so the widening does not widen again. */
    private boolean widening;
    /** The sentence being typed in place of a row; null when none. One at a time. */
    private Naming naming;
    /**
     * The selection when the last click began (first and last row, and {@link #shownRows} then); null when none, or the
     * click changed it on purpose.
     */
    private int[] pressedOn;
    /** Counts the times the rows changed: a selection kept from before a change names other rows. */
    private int shownRows;
    /** The app's component the list last took the keyboard from: naming gives it back there. */
    private Component appFocus;
    /** Puts the keyboard in a field of the palette's: the field that opens, or one a refusal left open. */
    Predicate<Component> takeFocus = Component::requestFocusInWindow;
    /** As last shown, so the field's own header row can come and go between the recording's refreshes. */
    private List<Inspector.StepLine> lastSteps = new ArrayList<Inspector.StepLine>();
    private Map<Integer, String> lastMarks = new HashMap<Integer, String>();
    private String lastPending;
    private String lastUndo;

    /**
     * {@code replayAllLabel}: what the first button says - "▶ Replay all", or "▶ Run" for a loaded recorded test.
     * {@code replayFrom} and {@code redoFrom} get the record of the selected row's step (its action, or record 0 for a
     * step of checks only), {@code delete} the selected row's own record.
     */
    StepsPanel(String replayAllLabel, Runnable replayAll, IntConsumer replayFrom, IntConsumer redoFrom, IntConsumer delete,
               Runnable undo, Runnable close) {
        super(new BorderLayout(0, 6));
        this.deleteRecord = delete;
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Overlay.NAVY, 2),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        setBackground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        JButton x = small("Close", close);
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        top.add(title, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        right.setOpaque(false);
        right.add(new JLabel("Replay speed"));
        speed.setSelectedIndex(1);
        speed.setFocusable(false);
        right.add(speed);
        right.add(x);
        top.add(right, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);
        list.setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) widenHeaders();
            deleteFits();
        });
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(l, value, index, selected, focus);
                Row row = index < rows.size() ? rows.get(index) : null;
                // the technical line on hover (the JList asks its renderer)
                setToolTipText(tooltipAt(index));
                if (row == null || row.line == null && !row.header() && !row.fresh) {
                    setForeground(Color.GRAY);
                    setFont(getFont().deriveFont(Font.ITALIC));
                    return this;
                }
                // a domain step's values in bold
                if (row.line != null && row.line.domain() && !row.line.params.isEmpty()) {
                    setText(html(lead(row), text(row.line), row.line.params.values()));
                }
                // a new group's header looks like any group's while its sentence is typed
                if (row.header() || row.fresh) {
                    setFont(getFont().deriveFont(Font.BOLD));
                    if (!selected) setForeground(Overlay.NAVY);
                    return this;
                }
                String mark = row.mark;
                if (!selected) {
                    setForeground("✗".equals(mark) ? Overlay.NO : "✓".equals(mark) ? Overlay.YES.darker()
                            : "▶".equals(mark) ? Overlay.HOVER : Color.DARK_GRAY);
                }
                return this;
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { popup(e); }
            @Override public void mouseReleased(MouseEvent e) { popup(e); }

            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 1 && SwingUtilities.isLeftMouseButton(e)) {
                    int row = rowAt(e);
                    if (row >= 0 && onTriangle(row, e.getX())) toggleRaw(row);
                    return;
                }
                if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) return;
                int row = rowAt(e);
                // a quick second click on ▸ shows or hides the steps again; it names nothing
                if (row < 0 || onTriangle(row, e.getX())) {
                    if (row >= 0) toggleRaw(row);
                    return;
                }
                int[] before = pressedOn;
                // rows that came or went between the presses (a new group's header closing with its field, a step
                // recorded) make the first press's rows other rows: only the row under the mouse counts then
                if (before != null && before[2] == shownRows && before[1] > before[0] && row >= before[0] && row <= before[1]) {
                    list.setSelectionInterval(before[0], before[1]);
                } else if (!list.isSelectedIndex(row)) {
                    list.setSelectedIndex(row);
                }
                describeSelection();
            }

            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = rowAt(e);
                if (row >= 0) openMenu(row, e.getX(), e.getY());
            }

            private int rowAt(MouseEvent e) {
                int row = list.locationToIndex(e.getPoint());
                Rectangle cell = row < 0 ? null : list.getCellBounds(row, row);
                return cell != null && cell.contains(e.getPoint()) ? row : -1;
            }
        });
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "describe");
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "describe");
        list.getActionMap().put("describe", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { describeSelection(); }
        });
        // → shows a fold's steps, ← hides them, as on a tree
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "showRaw");
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "hideRaw");
        list.getActionMap().put("showRaw", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { showRaw(list.getMinSelectionIndex(), true); }
        });
        list.getActionMap().put("hideRaw", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { showRaw(list.getMinSelectionIndex(), false); }
        });
        list.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { remember(e.getOppositeComponent()); }
        });
        // the field follows its row when the list is laid out anew
        list.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { placeField(); }
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(460, 200));
        add(scroll, BorderLayout.CENTER);
        hint.setForeground(Color.GRAY);
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC));
        hint.setVisible(false);
        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.setOpaque(false);
        south.add(hint, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);
        buttons.setOpaque(false);
        buttons.add(small(replayAllLabel, replayAll, "from a reset, as the exported test runs"));
        buttons.add(small("▶ From selected", () -> { if (selected() >= 0) replayFrom.accept(stepRecordAt(selected())); },
                "from the selected step on; from the first one, after a reset"));
        buttons.add(small("↺ Redo from selected", () -> { if (selected() >= 0) redoFrom.accept(stepRecordAt(selected())); },
                "cut the recording at the selected step, replay the steps before it from a reset, record on"));
        this.delete = small("✕ Delete", () -> { if (selected() >= 0 && oneStep()) delete.accept(recordAt(selected())); },
                DELETE_TIP);
        buttons.add(this.delete);
        this.undo = small("↶ Undo", undo);
        this.undo.setEnabled(false);
        buttons.add(this.undo);
        more = small("⋯", () -> {
            int row = selected();
            if (row >= 0) openMenu(row, 0, 0);
        }, "describe, ungroup, how a table row is named");
        buttons.add(more);
        add(south, BorderLayout.SOUTH);
    }

    private static final String DELETE_TIP = "a step with its checks, or one check";

    /** ✕ Delete deletes one step or one check: greyed, saying so, while the selection spans several steps. */
    private void deleteFits() {
        boolean one = oneStep();
        delete.setEnabled(one && !running);
        delete.setToolTipText(one ? DELETE_TIP : "select one step or check");
    }

    /** Whether the selected records are all of one step (or none is selected). */
    private boolean oneStep() {
        Set<String> steps = new LinkedHashSet<String>();
        for (Row r : selectedRows()) steps.add(r.step());
        return steps.size() <= 1;
    }

    /** Whether ✕ Delete can be pressed now, and what its tooltip says. */
    boolean deleteEnabled() {
        return delete.isEnabled();
    }

    String deleteTip() {
        return delete.getToolTipText();
    }

    /** Where the row menu's edits go. */
    void edits(Edits edits) {
        this.edits = edits;
    }

    /**
     * The selected rows' first record row (a header selects its steps: their first); -1 for none, or for the pending
     * row, which is no step yet.
     */
    int selected() {
        int from = list.getMinSelectionIndex();
        if (from < 0) return -1;
        for (int i = from; i <= list.getMaxSelectionIndex() && i < rows.size(); i++) {
            if (rows.get(i).line != null) return i;
        }
        return -1;
    }

    /** The id of the record in {@code row}, as last shown; -1 for a header, the pending row or no row. */
    int idAt(int row) {
        return row >= 0 && row < rows.size() ? rows.get(row).id() : -1;
    }

    /** The flat record index of {@code row}; -1 for a header, the pending row or no row. */
    int recordAt(int row) {
        return row >= 0 && row < rows.size() ? rows.get(row).record : -1;
    }

    /** The record of {@code row}'s step: its action, or record 0 for a step of checks only; -1 for no record. */
    int stepRecordAt(int row) {
        int r = recordAt(row);
        if (r < 0) return -1;
        String step = stepOf(labels.get(r));
        while (r > 0 && step.equals(stepOf(labels.get(r - 1)))) r--;
        return r;
    }

    /** The id of record {@code record} as last shown; -1 when there is none. */
    int idOfRecord(int record) {
        return record >= 0 && record < records.size() ? records.get(record).id : -1;
    }

    /** The ids of the selected rows' records, in order (headers and the pending row have none). */
    int[] selectedIds() {
        List<Integer> out = new ArrayList<Integer>();
        for (Row r : selectedRows()) out.add(r.id());
        int[] ids = new int[out.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = out.get(i);
        return ids;
    }

    /** Selects rows {@code from} to {@code to}, as the person's shift-click would. */
    void selectRange(int from, int to) {
        list.setSelectionInterval(from, to);
    }

    /** What hovering {@code row} shows: a record's technical line, a header's steps; null for the pending row. */
    String tooltipAt(int row) {
        if (row < 0 || row >= rows.size()) return null;
        Row r = rows.get(row);
        if (r.line != null && r.line.folded()) {
            return r.line.line + " - from " + r.line.raw.size() + " steps: " + String.join("; ", r.line.raw);
        }
        if (r.line != null) return r.line.line;
        if (r.raw) return model.get(row).trim().substring(2);
        if (!r.header()) return null;
        int[] span = groupSpan(row);
        String first = rows.get(span[0]).step();
        String last = rows.get(span[1]).step();
        return first.equals(last) ? "step " + first : "steps " + first + "-" + last;
    }

    /**
     * The row menu for {@code row}, as a right click there opens it: a row outside the selection is selected first (a
     * header selects its steps). Shown when the list is.
     */
    void openMenu(int row) {
        Rectangle cell = row < 0 ? null : list.getCellBounds(row, row);
        openMenu(row, cell == null ? 0 : cell.x + 20, cell == null ? 0 : cell.y + cell.height);
    }

    /** The row menu opened last, null before any. */
    StepMenu menu() {
        return menu;
    }

    /** The title: "Steps (n)", n the number of steps (checks are in their steps). */
    String title() {
        return title.getText();
    }

    private void openMenu(int row, int x, int y) {
        if (running || row < 0 || row >= rows.size()) return;
        if (!list.isSelectedIndex(row)) list.setSelectedIndex(row);
        List<Row> sel = selectedRows();
        if (sel.isEmpty()) return;
        menu = menuFor(sel);
        if (list.isShowing()) {
            if (x == 0 && y == 0) menu.show(more, 0, more.getHeight());
            else menu.show(list, x, y);
        }
    }

    /** What the person can do with the records {@code sel} (in order, at least one). */
    private StepMenu menuFor(List<Row> sel) {
        StepMenu m = new StepMenu();
        Row first = sel.get(0);
        Set<String> steps = new LinkedHashSet<String>();
        for (Row r : sel) steps.add(r.step());
        // the same field as a double click opens: here for those who look in the menu first
        Spec describe = specFor(sel);
        if (describe != null) m.item(describe.item, true, () -> open(describe));
        // a domain step: unfolded into the steps it stands for (a fold), or deleted, from its own row
        if (steps.size() == 1 && first.line.domain()) {
            Inspector.StepLine a = first.line;
            if (a.folded()) m.item("Unfold (show the " + a.raw.size() + " steps)", true, () -> edits.unfold(a.id));
            int record = first.record;
            m.item("Delete", true, () -> deleteRecord.accept(record));
        }
        m.item("Ungroup", first.line.groupId != 0, () -> edits.ungroup(first.id()));
        Inspector.RowLine row = first.line.row;
        // how a table row is named is the step's or the check's own: offered for rows of one step
        if (row != null && steps.size() == 1) {
            m.separator();
            // a way not known: the position is learned by one passing replay; a value may also be missing because no
            // column tells the rows apart, which the line cannot tell from not known yet
            String value = row.valueKnown ? Inspector.rowText(row.value) : "not known";
            String position = row.indexKnown ? "row " + (row.index + 1) : "replay it once";
            boolean byValue = "value".equals(row.by);
            m.choice("Row: this value (" + value + ")", byValue, row.valueKnown, () -> {
                if (!byValue) edits.rowBy(first.id(), "value");
            });
            m.choice("Row: by position (" + position + ")", !byValue, row.indexKnown, () -> {
                if (byValue) edits.rowBy(first.id(), "position");
            });
        }
        return m;
    }

    /**
     * What naming the records {@code sel} (in order, at least one) names: rows of several steps a group of the whole
     * steps they touch (the same steps again rename their group), one check alone that check, else the step; null for
     * a step of checks only, which has no sentence of its own (its checks are named one by one).
     */
    private Spec specFor(List<Row> sel) {
        Row first = sel.get(0);
        Row last = sel.get(sel.size() - 1);
        Set<String> steps = new LinkedHashSet<String>();
        for (Row r : sel) steps.add(r.step());
        if (steps.size() >= 2) {
            // a group's whole steps rename it; some of them only get a header of their own (the session says no to a
            // group in a group, under the header the person sees)
            boolean oneGroup = first.line.groupId != 0 && steps.equals(stepsOf(first.line.groupId));
            for (Row r : sel) oneGroup &= r.line.groupId == first.line.groupId;
            return new Spec("Describe these steps…", true, first.id(), last.id(), first.id(), oneGroup ? first.line.groupId : 0,
                    oneGroup ? first.line.groupSay : null, "steps " + first.step() + "-" + last.step());
        }
        if (sel.size() == 1 && first.line.check) {
            return new Spec("Describe this check…", false, first.id(), first.id(), first.id(), 0, first.line.say,
                    "check: " + checkText(first.line.line));
        }
        Inspector.StepLine a = records.get(stepRecordAt(rows.indexOf(first)));
        if (a.check) return null;
        // a domain step's sentence is its name: renaming it changes no step it runs
        return new Spec(a.domain() ? "Rename…" : "Describe this step…", false, first.id(), last.id(), a.id, 0, a.say, a.line);
    }

    /** The steps of group {@code group}, as last shown. */
    private Set<String> stepsOf(int group) {
        Set<String> out = new LinkedHashSet<String>();
        for (int i = 0; i < records.size(); i++) if (records.get(i).groupId == group) out.add(stepOf(labels.get(i)));
        return out;
    }

    /** Double click, F2 or Enter: the selected rows' sentence typed in place, when they can have one. */
    void describeSelection() {
        List<Row> sel = selectedRows();
        if (!sel.isEmpty()) open(specFor(sel));
    }

    /**
     * Opens the field for {@code s} over its row, unless the person may not name rows now. A field open on other rows
     * ends first, as a click elsewhere ends it (its change is saved); one open on the same rows keeps the keyboard.
     */
    private void open(Spec s) {
        if (s == null) return;
        if (naming != null && naming.spec.same(s)) {
            takeFocus.test(naming.field);
            return;
        }
        BooleanSupplier giveBack = edits.naming();
        if (giveBack == null) return;
        // the keyboard goes from the old field to the new one, not back to the app in between
        if (naming != null) commit(naming, false);
        SentenceField field = new SentenceField(s.initial, s.hint);
        Naming n = new Naming(s, giveBack, field);
        field.setFont(list.getFont());
        field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Overlay.NAVY),
                BorderFactory.createEmptyBorder(0, 2, 0, 2)));
        field.setToolTipText("Enter saves, Esc cancels. One line, at most 200 characters. " + (s.group
                ? "A group keeps a sentence: Ungroup ends it." : "Empty removes the sentence."));
        // the field's own keys: Enter never reaches the window's default button, nor Esc the app's dialog
        field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "save");
        field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        field.getActionMap().put("save", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { save(n, true); }
        });
        field.getActionMap().put("cancel", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { end(n, true); }
        });
        field.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { remember(e.getOppositeComponent()); }
            @Override public void focusLost(FocusEvent e) { if (!e.isTemporary()) clickedAway(n, e.getOppositeComponent()); }
        });
        naming = n;
        rerender();
        if (naming != n) return;
        SwingUtilities.invokeLater(() -> {
            if (naming != n) return;
            takeFocus.test(field);
            field.selectAll();
        });
    }

    /**
     * Enter, or a click elsewhere: a change goes on, an unchanged field just closes. Accepted, the field closes -
     * {@code giveBack}: the keyboard goes back to the app's component; refused, it stays open with the keyboard in it,
     * also when a click elsewhere took the keyboard, and the bar says why.
     */
    private void save(Naming n, boolean giveBack) {
        if (naming != n || n.saving) return;
        if (!n.changed()) {
            end(n, giveBack);
            return;
        }
        n.saving = true;
        n.field.setEditable(false);
        edits.describe(n.spec.fromId, n.spec.toId, n.field.getText().trim()).thenAccept(r -> SwingUtilities.invokeLater(() -> {
            if (naming != n) return;
            n.saving = false;
            n.field.setEditable(true);
            if (r.ok) end(n, giveBack);
            // a field left open without the keyboard would take no Enter or Esc, and a double click elsewhere nothing
            else if (!n.field.isFocusOwner()) takeFocus.test(n.field);
        }));
    }

    /**
     * The keyboard went to {@code to}: a change is saved, an unchanged field just closes. A click in the app keeps the
     * keyboard there; a click on the palette (another row) or nowhere gives it back to the app's component.
     */
    private void clickedAway(Naming n, Component to) {
        // the panel left the window and took the field with it: commitNaming ended it before
        if (naming != n || n.saving || getTopLevelAncestor() == null) return;
        save(n, to == null || Overlay.ours(to));
    }

    /**
     * The field closes; {@code giveBack}: the keyboard goes back to the app's component that had it. The field leaves
     * the list only after a focus request is on its way: a focus owner that leaves its window hands the keyboard to the
     * next focusable component (an app field, which the recorder would take for the person leaving the field they
     * typed in), unless a request is pending.
     */
    private void end(Naming n, boolean giveBack) {
        if (naming != n) return;
        naming = null;
        boolean held = n.field.isFocusOwner();
        boolean given = giveBack && n.giveBack.getAsBoolean();
        if (held && !given) {
            // the next field takes it from the list; with no app component to give it back to (or one gone), nobody has it
            if (giveBack) KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner();
            else list.requestFocusInWindow();
        }
        list.remove(n.field);
        rerender();
    }

    /**
     * The panel goes (closed, replaced, moved with the palette) or the person's replay starts: the open field ends as a
     * click elsewhere ends it - a change is saved, a refusal shows on the bar - and the keyboard goes back to the app.
     */
    void commitNaming() {
        if (naming != null) commit(naming, true);
    }

    /** Closes {@code n}, its change handed on without waiting for the answer. */
    private void commit(Naming n, boolean giveBack) {
        // saving: Enter or a click elsewhere handed it on already
        if (n.changed() && !n.saving) edits.describe(n.spec.fromId, n.spec.toId, n.field.getText().trim());
        end(n, giveBack);
    }

    /**
     * The agent's replay starts, or a new recording replaces this one: the open field closes without saving, and the
     * keyboard goes back to the app.
     */
    void cancelNaming() {
        if (naming != null) end(naming, true);
    }

    /** Whether a sentence is being typed in place of a row. */
    boolean naming() {
        return naming != null;
    }

    /** The field a sentence is typed in, null when none. */
    JTextField nameField() {
        return naming == null ? null : naming.field;
    }

    /** What the open field shows greyed while empty; null when none is open. */
    String nameHint() {
        return naming == null ? null : naming.field.hint();
    }

    /** The row the field covers, -1 when none. */
    int namingRow() {
        return naming == null ? -1 : naming.row;
    }

    /** The list, for the person's clicks and keys. */
    JList<String> stepsList() {
        return list;
    }

    /** The app's component the list last took the keyboard from; null when none. */
    Component appFocus() {
        return appFocus;
    }

    private void remember(Component from) {
        if (from != null && !Overlay.ours(from)) appFocus = from;
    }

    /** Shows the recording as last shown again: the field's own header row comes or goes. */
    private void rerender() {
        show(lastSteps, lastMarks, -1, lastPending, lastUndo);
    }

    /** Puts the field over its row's text, after the number and mark, which stay in view. */
    private void placeField() {
        if (naming == null || naming.row < 0 || naming.row >= model.size()) return;
        int row = naming.row;
        Rectangle cell = list.getCellBounds(row, row);
        if (cell == null) return;
        Component shown = list.getCellRenderer().getListCellRendererComponent(list, model.get(row), row, false, false);
        Insets in = shown instanceof JComponent ? ((JComponent) shown).getInsets() : new Insets(0, 0, 0, 0);
        int x = in.left + shown.getFontMetrics(shown.getFont()).stringWidth(prefixAt(row)) - 3;
        x = Math.max(0, Math.min(x, cell.width - 40));
        naming.field.setBounds(cell.x + x, cell.y, Math.max(40, cell.width - x), cell.height);
        if (naming.field.getParent() != list) list.add(naming.field);
        naming.field.revalidate();
        list.repaint();
    }

    /** The text before a row's sentence: number, mark and who for a record, the fold mark for a header. */
    private String prefixAt(int row) {
        Row r = rows.get(row);
        return r.line == null ? "▾ " : lead(r);
    }

    /** What {@code row}'s text begins with before its sentence: number, mark, who, and a domain step's ▸ and ◆. */
    private String lead(Row row) {
        return prefix(row.label, row.mark, row.line) + domainLead(row.line, opened.contains(row.line.id));
    }

    /**
     * Whether {@code x} (in the list) is on the ▸ or ▾ of fold row {@code row}, where a click shows or hides its steps.
     */
    private boolean onTriangle(int row, int x) {
        Row r = row < rows.size() ? rows.get(row) : null;
        if (r == null || r.line == null || !r.line.folded()) return false;
        Rectangle cell = list.getCellBounds(row, row);
        Component shown = list.getCellRenderer().getListCellRendererComponent(list, model.get(row), row, false, false);
        Insets in = shown instanceof JComponent ? ((JComponent) shown).getInsets() : new Insets(0, 0, 0, 0);
        FontMetrics fm = shown.getFontMetrics(shown.getFont());
        int from = cell.x + in.left + fm.stringWidth(prefix(r.label, r.mark, r.line));
        return x >= from - 2 && x <= from + fm.stringWidth("▸ ") + 2;
    }

    /** ▸ on fold row {@code row}: its steps show under it, greyed; ▾ hides them. Nothing for another row. */
    void toggleRaw(int row) {
        if (row < 0 || row >= rows.size()) return;
        Row r = rows.get(row);
        if (r.line == null || !r.line.folded()) return;
        showRaw(row, !opened.contains(r.line.id));
    }

    private void showRaw(int row, boolean show) {
        if (row < 0 || row >= rows.size()) return;
        Row r = rows.get(row);
        if (r.line == null || !r.line.folded() || opened.contains(r.line.id) == show) return;
        if (show) opened.add(r.line.id); else opened.remove(r.line.id);
        rerender();
    }

    /** What the renderer shows for {@code row}: the list's text, or for a domain step with values, HTML with them bold. */
    String rendered(int row) {
        Component c = list.getCellRenderer().getListCellRendererComponent(list, model.get(row), row, false, false);
        return ((JLabel) c).getText();
    }

    /** Whether {@code row} shows greyed: what the person is still doing, or a step a fold stands for. */
    boolean greyed(int row) {
        Component c = list.getCellRenderer().getListCellRendererComponent(list, model.get(row), row, false, false);
        return Color.GRAY.equals(c.getForeground());
    }

    /** What the last steps could become, as shown under the list; null when nothing is. */
    String hint() {
        return hint.isVisible() ? hint.getText() : null;
    }

    /** Shows {@code text} under the list (null hides it): what the last steps could become. */
    void hint(String text) {
        hint.setText(text == null ? "" : text);
        hint.setToolTipText(text);
        if (hint.isVisible() != (text != null)) {
            hint.setVisible(text != null);
            revalidate();
        }
    }

    /** The selected rows that are records, in order. */
    private List<Row> selectedRows() {
        List<Row> out = new ArrayList<Row>();
        int from = list.getMinSelectionIndex();
        if (from < 0) return out;
        for (int i = from; i <= list.getMaxSelectionIndex() && i < rows.size(); i++) {
            if (list.isSelectedIndex(i) && rows.get(i).line != null) out.add(rows.get(i));
        }
        return out;
    }

    /** A selection with a header in it takes in all of that header's steps. */
    private void widenHeaders() {
        if (widening) return;
        int from = list.getMinSelectionIndex();
        int to = list.getMaxSelectionIndex();
        if (from < 0) return;
        int wide = to;
        for (int i = from; i <= to && i < rows.size(); i++) {
            if (rows.get(i).header()) wide = Math.max(wide, groupSpan(i)[1]);
        }
        if (wide == to) return;
        widening = true;
        try {
            list.setSelectionInterval(from, wide);
        } finally {
            widening = false;
        }
    }

    /** The first and last rows of the steps under the header in {@code header}. */
    private int[] groupSpan(int header) {
        int group = rows.get(header).group;
        int first = -1;
        int last = header;
        for (int r = header + 1; r < rows.size(); r++) {
            Row row = rows.get(r);
            // a new group's header typed in among its steps (the session will say no to a group in a group)
            if (row.fresh || row.raw) continue;
            if (row.line == null || row.line.groupId != group) break;
            if (first < 0) first = r;
            last = r;
        }
        return new int[] {first < 0 ? header : first, last};
    }

    /** Whether Undo can be pressed: something can be taken back. */
    boolean undoEnabled() {
        return undo.isEnabled();
    }

    /** What Undo would take back, as its tooltip says; null when nothing. */
    String undoTip() {
        return undo.getToolTipText();
    }

    /** Selects row {@code i}, as if the person clicked it; a header selects its steps. */
    void select(int i) {
        list.setSelectedIndex(i);
    }

    /** The first button's label: what replaying all is called here. */
    String replayAllLabel() {
        return ((JButton) buttons.getComponent(0)).getText();
    }

    /** What the first button's tooltip says it does. */
    String replayAllTip() {
        return ((JButton) buttons.getComponent(0)).getToolTipText();
    }

    /** Presses the button labelled {@code label}, as the person would. */
    void press(String label) {
        for (Component c : buttons.getComponents()) {
            if (c instanceof JButton && label.equals(((JButton) c).getText())) {
                ((JButton) c).doClick();
                return;
            }
        }
        throw new IllegalArgumentException("no button '" + label + "' in the steps panel");
    }

    /**
     * A replay or run starts ({@code on}) or ends: while it goes on the list shows where it is, its buttons and row menu
     * rest, and Close still closes it.
     */
    void running(boolean on) {
        running = on;
        for (Component c : buttons.getComponents()) c.setEnabled(!on);
        if (on) return;
        deleteFits();
        undo.setEnabled(lastUndo != null);
    }

    /** {@code marks} on the rows as last shown: a replay's ▶, ✓ and ✗ while the recording is not read again. */
    void marks(Map<Integer, String> marks) {
        show(lastSteps, marks, -1, null, lastUndo);
    }

    /** Whether the button labelled {@code label} can be pressed now. */
    boolean enabled(String label) {
        for (Component c : buttons.getComponents()) {
            if (c instanceof JButton && label.equals(((JButton) c).getText())) return c.isEnabled();
        }
        throw new IllegalArgumentException("no button '" + label + "' in the steps panel");
    }

    /**
     * The rows it lists now, as the person reads them ("2  ✓  agent: click(…)", "      └ 2.1  ●  check: …",
     * "▾ Save the order"): the steps, then what is pending.
     */
    List<String> rows() {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < model.size(); i++) out.add(model.get(i));
        return out;
    }

    void speed(int index) {
        speed.setSelectedIndex(Math.max(0, Math.min(3, index)));
    }

    /** 0 fast, 1 normal, 2 slow, 3 step by step. */
    int speed() {
        return speed.getSelectedIndex();
    }

    /**
     * The records, each with its replay mark from {@code marks} (by id; none: ● or, for an edited one, ✎), a header
     * above each group, then - greyed - what the person is still doing (typing not yet committed), when anything.
     * {@code undoLabel}: what Undo would take back, null when nothing. {@code select} picks the row of that record
     * index; -1 keeps the selected records - by their ids, so a step the agent put in or took out above them never
     * moves the selection onto another; records that are gone leave nothing selected.
     */
    void show(List<Inspector.StepLine> steps, Map<Integer, String> marks, int select, String pending, String undoLabel) {
        lastSteps = new ArrayList<Inspector.StepLine>(steps);
        lastMarks = new HashMap<Integer, String>(marks);
        lastPending = pending;
        lastUndo = undoLabel;
        Set<Integer> keptIds = new LinkedHashSet<Integer>();
        Set<Integer> keptGroups = new LinkedHashSet<Integer>();
        if (select < 0) {
            for (int id : selectedIds()) keptIds.add(id);
            for (int r = Math.max(0, list.getMinSelectionIndex()); r <= list.getMaxSelectionIndex() && r < rows.size(); r++) {
                if (rows.get(r).header() && list.isSelectedIndex(r)) keptGroups.add(rows.get(r).group);
            }
        }
        int end = rows.size() - 1;
        boolean pendingSelected = select < 0 && keptIds.isEmpty() && end >= 0 && rows.get(end).line == null
                && !rows.get(end).header() && !rows.get(end).raw && list.isSelectedIndex(end);
        List<String> numbers = labels(steps);
        List<Row> shown = new ArrayList<Row>();
        List<String> texts = new ArrayList<String>();
        for (int i = 0; i < steps.size(); i++) {
            Inspector.StepLine s = steps.get(i);
            if (s.groupId != 0 && (i == 0 || steps.get(i - 1).groupId != s.groupId)) {
                shown.add(new Row(-1, null, null, null, s.groupId));
                texts.add("▾ " + (s.groupSay == null ? "" : s.groupSay));
            }
            String mark = marks.get(s.id);
            if (mark == null) mark = s.edited ? "✎" : "●";
            shown.add(new Row(i, s, numbers.get(i), mark, s.groupId));
            boolean open = s.folded() && opened.contains(s.id);
            texts.add(row(numbers.get(i), mark, s, open));
            if (!open) continue;
            for (String raw : s.raw) {
                shown.add(Row.raw(s.groupId));
                texts.add((s.groupId != 0 ? "  " : "") + RAW + raw);
            }
        }
        // a fold that is gone (unfolded, deleted, undone) shows nothing any more
        Set<Integer> folds = new LinkedHashSet<Integer>();
        for (Inspector.StepLine s : steps) if (s.folded()) folds.add(s.id);
        opened.retainAll(folds);
        if (pending != null) {
            shown.add(new Row(-1, null, null, "…", 0));
            texts.add("   …  " + pending);
        }
        // an open field stays with the records it names, by their ids; when the agent took them out it closes
        boolean lost = false;
        if (naming != null) {
            int at = hostIn(shown, naming.spec);
            if (at < 0) {
                lost = true;
            } else if (naming.spec.fresh()) {
                shown.add(at, new Row(-1, null, null, null, 0, true));
                texts.add(at, "▾ ");
            }
            naming.row = at;
        }
        int from = -1;
        int to = -1;
        for (int r = 0; r < shown.size(); r++) {
            Row row = shown.get(r);
            boolean keep = select >= 0 ? row.record == select
                    : row.header() ? keptGroups.contains(row.group) : keptIds.contains(row.id());
            if (keep) {
                if (from < 0) from = r;
                to = r;
            }
        }
        if (pendingSelected && pending != null && from < 0) from = to = shown.size() - 1;
        undo.setEnabled(undoLabel != null && !running);
        undo.setToolTipText(undoLabel);
        title.setText("Steps (" + stepCount(steps) + ")");
        boolean same = texts.size() == model.size();
        for (int i = 0; same && i < texts.size(); i++) same = texts.get(i).equals(model.get(i));
        rows.clear();
        rows.addAll(shown);
        records = new ArrayList<Inspector.StepLine>(steps);
        labels = numbers;
        if (same && select < 0 && from == list.getMinSelectionIndex() && to == list.getMaxSelectionIndex()) {
            list.repaint();
            afterShow(lost);
            return;
        }
        shownRows++;
        widening = true;
        try {
            model.clear();
            for (String t : texts) model.addElement(t);
            if (from >= 0) {
                list.setSelectionInterval(from, to);
                list.ensureIndexIsVisible(from);
            } else {
                list.clearSelection();
                if (!texts.isEmpty()) list.ensureIndexIsVisible(texts.size() - 1);
            }
        } finally {
            widening = false;
        }
        list.repaint();
        afterShow(lost);
    }

    /** The field over its row again, or - its records gone - closed, the keyboard back with the app. */
    private void afterShow(boolean lost) {
        if (lost) end(naming, true); else placeField();
    }

    /**
     * Where the field for {@code s} goes in {@code shown}: its record's row, its group's header, or for a new group the
     * row a header of its own goes above; -1 when the agent took them out meanwhile.
     */
    private static int hostIn(List<Row> shown, Spec s) {
        for (int r = 0; r < shown.size(); r++) {
            Row row = shown.get(r);
            if (s.groupId != 0 ? row.header() && row.group == s.groupId : row.id() == s.hostId) return r;
        }
        return -1;
    }

    /**
     * Each record's label: the session's ("7", "7.1"), else counted from the order - an action starts a step, a check
     * belongs to the step before it, checks before any action are step 1.
     */
    static List<String> labels(List<Inspector.StepLine> steps) {
        List<String> out = new ArrayList<String>(steps.size());
        int step = 0;
        int check = 0;
        for (Inspector.StepLine s : steps) {
            String counted;
            if (s.check()) {
                if (step == 0) step = 1;
                counted = step + "." + ++check;
            } else {
                step++;
                check = 0;
                counted = String.valueOf(step);
            }
            out.add(s.label != null ? s.label : counted);
        }
        return out;
    }

    /** How many steps {@code records} hold: checks are in their steps. */
    static int stepCount(List<Inspector.StepLine> records) {
        List<String> labels = labels(records);
        return labels.isEmpty() ? 0 : Integer.parseInt(stepOf(labels.get(labels.size() - 1)));
    }

    /** The step of a label: "7" for "7" and "7.1". */
    static String stepOf(String label) {
        if (label == null) return null;
        int dot = label.indexOf('.');
        return dot < 0 ? label : label.substring(0, dot);
    }

    /** How a step a fold stands for begins, under it. */
    static final String RAW = "         · ";

    /**
     * A record's row as the person reads it: its label and mark, "agent: " for the agent's, a domain step's ◆ (and a
     * fold's ▸, or ▾ while {@code open} shows its steps), and its sentence or else its line; a check is indented under
     * its step, a group's member by two more.
     */
    static String row(String label, String mark, Inspector.StepLine s, boolean open) {
        return prefix(label, mark, s) + domainLead(s, open) + text(s);
    }

    /** A record's sentence, or else its line ("check: …" for a check). */
    private static String text(Inspector.StepLine s) {
        if (s.say != null) return s.say;
        return s.check() ? "check: " + checkText(s.line) : s.line;
    }

    /** "▸ ◆ " for a fold ("▾ ◆ " while {@code open}), "◆ " for another domain step, "" for the rest. */
    private static String domainLead(Inspector.StepLine s, boolean open) {
        if (!s.domain()) return "";
        return (s.folded() ? (open ? "▾ " : "▸ ") : "") + "◆ ";
    }

    /** {@code lead} and {@code text} as HTML for a list row: {@code values} bold where the text shows them, spaces kept. */
    static String html(String lead, String text, Collection<String> values) {
        boolean[] bold = new boolean[text.length()];
        for (String v : values) {
            if (v == null || v.isEmpty()) continue;
            int at = text.indexOf(v);
            if (at >= 0) for (int k = at; k < at + v.length(); k++) bold[k] = true;
        }
        StringBuilder out = new StringBuilder("<html>").append(escape(lead).replace("  ", "&nbsp; "));
        for (int k = 0; k < text.length(); k++) {
            if (bold[k] && (k == 0 || !bold[k - 1])) out.append("<b>");
            out.append(escape(String.valueOf(text.charAt(k))));
            if (bold[k] && (k == text.length() - 1 || !bold[k + 1])) out.append("</b>");
        }
        return out.append("</html>").toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** A record's row up to its sentence or line. */
    private static String prefix(String label, String mark, Inspector.StepLine s) {
        String who = "agent".equals(s.author) ? "agent: " : "";
        String indent = s.groupId != 0 ? "  " : "";
        return indent + (s.check() ? "      └ " : "") + label + "  " + mark + "  " + who;
    }

    /** What a check checks: its line without {@code expect( )}. */
    static String checkText(String line) {
        return line.startsWith("expect(") && line.endsWith(")") ? line.substring(7, line.length() - 1) : line;
    }

    private static JButton small(String label, Runnable action, String tip) {
        JButton b = small(label, action);
        b.setToolTipText(tip);
        return b;
    }

    private static JButton small(String label, Runnable action) {
        JButton b = new JButton(label);
        b.setFocusable(false);
        b.setMargin(new java.awt.Insets(2, 6, 2, 6));
        b.addActionListener(e -> action.run());
        return b;
    }

    /** Not in a screenshot: a capture leaves the palette out. */
    @Override
    public void paint(Graphics g) {
        if (!Screenshots.capturing()) super.paint(g);
    }
}
