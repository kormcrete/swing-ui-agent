package io.github.kormcrete.swingui.driver.settle;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Settling, with a fake queue, fake modals (strings stand in for windows) and a fake clock. */
class SettleTest {

    private int idleAfter = 0;
    private int roundTrips = 0;
    private final List<String> modals = new ArrayList<String>();
    private long now = 0;
    private final List<Long> sleeps = new ArrayList<Long>();
    private Runnable onSleep = () -> {};

    private final Settle<String> settle = new Settle<String>(
            new Settle.EventPump() {
                @Override public boolean roundTrip(long timeoutMillis) { roundTrips++; return true; }
                @Override public boolean idle() { return roundTrips >= idleAfter; }
            },
            () -> new ArrayList<String>(modals),
            () -> now,
            millis -> { sleeps.add(millis); now += millis; onSleep.run(); });

    private static BusySignal busyFor(final String name, final int polls, final AtomicInteger asked) {
        return new BusySignal() {
            @Override public String name() { return name; }
            @Override public boolean isBusy() { return asked.incrementAndGet() <= polls; }
        };
    }

    @Test
    void drainStopsWhenTheQueueIsEmpty() {
        idleAfter = 3;
        assertThat(settle.drain()).isEqualTo(3);
    }

    @Test
    void drainIsCappedAtFiftyRounds() {
        idleAfter = Integer.MAX_VALUE;
        assertThat(settle.drain()).isEqualTo(Settle.MAX_DRAIN_ROUNDS);
    }

