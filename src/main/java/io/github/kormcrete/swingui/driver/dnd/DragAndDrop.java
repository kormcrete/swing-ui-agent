package io.github.kormcrete.swingui.driver.dnd;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Edt;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.input.HitCheck;
import io.github.kormcrete.swingui.driver.input.Poster;
import io.github.kormcrete.swingui.driver.platform.Capability;
import io.github.kormcrete.swingui.driver.platform.Seam;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Image;
import java.awt.Point;
import java.awt.Window;
import java.awt.datatransfer.FlavorMap;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DragGestureEvent;
import java.awt.dnd.DragGestureListener;
import java.awt.dnd.DragGestureRecognizer;
import java.awt.dnd.DragSource;
import java.awt.dnd.DragSourceContext;
import java.awt.dnd.DragSourceDragEvent;
import java.awt.dnd.DragSourceDropEvent;
import java.awt.dnd.DragSourceEvent;
import java.awt.dnd.DragSourceListener;
import java.awt.dnd.DragSourceMotionListener;
import java.awt.dnd.InvalidDnDOperationException;
import java.awt.dnd.peer.DragSourceContextPeer;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.lang.reflect.Method;
import java.util.List;

/**
 * A drag the app handles with drag and drop, done in process; a native drag is never started (posted input cannot
 * finish one, and it would take the person's real pointer). Two kinds of source:
 * <ul>
 * <li>AWT drag and drop: a DragGestureRecognizer on the component the press goes to, whose DragGestureListener
 * starts the drag;</li>
 * <li>Swing's own: a component with {@code setDragEnabled(true)} and a TransferHandler, whose drag goes through Swing's
 * one drag gesture recognizer and TransferHandler's drag handler.</li>
 * </ul>
 * The same steps for both:
 * <ol>
 * <li>While the driver posts the move, the press and the drag motion, the recognizer hands its gesture to the app with
 * the driver's stand-in as its drag source ({@link Seam#redirectGestures}): {@code DragGestureEvent.startDrag} (or
 * {@code getDragSource().startDrag}) records the drag - its Transferable and DragSourceListener - on a
 * DragSourceContext with the driver's peer instead of starting a native drag.</li>
 * <li>The toolkit's "drag and drop in progress" flag is set the whole time ({@link Seam#beginDragAndDrop}), as during a
 * native drag: no native drag can start meanwhile, not even from a DragSource the app holds itself - which is how a
 * drag started there shows, and the verb says so (CAPABILITY_GAP).</li>
 * <li>Once the app started its drag, the rest of the motion and the release are not posted, as a native drag takes
 * them; the recognizer gets its own drag source and listener back.</li>
 * <li>{@link Gesture#drop} delivers dragEnter, dragOver and drop (or dragExit) to the drop target where the drag ends
 * with the Transferable the app gave startDrag, a transfer within this JVM, and then exactly one dragDropEnd with the
 * drop's action and success to the DragSourceContext, which tells the app's DragSourceListener.</li>
 * </ol>
 * The app's components get no listener and no other state from the driver: only the recognizer's two fields change,
 * for the one driven gesture. Swing's recognizer, which TransferHandler makes on its first drag, is made the same way
 * when there is none yet.
 */
public final class DragAndDrop {

    private final Seam seam;
    private final DropInjector drops;

    public DragAndDrop(Seam seam, DropInjector drops) {
        this.seam = seam;
        this.drops = drops;
    }

    /**
     * EDT. The drag gesture recognizer of the component a press at {@code p} in {@code c} goes to (AWT's mouse target
     * there, {@link HitCheck#mouseTarget}); null when it has none.
     */
    public static DragGestureRecognizer recognizerAt(Component c, Point p) {
        Component hit = pressedAt(c, p);
        if (hit == null) return null;
        for (MouseListener l : hit.getMouseListeners()) {
            if (l instanceof DragGestureRecognizer && ((DragGestureRecognizer) l).getComponent() == hit) {
                return (DragGestureRecognizer) l;
            }
        }
        return null;
    }

