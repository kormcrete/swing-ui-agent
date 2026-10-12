package io.github.kormcrete.swingui.driver.model;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Expectation;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.widgets.Expectations;
import io.github.kormcrete.swingui.driver.window.WindowRoot;
import org.junit.jupiter.api.Test;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Window;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * An {@code id} locator matches a contributed section id as well as {@code getName()} - and
 * a duplicate across both is {@code AMBIGUOUS} only when it is two DISTINCT components; one
 * component matching both ways is one match. A claimed component keeps the role it already
 * derives: a claimed button stays role {@code button} (targetable by role+name and by bare id) and a claimed
 * titled panel stays role {@code section} from its caption (targetable by {@code role:"section"} plus id). A
 * claimed plain, uncaptioned {@code JPanel} is reachable by bare id only; explicit {@code role:"section"} does not
 * match it, since as a role "section" means a titled border.
 */
class ResolverSectionsTest {

    private final Refs refs = new Refs(c -> true);
    private final Names names = new Names(Visibility.TREE);
    private final FakeWindows windows = new FakeWindows();
    private final JPanel root = new JPanel(null);

    ResolverSectionsTest() {
        windows.roots.add(new WindowRoot(root, "dialog", "Order", false, null, true));
    }

    private static Section section(String id, String name, JComponent content) {
        Section s = new Section();
        s.id = id;
        s.name = name;
        s.content = content;
        return s;
    }

    private static JPanel titled(String title) {
        JPanel p = new JPanel();
        p.setBorder(BorderFactory.createTitledBorder(title));
        return p;
    }

    /** Returns the same fixed list on every call - the window parameter is unused, as {@link SectionsTest} does. */
    private static final class FixedSource implements SectionSource {
        private final List<Section> sections;

        FixedSource(Section... sections) {
            this.sections = Arrays.asList(sections);
        }

        @Override
        public List<Section> sections(Window window) {
            return sections;
        }
    }

    /** A source whose next answer can change between calls, to prove {@code liveSection} re-asks it. */
    private static final class MutableSource implements SectionSource {
        private final String id;
        private final JComponent content;
        List<String> states;

        MutableSource(String id, JComponent content, String... states) {
            this.id = id;
            this.content = content;
            this.states = Arrays.asList(states);
        }

        @Override
        public List<Section> sections(Window window) {
            Section s = new Section();
            s.id = id;
            s.name = id;
            s.content = content;
            for (String state : states) s.addState(state);
            return Collections.singletonList(s);
        }
    }

    private Resolver resolverWith(SectionSource source) {
        Sections sections = new Sections();
        sections.register(source);
        return new Resolver(Visibility.TREE, names, refs, windows, sections);
    }

    // ---- a claimed button keeps its role ----------------------

    @Test
    void aContributedButtonResolvesByBareId() {
        JButton save = new JButton("Save");
        root.add(save);
        Resolver resolver = resolverWith(new FixedSource(section("save-action", "Save", save)));

        Resolved r = resolver.resolve(Locator.ofId("save-action"));

        assertThat(r.component).isSameAs(save);
        assertThat(r.role).isEqualTo("button");
    }

    /** Claiming a button for a section must not take role+name targeting away. */
    @Test
    void aClaimedButtonStillResolvesByRoleAndName() {
        JButton save = new JButton("Save");
        root.add(save);
        Resolver resolver = resolverWith(new FixedSource(section("save-action", "Save", save)));

        Resolved r = resolver.resolve(Locator.of("button", "Save"));

        assertThat(r.component).isSameAs(save);
    }

    // ---- a claimed titled panel keeps role "section" from its caption --------------------

    @Test
    void anIdLocatorResolvesAContributedSection() {
        JPanel content = titled("Order");
        root.add(content);
        Resolver resolver = resolverWith(new FixedSource(section("order", "Order", content)));

        Locator l = Locator.of("section", null);
        l.id = "order";
        Resolved r = resolver.resolve(l);

        assertThat(r.component).isSameAs(content);
        assertThat(r.role).isEqualTo("section");
    }

    @Test
    void anUnknownIdReportsNotFoundNamingCandidates() {
        JPanel content = titled("Order");
        root.add(content);
        Resolver resolver = resolverWith(new FixedSource(section("order", "Order", content)));

        Locator l = Locator.of("section", null);
        l.id = "does-not-exist";

        DriverException e = catchThrowableOfType(() -> resolver.resolve(l), DriverException.class);

        assertThat(e.reason()).isEqualTo(Reason.NOT_FOUND);
        assertThat(e.details()).anyMatch(d -> d.contains("section 'Order'"));
    }

