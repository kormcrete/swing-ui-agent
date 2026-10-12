package io.github.kormcrete.swingui.driver.model;

import java.awt.Component;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The {@link PartSource} registry. A driver with no source registered finds no part anywhere. A source that throws
 * contributes nothing.
 */
public final class Parts {

    private final List<PartSource> sources = new CopyOnWriteArrayList<PartSource>();

    public void register(PartSource source) {
        sources.add(source);
    }

    /** The first non-null answer a registered source gives for {@code c}; empty when no source knows it. */
    public List<Part> of(Component c) {
        for (PartSource source : sources) {
            List<Part> parts;
            try {
                parts = source.parts(c);
            } catch (RuntimeException e) {
                continue;
            }
            if (parts != null) return parts;
        }
        return Collections.emptyList();
    }

    /** Part {@code index} of {@code c}, or null when it has no such part (any more). */
    public Part at(Component c, int index) {
        List<Part> parts = of(c);
        return index >= 0 && index < parts.size() ? parts.get(index) : null;
    }
}
