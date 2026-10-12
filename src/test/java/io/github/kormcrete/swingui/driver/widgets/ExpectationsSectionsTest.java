package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.Expectation;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Resolved;
import io.github.kormcrete.swingui.driver.model.Section;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code expect state} on a section judges the {@link Section} it is handed, so a section whose source answers
 * differently between two calls (as {@link io.github.kormcrete.swingui.driver.verbs.LiveVerbs#expect} re-asks
 * {@code Sections.forWindow} each retry) is judged by its current answer, never a stale one.
 *
 * <p>That contributed state is <b>merged onto</b> what the component derives, never substituted for it (second half
 * of this file). The plain two-argument {@code check} is covered by {@link ExpectationsTest}.
 */
class ExpectationsSectionsTest {

    private static Resolved on(JPanel c, String name) {
        return new Resolved(c, null, "section", name, "main", Collections.<String>emptyList(),
                Locator.of("section", name));
    }

    private static Expectation state(String... s) {
        Expectation e = new Expectation();
        e.state = Arrays.asList(s);
        return e;
    }

    private static Section liveSection(String... states) {
        Section s = new Section();
        s.id = "order";
        s.name = "Order";
        for (String st : states) s.addState(st);
        return s;
    }

    @Test
    void aSectionsStatePassesAgainstTheLiveAnswer() {
        Resolved r = on(new JPanel(), "Order");

        assertThat(Expectations.check(r, state("incomplete"), liveSection("incomplete"))).isNull();
    }

    @Test
    void aSectionsStateFailsAgainstTheLiveAnswerNamingWhatItCarries() {
        Resolved r = on(new JPanel(), "Order");

        assertThat(Expectations.check(r, state("incomplete"), liveSection("editable")))
                .isEqualTo("expected state incomplete but the states were [editable]");
    }

    /**
     * The mechanism the retry loop in {@code LiveVerbs.expect} relies on: the same target, the same
     * expectation, but the source's current answer has moved on - and the check must move with it,
     * never remembering the first section object it was handed.
     */
    @Test
    void theSameTargetAndExpectationTrackTheSourcesCurrentAnswerNotTheFirstOneSeen() {
        Resolved r = on(new JPanel(), "Order");
        Expectation wantsEditable = state("editable");

        assertThat(Expectations.check(r, wantsEditable, liveSection("incomplete"))).isNotNull();
        assertThat(Expectations.check(r, wantsEditable, liveSection("editable"))).isNull();
    }

    @Test
    void withNoLiveSectionTheTwoArgumentCheckIsUnchanged() {
        Resolved r = on(new JPanel(), "Order");

        assertThat(Expectations.check(r, state("!incomplete"))).isNull();
    }

    // ---- a claimed component is still judged on its OWN state --------------------------------

    private static Resolved button(JButton b) {
        return new Resolved(b, null, "button", b.getText(), "main", Collections.<String>emptyList(),
                Locator.of("button", b.getText()));
    }

    private static Resolved row(JTable table, int index) {
        Locator locator = Locator.of("table", "Orders");
        locator.row = Collections.<String, Object>singletonMap("index", (Object) index);
        return new Resolved(table, null, "table", "Orders", "main", Collections.<String>emptyList(), locator);
    }

    /**
     * A claim is found by component IDENTITY, not by role, so a source may claim a {@code JButton}. Its own
     * {@code disabled} still counts beside the contributed {@code [readonly, selected]}: {@code disabled} passes and
     * {@code !disabled} fails. Both directions are asserted, since either alone could pass on a constant answer.
     */
    @Test
    void aClaimedDisabledButtonIsStillJudgedOnItsOwnDisabledState() {
        JButton enterResults = new JButton("Enter quantities");
        enterResults.setEnabled(false);
        Resolved r = button(enterResults);
        Section live = liveSection("readonly", "selected");

        assertThat(Expectations.check(r, state("disabled"), live)).isNull();
        assertThat(Expectations.check(r, state("!disabled"), live))
                .isEqualTo("expected state !disabled but the states were [disabled, readonly, selected]");
    }

    /** The other direction, so neither answer is the constant one: an enabled claimed button. */
    @Test
    void anEnabledClaimedButtonPassesOnlyTheNegativeAssertion() {
        Resolved r = button(new JButton("Enter quantities"));
        Section live = liveSection("readonly");

        assertThat(Expectations.check(r, state("!disabled"), live)).isNull();
        assertThat(Expectations.check(r, state("disabled"), live))
                .isEqualTo("expected state disabled but the states were [readonly]");
    }

    /** Merged, not traded: what the app contributes is still readable on that same claimed button. */
    @Test
    void theContributedStateIsStillReadableOnAClaimedButton() {
        JButton enterResults = new JButton("Enter quantities");
        enterResults.setEnabled(false);

        assertThat(Expectations.check(button(enterResults), state("readonly", "disabled"),
                liveSection("readonly"))).isNull();
    }

    /**
     * The merge from the other side: a claimed widget whose section contributes no state still reports its own
     * {@code checked} and {@code selected}.
     */
    @Test
    void aClaimedToggleStillReportsWhatTheWidgetItselfSays() {
        JToggleButton notes = new JToggleButton("Notes", true);
        Resolved r = new Resolved(notes, null, "toggle", "Notes", "main",
                Collections.<String>emptyList(), Locator.of("toggle", "Notes"));
        Section claimedWithoutState = new Section();
        claimedWithoutState.id = "notes";
        claimedWithoutState.name = "Notes";

        assertThat(Expectations.check(r, state("checked", "selected"), claimedWithoutState)).isNull();
        assertThat(Expectations.check(r, state("!checked"), claimedWithoutState))
                .isEqualTo("expected state !checked but the states were [selected, checked]");
    }

    /** A claimed {@code JTable} addressed by row still reports that row's selection beside the contributed state. */
    @Test
    void aClaimedTablesRowStillReportsItsSelection() {
        JTable table = new JTable(new Object[][] {{"26-1"}, {"26-2"}}, new Object[] {"No."});
        table.setRowSelectionInterval(1, 1);
        Section live = liveSection("editable");

        assertThat(Expectations.check(row(table, 1), state("selected"), live)).isNull();
        assertThat(Expectations.check(row(table, 0), state("!selected"), live)).isNull();
        assertThat(Expectations.check(row(table, 0), state("selected"), live))
                .isEqualTo("expected state selected but the states were [editable]");
    }

    /**
     * The uncaptioned-panel shape, and the one place the merge has nothing to merge with: a claimed component
     * that derives no role of its own is a {@code section} node, and the snapshot gives that node no
     * derived state either - so {@code expect} must not invent one here. A disabled panel claimed as
     * a section reads exactly what the source says, which keeps the two views the same contract.
     */
    @Test
    void aRoleLessClaimedPanelReadsOnlyWhatTheSourceContributes() {
        JPanel content = new JPanel();
        content.setEnabled(false);
        Resolved r = on(content, "Order");

        assertThat(Expectations.check(r, state("incomplete"), liveSection("incomplete"))).isNull();
        assertThat(Expectations.check(r, state("!disabled"), liveSection("incomplete"))).isNull();
    }
}
