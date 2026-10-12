package io.github.kormcrete.swingui.driver.dnd;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.platform.Seam;

import java.awt.Component;
import java.awt.Point;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetContext;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.peer.DropTargetContextPeer;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Delivers a drop without the OS: drag-enter, drag-over and drop go to the drop target
 * under the point, through a fake DropTargetContextPeer. Never starts a native drag.
 */
public final class DropInjector {

    private final Seam seam;

    public DropInjector(Seam seam) {
        this.seam = seam;
    }

    /** EDT. {@code at} is in {@code target}'s coordinates. Returns what the drop target decided. */
    public String drop(Component target, Point at, Transferable data) {
        Component c = target;
        Point p = new Point(at);
        while (c != null && (c.getDropTarget() == null || !c.getDropTarget().isActive())) {
            p.translate(c.getX(), c.getY());
            c = c.getParent();
        }
        if (c == null) {
            throw new DriverException(Reason.NOT_ACTIONABLE, "no drop target at " + describe(target));
        }
        DropTarget dropTarget = c.getDropTarget();
        DropTargetContext context = dropTarget.getDropTargetContext();
        FakeDropPeer peer = new FakeDropPeer(dropTarget, data);
        seam.attachDropPeer(context, peer);
        try {
            int copy = DnDConstants.ACTION_COPY;
            dropTarget.dragEnter(new DropTargetDragEvent(context, p, copy, copy));
            dropTarget.dragOver(new DropTargetDragEvent(context, p, copy, copy));
            dropTarget.drop(new DropTargetDropEvent(context, p, copy, copy));
        } finally {
            context.removeNotify();
        }
        if (!peer.outcome.startsWith("drop accepted")) {
            throw new DriverException(Reason.NOT_ACTIONABLE, "drop rejected by " + describe(c) + ": " + peer.outcome);
        }
        return peer.outcome;
    }

    public static Transferable files(List<File> files) {
        return new FileListTransferable(files);
    }

    public static Transferable text(String text) {
        return new StringSelection(text);
    }

    private static String describe(Component c) {
        return c.getName() != null ? c.getName() : c.getClass().getSimpleName();
    }

    /** Stands in for the toolkit's drop peer: serves one Transferable and records what the listener decided. */
    static final class FakeDropPeer implements DropTargetContextPeer {
        private final DropTarget dropTarget;
        private final Transferable data;
        private int targetActions = DnDConstants.ACTION_COPY;
        String outcome = "untouched";

        FakeDropPeer(DropTarget dropTarget, Transferable data) {
            this.dropTarget = dropTarget;
            this.data = data;
        }

        @Override public void setTargetActions(int actions) { targetActions = actions; }
        @Override public int getTargetActions() { return targetActions; }
        @Override public DropTarget getDropTarget() { return dropTarget; }
        @Override public DataFlavor[] getTransferDataFlavors() { return data.getTransferDataFlavors(); }
        @Override public Transferable getTransferable() { return data; }
        @Override public boolean isTransferableJVMLocal() { return false; }
        @Override public void acceptDrag(int action) { outcome = "drag accepted"; }
        @Override public void rejectDrag() { outcome = "drag rejected"; }
        @Override public void acceptDrop(int action) { outcome = "drop accepted"; }
        @Override public void rejectDrop() { outcome = "drop rejected"; }
        @Override public void dropComplete(boolean success) { outcome += success ? ", completed" : ", failed"; }
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
