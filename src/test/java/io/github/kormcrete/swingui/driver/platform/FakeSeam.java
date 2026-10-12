package io.github.kormcrete.swingui.driver.platform;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Window;
import java.awt.dnd.DropTargetContext;
import java.awt.dnd.peer.DropTargetContextPeer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A seam with every capability, whose answers the test sets. */
public class FakeSeam implements Seam {
    public final EnumSet<Capability> available = EnumSet.allOf(Capability.class);
    public final Map<Capability, String> because = new EnumMap<Capability, String>(Capability.class);
    public final Map<Capability, String> notes = new EnumMap<Capability, String>(Capability.class);
    public boolean systemGenerated;
    public Boolean active = Boolean.FALSE;
    public Window pinned;
    public int held;
    public final List<Component> pending = new ArrayList<Component>();
    public final List<Component> cancelled = new ArrayList<Component>();
    private final DropPeerHook dropPeer = new DropPeerHook();

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
    @Override public Boolean appActive() { return active; }
}
