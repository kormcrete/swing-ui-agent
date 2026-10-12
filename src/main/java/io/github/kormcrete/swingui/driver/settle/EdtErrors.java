package io.github.kormcrete.swingui.driver.settle;

import io.github.kormcrete.swingui.driver.Edt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Catches uncaught exceptions on the event dispatch thread while an action runs.
 * The handler stays installed and passes every exception on to the handler it replaced.
 */
public final class EdtErrors {

    private final Supplier<Thread> thread;
    private final List<Throwable> caught = Collections.synchronizedList(new ArrayList<Throwable>());
    private volatile boolean recording;

    public EdtErrors(Supplier<Thread> thread) {
        this.thread = thread;
    }

    public static EdtErrors live() {
        return new EdtErrors(() -> Edt.call(Thread::currentThread));
    }

    /** Idempotent; checked again before every action in case AWT replaced the dispatch thread. */
    public void ensureInstalled() {
        Thread t = thread.get();
        Thread.UncaughtExceptionHandler current = t.getUncaughtExceptionHandler();
        if (current instanceof Recorder && ((Recorder) current).owner() == this) return;
        t.setUncaughtExceptionHandler(new Recorder(current));
    }

    public void begin() {
        caught.clear();
        ensureInstalled();
        recording = true;
    }

    public List<Throwable> end() {
        recording = false;
        synchronized (caught) {
            return new ArrayList<Throwable>(caught);
        }
    }

    private final class Recorder implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler delegate;

        Recorder(Thread.UncaughtExceptionHandler delegate) {
            this.delegate = delegate;
        }

        EdtErrors owner() {
            return EdtErrors.this;
        }

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            if (recording) caught.add(e);
            if (delegate != null) delegate.uncaughtException(t, e);
        }
    }
}
