package io.github.kormcrete.swingui.driver;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs driver work on the event dispatch thread; inline when the caller is already on it. Each
 * hand-over is timed into {@link EdtClock}.
 */
public final class Edt {

    private Edt() {}

    public static <T> T call(final Callable<T> task) {
        if (SwingUtilities.isEventDispatchThread()) {
            return callHere(task);
        }
        final AtomicReference<T> result = new AtomicReference<T>();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final long[] ran = new long[1];
        long handedOver = System.nanoTime();
        try {
            SwingUtilities.invokeAndWait(() -> {
                long started = System.nanoTime();
                try {
                    result.set(task.call());
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    ran[0] = System.nanoTime() - started;
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the event dispatch thread", e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        } finally {
            // invokeAndWait's return happens-after the task, so ran[0] is visible here.
            EdtClock.add(System.nanoTime() - handedOver, ran[0]);
        }
        rethrow(failure.get());
        return result.get();
    }

    public static void run(final Runnable task) {
        call(() -> {
            task.run();
            return null;
        });
    }

    private static <T> T callHere(Callable<T> task) {
        try {
            return task.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void rethrow(Throwable t) {
        if (t == null) return;
        if (t instanceof RuntimeException) throw (RuntimeException) t;
        if (t instanceof Error) throw (Error) t;
        throw new IllegalStateException(t);
    }
}
