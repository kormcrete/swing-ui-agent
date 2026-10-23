package io.github.kormcrete.swingui.driver.dnd;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Edt;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.input.HitCheck;
import io.github.kormcrete.swingui.driver.input.Poster;
import io.github.kormcrete.swingui.driver.platform.Capability;
import io.github.kormcrete.swingui.driver.platform.Seam;

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
import java.util.List;

/**
 * A drag the app handles with AWT drag and drop - a DragGestureRecognizer on the component the press goes to, whose
 * DragGestureListener starts the drag, and a DropTarget where it ends - done in process. A native drag is never
 * started: posted input cannot finish one, and it would take the person's real pointer.
 * <ol>
 * <li>While the driver posts the move, the press and the drag motion, the recognizer hands its gesture to the app with
 * the driver's stand-in as its drag source ({@link Seam#redirectGestures}): the app's
 * {@code DragGestureEvent.startDrag} records the drag - its Transferable and DragSourceListener - on a
 * DragSourceContext with the driver's peer instead of starting a native drag.</li>
 * <li>The toolkit's "drag and drop in progress" flag is set the whole time ({@link Seam#beginDragAndDrop}), as during a
 * native drag: no native drag can start meanwhile, not even from a DragSource of the app's own - which is how a drag
 * started there shows, and the verb says so (CAPABILITY_GAP).</li>
 * <li>Once the app started its drag, the rest of the motion and the release are not posted, as a native drag takes
 * them; the recognizer gets its own drag source and listener back.</li>
 * <li>{@link Gesture#drop} delivers dragEnter, dragOver and drop to the drop target where the drag ends with the
 * Transferable the app gave startDrag, a transfer within this JVM, and then dragDropEnd with the drop's action and
 * success to the DragSourceContext, which tells the app's DragSourceListener.</li>
 * </ol>
 * The app's components get no listener and no other state from the driver: only the recognizer's two fields change,
 * for the one driven gesture.
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
        Component root = c;
        while (!(root instanceof Window) && root.getParent() != null) root = root.getParent();
        Point at = SwingUtilities.convertPoint(c, p, root);
        Component hit = HitCheck.mouseTarget(root, at.x, at.y);
        if (hit == null) return null;
        for (MouseListener l : hit.getMouseListeners()) {
            if (l instanceof DragGestureRecognizer && ((DragGestureRecognizer) l).getComponent() == hit) {
                return (DragGestureRecognizer) l;
            }
        }
        return null;
    }

    /**
     * Driver thread. The drag from {@code from} to {@code to} (both in {@code source}'s coordinates) whose press goes
     * to {@code recognizer}: posts the move, the press and the drag motion one event at a time, each handled before the
     * next, until the app started its drag. Returns that drag, for {@link Gesture#drop} on the EDT; null when the
     * recognizer hands its gestures to no listener, so no drag and drop can start (nothing is posted: the caller drags
     * as for any component).
     *
     * <p>Throws, with nothing posted, CAPABILITY_GAP when this seam cannot stand in for a drag source or a drop peer,
     * and NOT_ACTIONABLE when a drag and drop is in progress already. When the app started no drag, the rest of the
     * motion and the release are posted, as a person's mouse would go on, and it throws NOT_ACTIONABLE (no gesture, or
     * the app started no drag for it) or CAPABILITY_GAP (the app started its drag on a DragSource of its own).</p>
     */
    public Gesture start(Poster poster, final DragGestureRecognizer recognizer, Component source, Point from, Point to) {
        require(Capability.DRAG_SOURCE);
        require(Capability.DROP_PEER);
        final Gesture gesture = Edt.call(() -> {
            DragGestureListener listener = seam.gestureListener(recognizer);
            if (listener == null) return null;
            if (!seam.beginDragAndDrop()) {
                throw new DriverException(Reason.NOT_ACTIONABLE, "a drag and drop is in progress in the app already: "
                        + "the drag from " + describe(recognizer.getComponent()) + " cannot start until it ends");
            }
            try {
                Gesture made = new Gesture(recognizer, listener);
                seam.redirectGestures(recognizer, made.standIn, made.gestures);
                return made;
            } catch (RuntimeException | Error e) {
                seam.endDragAndDrop();
                throw e;
            }
        });
        if (gesture == null) return null;
        boolean started = false;
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
            started = gesture.context != null;
            if (!started) throw gesture.notStarted(from, to);
            return gesture;
        } finally {
            if (!started) Edt.run(seam::endDragAndDrop);
        }
    }

    private void require(Capability capability) {
        if (!seam.available().contains(capability)) {
            throw new DriverException(Reason.CAPABILITY_GAP, "the drag starts an AWT drag and drop, which needs to "
                    + capability.what + ", unavailable on " + seam.name() + ": " + seam.missingBecause(capability));
        }
    }

    static String describe(Component c) {
        return DropInjector.describe(c);
    }

    private static String action(int action) {
        switch (action) {
            case DnDConstants.ACTION_MOVE: return "move";
            case DnDConstants.ACTION_COPY: return "copy";
            case DnDConstants.ACTION_LINK: return "link";
            default: return "none";
        }
    }

    /** One drag the app started through the driver's stand-in, until {@link #drop} ends it. */
    public final class Gesture {
        private final DragGestureRecognizer recognizer;
        private final DragSource ownSource;
        private final DragGestureListener ownListener;
        private final String name;
        final StandInSource standIn;
        final DragGestureListener gestures;
        private final StandInPeer peer = new StandInPeer();
        private volatile boolean recognized;
        /** The toolkit refused a drag the app started elsewhere than through the gesture: our flag was set. */
        private volatile InvalidDnDOperationException elsewhere;
        /** What startDrag made; null until the app started its drag. */
        private volatile DragSourceContext context;
        private volatile DragGestureEvent trigger;
        private volatile Transferable data;

        Gesture(DragGestureRecognizer recognizer, DragGestureListener ownListener) {
            this.recognizer = recognizer;
            this.ownSource = recognizer.getDragSource();
            this.ownListener = ownListener;
            this.name = describe(recognizer.getComponent());
            this.standIn = new StandInSource(ownSource);
            this.gestures = dge -> {
                recognized = true;
                try {
                    this.ownListener.dragGestureRecognized(dge);
                } catch (InvalidDnDOperationException e) {
                    if (context != null) throw e;
                    // DragSource.startDrag on another drag source, refused before anything native began
                    elsewhere = e;
                }
            };
        }

        /** EDT. The recognizer's own drag source and listener back, where they are still the driver's. */
        void giveBack() {
            DragSource source = recognizer.getDragSource() == standIn ? ownSource : recognizer.getDragSource();
            DragGestureListener now = seam.gestureListener(recognizer);
            DragGestureListener listener = now == gestures ? ownListener : now;
            if (source != recognizer.getDragSource() || listener != now) seam.redirectGestures(recognizer, source, listener);
        }

        DriverException notStarted(Point from, Point to) {
            if (elsewhere != null) {
                return new DriverException(Reason.CAPABILITY_GAP, "the app started its drag from " + name + " on a "
                        + "DragSource of its own (DragSource.startDrag) instead of through the gesture "
                        + "(DragGestureEvent.startDrag): the driver can stand in only for the gesture's drag source, so "
                        + "nothing was dragged (" + elsewhere.getMessage() + ")");
            }
            if (recognized) {
                return new DriverException(Reason.NOT_ACTIONABLE, name + " recognized the drag gesture, but the app "
                        + "started no drag (its DragGestureListener did not call startDrag, or started its drag on a "
                        + "DragSource of its own and caught the refusal); nothing was dropped");
            }
            int moved = Math.max(Math.abs(to.x - from.x), Math.abs(to.y - from.y));
            return new DriverException(Reason.NOT_ACTIONABLE, name + " did not recognize a drag gesture in the drag "
                    + "from " + from.x + "," + from.y + " to " + to.x + "," + to.y + " (" + moved + " px; the drag "
                    + "threshold is " + DragSource.getDragThreshold() + " px), so the app started no drag and drop; "
                    + "nothing was dropped");
        }

        /** EDT, from the app's startDrag through {@link StandInSource}. */
        private synchronized void started(DragGestureEvent dge, Cursor cursor, Image image, Point offset,
                                          Transferable t, DragSourceListener dsl) {
            if (context != null) throw new InvalidDnDOperationException("Drag and drop in progress");
            DragSourceContext made = seam.dragSourceContext(peer, dge, cursor, image, offset, t, dsl);
            trigger = dge;
            data = t;
            context = made;
        }

        /**
         * EDT. Drops at {@code at} in {@code target}: dragEnter, dragOver and drop to the drop target there (or its
         * nearest parent with one), with the Transferable the app gave startDrag; then dragDropEnd to the source.
         * Returns what happened, in words. Throws NOT_ACTIONABLE when there is no drop target, the drop target refused
         * the drag or rejected the drop, APP_ERROR when it accepted the drop but did not complete it. Ends the drag
         * and drop in every case.
         */
        public String drop(Component target, Point at) {
            try {
                return deliver(target, at);
            } finally {
                seam.endDragAndDrop();
            }
        }

        private String deliver(Component target, Point at) {
            int user = trigger.getDragAction();
            int allowed = context.getSourceActions();
            Point screen = new Point(at);
            if (target.isShowing()) SwingUtilities.convertPointToScreen(screen, target);
            DropInjector.Session s = drops.session(target, at, data, user, allowed, true);
            if (s == null) {
                context.dragDropEnd(new DragSourceDropEvent(context, DnDConstants.ACTION_NONE, false, screen.x, screen.y));
                throw new DriverException(Reason.NOT_ACTIONABLE, "no drop target at " + describe(target)
                        + ": the drag from " + name + " ended without a drop");
            }
            boolean ended = false;
            try {
                RuntimeException threw = null;
                try {
                    s.enter();
                } catch (RuntimeException e) {
                    threw = refuse(s, e);
                }
                if (accepts(s, threw)) context.dragEnter(dragEvent(user, s, screen));
                if (threw == null) {
                    try {
                        s.over(s.peer.action);
                    } catch (RuntimeException e) {
                        threw = refuse(s, e);
                    }
                }
                if (!accepts(s, threw)) {
                    s.exit();
                    context.dragExit(new DragSourceEvent(context, screen.x, screen.y));
                    ended = true;
                    context.dragDropEnd(new DragSourceDropEvent(context, DnDConstants.ACTION_NONE, false, screen.x,
                            screen.y));
                    throw new DriverException(Reason.NOT_ACTIONABLE, s.name() + " refused the drag from " + name
                            + (threw != null ? ": its drop target listener threw " + threw : " (it rejected the drag)")
                            + "; nothing was dropped");
                }
                context.dragOver(dragEvent(user, s, screen));
                try {
                    s.drop(s.peer.action);
                } finally {
                    // as the toolkit ends a drop: one neither accepted nor rejected is rejected, and one accepted but
                    // never completed has failed
                    boolean accepted = s.peer.drop == DropInjector.FakeDropPeer.Drop.ACCEPTED;
                    ended = true;
                    context.dragDropEnd(new DragSourceDropEvent(context, accepted ? s.peer.action : DnDConstants.ACTION_NONE,
                            accepted && Boolean.TRUE.equals(s.peer.completed), screen.x, screen.y));
                }
                if (s.peer.drop != DropInjector.FakeDropPeer.Drop.ACCEPTED) {
                    throw new DriverException(Reason.NOT_ACTIONABLE, "drop rejected by " + s.name()
                            + (s.peer.drop == DropInjector.FakeDropPeer.Drop.WAITING
                                    ? " (it neither accepted nor rejected the drop)" : "")
                            + "; the drag from " + name + " dropped nothing");
                }
                String how = "accepted the drop (" + action(s.peer.action) + ")";
                if (!Boolean.TRUE.equals(s.peer.completed)) {
                    throw new DriverException(Reason.APP_ERROR, "the drop into " + s.name() + " failed: it " + how
                            + (s.peer.completed == null ? " but never called dropComplete" : " but completed it as failed"));
                }
                return "drag and drop from " + name + " onto " + s.name() + ": it " + how + " and completed it";
            } finally {
                try {
                    if (!ended) {
                        context.dragDropEnd(new DragSourceDropEvent(context, DnDConstants.ACTION_NONE, false, screen.x,
                                screen.y));
                    }
                } finally {
                    s.close();
                }
            }
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

        private DragSourceDragEvent dragEvent(int user, DropInjector.Session s, Point screen) {
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
