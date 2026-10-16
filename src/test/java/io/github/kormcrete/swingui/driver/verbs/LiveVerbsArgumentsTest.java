package io.github.kormcrete.swingui.driver.verbs;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Resolved;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import javax.swing.JMenuItem;
import javax.swing.JTable;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Malformed verb arguments are BAD_INPUT before anything touches the app (no driver here). */
class LiveVerbsArgumentsTest {

    private final LiveVerbs verbs = new LiveVerbs(null);
    private final Locator button = Locator.of("button", "OK");

    @AfterEach
    void clearAnyMenuSelection() {
        // MenuSelectionManager is a JVM-wide singleton; a test that sets a selected path must not
        // leak it into whichever test the JVM happens to run next.
        MenuSelectionManager.defaultManager().clearSelectedPath();
    }

    @Test
    void clickArguments() {
        assertBad(() -> verbs.click(button, "middle", null, null, null), "button must be");
        assertBad(() -> verbs.click(button, null, 4, null, null), "count");
        assertBad(() -> verbs.click(button, null, null, Collections.singletonList("Hyper"), null), "unknown modifier");
        assertBad(() -> verbs.click(null, null, null, null, null), "'target' is required");
    }

    @Test
    void typePressAndCheckArguments() {
        assertBad(() -> verbs.type(button, null, null, null, null), "needs 'text'");
        assertBad(() -> verbs.press(" ", null, null), "needs 'keys'");
        assertBad(() -> verbs.press("Frob", null, null), "unknown key");
        assertBad(() -> verbs.check(button, null, null), "needs 'checked'");
        assertBad(() -> verbs.setClipboard(null, null), "needs 'text'");
    }

    /** Scroll takes exactly one of 'to', 'pages' and the target's 'row'. */
    @Test
    void scrollArguments() {
        assertBad(() -> verbs.scroll(null, "bottom", null, null), "needs a 'target'");
        assertBad(() -> verbs.scroll(button, null, null, null), "exactly one of");
        assertBad(() -> verbs.scroll(button, "bottom", 2, null), "exactly one of");
        assertBad(() -> verbs.scroll(button, "sideways", null, null), "'to' must be one of");
        assertBad(() -> verbs.scroll(button, null, 0, null), "must not be zero");
    }

    @Test
    void snapshotAndReadArguments() {
        assertBad(() -> verbs.snapshot("dialog", "e1", null, null), "not both");
        assertBad(() -> verbs.read(null, null), "needs a 'target'");
    }

    @Test
    void readAllRefusesATargetThatNamesOneElement() {
        Locator nth = Locator.of("button", "OK");
        nth.nth = 1;
        assertThatThrownBy(() -> verbs.readAll(nth, null)).isInstanceOf(DriverException.class)
                .hasMessageContaining("readAll");
        assertThatThrownBy(() -> verbs.readAll(Locator.ofRef("e1"), null)).isInstanceOf(DriverException.class);
    }

    @Test
    void pathArgumentsAreCheckedByTheHelper() {
        assertBad(() -> LiveVerbs.requirePath(null, 1, "pick"), "pick needs a 'path'");
        assertBad(() -> LiveVerbs.requirePath(Collections.singletonList("File"), 2, "menu"), "at least 2");
        assertBad(() -> LiveVerbs.requirePath(Arrays.asList("File", " "), 1, "menu"), "blank");
    }

    @Test
    void failureWrapsTheCaughtExceptionInAppErrorOnlyWhenEdtErrorsWereCaptured() {
        RuntimeException caught = new DriverException(Reason.NOT_ACTIONABLE, "the click did not change it");
        RuntimeException edtError = new IllegalStateException("boom on the event dispatch thread");

        RuntimeException wrapped = LiveVerbs.failure(caught, Collections.<Throwable>singletonList(edtError));
        assertThat(wrapped).isInstanceOf(DriverException.class);
        assertThat(((DriverException) wrapped).reason()).isEqualTo(Reason.APP_ERROR);
        assertThat(wrapped.getMessage()).contains("boom on the event dispatch thread");
        assertThat(wrapped.getSuppressed()).containsExactly(caught);

        RuntimeException unchanged = LiveVerbs.failure(caught, Collections.<Throwable>emptyList());
        assertThat(unchanged).isSameAs(caught);
    }

