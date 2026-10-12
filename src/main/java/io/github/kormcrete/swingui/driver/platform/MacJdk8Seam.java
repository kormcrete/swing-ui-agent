package io.github.kormcrete.swingui.driver.platform;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.DefaultKeyboardFocusManager;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.dnd.DropTargetContext;
import java.awt.dnd.peer.DropTargetContextPeer;
import java.awt.event.ActionEvent;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * macOS on Java 8. Every lookup happens once, in the constructor. A failed lookup leaves
 * its capability missing with the reason, and {@link SelfCheck} decides whether that stops the launch.
 *
 * <p>Constructing this starts the AWT toolkit, so {@code LaunchMode} must already have set {@code apple.awt.UIElement}.
 * The focus peer is reached through {@code sun.awt.KeyboardFocusManagerPeerProvider}: on 1.8.0_291
 * {@code sun.awt.ComponentFactory} does not declare {@code getKeyboardFocusManagerPeer()}.</p>
 */
public final class MacJdk8Seam implements Seam {

    private final Map<Capability, String> missing = new EnumMap<Capability, String>(Capability.class);
    private final Toolkit toolkit;

    private Object focusPeer;
    private Method setFocusedWindow;
    private Method getFocusedWindow;
    private Field typeAheadMarkers;
    private Field enqueuedKeyEvents;
    private Field untilFocused;
    private Method dequeueKeyEvents;
    private Method isSystemGenerated;
    private Method addNotify;
    private Method isApplicationActive;

    public MacJdk8Seam() {
        toolkit = Toolkit.getDefaultToolkit();
        lookUpFocusPeer();
        lookUpTypeAhead();
        lookUpSystemGenerated();
        lookUpDropPeer();
        lookUpApplicationActive();
    }

    private void lookUpFocusPeer() {
        try {
            Class<?> provider = Class.forName("sun.awt.KeyboardFocusManagerPeerProvider");
            if (!provider.isInstance(toolkit)) {
                throw new IllegalStateException(toolkit.getClass().getName() + " is no KeyboardFocusManagerPeerProvider");
            }
            focusPeer = provider.getMethod("getKeyboardFocusManagerPeer").invoke(toolkit);
            Class<?> peerType = Class.forName("java.awt.peer.KeyboardFocusManagerPeer");
            setFocusedWindow = peerType.getMethod("setCurrentFocusedWindow", Window.class);
            getFocusedWindow = peerType.getMethod("getCurrentFocusedWindow");
            getFocusedWindow.invoke(focusPeer);
        } catch (Exception e) {
            missing.put(Capability.PIN_FOCUSED_WINDOW, describe(e));
        }
    }

    private void lookUpTypeAhead() {
        try {
            typeAheadMarkers = accessible(DefaultKeyboardFocusManager.class.getDeclaredField("typeAheadMarkers"));
            enqueuedKeyEvents = accessible(DefaultKeyboardFocusManager.class.getDeclaredField("enqueuedKeyEvents"));
            untilFocused = accessible(Class.forName("java.awt.DefaultKeyboardFocusManager$TypeAheadMarker")
                    .getDeclaredField("untilFocused"));
            dequeueKeyEvents = accessible(DefaultKeyboardFocusManager.class
                    .getDeclaredMethod("dequeueKeyEvents", long.class, Component.class));
            pendingFocusRequests();
            heldKeyCount();
        } catch (Exception e) {
            missing.put(Capability.TYPE_AHEAD, describe(e));
        }
    }

