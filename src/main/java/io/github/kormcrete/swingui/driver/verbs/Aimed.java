package io.github.kormcrete.swingui.driver.verbs;

import io.github.kormcrete.swingui.driver.model.Resolved;

import java.awt.Component;
import java.awt.Point;

/**
 * A resolved, actionable target and the point to act at, in the coordinates of the component acted on:
 * the resolved component, or a table's header for a column target with no row ({@link Targets#surface}).
 */
final class Aimed {
    final Resolved resolved;
    final Point point;
    final Component on;

    Aimed(Resolved resolved, Point point, Component on) {
        this.resolved = resolved;
        this.point = point;
        this.on = on;
    }

    /** The component {@link #point} is in: what a pointer verb posts to. */
    Component component() {
        return on;
    }
}
