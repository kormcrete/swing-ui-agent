package io.github.kormcrete.swingui.driver.model;

import java.awt.Window;
import java.util.List;

/**
 * An app-contributed source of {@link Section}s for one window. {@link Sections#forWindow}
 * asks each registered source exactly once; a source that throws degrades to a {@code problems} entry
 * on the result rather than failing the call.
 */
public interface SectionSource {

    List<Section> sections(Window window);
}
