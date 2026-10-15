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
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Load recorded test…: the recorded test files in a folder (Choose… opens the palette's folder dialog; the folder field is editable too),
 * each with its description and step count; one that cannot be read is greyed with its reason and cannot be loaded.
 * Load (or a double click) makes the chosen recorded test the recording; a refused load keeps the panel open with the reason.
 */
final class RecordedTestsPanel extends JPanel {

    private final JLabel title = new JLabel("Load a recorded test");
    private final JTextField folder = new JTextField(22);
    private final DefaultListModel<Inspector.RecordedTestEntry> model = new DefaultListModel<Inspector.RecordedTestEntry>();
    private final JList<Inspector.RecordedTestEntry> list = new JList<Inspector.RecordedTestEntry>(model);
    private final JLabel empty = new JLabel("No recorded tests in this folder.");
    private final JLabel notice = new JLabel();
    private final JLabel message = new JLabel(" ");
    private final JButton load = small("Load", this::load);
    private final JButton cancel;
    private final Consumer<Path> relist;
    private final Consumer<Path> onLoad;
    private Path shown;
    private FolderDialog dialog;
    /** Where Choose… goes: the folder dialog, or a test's stand-in. */
    private ExportPanel.FolderChooser chooser = (start, picked) -> {
        if (dialog != null && dialog.isDisplayable()) {
            dialog.toFront();
            return;
        }
        dialog = FolderDialog.choose(this, start, picked);
    };

    /**
     * {@code recorded tests}: those in {@code folder}; {@code current}: how many steps the recording has now (loading replaces
     * them); {@code relist} lists another folder (answered with {@link #show}); {@code onLoad} loads a recorded test file.
     */
    RecordedTestsPanel(Path folder, List<Inspector.RecordedTestEntry> tests, int current, Consumer<Path> relist, Consumer<Path> onLoad,
               Runnable close) {
        super(new BorderLayout(0, 6));
        this.relist = relist;
        this.onLoad = onLoad;
        this.cancel = small("Cancel", close);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Overlay.NAVY, 2),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        setBackground(Color.WHITE);

        title.setFont(title.getFont().deriveFont(Font.BOLD));
        JPanel where = new JPanel(new BorderLayout(4, 0));
        where.setOpaque(false);
        where.add(new JLabel("Folder"), BorderLayout.WEST);
        where.add(this.folder, BorderLayout.CENTER);
        where.add(small("Choose…", this::choose), BorderLayout.EAST);
        this.folder.addActionListener(e -> {
            Path to = folderPath();
            if (to == null) {
                message.setText("'" + this.folder.getText().trim() + "' is not a folder");
                return;
            }
            relist.accept(to);
        });
        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setOpaque(false);
        top.add(title, BorderLayout.NORTH);
        top.add(where, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected, boolean focus) {
                Inspector.RecordedTestEntry f = (Inspector.RecordedTestEntry) value;
                super.getListCellRendererComponent(l, text(f), index, selected, focus);
                if (f.problem != null) {
                    setForeground(Color.GRAY);
                    setFont(getFont().deriveFont(Font.ITALIC));
                }
                return this;
            }
        });
        list.addListSelectionListener(e -> load.setEnabled(loadable(list.getSelectedIndex())));
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && list.locationToIndex(e.getPoint()) == list.getSelectedIndex()) load();
            }
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(460, 160));
        empty.setForeground(Color.GRAY);
        notice.setForeground(Color.DARK_GRAY);
        message.setForeground(Overlay.NO);
        JPanel lines = new JPanel(new GridLayout(0, 1));
        lines.setOpaque(false);
        lines.add(empty);
        lines.add(notice);
        lines.add(message);
        JPanel center = new JPanel(new BorderLayout(0, 4));
        center.setOpaque(false);
        center.add(scroll, BorderLayout.CENTER);
        center.add(lines, BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.setOpaque(false);
        buttons.add(cancel);
        buttons.add(load);
        add(buttons, BorderLayout.SOUTH);

        notice.setText(current > 0 ? "Loading replaces the current recording (" + current
                + (current == 1 ? " step" : " steps") + ")." : null);
        notice.setVisible(current > 0);
        show(folder, tests);
    }

    /** The recorded tests of {@code folder}, the folder field showing it; the first one that can be loaded is selected. */
    void show(Path folder, List<Inspector.RecordedTestEntry> tests) {
        shown = folder;
        this.folder.setText(folder == null ? "" : folder.toString());
        model.clear();
        for (Inspector.RecordedTestEntry f : tests) model.addElement(f);
        empty.setVisible(tests.isEmpty());
        message.setText(" ");
        int first = -1;
        for (int i = 0; i < tests.size() && first < 0; i++) if (tests.get(i).problem == null) first = i;
        if (first >= 0) list.setSelectedIndex(first); else list.clearSelection();
        load.setEnabled(first >= 0);
        revalidate();
        repaint();
    }

    /** Its title. */
    String title() {
        return title.getText();
    }

    /** The folder field's text. */
    String folder() {
        return folder.getText();
    }

    /** The rows as the person reads them. */
    List<String> rows() {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < model.size(); i++) out.add(text(model.get(i)));
        return out;
    }

    /** Whether row {@code i} is a recorded test that can be loaded. */
    boolean loadable(int i) {
        return i >= 0 && i < model.size() && model.get(i).problem == null;
    }

    /** Selects row {@code i}, as if the person clicked it. */
    void select(int i) {
        list.setSelectedIndex(i);
    }

    /** "Loading replaces the current recording (N steps).", or null when the recording has no steps. */
    String notice() {
        return notice.isVisible() ? notice.getText() : null;
    }

    /** The message under the list: blank, or why the last load did nothing. */
    String message() {
        return message.getText();
    }

    /** Load: the selected recorded test, when it can be loaded. */
    void load() {
        int i = list.getSelectedIndex();
        if (!loadable(i)) return;
        message.setText(" ");
        onLoad.accept(model.get(i).file);
    }

    /** As if the person pressed Cancel. */
    void cancel() {
        cancel.doClick();
    }

    /** Why the last load did nothing. */
    void refused(String why) {
        message.setText("Not loaded: " + why);
        revalidate();
    }

    /** Choose…: picks a folder, starting in the folder field's, and lists its recorded tests. */
    void choose() {
        Path start = folderPath();
        chooser.choose(start != null ? start : shown, picked -> {
            folder.setText(picked.toString());
            relist.accept(picked);
        });
    }

    /** Puts another chooser behind Choose… (tests). */
    void chooser(ExportPanel.FolderChooser chooser) {
        this.chooser = chooser;
    }

    /** The panel closes: an open folder dialog goes with it. */
    void closeChooser() {
        if (dialog != null) dialog.dispose();
        dialog = null;
    }

    /** The folder field as a path: blank is the folder shown; a relative one is under the working directory. */
    private Path folderPath() {
        String text = folder.getText().trim();
        if (text.isEmpty()) return shown;
        try {
            return Paths.get("").toAbsolutePath().resolve(text).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static String text(Inspector.RecordedTestEntry f) {
        if (f.problem != null) return f.name + "  -  " + f.problem;
        String about = f.description == null || f.description.trim().isEmpty() ? "" : "  -  " + f.description.trim();
        return f.name + about + "  (" + f.steps + (f.steps == 1 ? " step)" : " steps)");
    }

    private static JButton small(String label, Runnable action) {
        JButton b = new JButton(label);
        b.setFocusable(false);
        b.setMargin(new Insets(2, 6, 2, 6));
        b.addActionListener(e -> action.run());
        return b;
    }

    /** Not in a screenshot: a capture leaves the palette out. */
    @Override
    public void paint(Graphics g) {
        if (!Screenshots.capturing()) super.paint(g);
    }
}
