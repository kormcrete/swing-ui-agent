package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.driver.model.Geometry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where an element is, in its window's coordinates (a read: {@link Target#bounds()}). Immutable. Not clipped to what
 * shows: a row scrolled out of its viewport reports where it would be, outside {@link #viewportWidth()}.
 */
public final class Bounds {
    private final int x, y, width, height;
    private final List<Integer> columnWidths, headerTextWidths;
    private final Boolean horizontalScrollBar;
    private final Integer viewportWidth;

    private Bounds(Geometry g) {
        x = g.x; y = g.y; width = g.width; height = g.height;
        columnWidths = copy(g.columnWidths);
        headerTextWidths = copy(g.headerTextWidths);
        horizontalScrollBar = g.horizontalScrollBar;
        viewportWidth = g.viewportWidth;
    }

    static Bounds of(Geometry g) { return new Bounds(g); }

    private static List<Integer> copy(List<Integer> l) {
        return l == null ? Collections.<Integer>emptyList() : Collections.unmodifiableList(new ArrayList<Integer>(l));
    }

    public int x() { return x; }
    public int y() { return y; }
    public int width() { return width; }
    public int height() { return height; }
    /** A table's column widths, view order; empty for anything else. */
    public List<Integer> columnWidths() { return columnWidths; }
    /** A table's header cells' natural widths (text + icon + border); empty for anything else. */
    public List<Integer> headerTextWidths() { return headerTextWidths; }
    /** Whether the horizontal scroll bar of the pane this element is the view of shows; null when it is no view. */
    public Boolean horizontalScrollBar() { return horizontalScrollBar; }
    /** The width of the viewport this element is the view of; null when it is no view. */
    public Integer viewportWidth() { return viewportWidth; }

    @Override public String toString() { return "[" + x + "," + y + " " + width + "x" + height + "]"; }
}
