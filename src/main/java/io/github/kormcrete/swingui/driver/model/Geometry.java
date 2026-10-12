package io.github.kormcrete.swingui.driver.model;

import java.util.List;

/** Where an element is: its rectangle in its window's coordinates, plus a table's column geometry. */
public final class Geometry {
    public int x;
    public int y;
    public int width;
    public int height;
    public List<Integer> columnWidths;
    public List<Integer> headerTextWidths;
    public Boolean horizontalScrollBar;
    public Integer viewportWidth;
}
