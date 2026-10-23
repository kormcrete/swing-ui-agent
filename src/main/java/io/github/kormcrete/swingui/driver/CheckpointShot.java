package io.github.kormcrete.swingui.driver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A picture for comparing runs: one element, or the part of it a cell, row or column target names, painted at scale 1
 * and never scaled down, with the rectangles of the elements to leave out when comparing ({@link #masks}). Nothing is
 * painted over a mask; its rectangle is relative to the picture.
 */
public final class CheckpointShot {

    /** PNG bytes. */
    public final byte[] png;
    /** Size of the picture: the area as it shows on screen, pixel for pixel. */
    public final int width;
    public final int height;
    /** One per mask asked for, in the order asked. */
    public final List<Mask> masks;

    public CheckpointShot(byte[] png, int width, int height, List<Mask> masks) {
        this.png = png;
        this.width = width;
        this.height = height;
        this.masks = Collections.unmodifiableList(new ArrayList<Mask>(masks));
    }

    /** A mask's rectangle in the picture; {@link #clipped} when part of the element lies outside the picture. */
    public static final class Mask {
        public final int x;
        public final int y;
        public final int w;
        public final int h;
        public final boolean clipped;

        public Mask(int x, int y, int w, int h, boolean clipped) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.clipped = clipped;
        }
    }
}
