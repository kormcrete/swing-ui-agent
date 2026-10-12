package io.github.kormcrete.swingui.driver.platform;

import javax.swing.AbstractButton;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Records, per step, OS input that reached the process and app activations after launch.
 * Neither is reconciled; they make "the harness stayed out of the user's way" measurable.
 */
public final class Watch {

    private static final int KEEP = 1000;
    private static final long SAMPLE_MS = 100;
    private static final String REAL_INPUT = "input";
    private static final String ACTIVATION = "activation";

    public static final class Mark {
        final long seq;

        Mark(long seq) {
            this.seq = seq;
        }
    }

    private static final class Entry {
        final long seq;
        final String kind;
        final String text;

        Entry(long seq, String kind, String text) {
            this.seq = seq;
            this.kind = kind;
            this.text = text;
        }
    }

    private final Seam seam;
    private final Deque<Entry> entries = new ArrayDeque<Entry>();
    private long seq;
    private Boolean lastActive;

    public Watch(Seam seam) {
        this.seam = seam;
    }

    /** Hooks the toolkit and starts the activation sampler; each only when the seam can support it. */
    public void start() {
        if (seam.available().contains(Capability.OS_INPUT)) {
            Toolkit.getDefaultToolkit().addAWTEventListener(this::onEvent,
                    AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
        }
        if (seam.available().contains(Capability.APP_ACTIVE)) {
            Thread sampler = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    onActiveSample(seam.appActive());
                    try {
                        Thread.sleep(SAMPLE_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "ui-driver-activation-watch");
            sampler.setDaemon(true);
            sampler.start();
        }
    }

    public synchronized Mark mark() {
        return new Mark(seq);
    }

    public List<String> realInputSince(Mark mark) {
        return since(mark, REAL_INPUT);
    }

    public List<String> activationsSince(Mark mark) {
        return since(mark, ACTIVATION);
    }

    public void onEvent(AWTEvent e) {
        boolean press = e.getID() == MouseEvent.MOUSE_PRESSED || e.getID() == KeyEvent.KEY_PRESSED;
        if (!press || !seam.isSystemGenerated(e)) return;
        String what = e instanceof MouseEvent
                ? "mouse press on " + describe(e.getSource())
                : "key " + ((KeyEvent) e).getKeyCode() + " on " + describe(e.getSource());
        add(REAL_INPUT, what);
    }

    public synchronized void onActiveSample(Boolean active) {
        if (active == null) return;
        if (Boolean.FALSE.equals(lastActive) && active) add(ACTIVATION, "the app became active");
        lastActive = active;
    }

    private synchronized void add(String kind, String text) {
        entries.addLast(new Entry(++seq, kind, text));
        while (entries.size() > KEEP) entries.removeFirst();
    }

    private synchronized List<String> since(Mark mark, String kind) {
        List<String> out = new ArrayList<String>();
        for (Entry e : entries) {
            if (e.seq > mark.seq && e.kind.equals(kind)) out.add(e.text);
        }
        return out;
    }

    private static String describe(Object source) {
        if (source instanceof AbstractButton && ((AbstractButton) source).getText() != null) {
            return "'" + ((AbstractButton) source).getText() + "'";
        }
        if (source instanceof Component && ((Component) source).getName() != null) {
            return ((Component) source).getName();
        }
        return source == null ? "null" : source.getClass().getSimpleName();
    }
}
