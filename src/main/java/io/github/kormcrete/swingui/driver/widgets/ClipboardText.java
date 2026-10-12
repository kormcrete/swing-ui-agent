package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;

/**
 * The clipboard the app copies to and pastes from, as plain text. A block copied from a spreadsheet
 * arrives as exactly this: cells separated by tabs, rows by line breaks, each value as displayed.
 *
 * <p>Live, this is the SYSTEM clipboard, which the user shares: setting it replaces what they had
 * copied. There is no private one to swap in -- the app reads the toolkit's clipboard directly.</p>
 */
public final class ClipboardText {

    private ClipboardText() {}

    /** Puts {@code text} on {@code clipboard} as a plain string. */
    public static void set(Clipboard clipboard, String text) {
        StringSelection selection = new StringSelection(text);
        try {
            clipboard.setContents(selection, selection);
        } catch (IllegalStateException busy) {
            throw new DriverException(Reason.NOT_ACTIONABLE, "the clipboard is in use by another application");
        }
    }

    /** The clipboard's text; null when it holds none (nothing, or only an image or files). */
    public static String get(Clipboard clipboard) {
        try {
            Transferable contents = clipboard.getContents(null);
            if (contents == null || !contents.isDataFlavorSupported(DataFlavor.stringFlavor)) return null;
            return (String) contents.getTransferData(DataFlavor.stringFlavor);
        } catch (UnsupportedFlavorException | IOException gone) {
            return null;
        } catch (IllegalStateException busy) {
            throw new DriverException(Reason.NOT_ACTIONABLE, "the clipboard is in use by another application");
        }
    }
}
