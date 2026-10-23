package io.github.kormcrete.swingui.driver.dnd;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.platform.Capability;
import io.github.kormcrete.swingui.driver.platform.Seam;

import java.awt.Component;
import java.awt.Point;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.peer.DropTargetContextPeer;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Delivers a drop without the OS: drag-enter, drag-over and drop go to the drop target
 * under the point, through a fake DropTargetContextPeer. Never starts a native drag. {@link DragAndDrop} delivers the
 * drop of a drag the app started the same way ({@link #session}).
 */
public final class DropInjector {

    private final Seam seam;

    public DropInjector(Seam seam) {
        this.seam = seam;
    }

    /** EDT. {@code at} is in {@code target}'s coordinates. Returns what the drop target decided. */
    public String drop(Component target, Point at, Transferable data) {
        int copy = DnDConstants.ACTION_COPY;
        Session session = session(target, at, data, copy, copy, false);
        if (session == null) {
            throw new DriverException(Reason.NOT_ACTIONABLE, "no drop target at " + describe(target));
        }
        try {
            session.enter();
            session.over(copy);
            session.drop(copy);
        } finally {
            session.close();
        }
        FakeDropPeer peer = session.peer;
        if (!peer.outcome.startsWith("drop accepted")) {
            throw new DriverException(Reason.NOT_ACTIONABLE, "drop rejected by " + session.name() + ": " + peer.outcome);
        }
        return peer.outcome;
    }

    /**
     * EDT. A drop in steps into the drop target at {@code at} in {@code target} (or the nearest parent with an active
     * one), with the peer attached until {@link Session#close}; null when there is none. {@code userAction} is the
     * action the gesture asks for, {@code sourceActions} what the source allows, {@code local} a transfer within this
     * JVM.
     */
    Session session(Component target, Point at, Transferable data, int userAction, int sourceActions, boolean local) {
        if (!seam.available().contains(Capability.DROP_PEER)) {
            // checked first: on Java 9+ FakeDropPeer itself cannot load while java.awt.dnd.peer is closed to us
            throw new DriverException(Reason.CAPABILITY_GAP, Capability.DROP_PEER.what + " is unavailable on "
                    + seam.name() + ": " + seam.missingBecause(Capability.DROP_PEER));
        }
        Component c = target;
        Point p = new Point(at);
        while (c != null && (c.getDropTarget() == null || !c.getDropTarget().isActive())) {
            p.translate(c.getX(), c.getY());
            c = c.getParent();
        }
        if (c == null) return null;
        DropTarget dropTarget = c.getDropTarget();
        FakeDropPeer peer = new FakeDropPeer(dropTarget, data, userAction, sourceActions, local);
        seam.attachDropPeer(dropTarget.getDropTargetContext(), peer);
        return new Session(c, dropTarget, p, peer, userAction, sourceActions, local);
    }

    /** One drop's steps, in the order a drag delivers them: enter, over, then drop or exit; close in a finally. */
    final class Session {
        final Component component;
        final DropTarget dropTarget;
        /** In {@link #component}'s coordinates. */
        final Point at;
        final FakeDropPeer peer;
        private final int userAction;
        private final int sourceActions;
        private final boolean local;

        Session(Component component, DropTarget dropTarget, Point at, FakeDropPeer peer, int userAction,
                int sourceActions, boolean local) {
            this.component = component;
            this.dropTarget = dropTarget;
            this.at = at;
            this.peer = peer;
            this.userAction = userAction;
            this.sourceActions = sourceActions;
            this.local = local;
        }

        String name() {
            return describe(component);
        }

        void enter() {
            dropTarget.dragEnter(new DropTargetDragEvent(dropTarget.getDropTargetContext(), at, userAction, sourceActions));
        }

        /** {@code dropAction}: what the drag would drop with now. */
        void over(int dropAction) {
            dropTarget.dragOver(new DropTargetDragEvent(dropTarget.getDropTargetContext(), at, dropAction, sourceActions));
        }

        void exit() {
            dropTarget.dragExit(new DropTargetEvent(dropTarget.getDropTargetContext()));
        }

        void drop(int dropAction) {
            dropTarget.drop(new DropTargetDropEvent(dropTarget.getDropTargetContext(), at, dropAction, sourceActions,
                    local));
        }

        void close() {
            seam.detachDropPeer(dropTarget.getDropTargetContext());
        }
    }

    public static Transferable files(List<File> files) {
        return new FileListTransferable(files);
    }

    public static Transferable text(String text) {
        return new StringSelection(text);
    }

    static String describe(Component c) {
        return c.getName() != null ? c.getName() : c.getClass().getSimpleName();
    }

    /**
     * Stands in for the toolkit's drop peer: serves one Transferable and records what the listener decided, in words
     * ({@link #outcome}) and as the toolkit keeps it: the action a drag would drop with now ({@link #action}: the
     * gesture's until the listener accepts another, none once it rejects), and the drop's answer.
     */
    static final class FakeDropPeer implements DropTargetContextPeer {
        enum Drop { WAITING, ACCEPTED, REJECTED }

        private final DropTarget dropTarget;
        private final Transferable data;
        private final int sourceActions;
        private final boolean local;
        private int targetActions = DnDConstants.ACTION_COPY;
        String outcome = "untouched";
        int action;
        boolean dragRejected;
        Drop drop = Drop.WAITING;
        /** What dropComplete said; null when it was not called. */
        Boolean completed;

        FakeDropPeer(DropTarget dropTarget, Transferable data, int userAction, int sourceActions, boolean local) {
            this.dropTarget = dropTarget;
            this.data = data;
            this.sourceActions = sourceActions;
            this.local = local;
            this.action = userAction;
        }

        @Override public void setTargetActions(int actions) { targetActions = actions; }
        @Override public int getTargetActions() { return targetActions; }
        @Override public DropTarget getDropTarget() { return dropTarget; }
        @Override public DataFlavor[] getTransferDataFlavors() { return data.getTransferDataFlavors(); }
        @Override public Transferable getTransferable() { return data; }
        @Override public boolean isTransferableJVMLocal() { return local; }

        @Override public void acceptDrag(int dragAction) {
            outcome = "drag accepted";
            action = oneOf(dragAction);
            if (action != DnDConstants.ACTION_NONE) dragRejected = false;
        }

        @Override public void rejectDrag() {
            outcome = "drag rejected";
            action = DnDConstants.ACTION_NONE;
            dragRejected = true;
        }

        @Override public void acceptDrop(int dropAction) {
            outcome = "drop accepted";
            action = oneOf(dropAction & sourceActions);
            drop = Drop.ACCEPTED;
        }

        @Override public void rejectDrop() {
            outcome = "drop rejected";
            drop = Drop.REJECTED;
        }

        @Override public void dropComplete(boolean success) {
            outcome += success ? ", completed" : ", failed";
            completed = success;
        }

        /** The one action of {@code actions} a drop takes, move before copy before link, as the toolkit picks it. */
        static int oneOf(int actions) {
            for (int a : new int[] {DnDConstants.ACTION_MOVE, DnDConstants.ACTION_COPY, DnDConstants.ACTION_LINK}) {
                if ((actions & a) == a) return a;
            }
            return DnDConstants.ACTION_NONE;
        }
    }

    static final class FileListTransferable implements Transferable {
        private final List<File> files;

        FileListTransferable(List<File> files) {
            this.files = new ArrayList<File>(files);
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[] {DataFlavor.javaFileListFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
            return files;
        }
    }
}
