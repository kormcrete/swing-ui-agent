package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.widgets.Screenshots;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * The recording as the person sees it: steps and their checks, numbered {@code 7} and {@code 7.1}, each with what its
 * last replay did (● recorded, ✎ changed since and not replayed, ▶ running, ✓ replayed, ✗ failed), the agent's marked
 * "agent: ", a sentence in place of the technical line (which shows on hover), and a group as a header row above its
 * indented steps. Below, the buttons to replay all from a reset, as the exported test runs (for a loaded recorded test:
 * run it), replay from a step, redo from a step (cut the recording there, replay up to it from a reset, record on),
 * delete a step or a check, or undo the last edit - the agent's or the person's; the row menu (right click, or ⋯)
 * describes, ungroups and says how a table row is named. Rows know their record's id: what the person deletes, redoes
 * or describes is what they saw, even when the agent changed the recording since. The callbacks get flat record
 * indexes (the session's order), never rows: a header row is no record.
 */
final class StepsPanel extends JPanel {

    /** The row menu's edits; ids are the records the person saw. */
    interface Edits {
        /** Opens the ask panel titled {@code title} for the sentence of records {@code fromId}-{@code toId}. */
        void describe(String title, int fromId, int toId, String initial);

        void ungroup(int id);

        /** {@code by}: "value" or "position". */
        void rowBy(int id, String by);
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

        Row(int record, Inspector.StepLine line, String label, String mark, int group) {
            this.record = record;
            this.line = line;
            this.label = label;
            this.mark = mark;
            this.group = group;
        }

        boolean header() {
            return line == null && group != 0;
        }

        int id() {
            return line == null ? -1 : line.id;
        }

        /** The step's number: "7" for "7" and "7.1". */
        String step() {
            return stepOf(label);
        }
    }

    private final DefaultListModel<String> model = new DefaultListModel<String>();
    private final JList<String> list = new JList<String>(model);
    private final JLabel title = new JLabel("Steps");
    /** As last shown: the rows, and the records and their labels in the session's order. */
    private final List<Row> rows = new ArrayList<Row>();
    private List<Inspector.StepLine> records = new ArrayList<Inspector.StepLine>();
    private List<String> labels = new ArrayList<String>();
    private final JButton undo;
    private final javax.swing.JComboBox<String> speed =
            new javax.swing.JComboBox<String>(new String[] {"Fast", "Normal", "Slow", "Step by step"});
    private final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JButton more;
    private Edits edits = new Edits() {
        @Override public void describe(String title, int fromId, int toId, String initial) { }
        @Override public void ungroup(int id) { }
        @Override public void rowBy(int id, String by) { }
    };
    /** The row menu opened last (shown when the list is). */
    private StepMenu menu;
    /** Set while a header's selection is widened to its steps, so the widening does not widen again. */
    private boolean widening;

    /**
     * {@code replayAllLabel}: what the first button says - "▶ Replay all", or "▶ Run" for a loaded recorded test.
     * {@code replayFrom} and {@code redoFrom} get the record of the selected row's step (its action, or record 0 for a
     * step of checks only), {@code delete} the selected row's own record.
     */
    StepsPanel(String replayAllLabel, Runnable replayAll, IntConsumer replayFrom, IntConsumer redoFrom, IntConsumer delete,
               Runnable undo, Runnable close) {
        super(new BorderLayout(0, 6));
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
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) widenHeaders(); });
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(l, value, index, selected, focus);
                Row row = index < rows.size() ? rows.get(index) : null;
                // the technical line on hover (the JList asks its renderer)
                setToolTipText(tooltipAt(index));
                if (row == null || row.line == null && !row.header()) {
                    setForeground(Color.GRAY);
                    setFont(getFont().deriveFont(Font.ITALIC));
                    return this;
                }
                if (row.header()) {
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

            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = list.locationToIndex(e.getPoint());
                Rectangle cell = row < 0 ? null : list.getCellBounds(row, row);
                if (cell != null && cell.contains(e.getPoint())) openMenu(row, e.getX(), e.getY());
            }
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(460, 200));
        add(scroll, BorderLayout.CENTER);
        buttons.setOpaque(false);
        buttons.add(small(replayAllLabel, replayAll, "from a reset, as the exported test runs"));
        buttons.add(small("▶ From selected", () -> { if (selected() >= 0) replayFrom.accept(stepRecordAt(selected())); },
                "from the selected step on; from the first one, after a reset"));
        buttons.add(small("↺ Redo from selected", () -> { if (selected() >= 0) redoFrom.accept(stepRecordAt(selected())); },
                "cut the recording at the selected step, replay the steps before it from a reset, record on"));
        buttons.add(small("✕ Delete", () -> { if (selected() >= 0) delete.accept(recordAt(selected())); },
                "a step with its checks, or one check"));
        this.undo = small("↶ Undo", undo);
        this.undo.setEnabled(false);
        buttons.add(this.undo);
        more = small("⋯", () -> {
            int row = selected();
            if (row >= 0) openMenu(row, 0, 0);
        }, "describe, ungroup, how a table row is named");
        buttons.add(more);
        add(buttons, BorderLayout.SOUTH);
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
        if (r.line != null) return r.line.line;
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
        if (row < 0 || row >= rows.size()) return;
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
        Row last = sel.get(sel.size() - 1);
        Set<String> steps = new LinkedHashSet<String>();
        for (Row r : sel) steps.add(r.step());
        if (steps.size() >= 2) {
            // a group of the whole steps the rows touch; the same steps again rename their group
            boolean oneGroup = first.line.groupId != 0;
            for (Row r : sel) oneGroup &= r.line.groupId == first.line.groupId;
            String initial = oneGroup ? first.line.groupSay : null;
            m.item("Describe these steps…", true, () -> edits.describe("Describe steps " + first.step() + "-" + last.step(),
                    first.id(), last.id(), initial));
        } else if (sel.size() == 1 && first.line.check) {
            m.item("Describe this check…", true, () -> edits.describe("Describe check " + first.label, first.id(), first.id(),
                    first.line.say));
        } else {
            int action = stepRecordAt(rows.indexOf(first));
            Inspector.StepLine a = records.get(action);
            // a step of checks only has no sentence of its own: its checks are described one by one
            if (!a.check) {
                m.item("Describe this step…", true, () -> edits.describe("Describe step " + first.step(), first.id(), last.id(),
                        a.say));
            }
        }
        m.item("Ungroup", first.line.groupId != 0, () -> edits.ungroup(first.id()));
        Inspector.RowLine row = first.line.row;
        if (row != null) {
            m.separator();
            String value = row.valueKnown ? Inspector.rowText(row.value) : "replay it once";
            String position = row.indexKnown ? "row " + row.index : "replay it once";
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
        int last = header;
        while (last + 1 < rows.size() && rows.get(last + 1).line != null && rows.get(last + 1).line.groupId == group) last++;
        return new int[] {Math.min(header + 1, last), last};
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
                && !rows.get(end).header() && list.isSelectedIndex(end);
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
            texts.add(row(numbers.get(i), mark, s));
        }
        if (pending != null) {
            shown.add(new Row(-1, null, null, "…", 0));
            texts.add("   …  " + pending);
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
        undo.setEnabled(undoLabel != null);
        undo.setToolTipText(undoLabel);
        title.setText("Steps (" + Recording.stepCount(steps) + ")");
        boolean same = texts.size() == model.size();
        for (int i = 0; same && i < texts.size(); i++) same = texts.get(i).equals(model.get(i));
        rows.clear();
        rows.addAll(shown);
        records = new ArrayList<Inspector.StepLine>(steps);
        labels = numbers;
        if (same && select < 0 && from == list.getMinSelectionIndex() && to == list.getMaxSelectionIndex()) {
            list.repaint();
            return;
        }
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

    /** The step of a label: "7" for "7" and "7.1". */
    static String stepOf(String label) {
        if (label == null) return null;
        int dot = label.indexOf('.');
        return dot < 0 ? label : label.substring(0, dot);
    }

    /**
     * A record's row as the person reads it: its label and mark, "agent: " for the agent's, and its sentence or else
     * its line; a check is indented under its step, a group's member by two more.
     */
    static String row(String label, String mark, Inspector.StepLine s) {
        String who = "agent".equals(s.author) ? "agent: " : "";
        String indent = s.groupId != 0 ? "  " : "";
        if (!s.check()) return indent + label + "  " + mark + "  " + who + (s.say != null ? s.say : s.line);
        return indent + "      └ " + label + "  " + mark + "  " + who + (s.say != null ? s.say : "check: " + checkText(s.line));
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
