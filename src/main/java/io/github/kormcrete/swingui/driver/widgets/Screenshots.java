package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.Shot;

import io.github.kormcrete.swingui.driver.window.AppWindows;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Paints an app component into an offscreen image. EDT.
 *
 * <p>Not a screen grab: the app runs in the background, so a desktop capture would show whatever is in
 * front of it. Painting needs no permissions, but anything drawn in a window of its own (an open popup
 * menu, a combo's dropdown, a tooltip) is not in the image: capture that window instead. The inspector's
 * palette and boxes are left out too (see {@link #capturing()}).
 *
 * <p>With {@code palette} the image is the window as the person sees it, for documentation pictures: the palette
 * and boxes paint, and the popups open over the window (lightweight ones in its popup layer, panels Swing put there,
 * and popup windows of their own) are painted in at the spot they show. Still a paint of the app's own components.
 */
public final class Screenshots {

    /** The longest side an image may have before it is scaled down; an image is expensive to read. */
    public static final int DEFAULT_MAX_SIZE = 1200;

    /** The largest {@code scale}: a 4x image of a big window is already tens of megabytes in memory. */
    public static final double MAX_SCALE = 4;

    private static boolean capturing;

    private Screenshots() {}

    /**
     * True while a capture paints (read and written on the EDT only); the inspector's own
     * components paint nothing then, so the person's palette and boxes are never in an image.
     */
    public static boolean capturing() {
        return capturing;
    }

    /**
     * @param c      the component or window to paint; a window must be showing (a closed one keeps its size but
     *               paints nothing)
     * @param region the part of it to keep, in fractions 0..1 of its bounds (as {@code point} works),
     *               or null for all of it
     * @param maxSize longest side of the returned image, or null for {@link #DEFAULT_MAX_SIZE} times the scale
     * @param scale  image pixels per component pixel, 1 to {@link #MAX_SCALE}, or null for 1: 2 paints the
     *               component as a 2x (HiDPI) display would, at the same layout
     */
    public static Shot of(Component c, double[] region, Integer maxSize, Double scale) {
        return of(c, region, maxSize, scale, false);
    }

    /**
     * {@link #of(Component, double[], Integer, Double)}; {@code palette} keeps the person's palette, the inspector's
     * boxes and the popups open over {@code c} in the image.
     */
    public static Shot of(Component c, double[] region, Integer maxSize, Double scale, boolean palette) {
        if (c instanceof Window && !c.isShowing()) {
            throw new DriverException(Reason.NOT_ACTIONABLE,
                    "nothing to capture: the window is not showing (closed or hidden)");
        }
        if (c.getWidth() <= 0 || c.getHeight() <= 0) {
            throw new DriverException(Reason.NOT_ACTIONABLE, "nothing to capture: the target has no size"
                    + " on screen (" + c.getWidth() + "x" + c.getHeight() + ")");
        }
        double s = scale == null ? 1 : scale;
        if (!(s >= 1 && s <= MAX_SCALE)) {
            throw new DriverException(Reason.BAD_INPUT, "'scale' must be between 1 and " + (int) MAX_SCALE);
        }
        Rectangle shown = shown(c);
        int w = shown.width;
        int h = shown.height;
        BufferedImage image = palette ? paintAsSeen(c, shown, s) : paint(c, shown, s);
        Rectangle crop = crop(region, image.getWidth(), image.getHeight());
        if (crop != null) image = image.getSubimage(crop.x, crop.y, crop.width, crop.height);

        int limit = maxSize == null ? (int) Math.round(DEFAULT_MAX_SIZE * s) : maxSize;
        if (limit < 1) throw new DriverException(Reason.BAD_INPUT, "'maxSize' must be at least 1");
        boolean scaled = Math.max(image.getWidth(), image.getHeight()) > limit;
        if (scaled) image = scale(image, limit);

        return new Shot(png(image), image.getWidth(), image.getHeight(), w, h, scaled);
    }

    /**
     * The part of the component that is on screen: a component inside a viewport is as tall as all of its
     * content, but a capture shows what the user sees. For a component that is not scrolled, all of it.
     */
    private static Rectangle shown(Component c) {
        Rectangle all = new Rectangle(0, 0, c.getWidth(), c.getHeight());
        if (!(c instanceof JComponent)) return all;
        Rectangle visible = ((JComponent) c).getVisibleRect();
        return visible.isEmpty() ? all : visible.intersection(all);
    }

    /**
     * A non-opaque component paints nothing behind itself, which would leave the buffer black, so
     * the background is laid down first. The graphics is translated so that the scrolled-to part
     * lands at the image's origin. A scale above 1 is a transform on the graphics, the way a HiDPI
     * display paints: the layout is unchanged and text and borders are drawn at the finer resolution.
     */
    private static BufferedImage paint(Component c, Rectangle shown, double scale) {
        int iw = (int) Math.ceil(shown.width * scale);
        int ih = (int) Math.ceil(shown.height * scale);
        BufferedImage image = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            Color background = c.getBackground();
            g.setColor(background == null ? Color.WHITE : background);
            g.fillRect(0, 0, iw, ih);
            g.scale(scale, scale);
            g.translate(-shown.x, -shown.y);
            g.setClip(shown.x, shown.y, shown.width, shown.height);
            boolean before = capturing;
            capturing = true;
            try {
                c.paint(g);
            } finally {
                capturing = before;
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    /**
     * {@code shown} of {@code c} as the person sees it: what its window (or outermost container) paints there, so
     * the palette's layers over {@code c} are in, then the heavyweight panels Swing put in the window's popup layer
     * (they are not painted with the window), then the popup windows over it at their offset on screen, a submenu
     * after its menu. Nothing the window paints outside {@code shown} is kept.
     */
    private static BufferedImage paintAsSeen(Component c, Rectangle shown, double scale) {
        Component top = c;
        while (!(top instanceof Window) && top.getParent() != null) top = top.getParent();
        // a window that is not on screen paints nothing; its root pane still does
        if (top instanceof Window && !top.isShowing() && top instanceof RootPaneContainer) {
            top = ((RootPaneContainer) top).getRootPane();
        }
        Rectangle area = SwingUtilities.convertRectangle(c, shown, top);
        int iw = (int) Math.ceil(shown.width * scale);
        int ih = (int) Math.ceil(shown.height * scale);
        BufferedImage image = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            Color background = c.getBackground();
            g.setColor(background == null ? Color.WHITE : background);
            g.fillRect(0, 0, iw, ih);
            g.scale(scale, scale);
            g.translate(-area.x, -area.y);
            g.setClip(area.x, area.y, area.width, area.height);
            top.paint(g);
            JRootPane root = top instanceof RootPaneContainer ? ((RootPaneContainer) top).getRootPane()
                    : top instanceof JRootPane ? (JRootPane) top : null;
            if (root != null) paintHeavyweightChildren(g, root.getLayeredPane(), top);
            if (top instanceof Window && top.isShowing()) paintPopupWindows(g, (Window) top);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** The panels a medium-weight popup puts in the layered pane: the window's own paint skips heavyweights. */
    private static void paintHeavyweightChildren(Graphics2D g, JLayeredPane layers, Component top) {
        Component[] children = layers.getComponents();
        for (int i = children.length - 1; i >= 0; i--) {   // the last child is the bottom one
            Component child = children[i];
            if (child.isLightweight() || !child.isVisible() || !child.isShowing()) continue;
            Point at = SwingUtilities.convertPoint(layers, child.getLocation(), top);
            paintAt(g, child, at.x, at.y);
        }
    }

    /** Showing popup windows whose owner chain leads to {@code top}, painted where they are on screen. */
    private static void paintPopupWindows(Graphics2D g, Window top) {
        final Map<Window, Integer> depth = new HashMap<Window, Integer>();
        for (Window w : Window.getWindows()) {
            if (w == top || !w.isShowing() || !AppWindows.isPopupWindow(w)) continue;
            int d = 0;
            for (Window o = w.getOwner(); o != null; o = o.getOwner()) {
                d++;
                if (o == top) {
                    depth.put(w, d);
                    break;
                }
            }
        }
        List<Window> popups = new ArrayList<Window>(depth.keySet());
        Collections.sort(popups, (a, b) -> depth.get(a) - depth.get(b));
        Point origin = top.getLocationOnScreen();
        for (Window w : popups) {
            Point p = w.getLocationOnScreen();
            paintAt(g, w, p.x - origin.x, p.y - origin.y);
        }
    }

    private static void paintAt(Graphics2D g, Component c, int x, int y) {
        Graphics2D cg = (Graphics2D) g.create();
        try {
            cg.translate(x, y);
            cg.clipRect(0, 0, c.getWidth(), c.getHeight());
            c.paint(cg);
        } finally {
            cg.dispose();
        }
    }

    private static Rectangle crop(double[] region, int w, int h) {
        if (region == null) return null;
        int x = (int) Math.round(region[0] * w);
        int y = (int) Math.round(region[1] * h);
        int rw = (int) Math.round(region[2] * w);
        int rh = (int) Math.round(region[3] * h);
        Rectangle r = new Rectangle(x, y, rw, rh).intersection(new Rectangle(0, 0, w, h));
        if (r.isEmpty()) {
            throw new DriverException(Reason.BAD_INPUT,
                    "'region' selects nothing inside the target's " + w + "x" + h + " bounds");
        }
        return r;
    }

    private static BufferedImage scale(BufferedImage image, int limit) {
        double factor = (double) limit / Math.max(image.getWidth(), image.getHeight());
        int w = Math.max(1, (int) Math.round(image.getWidth() * factor));
        int h = Math.max(1, (int) Math.round(image.getHeight() * factor));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] png(BufferedImage image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new DriverException(Reason.APP_ERROR, "could not encode the capture as PNG: " + e.getMessage());
        }
        return out.toByteArray();
    }
}
