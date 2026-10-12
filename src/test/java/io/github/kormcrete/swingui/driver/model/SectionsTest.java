package io.github.kormcrete.swingui.driver.model;

import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The section registry, on synthetic sources - no window is ever shown. */
class SectionsTest {

    private static Section section(String id, JComponent content) {
        Section s = new Section();
        s.id = id;
        s.name = id;
        s.content = content;
        return s;
    }

    /** Returns the same fixed list on every call, and counts how many times it was asked. */
    private static final class FixedSource implements SectionSource {
        private final List<Section> sections;
        int calls;

        FixedSource(List<Section> sections) {
            this.sections = sections;
        }

        @Override
        public List<Section> sections(Window window) {
            calls++;
            return sections;
        }
    }

    private static final class ThrowingSource implements SectionSource {
        @Override
        public List<Section> sections(Window window) {
            throw new IllegalStateException("boom");
        }
    }

    @Test
    void aSourcesSectionsComeBackInOrderAndANullContentSectionStillAppears() {
        Section first = section("order", new JPanel());
        Section notBuiltYet = section("parameters", null);
        Section last = section("history", new JPanel());
        Sections sections = new Sections();
        sections.register(new FixedSource(Arrays.asList(first, notBuiltYet, last)));

        Sections.Lookup lookup = sections.forWindow(null);

        assertThat(lookup.sections()).containsExactly(first, notBuiltYet, last);
        assertThat(lookup.sectionFor(first.content)).isSameAs(first);
        assertThat(lookup.sectionFor(last.content)).isSameAs(last);
        assertThat(lookup.problems()).isEmpty();
    }

    @Test
    void aThrowingSourceDegradesToAProblemAndTheOtherSourceStillContributes() {
        Section ok = section("order", new JPanel());
        Sections sections = new Sections();
        sections.register(new ThrowingSource());
        sections.register(new FixedSource(Arrays.asList(ok)));

        Sections.Lookup lookup = sections.forWindow(null);

        assertThat(lookup.sections()).containsExactly(ok);
        assertThat(lookup.problems()).hasSize(1);
        assertThat(lookup.problems().get(0))
                .contains(ThrowingSource.class.getName())
                .contains(IllegalStateException.class.getName());
    }

    @Test
    void aDuplicateComponentResolvesLastWinsWithOneRecordedDrop() {
        JPanel shared = new JPanel();
        Section earlier = section("order", shared);
        Section later = section("order.duplicate", shared);
        Sections sections = new Sections();
        sections.register(new FixedSource(Arrays.asList(earlier, later)));

        Sections.Lookup lookup = sections.forWindow(null);

        assertThat(lookup.sections()).containsExactly(earlier, later);
        assertThat(lookup.sectionFor(shared)).isSameAs(later);
        assertThat(lookup.problems()).hasSize(1);
        assertThat(lookup.problems().get(0)).contains(earlier.id).contains(later.id);
    }

    @Test
    void forWindowAsksEachSourceExactlyOnce() {
        FixedSource a = new FixedSource(new ArrayList<Section>());
        FixedSource b = new FixedSource(new ArrayList<Section>());
        Sections sections = new Sections();
        sections.register(a);
        sections.register(b);

        sections.forWindow(null);

        assertThat(a.calls).isEqualTo(1);
        assertThat(b.calls).isEqualTo(1);
    }
}
