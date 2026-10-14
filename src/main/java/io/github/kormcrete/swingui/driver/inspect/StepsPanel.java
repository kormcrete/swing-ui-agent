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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * The recording as the person sees it: every step, agent's ("agent: …") and theirs, with what its last replay did
 * (● recorded, ✎ changed since and not replayed, ▶ running, ✓ replayed, ✗ failed), and the buttons to replay all (for
 * a loaded flow: run it, from a reset), replay from a step, redo from a step (cut the recording there, replay up to
 * it, record on), delete one, or undo the last edit - the agent's or the person's. Rows know their step's id: what the
 * person deletes or redoes is the step they saw, even when the agent changed the recording since.
 */
final class StepsPanel extends JPanel {

    private final DefaultListModel<String> model = new DefaultListModel<String>();
    private final JList<String> list = new JList<String>(model);
    private final JLabel title = new JLabel("Steps");
    /** Per row: its step's id and mark; the pending row has neither. */
    private final List<Integer> ids = new ArrayList<Integer>();
    private final List<String> rowMarks = new ArrayList<String>();
    private final JButton undo;
    private final javax.swing.JComboBox<String> speed =
            new javax.swing.JComboBox<String>(new String[] {"Fast", "Normal", "Slow", "Step by step"});
    private final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));

    /** {@code replayAllLabel}: what the first button says - "▶ Replay all", or "▶ Run" for a loaded flow. */
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
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected, boolean focus) {
                boolean pending = index >= rowMarks.size();
                String mark = pending ? "…" : rowMarks.get(index);
                super.getListCellRendererComponent(l, value, index, selected, focus);
                if (pending) {
                    setForeground(Color.GRAY);
                    setFont(getFont().deriveFont(Font.ITALIC));
                    return this;
                }
                if (!selected) {
                    setForeground("✗".equals(mark) ? Overlay.NO : "✓".equals(mark) ? Overlay.YES.darker()
                            : "▶".equals(mark) ? Overlay.HOVER : Color.DARK_GRAY);
                }
                return this;
            }
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(460, 200));
        add(scroll, BorderLayout.CENTER);
        buttons.setOpaque(false);
        buttons.add(small(replayAllLabel, replayAll));
        buttons.add(small("▶ From selected", () -> { if (selected() >= 0) replayFrom.accept(selected()); }));
        buttons.add(small("↺ Redo from selected", () -> { if (selected() >= 0) redoFrom.accept(selected()); }));
        buttons.add(small("✕ Delete", () -> { if (selected() >= 0) delete.accept(selected()); }));
        this.undo = small("↶ Undo", undo);
        this.undo.setEnabled(false);
        buttons.add(this.undo);
        add(buttons, BorderLayout.SOUTH);
    }

    /** The selected step's row; -1 for none, or for the pending row, which is no step yet. */
    int selected() {
        int i = list.getSelectedIndex();
        return i >= ids.size() ? -1 : i;
    }

    /** The id of the step in {@code row}, as last shown; -1 for the pending row or no row. */
    int idAt(int row) {
        return row >= 0 && row < ids.size() ? ids.get(row) : -1;
    }

    /** Whether Undo can be pressed: something can be taken back. */
    boolean undoEnabled() {
        return undo.isEnabled();
    }

    /** What Undo would take back, as its tooltip says; null when nothing. */
    String undoTip() {
        return undo.getToolTipText();
    }

    /** Selects step {@code i}, as if the person clicked it. */
    void select(int i) {
        list.setSelectedIndex(i);
    }

    /** The first button's label: what replaying all is called here. */
    String replayAllLabel() {
        return ((JButton) buttons.getComponent(0)).getText();
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

    /** The rows it lists now, as the person reads them ("2  ✓  agent: click(…)"): the steps, then what is pending. */
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
     * The steps, each with its replay mark from {@code marks} (by step id; none: ● or, for an edited step, ✎), then -
     * greyed - what the person is still doing (typing not yet committed), when anything. {@code undoLabel}: what Undo
     * would take back, null when nothing.
     */
    void show(List<Inspector.StepLine> steps, Map<Integer, String> marks, int select, String pending, String undoLabel) {
        int keep = select >= 0 ? select : list.getSelectedIndex();
        ids.clear();
        rowMarks.clear();
        List<String> rows = new ArrayList<String>();
        for (int i = 0; i < steps.size(); i++) {
            Inspector.StepLine s = steps.get(i);
            String mark = marks.get(s.id);
            if (mark == null) mark = s.edited ? "✎" : "●";
            ids.add(s.id);
            rowMarks.add(mark);
            rows.add(row(i + 1, mark, s));
        }
        if (pending != null) rows.add("   …  " + pending);
        undo.setEnabled(undoLabel != null);
        undo.setToolTipText(undoLabel);
        boolean same = rows.size() == model.size();
        for (int i = 0; same && i < rows.size(); i++) same = rows.get(i).equals(model.get(i));
        title.setText("Steps (" + steps.size() + ")");
        if (same && select < 0) {
            list.repaint();
            return;
        }
        model.clear();
        for (String r : rows) model.addElement(r);
        if (keep >= 0 && keep < rows.size()) {
            list.setSelectedIndex(keep);
            list.ensureIndexIsVisible(keep);
        } else if (!rows.isEmpty()) {
            list.ensureIndexIsVisible(rows.size() - 1);
        }
        list.repaint();
    }

    /** Row {@code n} as the person reads it; a check is indented under its action, an agent's step says so. */
    static String row(int n, String mark, Inspector.StepLine s) {
        String who = "agent".equals(s.author) ? "agent: " : "";
        // a check belongs to the action before it: indented under it, replayed with it step by step
        String text = s.check() ? "      └ " + who + "check: " + s.line.substring(7, s.line.length() - 1) : who + s.line;
        return n + "  " + mark + "  " + text;
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
