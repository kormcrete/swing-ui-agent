package io.github.kormcrete.swingui.driver;

/**
 * One captured image of the app. Component-scoped by construction: the bytes come
 * from painting a component of ours into an offscreen buffer, never from grabbing the desktop, so
 * the capture cannot pick up whatever window happens to be in front of the app.
 */
public final class Shot {

    /** PNG bytes. */
    public final byte[] png;
    /** Size of the image as returned, after any crop and downscale. */
    public final int width;
    public final int height;
    /**
     * The area that was captured, before any {@code region} crop and downscale: the component's own
     * size, or - when it is scrolled - the part of it on screen.
     */
    public final int sourceWidth;
    public final int sourceHeight;
    /** True when the image was scaled down to fit the size budget. */
    public final boolean scaled;

    public Shot(byte[] png, int width, int height, int sourceWidth, int sourceHeight, boolean scaled) {
        this.png = png;
        this.width = width;
        this.height = height;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.scaled = scaled;
    }
}
