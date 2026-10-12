package io.github.kormcrete.swingui.driver.model;

import javax.swing.JComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An area of a window that the application itself names - what a {@link SectionSource}
 * contributes. Public fields, no behaviour beyond {@link #addState}. {@code content} is null when the
 * app has not built that subtree yet; the section still belongs in the flat list a source returns, it
 * is only the component lookup ({@link Sections.Lookup#sectionFor}) that skips it.
 */
public final class Section {

    /** Stable, translation-proof. */
    public String id;

    /** What a person reads. */
    public String name;

    /** The subtree this section covers; null when not built yet. */
    public JComponent content;

    /** {@code selected | hidden | readonly | editable | incomplete} - documentation, not an enum. */
    public List<String> state;

    /** App text, e.g. why the section cannot be saved. */
    public Map<String, String> details;

    public void addState(String s) {
        if (state == null) state = new ArrayList<String>();
        if (!state.contains(s)) state.add(s);
    }
}
