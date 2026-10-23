package io.github.kormcrete.swingui.driver.dnd;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Edt;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.input.Poster;
import io.github.kormcrete.swingui.driver.platform.Capability;
import io.github.kormcrete.swingui.driver.platform.FakeSeam;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DragGestureListener;
import java.awt.dnd.DragGestureRecognizer;
import java.awt.dnd.DragSource;
import java.awt.dnd.DragSourceAdapter;
import java.awt.dnd.DragSourceDropEvent;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A table whose rows are reordered with AWT drag and drop (a gesture recognizer that starts the drag, a drop target that
 * moves the row), dragged through posted mouse input and the stand-in for the native drag.
 */
class DragAndDropTest {

    /** How the app's gesture listener starts its drag. */
    private enum Start { THROUGH_THE_GESTURE, ON_ITS_OWN_DRAG_SOURCE, NOT_AT_ALL }

    private final FakeSeam seam = new FakeSeam();
    private final Poster poster = Poster.forThisJvm();
    private final List<DragSourceDropEvent> ended = Collections.synchronizedList(new ArrayList<DragSourceDropEvent>());
    private final AtomicInteger presses = new AtomicInteger();
    private final AtomicReference<Object> dropped = new AtomicReference<Object>();
    private JFrame frame;
    private JTable table;
    private DefaultTableModel lines;

    @AfterEach
    void close() {
        if (frame != null) Edt.run(frame::dispose);
    }