    @Test
    void dropChecksTheOutcomeForAFailedImport() {
        DriverException e = catchThrowableOfType(
                () -> LiveVerbs.requireDropSucceeded("drop accepted, failed", "list 'Attachments'"), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.APP_ERROR);
        assertThat(e.getMessage()).contains("drop into list 'Attachments' failed").contains("drop accepted, failed");

        LiveVerbs.requireDropSucceeded("drop accepted, completed", "list 'Attachments'");
    }

    @Test
    void retryUntilRetriesOnlyTheThreeReasonsUntilTheDeadline() {
        AtomicInteger calls = new AtomicInteger();
        String found = LiveVerbs.retryUntil(1_000, () -> {
            if (calls.incrementAndGet() < 3) throw new DriverException(Reason.NOT_FOUND, "not yet");
            return "found";
        });
        assertThat(found).isEqualTo("found");
        assertThat(calls.get()).isEqualTo(3);

        AtomicInteger badCalls = new AtomicInteger();
        assertBad(() -> LiveVerbs.retryUntil(1_000, () -> {
            badCalls.incrementAndGet();
            throw new DriverException(Reason.BAD_INPUT, "no retry for bad input");
        }), "no retry for bad input");
        assertThat(badCalls.get()).isEqualTo(1);

        DriverException last = new DriverException(Reason.NOT_ACTIONABLE, "still blocked");
        DriverException thrown = catchThrowableOfType(() -> {
            LiveVerbs.<Object>retryUntil(50, () -> {
                throw last;
            });
        }, DriverException.class);
        assertThat(thrown).isSameAs(last);
    }

    @Test
    void aStableAmbiguousFailsWithinTwoSeconds() {
        long start = System.currentTimeMillis();
        DriverException e = catchThrowableOfType(() -> LiveVerbs.<Object>retryUntil(10_000, () -> {
            throw new DriverException(Reason.AMBIGUOUS, "2 elements match", java.util.Arrays.asList("a", "b"));
        }), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.AMBIGUOUS);
        assertThat(System.currentTimeMillis() - start).isLessThan(2_000);
    }

    @Test
    void anAmbiguousThatResolvesWithinTheWindowPasses() {
        AtomicInteger calls = new AtomicInteger();
        String found = LiveVerbs.retryUntil(10_000, () -> {
            if (calls.incrementAndGet() < 4) throw new DriverException(Reason.AMBIGUOUS, "2 match", java.util.Arrays.asList("a", "b"));
            return "one";
        });
        assertThat(found).isEqualTo("one");
    }

    @Test
    void aChangingMatchSetKeepsRetrying() {
        long start = System.currentTimeMillis();
        AtomicInteger calls = new AtomicInteger();
        DriverException e = catchThrowableOfType(() -> LiveVerbs.<Object>retryUntil(2_500, () -> {
            throw new DriverException(Reason.AMBIGUOUS, "2 match", java.util.Arrays.asList("a", "b" + calls.incrementAndGet()));
        }), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.AMBIGUOUS);
        assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(2_400);
    }

    @Test
    void runDropRoutesADriverExceptionToRefusalAndLeavesOtherThrowablesToPropagate() {
        AtomicReference<String> outcome = new AtomicReference<String>();
        AtomicReference<DriverException> refusal = new AtomicReference<DriverException>();

        LiveVerbs.runDrop(() -> "drop accepted, completed", outcome, refusal);
        assertThat(outcome.get()).isEqualTo("drop accepted, completed");
        assertThat(refusal.get()).isNull();

        outcome.set(null);
        DriverException rejected = new DriverException(Reason.NOT_ACTIONABLE, "drop rejected by JList: drop accepted, failed");
        LiveVerbs.runDrop(() -> { throw rejected; }, outcome, refusal);
        assertThat(outcome.get()).isNull();
        assertThat(refusal.get()).isSameAs(rejected);

        refusal.set(null);
        RuntimeException appError = new IllegalStateException("boom in a DropTargetListener");
        assertThat(catchThrowable(() -> LiveVerbs.runDrop(() -> { throw appError; }, outcome, refusal)))
                .isSameAs(appError);
        assertThat(refusal.get()).isNull();
    }

