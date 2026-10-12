package io.github.kormcrete.swingui.driver.model;

import java.awt.Component;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** A locator's single match, with where it was found and the locator's sub-target. */
public final class Resolved {

    public final Component component;
    /** Tab index when the match is a tab of {@link #component}, or the part's index for a {@link #part}; null otherwise. */
    public final Integer tab;
    /** The painted part of {@link #component} the match is, as read when it resolved; null for anything else. */
    public final Part part;
    public final String role;
    public final String name;
    /** The window token of the root it was found in. */
    public final String window;
    public final List<String> trail;
    public final Locator locator;

    public Resolved(Component component, Integer tab, String role, String name, String window,
                    List<String> trail, Locator locator) {
        this(component, tab, null, role, name, window, trail, locator);
    }

    /** A match that is {@code part} of {@code component}: {@link #tab} carries the part's index. */
    public static Resolved ofPart(Component component, Part part, String window, List<String> trail, Locator locator) {
        return new Resolved(component, part.index, part, part.role, part.name, window, trail, locator);
    }

    private Resolved(Component component, Integer tab, Part part, String role, String name, String window,
                     List<String> trail, Locator locator) {
        this.component = component;
        this.tab = tab;
        this.part = part;
        this.role = role;
        this.name = name;
        this.window = window;
        this.trail = Collections.unmodifiableList(trail);
        this.locator = locator;
    }

    public RowSpec row() {
        return locator.rowSpec();
    }

    public String column() {
        return locator.column;
    }

    public List<String> item() {
        return locator.item;
    }

    public Map<String, Double> point() {
        return locator.point;
    }

    public String describe() {
        return role + (name == null ? "" : " '" + name + "'") + " in " + window + (trail.isEmpty() ? "" : " " + trail);
    }

    /**
     * {@link #describe} with the component's own name ({@code getName()}, what a locator's {@code id}
     * matches) after the element's name, as a candidate list shows it: {@code button 'Save' #save in main [tab:Customer]}.
     */
    public String candidate() {
        String id = component == null ? null : component.getName();
        return role + (name == null ? "" : " '" + name + "'") + (id == null || id.isEmpty() ? "" : " #" + id)
                + " in " + window + (trail.isEmpty() ? "" : " " + trail);
    }
}
