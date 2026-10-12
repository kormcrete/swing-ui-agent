package io.github.kormcrete.swingui.driver.model;

import java.awt.Rectangle;

/**
 * One painted part of a component that is not a component of its own - a segment of a switch drawn as one control.
 * A {@link PartSource} says what parts a component has; the walk turns each into a child node of that component, a
 * locator resolves it by role and name, and a pointer verb aims at the middle of its {@link #bounds}.
 */
public final class Part {

    /** Its place among the component's parts, left to right; what a ref and a match carry as their index. */
    public final int index;
    /** A snapshot role ({@code toggle}, {@code button}, ...). */
    public final String role;
    /** What it shows, the word a user reads. */
    public final String name;
    public final boolean selected;
    public final boolean enabled;
    /** Its area in the component's own coordinates. */
    public final Rectangle bounds;

    public Part(int index, String role, String name, boolean selected, boolean enabled, Rectangle bounds) {
        this.index = index;
        this.role = role;
        this.name = name;
        this.selected = selected;
        this.enabled = enabled;
        this.bounds = new Rectangle(bounds);
    }

    /** The states a node of this part carries: {@code selected}, {@code disabled}. */
    public void addStates(Node node) {
        if (selected) node.addState("selected");
        if (!enabled) node.addState("disabled");
    }
}
