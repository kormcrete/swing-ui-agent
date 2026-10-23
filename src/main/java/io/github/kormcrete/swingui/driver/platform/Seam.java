package io.github.kormcrete.swingui.driver.platform;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Image;
import java.awt.Point;
import java.awt.Window;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DragGestureEvent;
import java.awt.dnd.DragGestureListener;
import java.awt.dnd.DragGestureRecognizer;
import java.awt.dnd.DragSource;
import java.awt.dnd.DragSourceContext;
import java.awt.dnd.DragSourceListener;
import java.awt.dnd.DropTargetContext;
import java.awt.dnd.peer.DragSourceContextPeer;
import java.awt.dnd.peer.DropTargetContextPeer;
import java.util.List;
import java.util.Set;

/**
 * The only code that touches JDK internals.
 * Every method belonging to a missing capability throws CAPABILITY_GAP.
 */
public interface Seam {

    String name();

    Set<Capability> available();

    /** Why a capability is missing, or null when it is available. */
    String missingBecause(Capability capability);

    /** Why an available capability needs nothing on this platform, or null. */
    default String note(Capability capability) {
        return null;
    }

    /** What to do when a required capability is missing. */
    String fix();

    /** Makes the toolkit believe {@code window} is the natively focused window, so app focus requests land. */
    void pinFocusedWindow(Window window);

    Window pinnedWindow();

    /** The components queued focus requests wait for, oldest first. EDT. */
    List<Component> pendingFocusRequests();

    /** Key events held back until a queued focus request is met. EDT. */
    int heldKeyCount();

    /** Drops the oldest queued focus request for {@code component}, releasing the keys it held. EDT. */
    void cancelPendingFocus(Component component);

    /** True for events the OS delivered, false for events posted through the event queue. */
    boolean isSystemGenerated(AWTEvent event);

    void attachDropPeer(DropTargetContext context, DropTargetContextPeer peer);

    /** Clears what {@link #attachDropPeer} set. */
    void detachDropPeer(DropTargetContext context);

    /** What {@code recognizer} hands a recognized gesture to (its protected field); null when nothing. EDT. */
    DragGestureListener gestureListener(DragGestureRecognizer recognizer);

    /**
     * Points {@code recognizer}'s next gestures at {@code source} and {@code listener} (its protected fields): the
     * DragGestureEvent it makes carries {@code source}. Set back by calling it again with what it had. EDT.
     */
    void redirectGestures(DragGestureRecognizer recognizer, DragSource source, DragGestureListener listener);

    /**
     * A DragSourceContext for {@code trigger} whose peer is {@code peer}, never the toolkit's: no native drag can start
     * from it. What DragSourceContext refuses (no drag action, no transferable) is thrown as itself. EDT.
     */
    DragSourceContext dragSourceContext(DragSourceContextPeer peer, DragGestureEvent trigger, Cursor cursor, Image image,
                                        Point offset, Transferable data, DragSourceListener listener);

    /**
     * Marks a drag and drop as in progress, as the toolkit does for a native one; while it is, no native drag can
     * start. False when one is in progress already.
     */
    boolean beginDragAndDrop();

    /** Ends what {@link #beginDragAndDrop} began. */
    void endDragAndDrop();

    /** Why this Java will not let {@link #dragSourceContext} put the driver's peer in, or null when it will. */
    String peerReplacementRefused();

    /** Swing's one drag gesture recognizer, which every TransferHandler drag goes through. EDT. */
    DragGestureRecognizer swingDragRecognizer();

    /** Whether the OS counts this process as the active app; null when unknown. */
    Boolean appActive();
}
