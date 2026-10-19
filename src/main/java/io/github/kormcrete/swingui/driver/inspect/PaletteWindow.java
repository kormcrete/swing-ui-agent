package io.github.kormcrete.swingui.driver.inspect;

import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JWindow;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;

/**
 * The palette beside a modal dialog too small to hold it (a confirmation such as "Discard this new order?"): the
 * dialog blocks the main window, so a palette left there could not be pressed - a replay waiting for Next would wait
 * for good. A window of the palette's own, owned by the dialog so the dialog does not block it, right under the dialog
 * (above it when the screen has no room below), as large as the bar and the panel open beside it. It follows the
 * dialog and the bar's width, and never takes the keyboard from the dialog. Its class lives in the palette's package,
 * so nothing the agent reads, finds or records includes it ({@code Roles.agentOwn}, {@code Overlay.ours}). EDT only.
 */
final class PaletteWindow extends JWindow {

    /** Space between the dialog and this window. */
    private static final int GAP = 4;

    /** Follows the dialog's moves and the bar's width (its text changes) while the window lives. */
    private final Timer follow = new Timer(150, e -> fit());
    private boolean gone;

    PaletteWindow(Window dialog) {
        super(dialog);
        // the person's keys stay with the dialog, as with the bar inside a window: pressing the bar never moves the focus
        setFocusableWindowState(false);
        GraphicsConfiguration gc = dialog.getGraphicsConfiguration();
        GraphicsDevice device = gc == null ? null : gc.getDevice();
        if (device != null && device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
            // only the bar and panel show; elsewhere (no such support) the margin around them is the window's own colour
            setBackground(new Color(0, 0, 0, 0));
            ((JComponent) getContentPane()).setOpaque(false);
            getRootPane().setOpaque(false);
        }
        follow.start();
    }

    /**
     * Sizes the window to what the palette shows in it - the bar with an 8 px margin, the panel above it - and puts it
     * beside its dialog; shown while the dialog shows and the palette has something in it (a test run's palette is out).
     */
    void fit() {
        if (gone) return;
        Dimension want = room();
        Window dialog = getOwner();
        if (want == null) {
            if (isVisible()) setVisible(false);
            return;
        }
        Rectangle at = beside(dialog.getBounds(), want, screen(dialog));
        if (!at.equals(getBounds())) {
            setBounds(at);
            // laid out at once, shown or not: the overlay places the bar in the layered pane's new size
            doLayout();
            getRootPane().doLayout();
            for (Component c : getLayeredPane().getComponents()) if (c instanceof Overlay) ((Overlay) c).relayout();
        }
        boolean show = dialog.isShowing();
        if (show != isVisible()) setVisible(show);
    }

    /** Whether the window was disposed: the palette left it. */
    boolean gone() {
        return gone;
    }

    @Override
    public void dispose() {
        gone = true;
        follow.stop();
        super.dispose();
    }

    /** The size the bar and the panel in the window need ({@link Overlay#panelBeside} above the bar); null: none shows. */
    private Dimension room() {
        Dimension bar = null;
        Dimension panel = null;
        JLayeredPane layers = getLayeredPane();
        for (Component c : layers.getComponents()) {
            if (c instanceof Overlay || !c.isVisible()) continue;
            if (c instanceof Bar) {
                if (bar == null) bar = c.getPreferredSize();
            } else if (c instanceof JComponent && c != getRootPane().getContentPane() && c != getRootPane().getJMenuBar()) {
                panel = c.getPreferredSize();
            }
        }
        if (bar == null) return null;
        int w = bar.width + 16;
        int h = bar.height + 16;
        if (panel != null) {
            w = Math.max(w, panel.width + 16);
            h = bar.height + panel.height + 30;
        }
        return new Dimension(w, h);
    }

    /**
     * Where a {@code size} window goes beside {@code dialog} on {@code screen}: centred under it, else above it, shifted
     * into the screen sideways; over the dialog's lower edge only when neither side has room.
     */
    static Rectangle beside(Rectangle dialog, Dimension size, Rectangle screen) {
        int x = dialog.x + (dialog.width - size.width) / 2;
        x = Math.max(screen.x, Math.min(x, screen.x + screen.width - size.width));
        int y = dialog.y + dialog.height + GAP;
        if (y + size.height > screen.y + screen.height) {
            y = dialog.y - GAP - size.height;
            if (y < screen.y) y = screen.y + screen.height - size.height;
        }
        return new Rectangle(x, y, size.width, size.height);
    }

    /** The usable part of the screen {@code w} is on: without the menu bar, dock or task bar. */
    private static Rectangle screen(Window w) {
        GraphicsConfiguration gc = w.getGraphicsConfiguration();
        if (gc == null) return new Rectangle(0, 0, Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2);
        Rectangle r = gc.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        return new Rectangle(r.x + in.left, r.y + in.top, r.width - in.left - in.right, r.height - in.top - in.bottom);
    }
}
