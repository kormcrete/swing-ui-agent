package io.github.kormcrete.swingui.driver.platform;

import java.awt.Cursor;
import java.awt.Image;
import java.awt.Point;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DragGestureEvent;
import java.awt.dnd.DragGestureListener;
import java.awt.dnd.DragGestureRecognizer;
import java.awt.dnd.DragSource;
import java.awt.dnd.DragSourceContext;
import java.awt.dnd.DragSourceListener;
import java.awt.dnd.InvalidDnDOperationException;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * What standing in for the toolkit's drag source takes, the same on every platform (shared {@code java.awt.dnd} and
 * {@code sun.awt.dnd} code):
 * <ul>
 * <li>a recognizer's drag source and gesture listener, its protected fields, set for one driven gesture;</li>
 * <li>a DragSourceContext whose peer is the driver's: Java 8 takes the peer in its constructor; Java 9+ asks the
 * toolkit for one there, which the driver then replaces (its private {@code peer} field) and clears the trigger it
 * gave the toolkit's peer;</li>
 * <li>the toolkit's "drag and drop in progress" flag ({@code SunDragSourceContextPeer.setDragDropInProgress}): set for
 * the whole driven drag, it keeps any real native drag from starting meanwhile, as during a real drag.</li>
 * </ul>
 * <li>Swing's one drag gesture recognizer ({@code TransferHandler}'s private static {@code recognizer}, which every
 * TransferHandler drag goes through), made the way TransferHandler makes it on its first drag when there is none yet.</li>
 * On Java 9+ it needs {@code java.awt.dnd}, {@code sun.awt.dnd} and {@code javax.swing} open to us ({@link ModuleAccess}).
 */
final class DragSourceHook {

    private final Field source;
    private final Field listener;
    /** Java 8: (peer, trigger, cursor, image, offset, transferable, listener). */
    private final Constructor<DragSourceContext> withPeer;
    /** Java 9+: (trigger, cursor, image, offset, transferable, listener), with {@link #peer} and {@link #setTrigger}. */
    private final Constructor<DragSourceContext> withoutPeer;
    private final Field peer;
    private final Method setTrigger;
    private final Method setInProgress;
    private final String missingBecause;
    private final Field swingRecognizer;
    private final Constructor<?> newSwingRecognizer;
    private final Constructor<?> newDragHandler;
    private final String swingMissingBecause;
    /** {@link #peerReplacementRefused}'s answer once asked: "" when allowed. */
    private String peerRefused;

    DragSourceHook() {
        Field s = null;
        Field l = null;
        Constructor<DragSourceContext> c8 = null;
        Constructor<DragSourceContext> c9 = null;
        Field p = null;
        Method trigger = null;
        Method progress = null;
        String why = null;
        try {
            s = accessible(DragGestureRecognizer.class.getDeclaredField("dragSource"));
            l = accessible(DragGestureRecognizer.class.getDeclaredField("dragGestureListener"));
            Class<?> peerType = Class.forName("java.awt.dnd.peer.DragSourceContextPeer");
            Class<?> sunPeer = Class.forName("sun.awt.dnd.SunDragSourceContextPeer");
            try {
                c8 = DragSourceContext.class.getConstructor(peerType, DragGestureEvent.class, Cursor.class, Image.class,
                        Point.class, Transferable.class, DragSourceListener.class);
            } catch (NoSuchMethodException java9) {
                c9 = DragSourceContext.class.getConstructor(DragGestureEvent.class, Cursor.class, Image.class,
                        Point.class, Transferable.class, DragSourceListener.class);
                p = accessible(DragSourceContext.class.getDeclaredField("peer"));
                trigger = accessible(sunPeer.getDeclaredMethod("setTrigger", DragGestureEvent.class));
            }
            progress = sunPeer.getMethod("setDragDropInProgress", boolean.class);
            // a read that changes nothing, to learn now whether the flag can be reached at all
            Method check = sunPeer.getMethod("checkDragDropInProgress");
            try {
                check.invoke(null);
            } catch (InvocationTargetException inProgress) {
                if (!(inProgress.getCause() instanceof InvalidDnDOperationException)) throw inProgress;
            }
        } catch (Exception | LinkageError e) {
            s = null;
            l = null;
            c8 = null;
            c9 = null;
            p = null;
            trigger = null;
            progress = null;
            why = AwtSeam.describe(e);
        }
        source = s;
        listener = l;
        withPeer = c8;
        withoutPeer = c9;
        peer = p;
        setTrigger = trigger;
        setInProgress = progress;
        missingBecause = why;
        Field r = null;
        Constructor<?> newRecognizer = null;
        Constructor<?> newHandler = null;
        String swingWhy = null;
        try {
            r = accessible(Class.forName("javax.swing.TransferHandler").getDeclaredField("recognizer"));
            newRecognizer = accessible(Class.forName("javax.swing.TransferHandler$SwingDragGestureRecognizer")
                    .getDeclaredConstructor(DragGestureListener.class));
            newHandler = accessible(Class.forName("javax.swing.TransferHandler$DragHandler").getDeclaredConstructor());
            r.get(null);
        } catch (Exception | LinkageError e) {
            r = null;
            newRecognizer = null;
            newHandler = null;
            swingWhy = AwtSeam.describe(e);
        }
        swingRecognizer = r;
        newSwingRecognizer = newRecognizer;
        newDragHandler = newHandler;
        swingMissingBecause = swingWhy;
    }

