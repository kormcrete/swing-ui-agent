package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.Shot;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Paints an app component into an offscreen image. EDT.
 *
 * <p>Not a screen grab: the app runs in the background, so a desktop capture would show whatever is in
 * front of it. Painting needs no permissions, but anything drawn in a window of its own (an open popup
 * menu, a combo's dropdown, a tooltip) is not in the image: capture that window instead.
 */
public final class Screenshots {

    /** The longest side an image may have before it is scaled down; an image is expensive to read. */
    public static final int DEFAULT_MAX_SIZE = 1200;

    private Screenshots() {}

    /**
     * @param c      the component or window to paint
     * @param region the part of it to keep, in fractions 0..1 of its bounds (as {@code point} works),
     *               or null for all of it
     * @param maxSize longest side of the returned image, or null for {@link #DEFAULT_MAX_SIZE}
     */
    public static Shot of(Component c, double[] region, Integer maxSize) {
        if (c.getWidth() <= 0 || c.getHeight() <= 0) {
            throw new DriverException(Reason.NOT_ACTIONABLE, "nothing to capture: the target has no size"
                    + " on screen (" + c.getWidth() + "x" + c.getHeight() + ")");
        }
        Rectangle shown = shown(c);
        int w = shown.width;
        int h = shown.height;
        BufferedImage image = paint(c, shown);
        Rectangle crop = crop(region, w, h);
        if (crop != null) image = image.getSubimage(crop.x, crop.y, crop.width, crop.height);

        int limit = maxSize == null ? DEFAULT_MAX_SIZE : maxSize;
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
     * lands at the image's origin.
     */
    private static BufferedImage paint(Component c, Rectangle shown) {
        BufferedImage image = new BufferedImage(shown.width, shown.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            Color background = c.getBackground();
            g.setColor(background == null ? Color.WHITE : background);
            g.fillRect(0, 0, shown.width, shown.height);
            g.translate(-shown.x, -shown.y);
            g.setClip(shown.x, shown.y, shown.width, shown.height);
            c.paint(g);
        } finally {
            g.dispose();
        }
        return image;
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
