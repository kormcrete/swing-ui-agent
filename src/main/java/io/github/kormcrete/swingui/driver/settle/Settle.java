package io.github.kormcrete.swingui.driver.settle;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Edt;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.window.Modality;

import javax.swing.SwingUtilities;
import java.awt.EventQueue;
import java.awt.Toolkit;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * After posting input: drain the event queue, end at a modal dialog that appeared, then
 * wait until every busy signal is quiet and still quiet after one more drain, still watching for modals. Generic over the modal type so
 * the rules are testable without windows.
 */
public final class Settle<W> {

    public static final int MAX_DRAIN_ROUNDS = 50;
    public static final long BUSY_POLL_MS = 100;

    /**
     * The round-trip budget {@link #drain()}'s no-arg form uses: effectively unbounded, but small
     * enough that converting it to nanoseconds (the live pump's {@link CountDownLatch#await}) never
     * overflows a {@code long} (100 years in ms times 1e6 stays well under {@link Long#MAX_VALUE}).
     */
    private static final long UNBOUNDED_MS = 100L * 365 * 24 * 60 * 60 * 1000;

    public interface EventPump {
        /** Attempts one event-queue round trip; returns whether it completed within {@code timeoutMillis}. */
        boolean roundTrip(long timeoutMillis);

        boolean idle();
    }

    public interface ModalSource<W> {
        List<W> showingModals();
    }

    public interface Clock {
        long now();
    }

    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final EventPump pump;
    private final ModalSource<W> modals;
    private final Clock clock;
    private final Sleeper sleeper;
    private final List<BusySignal> signals = new CopyOnWriteArrayList<BusySignal>();

    public Settle(EventPump pump, ModalSource<W> modals, Clock clock, Sleeper sleeper) {
        this.pump = pump;
        this.modals = modals;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public static Settle<Window> live() {
        EventPump pump = new EventPump() {
            @Override
            public boolean roundTrip(long timeoutMillis) {
                if (SwingUtilities.isEventDispatchThread()) {
                    throw new IllegalStateException("settle must not run on the event dispatch thread");
                }
                final CountDownLatch latch = new CountDownLatch(1);
                EventQueue.invokeLater(latch::countDown);
                try {
                    return latch.await(Math.max(0, timeoutMillis), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            @Override
            public boolean idle() {
                return Toolkit.getDefaultToolkit().getSystemEventQueue().peekEvent() == null;
            }
        };
        ModalSource<Window> modals = () -> Edt.call(() -> new ArrayList<Window>(Modality.showingModals()));
        return new Settle<Window>(pump, modals, System::currentTimeMillis, Thread::sleep);
    }

    /** Adds a signal; a second signal with the same name is ignored. */
    public void register(BusySignal signal) {
        for (BusySignal s : signals) {
            if (s.name().equals(signal.name())) return;
        }
        signals.add(signal);
    }

    /** Round trips until the queue is empty, at most {@link #MAX_DRAIN_ROUNDS}. Returns the rounds run. */
    public int drain() {
        return drain(clock.now() + UNBOUNDED_MS, UNBOUNDED_MS);
    }

    /**
     * {@code deadline} bounds every round trip (a stuck or deadlocked event dispatch thread throws
     * TIMEOUT instead of hanging); {@code timeoutMillis} is only for the TIMEOUT message.
     */
    private int drain(long deadline, long timeoutMillis) {
        int rounds = 0;
        do {
            long remaining = deadline - clock.now();
            if (remaining <= 0 || !pump.roundTrip(remaining)) {
                throw new DriverException(Reason.TIMEOUT, "event dispatch thread busy for " + timeoutMillis + " ms");
            }
            rounds++;
        } while (!pump.idle() && rounds < MAX_DRAIN_ROUNDS);
        return rounds;
    }

    public List<String> busyNames() {
        return busyNames(null);
    }

    /** {@code scope} null asks every signal globally; otherwise each signal answers for that window. */
    public List<String> busyNames(W scope) {
        List<String> out = new ArrayList<String>();
        for (BusySignal s : signals) {
            try {
                if (scope == null ? s.isBusy() : s.isBusyIn(scope)) out.add(s.name());
            } catch (RuntimeException e) {
                out.add(s.name() + " (failed: " + e + ")");
            }
        }
        return out;
    }

    public List<W> showingModals() {
        return modals.showingModals();
    }

    /** Returns the modal dialog that appeared since {@code modalsBefore}, or null once the app is quiet. */
    public W settle(Collection<W> modalsBefore, long timeoutMillis) {
        return settle(modalsBefore, timeoutMillis, null);
    }

    /**
     * Like {@link #settle(Collection, long)}, but busy signals are asked only for {@code scope} (null = every signal globally).
     *
     * <p>Quiet counts only when the signals are still quiet after one more drain: an app that ends one busy token on the
     * event dispatch thread and queues its next load behind it is quiet for an instant between the two.
     */
    public W settle(Collection<W> modalsBefore, long timeoutMillis, W scope) {
        long deadline = clock.now() + timeoutMillis;
        drain(deadline, timeoutMillis);
        W fresh = newModal(modalsBefore);
        if (fresh != null) return fresh;
        while (true) {
            List<String> busy = busyNames(scope);
            if (busy.isEmpty()) {
                drain(deadline, timeoutMillis);
                fresh = newModal(modalsBefore);
                if (fresh != null) return fresh;
                busy = busyNames(scope);
                if (busy.isEmpty()) return null;
            }
            if (clock.now() >= deadline) {
                throw new DriverException(Reason.TIMEOUT, "still busy after " + timeoutMillis + " ms: " + busy, busy);
            }
            pause();
            fresh = newModal(modalsBefore);
            if (fresh != null) return fresh;
        }
    }

    private W newModal(Collection<W> before) {
        W fresh = null;
        for (W modal : modals.showingModals()) {
            if (!containsSame(before, modal)) fresh = modal;
        }
        return fresh;
    }

    private static <W> boolean containsSame(Collection<W> items, W item) {
        for (W w : items) {
            if (w == item || w.equals(item)) return true;
        }
        return false;
    }

    private void pause() {
        try {
            sleeper.sleep(BUSY_POLL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DriverException(Reason.TIMEOUT, "interrupted while waiting for the app");
        }
    }
}