    /**
     * EDT. Where a press at {@code p} in {@code c} starts a drag and drop: the AWT recognizer there, or Swing's drag
     * recognizer when the component there drags with a TransferHandler ({@code setDragEnabled(true)}); null for neither,
     * a plain mouse drag. CAPABILITY_GAP when it is a Swing drag and this seam cannot stand in for it.
     */
    public DragGestureRecognizer sourceAt(Component c, Point p) {
        DragGestureRecognizer awt = recognizerAt(c, p);
        if (awt != null) return awt;
        Component hit = pressedAt(c, p);
        if (!swingDragSource(hit)) return null;
        if (!seam.available().contains(Capability.SWING_DRAG)) {
            throw new DriverException(Reason.CAPABILITY_GAP, "the drag starts Swing's drag and drop on " + describe(hit)
                    + " (setDragEnabled with a TransferHandler), which needs to " + Capability.SWING_DRAG.what
                    + ", unavailable on " + seam.name() + ": " + seam.missingBecause(Capability.SWING_DRAG));
        }
        return seam.swingDragRecognizer();
    }

    private static Component pressedAt(Component c, Point p) {
        Component root = c;
        while (!(root instanceof Window) && root.getParent() != null) root = root.getParent();
        Point at = SwingUtilities.convertPoint(c, p, root);
        return HitCheck.mouseTarget(root, at.x, at.y);
    }

    /** A component whose drags Swing turns into drag and drop: a TransferHandler, and {@code getDragEnabled()} true. */
    private static boolean swingDragSource(Component c) {
        if (!(c instanceof JComponent) || ((JComponent) c).getTransferHandler() == null) return false;
        try {
            Method enabled = c.getClass().getMethod("getDragEnabled");
            return Boolean.TRUE.equals(enabled.invoke(c));
        } catch (ReflectiveOperationException none) {
            return false;
        }
    }

    /**
     * Driver thread. The drag from {@code from} to {@code to} (both in {@code source}'s coordinates) whose press starts
     * a gesture of {@code recognizer} ({@link #sourceAt}): posts the move, the press and the drag motion one event at a
     * time, each handled before the next, until the app started its drag. Returns the gesture; when it {@link
     * Gesture#started started} a drag, {@link Gesture#drop} ends it on the EDT. Null when the recognizer hands its
     * gestures to no listener, so no drag and drop can start (nothing is posted: the caller drags as for any component).
     *
     * <p>Throws, with nothing posted: CAPABILITY_GAP when this seam or Java cannot stand in for the drag source or the
     * drop peer, or the app's drag source is a DragSource subclass of its own; NOT_ACTIONABLE when a drag and drop is in
     * progress already. When the app started no drag, the rest of the motion and the release are posted, as a person's
     * mouse would go on: the gesture is returned with a {@link Gesture#note} (the app chose not to drag) unless the
     * drag moved too little to be a gesture (NOT_ACTIONABLE), the app started its drag on a DragSource it holds itself
     * or the stand-in failed inside the app's listener (CAPABILITY_GAP).</p>
     */
    public Gesture start(Poster poster, final DragGestureRecognizer recognizer, final Component source, Point from,
                         Point to) {
        require(Capability.DRAG_SOURCE);
        require(Capability.DROP_PEER);
        final Gesture gesture = Edt.call(() -> {
            DragGestureListener listener = seam.gestureListener(recognizer);
            if (listener == null) return null;
            DragSource own = recognizer.getDragSource();
            if (own != null && own.getClass() != DragSource.class) {
                throw new DriverException(Reason.CAPABILITY_GAP, "the drag from " + describe(source) + " starts on "
                        + own.getClass().getName() + ", a subclass of DragSource of the app's own: the driver's stand-in "
                        + "would skip what it changes, so nothing was dragged");
            }
            String refused = seam.peerReplacementRefused();
            if (refused != null) {
                throw new DriverException(Reason.CAPABILITY_GAP, "the drag from " + describe(source) + " starts a drag "
                        + "and drop, which needs to " + Capability.DRAG_SOURCE.what + ", refused on " + seam.name()
                        + ": " + refused);
            }
            if (!seam.beginDragAndDrop()) {
                throw new DriverException(Reason.NOT_ACTIONABLE, "a drag and drop is in progress in the app already: "
                        + "the drag from " + describe(source) + " cannot start until it ends");
            }
            try {
                Gesture made = new Gesture(recognizer, listener, describe(source));
                seam.redirectGestures(recognizer, made.standIn, made.gestures);
                return made;
            } catch (RuntimeException | Error e) {
                seam.endDragAndDrop();
                throw e;
            }
        });
        if (gesture == null) return null;
        boolean keep = false;
        try {
            try {
                List<MouseEvent> events = poster.dragEvents(source, from, to);
                for (MouseEvent e : events) {
                    poster.post(e);
                    // queued after it: the app has handled the event when this returns
                    Edt.run(() -> { });
                    if (gesture.context != null) break;
                }
            } finally {
                Edt.run(gesture::giveBack);
            }
            if (gesture.context == null) gesture.notStarted(from, to);
            keep = gesture.context != null;
            return gesture;
        } finally {
            if (!keep) {
                Edt.run(() -> {
                    try {
                        // a drag that started and cannot go on still ends, for the app's source listener
                        if (gesture.context != null) gesture.end(DnDConstants.ACTION_NONE, false);
                    } finally {
                        seam.endDragAndDrop();
                    }
                });
            }
        }
    }

