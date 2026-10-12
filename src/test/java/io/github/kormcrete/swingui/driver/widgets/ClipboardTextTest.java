package io.github.kormcrete.swingui.driver.widgets;

import org.junit.jupiter.api.Test;

import java.awt.Image;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/** A private Clipboard stands in for the system one, which a unit test must not overwrite. */
class ClipboardTextTest {

    private final Clipboard clipboard = new Clipboard("test");

    @Test
    void aSpreadsheetBlockGoesOnAndComesBackUnchanged() {
        String block = "7.2\t<0.5\n12\tn/a\n";
        ClipboardText.set(clipboard, block);
        assertThat(ClipboardText.get(clipboard)).isEqualTo(block);
        assertThat(clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)).isTrue();
    }

    @Test
    void anEmptyClipboardHoldsNoText() {
        assertThat(ClipboardText.get(clipboard)).isNull();
    }

    @Test
    void aClipboardHoldingOnlyAnImageHoldsNoText() {
        final Image image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        clipboard.setContents(new Transferable() {
            @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[] {DataFlavor.imageFlavor}; }
            @Override public boolean isDataFlavorSupported(DataFlavor f) { return DataFlavor.imageFlavor.equals(f); }
            @Override public Object getTransferData(DataFlavor f) { return image; }
        }, null);
        assertThat(ClipboardText.get(clipboard)).isNull();
    }
}
