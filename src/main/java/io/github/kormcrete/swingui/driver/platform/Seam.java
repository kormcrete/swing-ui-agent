package io.github.kormcrete.swingui.driver.platform;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Window;
import java.awt.dnd.DropTargetContext;
import java.awt.dnd.peer.DropTargetContextPeer;
import java.util.List;
import java.util.Set;

/**
 * The only code that touches JDK internals. One implementation per platform and JDK line.
 * Every method belonging to a missing capability throws CAPABILITY_GAP.
 */
public interface Seam {

    String name();

    Set<Capability> available();

    /** Why a capability is missing, or null when it is available. */
    String missingBecause(Capability capability);

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

    /** Whether the OS counts this process as the active app; null when unknown. */
    Boolean appActive();
}