    private void require(Capability capability) {
        if (!seam.available().contains(capability)) {
            throw new DriverException(Reason.CAPABILITY_GAP, "the drag starts a drag and drop, which needs to "
                    + capability.what + ", unavailable on " + seam.name() + ": " + seam.missingBecause(capability));
        }
    }

    static String describe(Component c) {
        return c == null ? "the drag source" : DropInjector.describe(c);
    }

    private static String action(int action) {
        switch (action) {
            case DnDConstants.ACTION_MOVE: return "move";
            case DnDConstants.ACTION_COPY: return "copy";
            case DnDConstants.ACTION_LINK: return "link";
            case DnDConstants.ACTION_COPY_OR_MOVE: return "copy or move";
            default: return "none";
        }
    }

    /** One gesture of a drag the driver runs: the drag the app started through the stand-in, until {@link #drop}. */
    public final class Gesture {
        private final DragGestureRecognizer recognizer;
        private final DragSource ownSource;
        private final DragGestureListener ownListener;
        private final String name;
        final StandInSource standIn;
        final Gestures gestures;
        private final StandInPeer peer = new StandInPeer();
        private volatile boolean recognized;
        /** The toolkit refused a drag the app started elsewhere than through the gesture: our flag was set. */
        private volatile InvalidDnDOperationException elsewhere;
        /** The stand-in could not make the drag's context (inside the app's listener, which may have caught it). */
        private volatile DriverException standInFailed;
        /** What startDrag made; null until the app started its drag. */
        private volatile DragSourceContext context;
        private volatile DragGestureEvent trigger;
        private volatile Transferable data;
        private volatile String note;
        /** dragDropEnd went to the source. */
        private boolean ended;
        /** Where the drag ends, on the screen: for the source's events. */
        private Point screen = new Point();

        Gesture(DragGestureRecognizer recognizer, DragGestureListener ownListener, String name) {
            this.recognizer = recognizer;
            this.ownSource = recognizer.getDragSource();
            this.ownListener = ownListener;
            this.name = name;
            this.standIn = new StandInSource(ownSource);
            this.gestures = new Gestures();
        }

        /** The app started its drag: {@link #drop} ends it. */
        public boolean started() {
            return context != null;
        }

        /** For a gesture that started no drag and passed: what happened, in words; else null. */
        public String note() {
            return note;
        }

        /**
         * The app's gesture listener behind the driver's. It also answers to the app's own listener, so the app can
         * remove that one ({@code removeDragGestureListener}) while the driver's is in place.
         */
        final class Gestures implements DragGestureListener {
            @Override
            public void dragGestureRecognized(DragGestureEvent dge) {
                recognized = true;
                try {
                    ownListener.dragGestureRecognized(dge);
                } catch (InvalidDnDOperationException e) {
                    if (context != null || standInFailed != null) throw e;
                    // DragSource.startDrag on another drag source, refused before anything native began
                    elsewhere = e;
                }
            }

            @Override
            public boolean equals(Object o) {
                return o == this || o == ownListener;
            }

            @Override
            public int hashCode() {
                return System.identityHashCode(ownListener);
            }
        }

        /** EDT. The recognizer's own drag source and listener back, where they are still the driver's. */
        void giveBack() {
            DragSource source = recognizer.getDragSource() == standIn ? ownSource : recognizer.getDragSource();
            DragGestureListener now = seam.gestureListener(recognizer);
            DragGestureListener listener = now == gestures ? ownListener : now;
            if (source != recognizer.getDragSource() || listener != now) seam.redirectGestures(recognizer, source, listener);
        }