    /** Shows the Lines table; its rows move by drag and drop when {@code start} says the app starts a drag. */
    private void show(final Start start, final boolean takesDrops) {
        Edt.run(() -> {
            lines = new DefaultTableModel(new Object[][] {{"Coffee"}, {"Tea"}, {"Milk"}}, new Object[] {"Product"});
            table = new JTable(lines);
            table.setName("lines");
            final int[] dragRow = {-1};
            final DragSource own = new DragSource();
            DragGestureListener gestures = dge -> {
                int row = table.rowAtPoint(dge.getDragOrigin());
                if (row < 0 || start == Start.NOT_AT_ALL) return;
                dragRow[0] = row;
                StringSelection data = new StringSelection(String.valueOf(lines.getValueAt(row, 0)));
                DragSourceAdapter ends = new DragSourceAdapter() {
                    @Override public void dragDropEnd(DragSourceDropEvent e) { ended.add(e); }
                };
                if (start == Start.THROUGH_THE_GESTURE) dge.startDrag(DragSource.DefaultMoveDrop, data, ends);
                else own.startDrag(dge, DragSource.DefaultMoveDrop, data, ends);
            };
            own.createDefaultDragGestureRecognizer(table, DnDConstants.ACTION_MOVE, gestures);
            new DropTarget(table, DnDConstants.ACTION_MOVE, new DropTargetAdapter() {
                @Override
                public void drop(DropTargetDropEvent e) {
                    int to = table.rowAtPoint(e.getLocation());
                    if (!takesDrops || dragRow[0] < 0 || to < 0) {
                        e.rejectDrop();
                        return;
                    }
                    e.acceptDrop(DnDConstants.ACTION_MOVE);
                    try {
                        dropped.set(e.getTransferable().getTransferData(DataFlavor.stringFlavor));
                    } catch (Exception ex) {
                        e.dropComplete(false);
                        return;
                    }
                    lines.moveRow(dragRow[0], dragRow[0], to);
                    e.dropComplete(true);
                }
            });
            table.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { presses.incrementAndGet(); }
            });
            frame = new JFrame("Line order");
            frame.setContentPane(new JScrollPane(table));
            frame.setSize(300, 200);
            frame.setLocation(60, 60);
            frame.setVisible(true);
        });
    }

    private Point cell(int row) {
        return Edt.call(() -> {
            Rectangle r = table.getCellRect(row, 0, true);
            return new Point(r.x + r.width / 2, r.y + r.height / 2);
        });
    }

    private DragGestureRecognizer recognizer() {
        return Edt.call(() -> DragAndDrop.recognizerAt(table, cell(2)));
    }

    /** The whole drag: the gesture, then the drop where it ends. */
    private String drag(DragAndDrop dnd, int from, int to) {
        Point a = cell(from);
        Point b = cell(to);
        DragAndDrop.Gesture gesture = dnd.start(poster, recognizer(), table, a, b);
        return Edt.call(() -> gesture.drop(table, b));
    }

    private List<Object> products() {
        return Edt.call(() -> {
            List<Object> out = new ArrayList<Object>();
            for (int r = 0; r < lines.getRowCount(); r++) out.add(lines.getValueAt(r, 0));
            return out;
        });
    }

    @Test
    void aRowDraggedOntoAnotherMovesThereAndTheSourceHearsTheMove() {
        show(Start.THROUGH_THE_GESTURE, true);
        String outcome = drag(new DragAndDrop(seam, new DropInjector(seam)), 2, 0);
        assertThat(products()).containsExactly("Milk", "Coffee", "Tea");
        assertThat(dropped.get()).isEqualTo("Milk");
        assertThat(outcome).contains("lines").contains("move");
        assertThat(ended).hasSize(1);
        assertThat(ended.get(0).getDropSuccess()).isTrue();
        assertThat(ended.get(0).getDropAction()).isEqualTo(DnDConstants.ACTION_MOVE);
        // no drag and drop is left in progress: the next one can start
        assertThat(seam.beginDragAndDrop()).isTrue();
        seam.endDragAndDrop();
        // and the next drag works the same way
        drag(new DragAndDrop(seam, new DropInjector(seam)), 1, 2);
        assertThat(products()).containsExactly("Milk", "Tea", "Coffee");
    }

    @Test
    void theDriversStandInLeavesTheRecognizerAsItWas() {
        show(Start.THROUGH_THE_GESTURE, true);
        DragGestureRecognizer r = recognizer();
        DragSource source = r.getDragSource();
        DragGestureListener listener = seam.gestureListener(r);
        drag(new DragAndDrop(seam, new DropInjector(seam)), 2, 0);
        assertThat(r.getDragSource()).isSameAs(source);
        assertThat(seam.gestureListener(r)).isSameAs(listener);
    }

    @Test
    void aDropTheTargetRejectsIsNotActionableAndTheSourceHearsNoDrop() {
        show(Start.THROUGH_THE_GESTURE, false);
        DriverException e = catchThrowableOfType(
                () -> drag(new DragAndDrop(seam, new DropInjector(seam)), 2, 0), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.NOT_ACTIONABLE);
        assertThat(e.getMessage()).contains("drop rejected by lines");
        assertThat(products()).containsExactly("Coffee", "Tea", "Milk");
        assertThat(ended).hasSize(1);
        assertThat(ended.get(0).getDropSuccess()).isFalse();
        assertThat(seam.beginDragAndDrop()).isTrue();
        seam.endDragAndDrop();
    }

    @Test
    void aDragTheAppStartsOnItsOwnDragSourceIsACapabilityGapAndNoNativeDragBegins() {
        show(Start.ON_ITS_OWN_DRAG_SOURCE, true);
        Point a = cell(2);
        Point b = cell(0);
        DriverException e = catchThrowableOfType(
                () -> new DragAndDrop(seam, new DropInjector(seam)).start(poster, recognizer(), table, a, b),
                DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.CAPABILITY_GAP);
        assertThat(e.getMessage()).contains("DragGestureEvent.startDrag");
        assertThat(products()).containsExactly("Coffee", "Tea", "Milk");
        assertThat(ended).isEmpty();
        assertThat(seam.beginDragAndDrop()).isTrue();
        seam.endDragAndDrop();
    }

    @Test
    void aGestureTheAppStartsNoDragForIsNotActionableAndThePressEndsAsAPlainDrag() {
        show(Start.NOT_AT_ALL, true);
        Point a = cell(2);
        Point b = cell(0);
        DriverException e = catchThrowableOfType(
                () -> new DragAndDrop(seam, new DropInjector(seam)).start(poster, recognizer(), table, a, b),
                DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.NOT_ACTIONABLE);
        assertThat(e.getMessage()).contains("started no drag");
        assertThat(presses.get()).isEqualTo(1);
        assertThat(seam.beginDragAndDrop()).isTrue();
        seam.endDragAndDrop();
    }

    @Test
    void aDragShorterThanTheThresholdIsNoGestureAndSaysSo() {
        show(Start.THROUGH_THE_GESTURE, true);
        Point a = cell(2);
        Point b = new Point(a.x + 1, a.y);
        DriverException e = catchThrowableOfType(
                () -> new DragAndDrop(seam, new DropInjector(seam)).start(poster, recognizer(), table, a, b),
                DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.NOT_ACTIONABLE);
        assertThat(e.getMessage()).contains("did not recognize a drag gesture").contains("1 px");
        assertThat(ended).isEmpty();
    }

    @Test
    void withoutTheDragSourceCapabilityTheDragIsACapabilityGapAndNothingIsPosted() {
        show(Start.THROUGH_THE_GESTURE, true);
        seam.without(Capability.DRAG_SOURCE, "java.awt.dnd is closed");
        DragGestureRecognizer r = recognizer();
        Point a = cell(2);
        Point b = cell(0);
        DriverException e = catchThrowableOfType(
                () -> new DragAndDrop(seam, new DropInjector(seam)).start(poster, r, table, a, b), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.CAPABILITY_GAP);
        assertThat(e.getMessage()).contains("stand in for a drag source").contains("java.awt.dnd is closed");
        Edt.run(() -> { });
        assertThat(presses.get()).isZero();
        assertThat(products()).containsExactly("Coffee", "Tea", "Milk");
    }

    @Test
    void onlyAComponentWithADragGestureRecognizerIsADragAndDropSource() {
        show(Start.THROUGH_THE_GESTURE, true);
        assertThat(recognizer()).isNotNull();
        assertThat(recognizer().getComponent()).isSameAs(table);
        JSlider slider = Edt.call(() -> {
            JSlider s = new JSlider();
            s.setSize(120, 20);
            return s;
        });
        assertThat(Edt.call(() -> DragAndDrop.recognizerAt(slider, new Point(1, 1)))).isNull();
        JTable plain = Edt.call(() -> {
            JTable t = new JTable(lines);
            t.setSize(120, 60);
            return t;
        });
        assertThat(Edt.call(() -> DragAndDrop.recognizerAt(plain, new Point(1, 1)))).isNull();
    }
}