    @Test
    void aQuietAppSettlesAtOnce() {
        assertThat(settle.settle(Collections.<String>emptyList(), 30_000)).isNull();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void aModalThatAppearedIsReturned() {
        modals.add("Delete?");
        assertThat(settle.settle(Collections.<String>emptyList(), 30_000)).isEqualTo("Delete?");
    }

    @Test
    void modalsThatWereAlreadyShowingAreNotNew() {
        modals.add("old");
        assertThat(settle.settle(Collections.singletonList("old"), 30_000)).isNull();
    }

    @Test
    void busySignalsArePolledEveryHundredMillisUntilQuiet() {
        settle.register(busyFor("jobs", 3, new AtomicInteger()));
        assertThat(settle.settle(Collections.<String>emptyList(), 30_000)).isNull();
        assertThat(sleeps).containsExactly(100L, 100L, 100L);
    }

    @Test
    void aTimeoutNamesTheBusySignals() {
        settle.register(busyFor("jobs", Integer.MAX_VALUE, new AtomicInteger()));
        DriverException e = catchThrowableOfType(
                () -> settle.settle(Collections.<String>emptyList(), 250), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.TIMEOUT);
        assertThat(e.getMessage()).contains("jobs").contains("250");
        assertThat(e.details()).containsExactly("jobs");
    }

    @Test
    void aModalDuringTheBusyWaitEndsIt() {
        settle.register(busyFor("jobs", Integer.MAX_VALUE, new AtomicInteger()));
        onSleep = () -> { if (sleeps.size() == 2) modals.add("Question"); };
        assertThat(settle.settle(Collections.<String>emptyList(), 30_000)).isEqualTo("Question");
    }

    @Test
    void aModalOpenedAsTheJobEndsIsCaughtByTheLastCheck() {
        final AtomicInteger asked = new AtomicInteger();
        settle.register(new BusySignal() {
            @Override public String name() { return "jobs"; }
            @Override public boolean isBusy() {
                if (asked.incrementAndGet() == 1) return true;
                modals.add("Done");
                return false;
            }
        });
        assertThat(settle.settle(Collections.<String>emptyList(), 30_000)).isEqualTo("Done");
    }

    @Test
    void aFailingSignalCountsAsBusyAndSaysWhy() {
        settle.register(new BusySignal() {
            @Override public String name() { return "broken"; }
            @Override public boolean isBusy() { throw new IllegalStateException("no bean"); }
        });
        assertThat(settle.busyNames()).containsExactly("broken (failed: java.lang.IllegalStateException: no bean)");
    }

    @Test
    void registeringTheSameNameTwiceKeepsOne() {
        settle.register(busyFor("jobs", 0, new AtomicInteger()));
        settle.register(busyFor("jobs", Integer.MAX_VALUE, new AtomicInteger()));
        assertThat(settle.busyNames()).isEmpty();
    }

    @Test
    void aStuckEventDispatchThreadTimesOutWithinTheBudget() {
        Settle<String> stuck = new Settle<String>(
                new Settle.EventPump() {
                    @Override public boolean roundTrip(long timeoutMillis) { return false; }
                    @Override public boolean idle() { return false; }
                },
                () -> new ArrayList<String>(modals),
                () -> now,
                millis -> { sleeps.add(millis); now += millis; onSleep.run(); });
        DriverException e = catchThrowableOfType(
                () -> stuck.settle(Collections.<String>emptyList(), 250), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.TIMEOUT);
        assertThat(e.getMessage()).contains("event dispatch thread").contains("250");
    }

    private static BusySignal busyOnlyIn(final String name, final String window) {
        return new BusySignal() {
            @Override public String name() { return name; }
            @Override public boolean isBusy() { return true; }
            @Override public boolean isBusyIn(Object w) { return window.equals(w); }
        };
    }

    @Test
    void aSignalBusyInAnotherWindowDoesNotHoldAScopedSettle() {
        settle.register(busyOnlyIn("busy spinner", "main"));
        assertThat(settle.settle(Collections.<String>emptyList(), 30_000, "preview")).isNull();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void aSignalBusyInTheScopedWindowStillHoldsIt() {
        settle.register(busyOnlyIn("busy spinner", "preview"));
        DriverException e = catchThrowableOfType(
                () -> settle.settle(Collections.<String>emptyList(), 1_000, "preview"), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.TIMEOUT);
        assertThat(e.getMessage()).contains("busy spinner");
    }

    @Test
    void anUnscopedSettleStillAsksEverySignalGlobally() {
        settle.register(busyOnlyIn("busy spinner", "main"));
        DriverException e = catchThrowableOfType(
                () -> settle.settle(Collections.<String>emptyList(), 1_000), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.TIMEOUT);
    }

    @Test
    void aSignalWithoutAWindowNotionIsBusyInEveryScope() {
        settle.register(busyFor("background jobs", 2, new AtomicInteger()));
        assertThat(settle.settle(Collections.<String>emptyList(), 30_000, "preview")).isNull();
        assertThat(sleeps).containsExactly(100L, 100L);
    }

    // ---- a window-bound signal (spinner, glass pane) answers by WindowBusy: strings stand in for windows

    private final List<String> windows = new ArrayList<String>(Arrays.asList("main", "Delete?"));
    private final Set<String> showing = new HashSet<String>(Arrays.asList("main"));
    private final Set<String> busyWindows = new HashSet<String>(Arrays.asList("main"));

    private BusySignal windowBound(final String name) {
        return new BusySignal() {
            @Override public String name() { return name; }
            @Override public boolean isBusy() { return WindowBusy.anywhere(windows, showing::contains, busyWindows::contains); }
            @Override public boolean isBusyIn(Object w) {
                return WindowBusy.scoped((String) w, windows, showing::contains, busyWindows::contains);
            }
        };
    }

    @Test
    void aScopeTheActionClosedFallsBackToTheGlobalAnswer() {
        // the step clicked OK in "Delete?": the dialog is gone, and main's spinner runs for two more polls
        settle.register(windowBound("busy spinner"));
        onSleep = () -> { if (sleeps.size() == 2) busyWindows.remove("main"); };
        assertThat(settle.settle(Collections.<String>emptyList(), 30_000, "Delete?")).isNull();
        assertThat(sleeps).containsExactly(100L, 100L);
    }

    @Test
    void aScopeStillShowingAnswersForItselfOnly() {
        showing.add("Delete?");
        settle.register(windowBound("busy spinner"));
        assertThat(settle.settle(Collections.<String>emptyList(), 30_000, "Delete?")).isNull();
        assertThat(sleeps).isEmpty();
    }
}