    /** Null when Swing's drag recognizer can be reached. */
    String swingMissingBecause() {
        return swingMissingBecause;
    }

    /** Swing's drag recognizer; made as TransferHandler makes it on its first drag when there is none yet. EDT. */
    DragGestureRecognizer swingRecognizer() throws ReflectiveOperationException {
        if (swingMissingBecause != null) {
            throw new IllegalStateException("Swing drag hook unavailable: " + swingMissingBecause);
        }
        Object r = swingRecognizer.get(null);
        if (r == null) {
            r = newSwingRecognizer.newInstance(newDragHandler.newInstance());
            swingRecognizer.set(null, r);
        }
        return (DragGestureRecognizer) r;
    }

    /**
     * Why this Java will not let a final field be written by reflection, which {@link #context} needs on Java 9+ (its
     * {@code peer}); null when it will, and on Java 8. Asked once, on the first drag and drop, on a field of the
     * driver's own, so a refusal is known before anything is posted.
     */
    synchronized String peerReplacementRefused() {
        if (withoutPeer == null) return null;
        if (peerRefused == null) {
            try {
                Field value = accessible(FinalProbe.class.getDeclaredField("value"));
                FinalProbe probe = new FinalProbe();
                value.set(probe, "written");
                peerRefused = "written".equals(probe.value()) ? "" : "a final field kept its value after a write";
            } catch (Exception | LinkageError e) {
                peerRefused = "this Java will not let a final field be written: " + AwtSeam.describe(e);
            }
        }
        return peerRefused.isEmpty() ? null : peerRefused;
    }

    /** A final field of the driver's own, written once by {@link #peerReplacementRefused}. */
    private static final class FinalProbe {
        private final Object value;

        FinalProbe() {
            value = "unwritten";
        }

        Object value() {
            return value;
        }
    }

    /** Null when available. */
    String missingBecause() {
        return missingBecause;
    }

    DragGestureListener listener(DragGestureRecognizer recognizer) throws ReflectiveOperationException {
        require();
        return (DragGestureListener) listener.get(recognizer);
    }

    void redirect(DragGestureRecognizer recognizer, DragSource to, DragGestureListener gestures)
            throws ReflectiveOperationException {
        require();
        source.set(recognizer, to);
        listener.set(recognizer, gestures);
    }

    /**
     * A context for {@code trigger} on {@code dragPeer}. What the constructor refuses (no drag action, no transferable)
     * it throws as itself, as DragSource.startDrag would.
     */
    DragSourceContext context(Object dragPeer, DragGestureEvent trigger, Cursor cursor, Image image, Point offset,
                              Transferable data, DragSourceListener dsl) throws ReflectiveOperationException {
        require();
        try {
            if (withPeer != null) return withPeer.newInstance(dragPeer, trigger, cursor, image, offset, data, dsl);
            DragSourceContext made = withoutPeer.newInstance(trigger, cursor, image, offset, data, dsl);
            Object toolkits = peer.get(made);
            peer.set(made, dragPeer);
            // the toolkit's peer was handed the trigger, as for a real drag: it keeps no reference to the app's component
            if (setTrigger.getDeclaringClass().isInstance(toolkits)) setTrigger.invoke(toolkits, (Object) null);
            return made;
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException) throw (RuntimeException) e.getCause();
            throw e;
        }
    }

    /** False when a drag and drop is in progress already. */
    boolean begin() throws ReflectiveOperationException {
        require();
        try {
            setInProgress.invoke(null, true);
            return true;
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof InvalidDnDOperationException) return false;
            throw e;
        }
    }

    void end() throws ReflectiveOperationException {
        require();
        try {
            setInProgress.invoke(null, false);
        } catch (InvocationTargetException e) {
            // already ended: nothing left to do
            if (!(e.getCause() instanceof InvalidDnDOperationException)) throw e;
        }
    }

    private void require() {
        if (missingBecause != null) throw new IllegalStateException("drag source hook unavailable: " + missingBecause);
    }

    private static <T extends AccessibleObject> T accessible(T member) {
        member.setAccessible(true);
        return member;
    }
}
