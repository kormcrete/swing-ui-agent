package io.github.kormcrete.swingui.driver.platform;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;

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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A seam with every capability, whose answers the test sets; drop and drag peers are the real hooks. */
public class FakeSeam implements Seam {
    public final EnumSet<Capability> available = EnumSet.allOf(Capability.class);
    public final Map<Capability, String> because = new EnumMap<Capability, String>(Capability.class);
    public final Map<Capability, String> notes = new EnumMap<Capability, String>(Capability.class);
    public boolean systemGenerated;
    /** {@link #peerReplacementRefused}'s answer. */
    public String peerReplacementRefused;
    /** Makes {@link #dragSourceContext} fail as a capability gap. */
    public boolean contextRefused;
    public Boolean active = Boolean.FALSE;
    public Window pinned;
    public int held;
    public final List<Component> pending = new ArrayList<Component>();
    public final List<Component> cancelled = new ArrayList<Component>();
    private final DropPeerHook dropPeer = new DropPeerHook();
    private final DragSourceHook dragSource = new DragSourceHook();

    public FakeSeam without(Capability c, String reason) {
        available.remove(c);
        because.put(c, reason);
        return this;
    }

    @Override public String name() { return "FakeSeam"; }
    @Override public Set<Capability> available() { return available; }
    @Override public String missingBecause(Capability c) { return because.get(c); }
    @Override public String note(Capability c) { return notes.get(c); }
    @Override public String fix() { return "use the real seam"; }
    @Override public void pinFocusedWindow(Window w) { pinned = w; }
    @Override public Window pinnedWindow() { return pinned; }
    @Override public List<Component> pendingFocusRequests() { return new ArrayList<Component>(pending); }
    @Override public int heldKeyCount() { return held; }
    @Override public void cancelPendingFocus(Component c) { cancelled.add(c); pending.remove(c); }
    @Override public boolean isSystemGenerated(AWTEvent e) { return systemGenerated; }
    @Override public void attachDropPeer(DropTargetContext context, DropTargetContextPeer peer) {
        try {
            dropPeer.attach(context, peer);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
    @Override public void detachDropPeer(DropTargetContext context) {
        try {
            dropPeer.detach(context);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
    @Override public DragGestureListener gestureListener(DragGestureRecognizer r) {
        try {
            return dragSource.listener(r);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
    @Override public void redirectGestures(DragGestureRecognizer r, DragSource source, DragGestureListener listener) {
        try {
            dragSource.redirect(r, source, listener);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
    @Override public DragSourceContext dragSourceContext(DragSourceContextPeer peer, DragGestureEvent trigger, Cursor cursor,
                                                         Image image, Point offset, Transferable data, DragSourceListener l) {
        if (contextRefused) {
            throw new DriverException(Reason.CAPABILITY_GAP, Capability.DRAG_SOURCE.what + " failed: refused by the test");
        }
        try {
            return dragSource.context(peer, trigger, cursor, image, offset, data, l);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
    @Override public boolean beginDragAndDrop() {
        try {
            return dragSource.begin();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
    @Override public void endDragAndDrop() {
        try {
            dragSource.end();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
    @Override public String peerReplacementRefused() { return peerReplacementRefused; }
    @Override public DragGestureRecognizer swingDragRecognizer() {
        try {
            return dragSource.swingRecognizer();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
    @Override public Boolean appActive() { return active; }
}
