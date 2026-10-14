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
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The recording as the person sees it: every step, agent's and theirs, with what its last replay did
 * (● recorded, ▶ running, ✓ replayed, ✗ failed), and the buttons to replay all, replay from a step, redo from a step
 * (cut the recording there, replay up to it, record on) or delete one.
 */
final class StepsPanel extends JPanel {

    private final DefaultListModel<String> model = new DefaultListModel<String>();
    private final JList<String> list = new JList<String>(model);
    private final JLabel title = new JLabel("Steps");
    private List<String> marks;
    private final javax.swing.JComboBox<String> speed =
            new javax.swing.JComboBox<String>(new String[] {"Fast", "Normal", "Slow", "Step by step"});

    StepsPanel(Runnable replayAll, IntConsumer replayFrom, IntConsumer redoFrom, IntConsumer delete, Runnable close) {
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
                boolean pending = marks != null && index >= marks.size();
                String mark = pending ? "…" : marks != null ? marks.get(index) : "●";
                String text = String.valueOf(value);
                // a check belongs to the action before it: indented under it, replayed with it step by step
                if (text.startsWith("expect(")) text = "      └ check: " + text.substring(7, text.length() - 1);
                super.getListCellRendererComponent(l, (pending ? "   " : (index + 1) + "  ") + mark + "  " + text, index,
                        selected, focus);
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
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.setOpaque(false);
        buttons.add(small("▶ Replay all", replayAll));
        buttons.add(small("▶ From selected", () -> { if (selected() >= 0) replayFrom.accept(selected()); }));
        buttons.add(small("↺ Redo from selected", () -> { if (selected() >= 0) redoFrom.accept(selected()); }));
        buttons.add(small("✕ Delete", () -> { if (selected() >= 0) delete.accept(selected()); }));
        add(buttons, BorderLayout.SOUTH);
    }

    /** The selected step; -1 for none, or for the pending row, which is no step yet. */
    int selected() {
        int i = list.getSelectedIndex();
        return marks != null && i >= marks.size() ? -1 : i;
    }

    void speed(int index) {
        speed.setSelectedIndex(Math.max(0, Math.min(3, index)));
    }

    /** 0 fast, 1 normal, 2 slow, 3 step by step. */
    int speed() {
        return speed.getSelectedIndex();
    }

    /** The steps, then - greyed - what the person is still doing (typing not yet committed), when anything. */
    void show(List<String> steps, List<String> marks, int select, String pending) {
        this.marks = marks;
        int keep = select >= 0 ? select : list.getSelectedIndex();
        List<String> rows = new java.util.ArrayList<String>(steps);
        if (pending != null) rows.add(pending);
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
