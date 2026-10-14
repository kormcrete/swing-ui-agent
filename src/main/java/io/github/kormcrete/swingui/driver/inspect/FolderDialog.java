package io.github.kormcrete.swingui.driver.inspect;

import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Window;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Choose…: the person picks the folder a flow goes to. A modeless dialog of the palette's own, owned by the palette's
 * window, so the app stays usable and nothing the agent reads, finds or records ever includes it (its class lives in
 * the palette's package: {@code Roles.agentOwn}, {@code Overlay.ours}). Choose sets the folder; Cancel or closing it
 * leaves it as it was; either way the dialog is disposed. Out of scope: the agent pressing keys without a target while
 * this dialog has the focus (they go to the dialog).
 */
final class FolderDialog extends JDialog {

    private final JFileChooser files = new JFileChooser();
    private Consumer<Path> picked;

    FolderDialog(Window owner) {
        super(owner, "Choose the folder for flows", ModalityType.MODELESS);
        files.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        files.setApproveButtonText("Choose");
        files.addActionListener(e -> {
            Consumer<Path> to = picked;
            File chosen = files.getSelectedFile() != null ? files.getSelectedFile() : files.getCurrentDirectory();
            dispose();
            if (JFileChooser.APPROVE_SELECTION.equals(e.getActionCommand()) && to != null && chosen != null) {
                to.accept(chosen.toPath());
            }
        });
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getContentPane().add(files);
        pack();
    }

    /** Shows the dialog in {@code start} (its nearest existing parent when it does not exist yet). EDT. */
    void open(Path start, Consumer<Path> picked) {
        this.picked = picked;
        Path at = existing(start);
        if (at != null) files.setCurrentDirectory(at.toFile());
        setLocationRelativeTo(getOwner());
        setVisible(true);
    }

    /** Opens a folder dialog owned by {@code near}'s window, in {@code start}; {@code picked} gets the choice. EDT. */
    static FolderDialog choose(Component near, Path start, Consumer<Path> picked) {
        FolderDialog d = new FolderDialog(near == null ? null : SwingUtilities.getWindowAncestor(near));
        d.open(start, picked);
        return d;
    }

    /** {@code p}, or its nearest parent that is an existing folder; null when none is. */
    static Path existing(Path p) {
        for (Path at = p == null ? null : p.toAbsolutePath(); at != null; at = at.getParent()) {
            if (Files.isDirectory(at)) return at;
        }
        return null;
    }
}