        /** No drag started: throws when that is a failure, else keeps the {@link #note}. */
        void notStarted(Point from, Point to) {
            if (standInFailed != null) {
                throw new DriverException(Reason.CAPABILITY_GAP, "the driver could not stand in for the drag from "
                        + name + ": " + DriverException.stripReason(standInFailed.getMessage(), standInFailed.reason()));
            }
            if (elsewhere != null) {
                throw new DriverException(Reason.CAPABILITY_GAP, "the app started its drag from " + name + " on a "
                        + "DragSource it holds itself (DragSource.startDrag) instead of through the gesture "
                        + "(DragGestureEvent.startDrag or its getDragSource().startDrag): the driver can stand in only "
                        + "for the gesture's drag source, so nothing was dragged (" + elsewhere.getMessage() + ")");
            }
            if (recognized) {
                note = name + " recognized the drag gesture but the app started no drag; the mouse drag was posted as is";
                return;
            }
            int moved = Math.max(Math.abs(to.x - from.x), Math.abs(to.y - from.y));
            int threshold = DragSource.getDragThreshold();
            if (moved <= threshold) {
                throw new DriverException(Reason.NOT_ACTIONABLE, name + " did not recognize a drag gesture in the drag "
                        + "from " + from.x + "," + from.y + " to " + to.x + "," + to.y + " (" + moved + " px; a gesture "
                        + "takes more than the drag threshold, " + threshold + " px), so no drag and drop started; "
                        + "nothing was dropped");
            }
            note = name + " recognized no drag gesture, so no drag and drop started; the mouse drag was posted as is";
        }

        /** EDT, from the app's startDrag through {@link StandInSource}. */
        private synchronized void started(DragGestureEvent dge, Cursor cursor, Image image, Point offset,
                                          Transferable t, DragSourceListener dsl) {
            if (context != null) throw new InvalidDnDOperationException("Drag and drop in progress");
            DragSourceContext made;
            try {
                made = seam.dragSourceContext(peer, dge, cursor, image, offset, t, dsl);
            } catch (DriverException e) {
                // the app hears what a real startDrag that cannot start throws; the verb says what happened
                standInFailed = e;
                throw new InvalidDnDOperationException(e.getMessage());
            }
            trigger = dge;
            data = t;
            context = made;
        }

        /** EDT. The one dragDropEnd the source hears. */
        void end(int action, boolean success) {
            if (ended) return;
            ended = true;
            context.dragDropEnd(new DragSourceDropEvent(context, action, success, screen.x, screen.y));
        }

        /**
         * EDT. Drops at {@code at} in {@code target}: dragEnter, dragOver and drop (or dragExit) to the drop target there
         * (or its nearest parent with one), with the Transferable the app gave startDrag; then dragDropEnd to the
         * source, whatever happens. Returns what happened, in words. Throws NOT_ACTIONABLE when there is no drop target,
         * the drop target refused the drag or rejected the drop, APP_ERROR when it accepted the drop but did not
         * complete it. A drop the target ran without accepting or rejecting it passes (as with a person: the app may
         * act in drop() without telling the toolkit); the source hears that it did not complete. Ends the drag and drop
         * in every case.
         */
        public String drop(Component target, Point at) {
            if (context == null) throw new IllegalStateException("the app started no drag");
            try {
                return deliver(target, at);
            } finally {
                try {
                    end(DnDConstants.ACTION_NONE, false);
                } finally {
                    seam.endDragAndDrop();
                }
            }
        }

        private String deliver(Component target, Point at) {
            int user = trigger.getDragAction();
            int allowed = context.getSourceActions();
            screen = new Point(at);
            if (target.isShowing()) SwingUtilities.convertPointToScreen(screen, target);
            DropInjector.Session s = drops.session(target, at, data, user, allowed, true);
            if (s == null) {
                end(DnDConstants.ACTION_NONE, false);
                throw new DriverException(Reason.NOT_ACTIONABLE, "no drop target at " + describe(target)
                        + ": the drag from " + name + " ended without a drop");
            }
            // the drop target counts the drag inside it from its dragEnter on, until dragExit or drop
            boolean targetIn = true;
            boolean sourceIn = false;
            try {
                RuntimeException threw = null;
                try {
                    s.enter();
                } catch (RuntimeException e) {
                    threw = refuse(s, e);
                }
                if (accepts(s, threw)) {
                    sourceIn = true;
                    context.dragEnter(dragEvent(user, s));
                }
                try {
                    s.over(s.peer.action);
                } catch (RuntimeException e) {
                    if (threw == null) threw = refuse(s, e);
                }
                if (!accepts(s, threw)) {
                    targetIn = false;
                    s.exit();
                    if (sourceIn) context.dragExit(new DragSourceEvent(context, screen.x, screen.y));
                    end(DnDConstants.ACTION_NONE, false);
                    throw new DriverException(Reason.NOT_ACTIONABLE, s.name() + " refused the drag from " + name
                            + (threw != null ? ": its drop target listener threw " + threw : " (it rejected the drag)")
                            + "; nothing was dropped");
                }
                if (sourceIn) {
                    context.dragOver(dragEvent(user, s));
                } else {
                    sourceIn = true;
                    context.dragEnter(dragEvent(user, s));
                }
                targetIn = false;
                try {
                    s.drop(s.peer.action);
                } finally {
                    // as the toolkit ends a drop: only one accepted with an action the source allows can succeed, and
                    // only once completed
                    if (taken(s)) end(s.peer.action, Boolean.TRUE.equals(s.peer.completed));
                    else end(DnDConstants.ACTION_NONE, false);
                }
                return dropped(s);
            } finally {
                try {
                    if (targetIn) s.exit();
                } finally {
                    s.close();
                }
            }
        }

