package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.model.Locator;

import java.awt.Component;

/**
 * One addressable element as the inspector shows it: the locator the recorder writes for it, how it reads,
 * and where to draw its box. A table cell has a row and a column, a whole column only a column (view indices, -1 none).
 */
public final class Target {
    public final Locator locator;
    public final boolean fragile;
    public final String element;
    public final String role;
    final Component component;
    final Integer tab;
    final int row;
    final int column;

    Target(Locator locator, boolean fragile, String element, String role, Component component, Integer tab, int row,
           int column) {
        this.locator = locator;
        this.fragile = fragile;
        this.element = element;
        this.role = role;
        this.component = component;
        this.tab = tab;
        this.row = row;
        this.column = column;
    }

    boolean cell() {
        return row >= 0 && column >= 0;
    }

    boolean wholeColumn() {
        return row < 0 && column >= 0;
    }

    boolean same(Target o) {
        return o != null && o.component == component && o.row == row && o.column == column
                && (tab == null ? o.tab == null : tab.equals(o.tab));
    }
}
