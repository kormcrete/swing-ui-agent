package io.github.kormcrete.swingui.driver.dnd;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.platform.FakeSeam;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Point;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Drops into an AWT DropTargetListener that takes a file list. */
class DropInjectorTest {

    private final DropInjector drops = new DropInjector(new FakeSeam());
    private final AtomicReference<Object> received = new AtomicReference<Object>();
    private final AtomicReference<Point> at = new AtomicReference<Point>();

    private JPanel target(final boolean accepts) {
        JPanel panel = new JPanel(null);
        panel.setBounds(0, 0, 200, 100);
        new DropTarget(panel, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent e) {
                at.set(e.getLocation());
                if (!accepts) {
                    e.rejectDrop();
                    return;
                }
                e.acceptDrop(DnDConstants.ACTION_COPY);
                try {
                    received.set(e.getTransferable().getTransferData(DataFlavor.javaFileListFlavor));
                    e.dropComplete(true);
                } catch (Exception ex) {
                    e.dropComplete(false);
                }
            }
        });
        return panel;
    }

    @Test
    void aFileListReachesTheListener() {
        File file = new File("report.pdf");
        String outcome = drops.drop(target(true), new Point(5, 5), DropInjector.files(Collections.singletonList(file)));
        assertThat(outcome).isEqualTo("drop accepted, completed");
        assertThat((List<?>) received.get()).containsExactly(file);
    }

    @Test
    void aChildWithoutADropTargetPassesTheDropToItsParentAtTheParentsPoint() {
        JPanel parent = target(true);
        JLabel child = new JLabel("Attachments");
        child.setBounds(30, 40, 50, 20);
        parent.add(child);
        drops.drop(child, new Point(2, 3), DropInjector.files(Collections.singletonList(new File("a.txt"))));
        assertThat(at.get()).isEqualTo(new Point(32, 43));
    }

    @Test
    void noDropTargetIsNotActionable() {
        DriverException e = catchThrowableOfType(
                () -> drops.drop(new JPanel(), new Point(1, 1), DropInjector.text("x")), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.NOT_ACTIONABLE);
        assertThat(e.getMessage()).contains("no drop target");
    }

    @Test
    void aRejectedDropIsNotActionable() {
        DriverException e = catchThrowableOfType(
                () -> drops.drop(target(false), new Point(1, 1), DropInjector.text("x")), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.NOT_ACTIONABLE);
        assertThat(e.getMessage()).contains("drop rejected");
    }
}
