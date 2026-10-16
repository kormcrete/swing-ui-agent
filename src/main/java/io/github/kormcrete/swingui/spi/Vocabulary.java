package io.github.kormcrete.swingui.spi;

import java.util.List;

/**
 * The domain steps a plugin offers, e.g. "Add {qty} × {product} to the cart" as one step instead of the clicks it
 * takes. Any plugin may supply one ({@link PluginParts#vocabulary}); it needs no {@link StepHost}. {@link #steps} is
 * read once, when the plugin becomes ready, on the plugin's start thread. Each step is checked then: a step that
 * fails a check is dropped and listed by {@code session} under {@code vocabularyProblems}, the rest stay usable,
 * and the plugin stays ready. A throw from {@code steps()} is listed the same way and leaves the vocabulary empty.
 */
public interface Vocabulary {
    /** The steps, in the order the agent lists them. Ids are unique within one plugin. */
    List<DomainStep> steps();
}
