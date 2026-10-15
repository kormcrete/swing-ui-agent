package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.widgets.Screenshots;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Graphics;
import java.awt.Insets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Export…: the person writes the recording, without an agent, as a recorded test file (name and folder; Choose… opens a
 * folder dialog of the palette's own) or as a Java test (class and package). Recorded test comes first and is where the
 * panel opens unless they chose Java test before: it is what a person showing the app makes, and it runs without
 * compiling. Saving over the recorded test the recording came from
 * reads "Save"; any other name "Export". A refused export keeps the panel open with the reason, so they can fix it and
 * try again; a folder that cannot be written opens Choose… by itself.
 */
final class ExportPanel extends JPanel {

    /** The kinds the panel writes. */
    static final String JAVA = "java";
    static final String RECORDED = "recorded";

    /** Picks a folder, starting in {@code start}; {@code picked} gets it, never called when the person cancels. */
    interface FolderChooser {
        void choose(Path start, Consumer<Path> picked);
    }

    private final JTextField className = new JTextField(22);
    private final JTextField packageName = new JTextField(22);
    private final JTextField testName = new JTextField(22);
    private final JTextField folder = new JTextField(22);
    private final JToggleButton recordedKind = toggle("Recorded test");
    private final JToggleButton javaKind = toggle("Java test");
    private final JPanel kinds = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JLabel title = new JLabel();
    private final JLabel message = new JLabel(" ");
    private final CardLayout cards = new CardLayout();
    private final JPanel fields = new JPanel(cards);
    private final JButton export;
    private final Path defaultFolder;
    private final Path loadedTest;
    private String kind;
    private FolderDialog dialog;
    /** Where Choose… goes: the folder dialog, or a test's stand-in. */
    private FolderChooser chooser = (start, picked) -> {
        if (dialog != null && dialog.isDisplayable()) {
            dialog.toFront();
            return;
        }
        dialog = FolderDialog.choose(this, start, picked);
    };

    /**
     * {@code folder}: where a recorded test goes unless the person picks another; {@code loadedTest}: the recorded test file the
     * recording came from (null: none), which opens the panel on Recorded test; else it opens on {@code kind}, Recorded
     * test unless that is {@link #JAVA}.
     */
    ExportPanel(String suggestedClass, String suggestedPackage, Path folder, Path loadedTest, String kind,
                BiConsumer<String, String> onExport, BiConsumer<String, Path> onExportRecorded, Runnable close) {
        super(new BorderLayout(0, 6));
        this.defaultFolder = loadedTest != null ? loadedTest.getParent() : folder;
        this.loadedTest = loadedTest == null ? null : loadedTest.toAbsolutePath().normalize();
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Overlay.NAVY, 2),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        setBackground(Color.WHITE);

        ButtonGroup group = new ButtonGroup();
        group.add(recordedKind);
        group.add(javaKind);
        recordedKind.addActionListener(e -> kind(RECORDED));
        javaKind.addActionListener(e -> kind(JAVA));
        kinds.setOpaque(false);
        kinds.add(recordedKind);
        kinds.add(javaKind);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setOpaque(false);
        top.add(kinds, BorderLayout.NORTH);
        top.add(title, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        className.setText(suggestedClass);
        packageName.setText(suggestedPackage);
        JPanel java = form();
        row(java, 0, "Class", className);
        row(java, 1, "Package", packageName);
        testName.setText(loadedTest != null ? baseName(loadedTest) : "recorded-test");
        this.folder.setText(defaultFolder == null ? "" : defaultFolder.toString());
        JPanel where = new JPanel(new BorderLayout(4, 0));
        where.setOpaque(false);
        where.add(this.folder, BorderLayout.CENTER);
        where.add(small("Choose…", this::choose), BorderLayout.EAST);
        JPanel recordedForm = form();
        row(recordedForm, 0, "Name", testName);
        row(recordedForm, 1, "Folder", where);
        fields.setOpaque(false);
        fields.add(java, JAVA);
        fields.add(recordedForm, RECORDED);
        JPanel center = new JPanel(new BorderLayout());
        center.setOpaque(false);
        center.add(fields, BorderLayout.CENTER);
        message.setForeground(Overlay.NO);
        message.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        center.add(message, BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);

        export = small("Export", () -> {
            message.setText(" ");
            if (JAVA.equals(this.kind)) {
                onExport.accept(className.getText().trim(), packageName.getText().trim());
                return;
            }
            Path to = folderPath();
            if (to == null) {
                refused("'" + this.folder.getText().trim() + "' is not a folder", false);
                return;
            }
            onExportRecorded.accept(testName.getText().trim(), to);
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.setOpaque(false);
        buttons.add(small("Cancel", close));
        buttons.add(export);
        add(buttons, BorderLayout.SOUTH);
        for (JTextField f : new JTextField[] {className, packageName, testName, this.folder}) {
            f.addActionListener(e -> export.doClick());
        }
        DocumentListener relabel = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { relabel(); }
            @Override public void removeUpdate(DocumentEvent e) { relabel(); }
            @Override public void changedUpdate(DocumentEvent e) { relabel(); }
        };
        testName.getDocument().addDocumentListener(relabel);
        this.folder.getDocument().addDocumentListener(relabel);
        kind(loadedTest != null ? RECORDED : JAVA.equals(kind) ? JAVA : RECORDED);
    }

    /** Which kind the panel writes now: {@link #JAVA} or {@link #RECORDED}. */
    String kind() {
        return kind;
    }

    /** The kind toggles' labels, in the order the person sees them. */
    List<String> kindLabels() {
        List<String> out = new ArrayList<String>();
        for (Component c : kinds.getComponents()) out.add(((JToggleButton) c).getText());
        return out;
    }

    /** Shows the fields of {@code kind}, as if the person pressed its toggle. */
    void kind(String kind) {
        this.kind = JAVA.equals(kind) ? JAVA : RECORDED;
        boolean recorded = RECORDED.equals(this.kind);
        (recorded ? recordedKind : javaKind).setSelected(true);
        cards.show(fields, this.kind);
        title.setText(recorded ? "Export as a recorded test" : "Export as a Java test");
        message.setText(" ");
        relabel();
        JTextField first = recorded ? testName : className;
        SwingUtilities.invokeLater(() -> {
            first.requestFocusInWindow();
            first.selectAll();
        });
    }

    /** What the fields hold now: class, package. */
    String[] names() {
        return new String[] {className.getText(), packageName.getText()};
    }

    /** What the recorded test fields hold now: name, folder. */
    String[] recordedFields() {
        return new String[] {testName.getText(), folder.getText()};
    }

    /** As if the person typed {@code name} as the recorded test's name. */
    void testName(String name) {
        testName.setText(name);
    }

    /** The action button's label: "Save" over the recorded test the recording came from, else "Export". */
    String actionLabel() {
        return export.getText();
    }

    /** The message under the fields: blank, or why the last export wrote nothing. */
    String message() {
        return message.getText();
    }

    /** As if the person pressed Export (or Save). */
    void submit() {
        export.doClick();
    }

    /** Why the last export wrote nothing. */
    void refused(String why) {
        refused(why, false);
    }

    /** Why the last export wrote nothing; {@code folder}: the folder cannot be written, so Choose… opens by itself. */
    void refused(String why, boolean folder) {
        message.setText(folder ? why + " - choose another folder." : "Not exported: " + why);
        revalidate();
        if (folder) choose();
    }

    /** Choose…: picks a folder for the recorded test, starting in the folder field's. */
    void choose() {
        Path start = folderPath();
        chooser.choose(start != null ? start : defaultFolder, picked -> folder.setText(picked.toString()));
    }

    /** Puts another chooser behind Choose… (tests). */
    void chooser(FolderChooser chooser) {
        this.chooser = chooser;
    }

    /** The panel closes: an open folder dialog goes with it. */
    void closeChooser() {
        if (dialog != null) dialog.dispose();
        dialog = null;
    }

    /** The folder field as a path: blank is the folder offered; a relative one is under the working directory. */
    private Path folderPath() {
        String text = folder.getText().trim();
        if (text.isEmpty()) return defaultFolder;
        try {
            return Paths.get("").toAbsolutePath().resolve(text).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** "Save" when the recorded test fields name the file the recording came from. */
    private void relabel() {
        boolean save = false;
        if (RECORDED.equals(kind) && loadedTest != null) {
            String name = testName.getText().trim();
            if (name.endsWith(".json")) name = name.substring(0, name.length() - ".json".length());
            Path to = folderPath();
            try {
                save = !name.isEmpty() && to != null && to.resolve(name + ".json").normalize().equals(loadedTest);
            } catch (InvalidPathException e) {
                save = false;
            }
        }
        export.setText(save ? "Save" : "Export");
    }

    private static String baseName(Path file) {
        String n = file.getFileName().toString();
        return n.endsWith(".json") ? n.substring(0, n.length() - ".json".length()) : n;
    }

    private static JPanel form() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        return p;
    }

    private static void row(JPanel to, int y, String label, Component field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 0, 2, 8);
        to.add(new JLabel(label), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(2, 0, 2, 0);
        to.add(field, c);
    }

    private static JButton small(String label, Runnable action) {
        JButton b = new JButton(label);
        b.setFocusable(false);
        b.setMargin(new Insets(2, 6, 2, 6));
        b.addActionListener(e -> action.run());
        return b;
    }

    private static JToggleButton toggle(String label) {
        JToggleButton b = new JToggleButton(label);
        b.setFocusable(false);
        b.setMargin(new Insets(1, 6, 1, 6));
        return b;
    }

    /** Not in a screenshot: a capture leaves the palette out. */
    @Override
    public void paint(Graphics g) {
        if (!Screenshots.capturing()) super.paint(g);
    }
}