        /** What the drop target did with the drop, in words; throws when that fails the step. */
        private String dropped(DropInjector.Session s) {
            String onto = "drag and drop from " + name + " onto " + s.name() + ": ";
            switch (s.peer.drop) {
                case WAITING:
                    return onto + s.name() + " took the drop without accepting it; the source was told the drop did "
                            + "not complete";
                case REJECTED:
                    throw new DriverException(Reason.NOT_ACTIONABLE, "drop rejected by " + s.name() + "; the drag from "
                            + name + " dropped nothing");
                default:
                    break;
            }
            if (!taken(s)) {
                throw new DriverException(Reason.NOT_ACTIONABLE, "drop rejected by " + s.name() + ": it accepted the "
                        + "drop as a " + action(s.peer.acceptedAs) + ", which the drag from " + name + " does not allow ("
                        + action(context.getSourceActions()) + "); nothing was dropped");
            }
            String how = "accepted the drop (" + action(s.peer.action) + ")";
            if (!Boolean.TRUE.equals(s.peer.completed)) {
                throw new DriverException(Reason.APP_ERROR, "the drop into " + s.name() + " failed: it " + how
                        + (s.peer.completed == null ? " but never called dropComplete" : " but completed it as failed"));
            }
            return onto + "it " + how + " and completed it";
        }

        private boolean taken(DropInjector.Session s) {
            return s.peer.drop == DropInjector.FakeDropPeer.Drop.ACCEPTED && s.peer.action != DnDConstants.ACTION_NONE;
        }

        /** As the toolkit does with a drop target listener that throws while a drag is over it: no drop there. */
        private RuntimeException refuse(DropInjector.Session s, RuntimeException e) {
            e.printStackTrace();
            s.peer.action = DnDConstants.ACTION_NONE;
            return e;
        }

        private boolean accepts(DropInjector.Session s, RuntimeException threw) {
            return threw == null && s.peer.action != DnDConstants.ACTION_NONE;
        }

        private DragSourceDragEvent dragEvent(int user, DropInjector.Session s) {
            return new DragSourceDragEvent(context, user, s.peer.action & context.getSourceActions(),
                    InputEvent.BUTTON1_DOWN_MASK, screen.x, screen.y);
        }

        /**
         * The gesture's drag source while the driver runs it: startDrag records the drag instead of starting a native
         * one. The listeners added to the recognizer's own drag source hear this drag too.
         */
        final class StandInSource extends DragSource {
            private static final long serialVersionUID = 1L;

            StandInSource(DragSource own) {
                if (own == null) return;
                for (DragSourceListener l : own.getDragSourceListeners()) addDragSourceListener(l);
                for (DragSourceMotionListener l : own.getDragSourceMotionListeners()) addDragSourceMotionListener(l);
            }

            @Override
            public void startDrag(DragGestureEvent dge, Cursor cursor, Image image, Point offset, Transferable t,
                                  DragSourceListener dsl, FlavorMap flavors) throws InvalidDnDOperationException {
                started(dge, cursor, image, offset, t, dsl);
            }
        }
    }

    /** Stands in for the toolkit's drag source peer: keeps the cursor it is given, starts nothing. */
    static final class StandInPeer implements DragSourceContextPeer {
        private Cursor cursor;

        @Override public void startDrag(DragSourceContext dsc, Cursor c, Image dragImage, Point imageOffset) { cursor = c; }
        @Override public Cursor getCursor() { return cursor; }
        @Override public void setCursor(Cursor c) { cursor = c; }
        @Override public void transferablesFlavorsChanged() { }
    }
}
