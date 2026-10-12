package io.github.kormcrete.swingui.driver.model;

import java.awt.Component;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The section registry: holds the {@link SectionSource}s the app registers and, on
 * {@link #forWindow}, asks each exactly once and merges what it returns into one {@link Lookup}.
 * A driver with no source registered never calls anything and returns an empty {@link Lookup}.
 */
public final class Sections {

    private final List<SectionSource> sources = new CopyOnWriteArrayList<SectionSource>();

    /** Several sources may register; each is asked, once, per {@link #forWindow} call. */
    public void register(SectionSource source) {
        sources.add(source);
    }

    /**
     * Asks every registered source exactly once, in registration order, and merges what it returns
     * into one flat list plus a component lookup.
     *
     * <p>A source that throws contributes nothing; the throw is recorded as a {@link Lookup#problems()}
     * entry - the source's class name plus the throwable's type - never propagated. Where two sections
     * claim the same {@link Section#content} component, the one examined later (a later source, or a
     * later entry from the same source) wins and the drop is recorded once, naming both section ids.
     * A section whose {@code content} is null is not a problem: it still joins the flat list, only the
     * component lookup skips it.
     */
    public Lookup forWindow(Window window) {
        List<Section> flat = new ArrayList<Section>();
        Map<Component, Section> byComponent = new IdentityHashMap<Component, Section>();
        List<String> problems = new ArrayList<String>();
        for (SectionSource source : sources) {
            List<Section> contributed;
            try {
                contributed = source.sections(window);
            } catch (RuntimeException e) {
                problems.add(source.getClass().getName() + " threw " + e.getClass().getName());
                continue;
            }
            if (contributed == null) continue;
            for (Section section : contributed) {
                flat.add(section);
                if (section.content == null) continue;
                Section previous = byComponent.put(section.content, section);
                if (previous != null) {
                    problems.add("duplicate section for one component: '" + previous.id
                            + "' dropped for '" + section.id + "'");
                }
            }
        }
        return new Lookup(flat, byComponent, problems);
    }

    /**
     * The merged result of one {@link #forWindow} call: the flat list in registration order, a
     * component lookup for the sections that have {@code content}, and any problems a source raised.
     */
    public static final class Lookup {

        private final List<Section> sections;
        private final Map<Component, Section> byComponent;
        private final List<String> problems;

        private Lookup(List<Section> sections, Map<Component, Section> byComponent, List<String> problems) {
            this.sections = Collections.unmodifiableList(sections);
            this.byComponent = byComponent;
            this.problems = Collections.unmodifiableList(problems);
        }

        /** The flat list, in registration order; includes sections whose {@code content} is null. */
        public List<Section> sections() {
            return sections;
        }

        /** The section that claims {@code component}, or null - never true for a null-content section. */
        public Section sectionFor(Component component) {
            return byComponent.get(component);
        }

        public List<String> problems() {
            return problems;
        }
    }
}
