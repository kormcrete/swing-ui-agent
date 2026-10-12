package io.github.kormcrete.swingui.driver.model;

import java.awt.Component;
import java.util.List;

/**
 * An app-contributed reader of a component's painted {@link Part}s - the parts the app draws inside one component, which
 * the component tree cannot show (a segmented switch's words). Asked for every {@code clickable} component the walk or a
 * locator meets, so it must answer fast and return null for a component it does not know. EDT.
 */
public interface PartSource {

    /** The component's parts, left to right, or null when this source does not know the component. */
    List<Part> parts(Component component);
}