    /**
     * A refused context-menu item (e.g. disabled) must leave no popup showing, or the next
     * {@code contextMenu} call's right-click just toggles the leftover one shut instead of opening a
     * new one - and burns a whole timeout finding "no context menu opened".
     */
    @Test
    void contextMenuLeavesNoPopupSelectedWhenTheActFails() {
        // A bare, never-shown JPopupMenu has no invoker; feeding one to setSelectedPath crashes
        // Swing's own BasicPopupMenuUI.MouseGrabber (NPE resolving its window). A JMenuItem alone is
        // a MenuElement too and carries none of that machinery, so it exercises the same selected-path
        // bookkeeping without depending on a realised popup.
        JMenuItem item = new JMenuItem("Export -> PDF (summary)");
        MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[] {item});
        DriverException refused = new DriverException(Reason.NOT_ACTIONABLE,
                "disabled: menu item 'Export -> PDF (summary)'");

        DriverException thrown = catchThrowableOfType(
                () -> LiveVerbs.withPopupClosedOnFailure(() -> { throw refused; }), DriverException.class);

        assertThat(thrown).isSameAs(refused);
        assertThat(MenuSelectionManager.defaultManager().getSelectedPath()).isEmpty();
    }

    @Test
    void contextMenuLeavesAnOpenSelectionAloneOnSuccess() {
        MenuElement[] path = {new JMenuItem("Export")};
        MenuSelectionManager.defaultManager().setSelectedPath(path);

        String via = LiveVerbs.withPopupClosedOnFailure(() -> "posted");

        assertThat(via).isEqualTo("posted");
        assertThat(MenuSelectionManager.defaultManager().getSelectedPath()).isEqualTo(path);
    }

    /** The refusal must say which cell was tested, not just name the whole table. */
    @Test
    void notEditableCellNamesTheRowAndColumnNotJustTheTable() {
        JTable table = new JTable(new Object[][] {{"26-1", "x"}, {"26-2", "y"}}, new Object[] {"No.", "Notes"});
        Resolved r = new Resolved(table, null, "table", null, "main",
                Collections.<String>emptyList(), Locator.of("table", null));

        DriverException e = LiveVerbs.notEditableCell(table, 1, 1, r);

        assertThat(e.reason()).isEqualTo(Reason.NOT_ACTIONABLE);
        assertThat(e.getMessage()).contains("row 1").contains("column 'Notes'").contains("table in main");
    }

    @Test
    void resizeColumnNeedsAColumnWithoutARow() {
        assertThatThrownBy(() -> verbs.resizeColumn(Locator.of("table", "Orders"), -300, null))
                .isInstanceOf(DriverException.class).hasMessageContaining("resizeColumn");
    }

    @Test
    void closeWindowNeedsAWindowAndNeverClosesTheMainOne() {
        assertBad(() -> verbs.closeWindow(null, null), "closeWindow needs a 'window'");
        assertBad(() -> verbs.closeWindow("main", null), "main window");
    }

    @Test
    void resizeWindowNeedsAPositiveSize() {
        assertThatThrownBy(() -> verbs.resizeWindow("main", 0, 600, null))
                .isInstanceOf(DriverException.class).hasMessageContaining("positive");
    }

    private static void assertBad(Executable call, String text) {
        DriverException e = catchThrowableOfType(call::execute, DriverException.class);
        assertThat(e).as("expected BAD_INPUT containing " + text).isNotNull();
        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains(text);
    }
}
