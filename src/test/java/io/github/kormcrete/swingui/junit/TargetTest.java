package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.testing.RecordingVerbs;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Locator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class TargetTest {

    private final RecordingVerbs verbs = new RecordingVerbs();
    private final Client ui = new Client(verbs, null, "UIT-test");

    private static Locator loc(String role, String name) { return Locator.of(role, name); }

    @Test
    void aScreenshotCanAskForAScale() {
        ui.screenshot(2);
        ui.button("OK").screenshot(2);
        ui.screenshot();
        assertThat(verbs.calls).containsExactly("screenshot null 2.0",
                "screenshot " + loc("button", "OK") + " 2.0", "screenshot null");
    }

    @Test
    void aScreenshotCanKeepThePalette() {
        ui.screenshot(2, true);
        ui.button("OK").screenshot(1, true);
        ui.screenshot(2, false);
        assertThat(verbs.calls).containsExactly("screenshot null 2.0 palette",
                "screenshot " + loc("button", "OK") + " 1.0 palette", "screenshot null 2.0");
    }

    @Test
    void aLeafBecomesRoleAndName() {
        ui.button("Save").click();
        assertThat(verbs.calls).containsExactly("click " + loc("button", "Save") + " null null null null");
    }

    @Test
    void chainingATabOrSectionBecomesScope() {
        ui.dialog("Assign").tab("Line items").table("Orders").row("Order no.", "ORD-1640").cell("Apply").check();
        Locator expected = loc("table", "Orders");
        expected.window = "dialog:Assign";
        expected.scope = Arrays.asList("tab:Line items");
        expected.row = Rows.row("Order no.", "ORD-1640");
        expected.column = "Apply";
        assertThat(verbs.calls).containsExactly("check " + expected + " true null");
    }

    @Test
    void aTabActedOnIsTheTabItself() {
        ui.tab("Master data").click();
        assertThat(verbs.calls.get(0)).startsWith("click " + loc("tab", "Master data"));
    }

    @Test
    void aButtonCannotContainAnything() {
        IllegalStateException e = catchThrowableOfType(() -> ui.button("OK").field("x"), IllegalStateException.class);
        assertThat(e).hasMessageContaining("button 'OK' cannot contain");
    }

    @Test
    void fillReplacesAndCommits() {
        ui.field("Description").fill("Widget 3");
        assertThat(verbs.calls).containsExactly("type " + loc("text", "Description") + " Widget 3 true true null");
    }

    @Test
    void openIsADoubleClickAndRightClickWithAPathIsTheContextMenu() {
        ui.table().rowAt(2).open();
        ui.table("Orders").rowAt(0).rightClick("Delete");
        Locator row2 = new Locator(); row2.role = "table"; row2.row = Rows.index(2);
        Locator row0 = loc("table", "Orders"); row0.row = Rows.index(0);
        assertThat(verbs.calls).containsExactly(
                "click " + row2 + " null 2 null null",
                "contextMenu " + row0 + " [Delete] null");
    }

    @Test
    void aTimeoutOverrideIsPassedAsMillis() {
        ui.button("OK").timeout(Duration.ofSeconds(90)).click();
        ui.timeout(Duration.ofSeconds(5)).menu("File", "Export");
        assertThat(verbs.calls).containsExactly(
                "click " + loc("button", "OK") + " null null null 90000",
                "menu [File, Export] 5000");
    }

    @Test
    void aTargetIsImmutable() {
        Target table = ui.table("Orders");
        table.rowAt(1);
        assertThat(table.locator().row).isNull();

        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("Order no.", "ORD-1640");
        Target withRow = table.row(row);
        row.put("Order no.", "mutated after row() was called");
        assertThat(withRow.locator().row).containsEntry("Order no.", "ORD-1640");
    }

    @Test
    void scrollToPassesOnlyTheDestination() {
        ui.table("Orders").scrollTo("bottom");
        assertThat(verbs.calls).containsExactly("scroll " + loc("table", "Orders") + " bottom null null");
    }

    @Test
    void scrollByPassesOnlyThePageCount() {
        ui.table("Orders").scrollBy(3);
        assertThat(verbs.calls).containsExactly("scroll " + loc("table", "Orders") + " null 3 null");
    }

    @Test
    void scrollIntoViewPassesNeitherDestinationNorPageCount() {
        ui.table("Orders").rowAt(5).scrollIntoView();
        Locator row5 = loc("table", "Orders"); row5.row = Rows.index(5);
        assertThat(verbs.calls).containsExactly("scroll " + row5 + " null null null");
    }

    @Test
    void aDriverFailureBecomesUiActionFailedWithCandidates() {
        verbs.failNext = new DriverException(Reason.NOT_FOUND, "no button 'Save'");
        verbs.candidates = Arrays.asList("button 'Save ▾'");
        UiActionFailed e = catchThrowableOfType(() -> ui.button("Save").click(), UiActionFailed.class);
        assertThat(e.reason()).isEqualTo(Reason.NOT_FOUND);
        assertThat(e.getMessage()).contains("click", "Save", "no button", "Save ▾");
    }

    @Test
    void aWarningIsCollectedNotThrown() {
        verbs.outcome.warning = "the action ran, but the app did not settle";
        ui.button("Save to File").click();
        assertThat(ui.warnings()).hasSize(1);
        assertThat(ui.warnings().get(0)).contains("Save to File", "did not settle");
    }

    @Test
    void anIdCanContainATarget() {
        ui.byId("summary.header").role("clickable", null).click();
        Locator expected = loc("clickable", null);
        expected.scope = Arrays.asList("id:summary.header");
        assertThat(verbs.calls).containsExactly("click " + expected + " null null null null");
    }

    @Test
    void anIdScopeFollowsTheScopesBeforeItAndKeepsTheWindow() {
        ui.main().tab("Summary").byId("summary.column.ORD-1610").button("Open").click();
        Locator expected = loc("button", "Open");
        expected.window = "main";
        expected.scope = Arrays.asList("tab:Summary", "id:summary.column.ORD-1610");
        assertThat(verbs.calls).containsExactly("click " + expected + " null null null null");
    }

    @Test
    void anIdWithASubTargetCannotContainAnything() {
        IllegalStateException e = catchThrowableOfType(() -> ui.byId("grid").cell("Status").button("x"),
                IllegalStateException.class);
        assertThat(e).hasMessageContaining("element 'grid' cannot contain");
        e = catchThrowableOfType(() -> ui.byId("grid").nth(1).button("x"), IllegalStateException.class);
        assertThat(e).hasMessageContaining("element 'grid' cannot contain");
    }

    @Test
    void byIdAndNthAndContains() {
        ui.byId("orderList.export.pdf").click();
        ui.button("Next").nth(1).contains().click();
        Locator second = loc("button", "Next"); second.nth = 1; second.match = "contains";
        assertThat(verbs.calls).containsExactly(
                "click " + Locator.ofId("orderList.export.pdf") + " null null null null",
                "click " + second + " null null null null");
    }

    @Test
    void clickAtSendsAFractionalPointAndLeavesTheTargetUnchanged() {
        Target cell = ui.table("Issues").rowAt(0).cell("Reason");
        cell.clickAt(0.9, 0.5);
        Locator expected = loc("table", "Issues");
        expected.row = Rows.index(0);
        expected.column = "Reason";
        Map<String, Double> point = new LinkedHashMap<String, Double>();
        point.put("x", 0.9);
        point.put("y", 0.5);
        expected.point = point;
        assertThat(verbs.calls).containsExactly("click " + expected + " null null null null");
        assertThat(cell.locator().point).isNull();
    }

    @Test
    void aFailedClickAtNamesThePoint() {
        verbs.failNext = new DriverException(Reason.BAD_INPUT, "'point' needs x and y between 0 and 1");
        UiActionFailed e = catchThrowableOfType(() -> ui.table("Issues").clickAt(1.5, 0.5), UiActionFailed.class);
        assertThat(e).isNotNull();
        assertThat(e.target().point).containsEntry("x", 1.5);
    }

    /** An app whose navigate and closeActiveTab fail the way app hooks do: with a DriverException. */
    private static final class FailingApp implements ClientApp {
        @Override public void navigate(String target) {
            throw new DriverException(Reason.TIMEOUT, "rows of table in tab:" + target + " never reached 1");
        }
        @Override public void closeActiveTab() {
            throw new DriverException(Reason.TIMEOUT, "closeActiveTab: the active tab did not close within 10s");
        }
        @Override public void loginAs(String alias) { throw new UnsupportedOperationException(); }
        @Override public void loginAs(String user, String password) { throw new UnsupportedOperationException(); }
        @Override public void relogin() { throw new UnsupportedOperationException(); }
        @Override public <T> T hooks(Class<T> type) { throw new UnsupportedOperationException(); }
    }

    @Test
    void aFailedNavigateIsAUiActionFailedWithoutALocator() {
        Client failing = new Client(verbs, new FailingApp(), "UIT-test");
        UiActionFailed e = catchThrowableOfType(() -> failing.navigate("Orders"), UiActionFailed.class);
        assertThat(e.reason()).isEqualTo(Reason.TIMEOUT);
        assertThat(e.target()).isNull();
        assertThat(e).hasMessageStartingWith("navigate Orders: ").hasMessageContaining("never reached 1");
    }

    @Test
    void aFailedCloseActiveTabIsAUiActionFailedWithoutALocator() {
        Client failing = new Client(verbs, new FailingApp(), "UIT-test");
        UiActionFailed e = catchThrowableOfType(failing::closeActiveTab, UiActionFailed.class);
        assertThat(e.reason()).isEqualTo(Reason.TIMEOUT);
        assertThat(e.target()).isNull();
        assertThat(e).hasMessageStartingWith("closeActiveTab: ");
    }

    @Test
    void hoverAndUnhoverAreOneVerbEach() {
        ui.byId("userMenu").hover();
        ui.byId("userMenu").unhover();
        assertThat(verbs.calls).containsExactly("hover #userMenu true null", "hover #userMenu false null");
    }

    @Test
    void itemNarrowsToATreeRowByPath() {
        ui.tree("Grouping").item("A", "B").click();
        Locator expected = loc("tree", "Grouping");
        expected.item = Arrays.asList("A", "B");
        assertThat(verbs.calls).containsExactly("click " + expected + " null null null null");
    }

    @Test
    void closeIsOneVerbOnTheWindowAndNeedsAWindow() {
        ui.dialog("Order ORD-1640*").close();
        assertThat(verbs.calls).containsExactly("closeWindow dialog:Order ORD-1640* null");
        assertThatThrownBy(() -> ui.button("Save").close()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("window target");
    }

    @Test
    void resizeColumnAndResizeToAreOneVerbEach() {
        ui.table("Orders").column("Description").resizeColumn(2000);
        ui.main().resizeTo(1600, 900);
        Locator column = loc("table", "Orders");
        column.column = "Description";
        assertThat(verbs.calls).containsExactly("resizeColumn " + column + " 2000 null", "resizeWindow main 1600x900 null");
    }

    @Test
    void resizeToNeedsAWindowAndResizeColumnAColumn() {
        assertThatThrownBy(() -> ui.button("OK").resizeTo(10, 10)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ui.table("Orders").resizeColumn(10)).isInstanceOf(IllegalStateException.class);
    }
}
