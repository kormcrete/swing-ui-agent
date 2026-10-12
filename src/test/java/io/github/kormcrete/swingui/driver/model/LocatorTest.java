package io.github.kormcrete.swingui.driver.model;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class LocatorTest {

    private static Locator cell() {
        Locator l = Locator.of("table", "Orders");
        l.window = "dialog:Order editor*";
        l.scope = Arrays.asList("tab:Line items");
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("Order no.", "ORD-1640");
        l.row = row;
        l.column = "Apply";
        return l;
    }

    @Test
    void aFullCellLocatorIsValid() {
        Locator l = cell();
        l.validate();
        assertThat(l.role).isEqualTo("table");
        assertThat(l.name).isEqualTo("Orders");
        assertThat(l.window).isEqualTo("dialog:Order editor*");
        assertThat(l.scope).containsExactly("tab:Line items");
        assertThat(l.row).containsEntry("Order no.", "ORD-1640");
        assertThat(l.column).isEqualTo("Apply");
    }

    @Test
    void aRefTakesOnlySubTargets() {
        Locator ok = Locator.ofRef("e12");
        ok.column = "Status";
        ok.row = Collections.<String, Object>singletonMap("index", 2);
        ok.validate();
        Locator bad = Locator.ofRef("e12");
        bad.role = "button";
        assertBad(bad, "a ref target takes no");
        assertBad(Locator.ofRef("12"), "ref must look like e12");
    }

    @Test
    void malformedLocatorsAreBadInput() {
        assertBad(new Locator(), "needs a ref, an id, a role or a name");
        assertBad(Locator.of("knob", "OK"), "unknown role 'knob'");
        Locator match = Locator.of("button", "OK");
        match.match = "regex";
        assertBad(match, "match must be");
        Locator window = Locator.of("button", "OK");
        window.window = "sheet:X";
        assertBad(window, "window must be");
        Locator scope = Locator.of("button", "OK");
        scope.scope = Arrays.asList("pane:north");
        assertBad(scope, "scope step");
        Locator point = Locator.of("clickable", null);
        point.point = Collections.singletonMap("x", 0.5);
        assertBad(point, "'point' needs x and y");
        Locator index = Locator.of("table", null);
        index.row = Collections.<String, Object>singletonMap("index", "two");
        assertBad(index, "{\"index\": n}");
    }

    /** A {@code column} may stand alone (it narrows to the table owning it), and so may a {@code row}. */
    @Test
    void aColumnAloneValidatesAndRowAloneIsUnaffected() {
        Locator columnOnly = Locator.of("table", null);
        columnOnly.column = "Status";
        columnOnly.validate();
        assertThat(columnOnly.row).isNull();
        assertThat(columnOnly.column).isEqualTo("Status");

        Locator rowOnly = Locator.of("table", null);
        rowOnly.row = Collections.<String, Object>singletonMap("index", 0);
        rowOnly.validate();
        assertThat(rowOnly.column).isNull();

        Locator both = cell();
        both.validate();
        assertThat(both.row).containsEntry("Order no.", "ORD-1640");
        assertThat(both.column).isEqualTo("Apply");
    }

    @Test
    void windowTokensParseAndGlob() {
        assertThat(WindowRef.parse("main").kind).isEqualTo(WindowRef.Kind.MAIN);
        assertThat(WindowRef.parse("popup").kind).isEqualTo(WindowRef.Kind.POPUP);
        WindowRef bare = WindowRef.parse("dialog");
        assertThat(bare.kind).isEqualTo(WindowRef.Kind.DIALOG);
        assertThat(bare.title).isNull();
        assertThat(bare.titleMatches("anything")).isTrue();
        WindowRef glob = WindowRef.parse("dialog:Order editor*");
        assertThat(glob.titleMatches("Order editor – Order ORD-1640")).isTrue();
        assertThat(glob.titleMatches("Delete")).isFalse();
        WindowRef exact = WindowRef.parse("window:OrderDesk");
        assertThat(exact.kind).isEqualTo(WindowRef.Kind.WINDOW);
        assertThat(exact.titleMatches("OrderDesk")).isTrue();
        assertThat(exact.titleMatches("OrderDesk 2")).isFalse();
        WindowRef suffix = WindowRef.parse("dialog:*Order details");
        assertThat(suffix.titleMatches("ORD-1640 (Acme) - Order details")).isTrue();
        assertThat(suffix.titleMatches("Order details reminder")).isFalse();
        WindowRef contains = WindowRef.parse("dialog:*(Acme)*");
        assertThat(contains.titleMatches("ORD-1641 (Acme) - Order details")).isTrue();
        assertThat(contains.titleMatches("ORD-1642 (Globex) - Order details")).isFalse();
    }

    @Test
    void scopeStepsAndRowsParse() {
        ScopeStep tab = ScopeStep.parse("tab:Line items");
        assertThat(tab.kind).isEqualTo(ScopeStep.Kind.TAB);
        assertThat(tab.title).isEqualTo("Line items");
        assertThat(ScopeStep.parse(ScopeStep.section("Header")).kind).isEqualTo(ScopeStep.Kind.SECTION);
        assertThat(RowSpec.of(Collections.<String, Object>singletonMap("index", 3)).index).isEqualTo(3);
        RowSpec keys = cell().rowSpec();
        assertThat(keys.index).isNull();
        assertThat(keys.keys).containsEntry("Order no.", "ORD-1640");
    }

    @Test
    void anIdScopeStepValidatesAndAMissingColonStillFails() {
        Locator inside = Locator.of("button", "Open");
        inside.scope = Arrays.asList("id:x");
        inside.validate();
        ScopeStep id = ScopeStep.parse(ScopeStep.id("summary.column.ORD-1610"));
        assertThat(id.kind).isEqualTo(ScopeStep.Kind.ID);
        assertThat(id.title).isEqualTo("summary.column.ORD-1610");
        Locator bad = Locator.of("button", "Open");
        bad.scope = Arrays.asList("idx");
        assertBad(bad, "id:<name>");
    }

    @Test
    void equalityCoversEveryFieldAndCopiesAreDeep() {
        assertThat(cell()).isEqualTo(cell()).hasSameHashCodeAs(cell());
        Locator copy = cell().copy();
        copy.row.put("Customer", "X");
        assertThat(copy).isNotEqualTo(cell());
        Locator element = cell().element();
        assertThat(element.row).isNull();
        assertThat(element.column).isNull();
        assertThat(element.name).isEqualTo("Orders");
    }

    private static void assertBad(Locator l, String text) {
        DriverException e = catchThrowableOfType(l::validate, DriverException.class);
        assertThat(e).as("expected BAD_INPUT containing: " + text).isNotNull();
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains(text);
    }
}