    private void lookUpSystemGenerated() {
        try {
            isSystemGenerated = Class.forName("sun.awt.SunToolkit").getMethod("isSystemGenerated", AWTEvent.class);
            isSystemGenerated.invoke(null, new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "probe"));
        } catch (Exception e) {
            missing.put(Capability.OS_INPUT, describe(e));
        }
    }

    private void lookUpDropPeer() {
        try {
            addNotify = DropTargetContext.class.getMethod("addNotify", DropTargetContextPeer.class);
        } catch (Exception e) {
            missing.put(Capability.DROP_PEER, describe(e));
        }
    }

    private void lookUpApplicationActive() {
        try {
            for (Class<?> c = toolkit.getClass(); c != null && isApplicationActive == null; c = c.getSuperclass()) {
                try {
                    isApplicationActive = accessible(c.getDeclaredMethod("isApplicationActive"));
                } catch (NoSuchMethodException ignored) {
                    // keep looking up the hierarchy
                }
            }
            if (isApplicationActive == null) {
                throw new NoSuchMethodException(toolkit.getClass().getName() + ".isApplicationActive()");
            }
            invokeActive();
        } catch (Exception e) {
            isApplicationActive = null;
            missing.put(Capability.APP_ACTIVE, describe(e));
        }
    }

    @Override
    public String name() {
        return "MacJdk8Seam(" + System.getProperty("java.version") + ")";
    }

    @Override
    public Set<Capability> available() {
        EnumSet<Capability> out = EnumSet.allOf(Capability.class);
        out.removeAll(missing.keySet());
        return out;
    }

    @Override
    public String missingBecause(Capability capability) {
        return missing.get(capability);
    }

    @Override
    public String fix() {
        return "run the client on JDK 1.8.0_291, the JDK these lookups were verified on, or update the lookups "
                + "in MacJdk8Seam for this JDK";
    }

    @Override
    public void pinFocusedWindow(Window window) {
        require(Capability.PIN_FOCUSED_WINDOW);
        invoke(Capability.PIN_FOCUSED_WINDOW, setFocusedWindow, focusPeer, window);
    }

    @Override
    public Window pinnedWindow() {
        require(Capability.PIN_FOCUSED_WINDOW);
        return (Window) invoke(Capability.PIN_FOCUSED_WINDOW, getFocusedWindow, focusPeer);
    }

    @Override
    public List<Component> pendingFocusRequests() {
        require(Capability.TYPE_AHEAD);
        DefaultKeyboardFocusManager manager = manager();
        List<Component> out = new ArrayList<Component>();
        try {
            synchronized (manager) {
                for (Object marker : (List<?>) typeAheadMarkers.get(manager)) {
                    out.add((Component) untilFocused.get(marker));
                }
            }
        } catch (IllegalAccessException e) {
            throw gap(Capability.TYPE_AHEAD, e);
        }
        return out;
    }

    @Override
    public int heldKeyCount() {
        require(Capability.TYPE_AHEAD);
        DefaultKeyboardFocusManager manager = manager();
        try {
            synchronized (manager) {
                return ((List<?>) enqueuedKeyEvents.get(manager)).size();
            }
        } catch (IllegalAccessException e) {
            throw gap(Capability.TYPE_AHEAD, e);
        }
    }

    @Override
    public void cancelPendingFocus(Component component) {
        require(Capability.TYPE_AHEAD);
        invoke(Capability.TYPE_AHEAD, dequeueKeyEvents, manager(), -1L, component);
    }

    @Override
    public boolean isSystemGenerated(AWTEvent event) {
        require(Capability.OS_INPUT);
        return Boolean.TRUE.equals(invoke(Capability.OS_INPUT, isSystemGenerated, null, event));
    }

    @Override
    public void attachDropPeer(DropTargetContext context, DropTargetContextPeer peer) {
        require(Capability.DROP_PEER);
        invoke(Capability.DROP_PEER, addNotify, context, peer);
    }

    @Override
    public Boolean appActive() {
        if (isApplicationActive == null) return null;
        try {
            return invokeActive();
        } catch (Exception e) {
            return null;
        }
    }

    private Boolean invokeActive() throws Exception {
        Object target = Modifier.isStatic(isApplicationActive.getModifiers()) ? null : toolkit;
        return (Boolean) isApplicationActive.invoke(target);
    }

    private static DefaultKeyboardFocusManager manager() {
        KeyboardFocusManager m = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        if (!(m instanceof DefaultKeyboardFocusManager)) {
            throw new IllegalStateException("the focus manager is " + m.getClass().getName()
                    + ", not a DefaultKeyboardFocusManager");
        }
        return (DefaultKeyboardFocusManager) m;
    }

    private void require(Capability capability) {
        if (missing.containsKey(capability)) {
            throw new DriverException(Reason.CAPABILITY_GAP, capability.what + " is unavailable on " + name()
                    + ": " + missing.get(capability));
        }
    }

    private static Object invoke(Capability capability, Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (Exception e) {
            throw gap(capability, e);
        }
    }

    private static DriverException gap(Capability capability, Exception e) {
        return new DriverException(Reason.CAPABILITY_GAP, capability.what + " failed: " + describe(e));
    }

    private static String describe(Exception e) {
        Throwable t = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private static <T extends AccessibleObject> T accessible(T member) {
        member.setAccessible(true);
        return member;
    }
}