    /**
     * AMBIGUOUS needs two DISTINCT components: a titled panel named "dup" (matches by getName()) and a separately
     * claimed titled panel with section id "dup" (matches only via the claim) collide.
     */
    @Test
    void twoDistinctComponentsMatchingTheSameIdAreAmbiguous() {
        JPanel named = titled("Header");
        named.setName("dup"); // matches by getName()
        root.add(named);
        JPanel claimed = titled("Header II");
        root.add(claimed); // matches only via the contributed section id
        Resolver resolver = resolverWith(new FixedSource(section("dup", "Header II", claimed)));

        Locator l = Locator.of("section", null);
        l.id = "dup";

        DriverException e = catchThrowableOfType(() -> resolver.resolve(l), DriverException.class);

        assertThat(e.reason()).isEqualTo(Reason.AMBIGUOUS);
        assertThat(e.details()).hasSize(2);
    }

    /** The counterpart: one component named by {@code setName()} and claimed under the same id is one match. */
    @Test
    void oneComponentMatchingBothWaysResolvesNormally() {
        JButton save = new JButton("Save");
        save.setName("save-action");
        root.add(save);
        Resolver resolver = resolverWith(new FixedSource(section("save-action", "Save", save)));

        Resolved r = resolver.resolve(Locator.ofId("save-action"));

        assertThat(r.component).isSameAs(save);
    }

    @Test
    void liveSectionReAsksTheSourceEachCall() {
        JButton save = new JButton("Save");
        root.add(save);
        MutableSource source = new MutableSource("save-action", save, "editable");
        Resolver resolver = resolverWith(source);
        Resolved r = resolver.resolve(Locator.ofId("save-action"));

        assertThat(resolver.liveSection(r).state).containsExactly("editable");

        source.states = Arrays.asList("incomplete");

        assertThat(resolver.liveSection(r).state).containsExactly("incomplete");
    }

    // ---- a claimed, uncaptioned, role-less panel is still reachable, by id only ----

    /**
     * A plain {@code JPanel} with no Swing-typed role and no titled border is unreachable like any other untyped,
     * uncaptioned component - unless a registered source claims it.
     */
    @Test
    void aClaimedPlainUncaptionedPanelResolvesByBareId() {
        JPanel content = new JPanel(); // no role, no titled border
        root.add(content);
        Resolver resolver = resolverWith(new FixedSource(section("order", "Order", content)));

        Resolved r = resolver.resolve(Locator.ofId("order"));

        assertThat(r.component).isSameAs(content);
        assertThat(r.role).isEqualTo("section");
        assertThat(r.name).isEqualTo("Order");
    }

    /**
     * {@code role:"section"} means a titled border, so it must not also match a contributed section that has no
     * caption - captions and ids never share a namespace. Same fixture and id as the test above; only the locator's
     * {@code role} differs.
     */
    @Test
    void roleSectionAloneDoesNotMatchAClaimedUncaptionedPanel() {
        JPanel content = new JPanel();
        root.add(content);
        Resolver resolver = resolverWith(new FixedSource(section("order", "Order", content)));

        Locator l = Locator.of("section", null);
        l.id = "order";

        assertThat(resolver.matches(l)).isEmpty();
    }

    /**
     * {@code expect} on a plain, uncaptioned panel section: resolved by bare id, as
     * {@link io.github.kormcrete.swingui.driver.verbs.LiveVerbs#expect} resolves a target, with the live section asked
     * fresh each time via {@link Resolver#liveSection}, not cached from the first resolve.
     */
    @Test
    void expectOnAPlainPanelSectionTracksTheSourcesLiveAnswer() {
        JPanel content = new JPanel();
        root.add(content);
        MutableSource source = new MutableSource("order", content, "editable");
        Resolver resolver = resolverWith(source);
        Locator target = Locator.ofId("order");
        Expectation wantsIncomplete = new Expectation();
        wantsIncomplete.state = Arrays.asList("incomplete");

        // fails while the source still answers "editable" - the failure message names what it carries,
        // the same shape every other expect {state} failure uses.
        Resolved firstResolve = resolver.resolve(target);
        Section firstLive = resolver.liveSection(firstResolve);
        assertThat(Expectations.check(firstResolve, wantsIncomplete, firstLive))
                .isEqualTo("expected state incomplete but the states were [editable]");

        // the source's answer moves on - a fresh resolve and a fresh live read, exactly what the
        // expect retry loop in LiveVerbs does on every iteration, now passes.
        source.states = Arrays.asList("incomplete");
        Resolved secondResolve = resolver.resolve(target);
        Section secondLive = resolver.liveSection(secondResolve);
        assertThat(Expectations.check(secondResolve, wantsIncomplete, secondLive)).isNull();
    }
}
