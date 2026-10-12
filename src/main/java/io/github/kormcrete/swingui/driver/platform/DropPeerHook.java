package io.github.kormcrete.swingui.driver.platform;

import java.awt.dnd.DropTargetContext;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Sets and clears a DropTargetContext's peer. Java 8 has public {@code addNotify(peer)} / {@code removeNotify()};
 * Java 9+ has package-private {@code setDropTargetContextPeer(peer)} / {@code reset()}, reachable once
 * {@code java.awt.dnd} is open to us ({@link ModuleAccess}). Same on every platform.
 */
final class DropPeerHook {

    private final Method attach;
    private final Method detach;
    private final String missingBecause;

    DropPeerHook() {
        Method a = null;
        Method d = null;
        String why = null;
        try {
            Class<?> peerType = Class.forName("java.awt.dnd.peer.DropTargetContextPeer");
            try {
                a = DropTargetContext.class.getMethod("addNotify", peerType);
                d = DropTargetContext.class.getMethod("removeNotify");
            } catch (NoSuchMethodException java9) {
                a = DropTargetContext.class.getDeclaredMethod("setDropTargetContextPeer", peerType);
                d = DropTargetContext.class.getDeclaredMethod("reset");
                a.setAccessible(true);
                d.setAccessible(true);
            }
        } catch (Exception | LinkageError e) {
            a = null;
            d = null;
            why = AwtSeam.describe(e);
        }
        attach = a;
        detach = d;
        missingBecause = why;
    }

    /** Null when available. */
    String missingBecause() {
        return missingBecause;
    }

    void attach(DropTargetContext context, Object peer) throws ReflectiveOperationException {
        call(attach, context, peer);
    }

    void detach(DropTargetContext context) throws ReflectiveOperationException {
        call(detach, context);
    }

    private void call(Method method, DropTargetContext context, Object... args) throws ReflectiveOperationException {
        if (method == null) throw new IllegalStateException("drop peer hook unavailable: " + missingBecause);
        try {
            method.invoke(context, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException) throw (RuntimeException) e.getCause();
            throw e;
        }
    }
}
