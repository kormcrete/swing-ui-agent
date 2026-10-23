package io.github.kormcrete.swingui.driver.dnd;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Edt;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.input.Poster;
import io.github.kormcrete.swingui.driver.platform.Capability;
import io.github.kormcrete.swingui.driver.platform.FakeSeam;
import io.github.kormcrete.swingui.driver.settle.EdtErrors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.DefaultListModel;
import javax.swing.DropMode;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.TransferHandler;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DragGestureEvent;
import java.awt.dnd.DragGestureListener;
import java.awt.dnd.DragGestureRecognizer;
import java.awt.dnd.DragSource;
import java.awt.dnd.DragSourceAdapter;
import java.awt.dnd.DragSourceDragEvent;
import java.awt.dnd.DragSourceDropEvent;
import java.awt.dnd.DragSourceEvent;
import java.awt.dnd.DragSourceListener;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
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
 * moves the row), and a list reordered by Swing's own drag and drop (a TransferHandler), dragged through posted mouse
 * input and the stand-in for the native drag.
 */
class DragAndDropTest {

    /** How the app's gesture listener starts its drag. */
    private enum Start { THROUGH_THE_GESTURE, ON_ITS_OWN_DRAG_SOURCE, NOT_AT_ALL, REMOVING_ITS_LISTENER_FIRST,
        CATCHING_A_REFUSAL }

    /** What the app's drop target does. */
    private enum Takes { ACCEPTS_AND_COMPLETES, REJECTS_THE_DROP, MOVES_WITHOUT_ACCEPTING, ACCEPTS_A_COPY,
        NEVER_COMPLETES, REJECTS_ON_ENTER, REJECTS_ON_OVER, THROWS_ON_OVER, ASKS_FIRST }

    private final FakeSeam seam = new FakeSeam();
    private final Poster poster = Poster.forThisJvm();
    private final List<DragSourceDropEvent> ended = Collections.synchronizedList(new ArrayList<DragSourceDropEvent>());
    /** What the source's listener and the drop target heard, in order: "source enter", "target drop", ... */
    private final List<String> heard = Collections.synchronizedList(new ArrayList<String>());
    private final AtomicInteger presses = new AtomicInteger();
    private final AtomicReference<Object> dropped = new AtomicReference<Object>();
    private JFrame frame;
    private JTable table;
    private JLabel label;
    private DefaultTableModel lines;
    private JDialog asking;
    /** Makes the app's drag source a subclass of its own. */
    private boolean ownDragSourceClass;
    /** Makes the source's listener throw when the drag enters a drop target. */
    private boolean sourceThrowsOnEnter;

    @AfterEach
    void close() {
        if (frame != null) Edt.run(frame::dispose);
        if (asking != null) Edt.run(asking::dispose);
    }

    private DragAndDrop dnd() {
        return new DragAndDrop(seam, new DropInjector(seam));
    }

    private void show(Start start) {
        show(start, Takes.ACCEPTS_AND_COMPLETES);
    }

    /** Shows the Lines table (Coffee, Tea, Milk); its rows move by AWT drag and drop as {@code start} and {@code takes} say. */
    private void show(final Start start, final Takes takes) {
        Edt.run(() -> {
            lines = new DefaultTableModel(new Object[][] {{"Coffee"}, {"Tea"}, {"Milk"}}, new Object[] {"Product"});
            table = new JTable(lines);
            table.setName("lines");
            final int[] dragRow = {-1};
            final DragSource own = ownDragSourceClass ? new DragSource() {
                private static final long serialVersionUID = 1L;
            } : new DragSource();
            final DragGestureRecognizer[] recognizer = new DragGestureRecognizer[1];
            DragGestureListener gestures = new DragGestureListener() {
                @Override public void dragGestureRecognized(DragGestureEvent dge) {
                    int row = table.rowAtPoint(dge.getDragOrigin());
                    if (row < 0 || start == Start.NOT_AT_ALL) return;
                    dragRow[0] = row;
                    StringSelection data = new StringSelection(String.valueOf(lines.getValueAt(row, 0)));
                    DragSourceListener sourceListener = new DragSourceAdapter() {
                        @Override public void dragEnter(DragSourceDragEvent e) {
                            heard.add("source enter");
                            if (sourceThrowsOnEnter) throw new IllegalStateException("cursor table missing");
                        }
                        @Override public void dragOver(DragSourceDragEvent e) { heard.add("source over"); }
                        @Override public void dragExit(DragSourceEvent e) { heard.add("source exit"); }
                        @Override public void dragDropEnd(DragSourceDropEvent e) { ended.add(e); }
                    };
                    if (start == Start.REMOVING_ITS_LISTENER_FIRST) recognizer[0].removeDragGestureListener(this);
                    if (start == Start.ON_ITS_OWN_DRAG_SOURCE) {
                        own.startDrag(dge, DragSource.DefaultMoveDrop, data, sourceListener);
                    } else if (start == Start.CATCHING_A_REFUSAL) {
                        try {
                            dge.startDrag(DragSource.DefaultMoveDrop, data, sourceListener);
                        } catch (RuntimeException ignored) {
                            // as Swing's own drag handler does: a drag that cannot start is no drag
                        }
                    } else {
                        dge.startDrag(DragSource.DefaultMoveDrop, data, sourceListener);
                    }
                }
            };
            recognizer[0] = own.createDefaultDragGestureRecognizer(table, DnDConstants.ACTION_MOVE, gestures);
            new DropTarget(table, DnDConstants.ACTION_MOVE, dropTargetListener(takes, dragRow));
            table.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { presses.incrementAndGet(); }
            });
            label = new JLabel("no drop target here");
            JPanel content = new JPanel(new BorderLayout());
            content.add(new JScrollPane(table), BorderLayout.CENTER);
            content.add(label, BorderLayout.SOUTH);
            frame = new JFrame("Line order");
            frame.setContentPane(content);
            frame.setSize(300, 200);
            frame.setLocation(60, 60);
            frame.setVisible(true);
        });
    }

    private DropTargetListener dropTargetListener(final Takes takes, final int[] dragRow) {
        return new DropTargetListener() {
            @Override public void dragEnter(DropTargetDragEvent e) {
                heard.add("target enter");
                if (takes == Takes.REJECTS_ON_ENTER) e.rejectDrag();
            }
            @Override public void dragOver(DropTargetDragEvent e) {
                heard.add("target over");
                if (takes == Takes.REJECTS_ON_OVER) e.rejectDrag();
                if (takes == Takes.THROWS_ON_OVER) throw new IllegalStateException("no row under the point");
            }
            @Override public void dropActionChanged(DropTargetDragEvent e) { }
            @Override public void dragExit(DropTargetEvent e) { heard.add("target exit"); }
            @Override public void drop(DropTargetDropEvent e) {
                heard.add("target drop");
                int to = table.rowAtPoint(e.getLocation());
                int from = dragRow[0];
                switch (takes) {
                    case REJECTS_THE_DROP:
                        e.rejectDrop();
                        return;
                    case MOVES_WITHOUT_ACCEPTING:
                        // as an app may: it moves the row, and tells the toolkit nothing
                        lines.moveRow(from, from, to);
                        return;
                    case ACCEPTS_A_COPY:
                        e.acceptDrop(DnDConstants.ACTION_COPY);
                        e.dropComplete(true);
                        return;
                    case NEVER_COMPLETES:
                        e.acceptDrop(DnDConstants.ACTION_MOVE);
                        return;
                    case ASKS_FIRST:
                        asking = new JDialog(frame, "Move line?", true);
                        asking.setSize(200, 80);
                        asking.setVisible(true);
                        break;
                    default:
                        break;
                }
                e.acceptDrop(DnDConstants.ACTION_MOVE);
                try {
                    dropped.set(e.getTransferable().getTransferData(DataFlavor.stringFlavor));
                } catch (Exception ex) {
                    e.dropComplete(false);
                    return;
                }
                lines.moveRow(from, from, to);
                e.dropComplete(true);
            }
        };
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

    private DragAndDrop.Gesture start(DragAndDrop dnd, int from, int to) {
        return dnd.start(poster, recognizer(), table, cell(from), cell(to));
    }

    /** The whole drag: the gesture, then the drop where it ends. */
    private String drag(DragAndDrop dnd, int from, int to) {
        DragAndDrop.Gesture gesture = start(dnd, from, to);
        assertThat(gesture.started()).isTrue();
        Point b = cell(to);
        return Edt.call(() -> gesture.drop(table, b));
    }

    private DriverException failure(Runnable drag) {
        return catchThrowableOfType(drag::run, DriverException.class);
    }

    private List<Object> products() {
        return Edt.call(() -> {
            List<Object> out = new ArrayList<Object>();
            for (int r = 0; r < lines.getRowCount(); r++) out.add(lines.getValueAt(r, 0));
            return out;
        });
    }

    private void noDragAndDropLeftInProgress() {
        assertThat(seam.beginDragAndDrop()).isTrue();
        seam.endDragAndDrop();
    }

    @Test
    void aRowDraggedOntoAnotherMovesThereAndTheSourceHearsTheMove() {
        show(Start.THROUGH_THE_GESTURE);
        String outcome = drag(dnd(), 2, 0);
        assertThat(products()).containsExactly("Milk", "Coffee", "Tea");
        assertThat(dropped.get()).isEqualTo("Milk");
        assertThat(outcome).contains("lines").contains("move");
        assertThat(ended).hasSize(1);
        assertThat(ended.get(0).getDropSuccess()).isTrue();
        assertThat(ended.get(0).getDropAction()).isEqualTo(DnDConstants.ACTION_MOVE);
        assertThat(heard).containsExactly("target enter", "source enter", "target over", "source over", "target drop");
        noDragAndDropLeftInProgress();
        // and the next drag works the same way
        drag(dnd(), 1, 2);
        assertThat(products()).containsExactly("Milk", "Tea", "Coffee");
    }

    @Test
    void theDriversStandInLeavesTheRecognizerAsItWas() {
        show(Start.THROUGH_THE_GESTURE);
        DragGestureRecognizer r = recognizer();
        DragSource source = r.getDragSource();
        DragGestureListener listener = seam.gestureListener(r);
        drag(dnd(), 2, 0);
        assertThat(r.getDragSource()).isSameAs(source);
        assertThat(seam.gestureListener(r)).isSameAs(listener);
    }

    @Test
    void aDropTheTargetRunsWithoutAcceptingItPassesAndTheSourceHearsItDidNotComplete() {
        show(Start.THROUGH_THE_GESTURE, Takes.MOVES_WITHOUT_ACCEPTING);
        String outcome = drag(dnd(), 2, 0);
        assertThat(products()).containsExactly("Milk", "Coffee", "Tea");
        assertThat(outcome).contains("lines took the drop without accepting it")
                .contains("the source was told the drop did not complete");
        assertThat(ended).hasSize(1);
        assertThat(ended.get(0).getDropSuccess()).isFalse();
        assertThat(ended.get(0).getDropAction()).isEqualTo(DnDConstants.ACTION_NONE);
        noDragAndDropLeftInProgress();
    }

    @Test
    void aDropTheTargetRejectsIsNotActionableAndTheSourceHearsNoDrop() {
        show(Start.THROUGH_THE_GESTURE, Takes.REJECTS_THE_DROP);
        DriverException e = failure(() -> drag(dnd(), 2, 0));
        assertThat(e.reason()).isEqualTo(Reason.NOT_ACTIONABLE);
        assertThat(e.getMessage()).contains("drop rejected by lines");
        assertThat(products()).containsExactly("Coffee", "Tea", "Milk");
        assertThat(ended).hasSize(1);
        assertThat(ended.get(0).getDropSuccess()).isFalse();
        noDragAndDropLeftInProgress();
    }

    @Test
    void aDropAcceptedWithAnActionTheSourceDoesNotAllowIsRejected() {
        show(Start.THROUGH_THE_GESTURE, Takes.ACCEPTS_A_COPY);
        DriverException e = failure(() -> drag(dnd(), 2, 0));
        assertThat(e.reason()).isEqualTo(Reason.NOT_ACTIONABLE);
        assertThat(e.getMessage()).contains("drop rejected by lines").contains("copy");
        assertThat(ended).hasSize(1);
        assertThat(ended.get(0).getDropSuccess()).isFalse();
    }

    @Test
    void aDropAcceptedButNeverCompletedIsAnAppError() {
        show(Start.THROUGH_THE_GESTURE, Takes.NEVER_COMPLETES);
        DriverException e = failure(() -> drag(dnd(), 2, 0));
        assertThat(e.reason()).isEqualTo(Reason.APP_ERROR);
        assertThat(e.getMessage()).contains("accepted the drop (move) but never called dropComplete");
        assertThat(ended).hasSize(1);
        assertThat(ended.get(0).getDropSuccess()).isFalse();
        noDragAndDropLeftInProgress();
    }

    @Test
    void aDragTheTargetRefusesOnEnterIsNotActionableAndTheSourceNeverHearsAnExitItDidNotEnter() {
        show(Start.THROUGH_THE_GESTURE, Takes.REJECTS_ON_ENTER);
        DriverException e = failure(() -> drag(dnd(), 2, 0));
        assertThat(e.reason()).isEqualTo(Reason.NOT_ACTIONABLE);
        assertThat(e.getMessage()).contains("lines refused the drag");
        assertThat(heard).containsExactly("target enter", "target over", "target exit");
        assertThat(ended).hasSize(1);
        assertThat(ended.get(0).getDropSuccess()).isFalse();
        noDragAndDropLeftInProgress();
    }

    @Test
    void aDragTheTargetRefusesOnOverExitsBothSides() {
        show(Start.THROUGH_THE_GESTURE, Takes.REJECTS_ON_OVER);
        DriverException e = failure(() -> drag(dnd(), 2, 0));
        assertThat(e.reason()).isEqualTo(Reason.NOT_ACTIONABLE);
        assertThat(e.getMessage()).contains("lines refused the drag");
        assertThat(heard).containsExactly("target enter", "source enter", "target over", "target exit", "source exit");
        assertThat(ended).hasSize(1);
    }

    @Test
    void aTargetListenerThatThrowsTakesNoDropAndTheStepSaysWhy() {
        show(Start.THROUGH_THE_GESTURE, Takes.THROWS_ON_OVER);
        DriverException e = failure(() -> drag(dnd(), 2, 0));
        assertThat(e.reason()).isEqualTo(Reason.NOT_ACTIONABLE);
        assertThat(e.getMessage()).contains("lines refused the drag").contains("no row under the point");
        assertThat(products()).containsExactly("Coffee", "Tea", "Milk");
        assertThat(ended).hasSize(1);
    }

    @Test
    void aSourceListenerThatThrowsLeavesTheTargetExitedAndTheDragEnded() {
        sourceThrowsOnEnter = true;
        show(Start.THROUGH_THE_GESTURE);
        DragAndDrop.Gesture gesture = start(dnd(), 2, 0);
        Point b = cell(0);
        IllegalStateException e = catchThrowableOfType(() -> Edt.call(() -> gesture.drop(table, b)),
                IllegalStateException.class);
        assertThat(e).hasMessageContaining("cursor table missing");
        assertThat(heard).containsExactly("target enter", "source enter", "target exit");
        assertThat(ended).hasSize(1);
        assertThat(ended.get(0).getDropSuccess()).isFalse();
        noDragAndDropLeftInProgress();
    }

    @Test
    void noDropTargetWhereTheDragEndsIsNotActionableAndTheSourceHearsNoDrop() {
        show(Start.THROUGH_THE_GESTURE);
        DragAndDrop.Gesture gesture = start(dnd(), 2, 0);
        DriverException e = failure(() -> Edt.call(() -> gesture.drop(label, new Point(2, 2))));
        assertThat(e.reason()).isEqualTo(Reason.NOT_ACTIONABLE);
        assertThat(e.getMessage()).contains("no drop target at").contains("ended without a drop");
        assertThat(ended).hasSize(1);
        assertThat(ended.get(0).getDropSuccess()).isFalse();
        noDragAndDropLeftInProgress();
    }

    @Test
    void aDropThatAsksInAModalDialogIsLeftOpenAndFinishesWhenItCloses() {
        show(Start.THROUGH_THE_GESTURE, Takes.ASKS_FIRST);
        DragAndDrop.Gesture gesture = start(dnd(), 2, 0);
        Point b = cell(0);
        AtomicReference<String> outcome = new AtomicReference<String>();
        // queued, as the verb queues it: the driver thread gets its round trip while the dialog is open
        java.awt.EventQueue.invokeLater(() -> outcome.set(gesture.drop(table, b)));
        long end = System.currentTimeMillis() + 5_000;
        while (Edt.call(() -> asking == null || !asking.isShowing())) {
            assertThat(System.currentTimeMillis()).as("the dialog never showed").isLessThan(end);
            Edt.run(() -> { });
        }
        assertThat(outcome.get()).isNull();
        assertThat(seam.beginDragAndDrop()).as("the drag is still in progress").isFalse();
        Edt.run(() -> asking.setVisible(false));
        Edt.run(() -> { });
        assertThat(outcome.get()).contains("accepted the drop (move)");
        assertThat(products()).containsExactly("Milk", "Coffee", "Tea");
        noDragAndDropLeftInProgress();
    }

    @Test
    void aDragTheAppStartsOnItsOwnDragSourceIsACapabilityGapAndNoNativeDragBegins() {
        show(Start.ON_ITS_OWN_DRAG_SOURCE);
        DriverException e = failure(() -> start(dnd(), 2, 0));
        assertThat(e.reason()).isEqualTo(Reason.CAPABILITY_GAP);
        assertThat(e.getMessage()).contains("DragGestureEvent.startDrag");
        assertThat(products()).containsExactly("Coffee", "Tea", "Milk");
        assertThat(ended).isEmpty();
        noDragAndDropLeftInProgress();
    }

    @Test
    void aDragSourceOfTheAppsOwnClassIsACapabilityGapBeforeAnythingIsPosted() {
        ownDragSourceClass = true;
        show(Start.THROUGH_THE_GESTURE);
        DriverException e = failure(() -> start(dnd(), 2, 0));
        assertThat(e.reason()).isEqualTo(Reason.CAPABILITY_GAP);
        assertThat(e.getMessage()).contains("subclass of DragSource");
        Edt.run(() -> { });
        assertThat(presses.get()).isZero();
        noDragAndDropLeftInProgress();
    }

    @Test
    void theAppMayRemoveItsGestureListenerDuringTheGesture() {
        show(Start.REMOVING_ITS_LISTENER_FIRST);
        DragGestureRecognizer r = recognizer();
        drag(dnd(), 2, 0);
        assertThat(products()).containsExactly("Milk", "Coffee", "Tea");
        assertThat(seam.gestureListener(r)).isNull();
    }

    @Test
    void aGestureTheAppStartsNoDragForPassesAsThePlainMouseDragItWas() {
        show(Start.NOT_AT_ALL);
        DragAndDrop.Gesture gesture = start(dnd(), 2, 0);
        assertThat(gesture.started()).isFalse();
        assertThat(gesture.note()).contains("lines recognized the drag gesture but the app started no drag")
                .contains("the mouse drag was posted as is");
        assertThat(presses.get()).isEqualTo(1);
        noDragAndDropLeftInProgress();
    }

    @Test
    void aDragShorterThanTheThresholdIsNoGestureAndSaysSo() {
        show(Start.THROUGH_THE_GESTURE);
        Point a = cell(2);
        Point b = new Point(a.x + 1, a.y);
        DriverException e = failure(() -> dnd().start(poster, recognizer(), table, a, b));
        assertThat(e.reason()).isEqualTo(Reason.NOT_ACTIONABLE);
        assertThat(e.getMessage()).contains("did not recognize a drag gesture").contains("1 px");
        assertThat(ended).isEmpty();
    }

    @Test
    void withoutTheDragSourceCapabilityTheDragIsACapabilityGapAndNothingIsPosted() {
        show(Start.THROUGH_THE_GESTURE);
        seam.without(Capability.DRAG_SOURCE, "java.awt.dnd is closed");
        DriverException e = failure(() -> start(dnd(), 2, 0));
        assertThat(e.reason()).isEqualTo(Reason.CAPABILITY_GAP);
        assertThat(e.getMessage()).contains("stand in for a drag source").contains("java.awt.dnd is closed");
        Edt.run(() -> { });
        assertThat(presses.get()).isZero();
        assertThat(products()).containsExactly("Coffee", "Tea", "Milk");
    }

    @Test
    void aJavaThatRefusesToReplaceTheContextsPeerIsACapabilityGapBeforeAnythingIsPosted() {
        show(Start.THROUGH_THE_GESTURE);
        seam.peerReplacementRefused = "final field mutation is disabled";
        DriverException e = failure(() -> start(dnd(), 2, 0));
        assertThat(e.reason()).isEqualTo(Reason.CAPABILITY_GAP);
        assertThat(e.getMessage()).contains("final field mutation is disabled");
        Edt.run(() -> { });
        assertThat(presses.get()).isZero();
        noDragAndDropLeftInProgress();
    }

    @Test
    void aStandInThatFailsInsideTheAppsListenerIsACapabilityGapEvenWhenTheAppCatchesIt() {
        show(Start.CATCHING_A_REFUSAL);
        seam.contextRefused = true;
        DriverException e = failure(() -> start(dnd(), 2, 0));
        assertThat(e.reason()).isEqualTo(Reason.CAPABILITY_GAP);
        assertThat(e.getMessage()).contains("the driver could not stand in for the drag");
        noDragAndDropLeftInProgress();
    }

    @Test
    void aStandInThatFailsInsideAListenerThatDoesNotCatchItIsACapabilityGapAndNoAppError() {
        show(Start.THROUGH_THE_GESTURE);
        seam.contextRefused = true;
        // what the verb turns into APP_ERROR: an exception uncaught on the event dispatch thread
        EdtErrors errors = EdtErrors.live();
        errors.ensureInstalled();
        errors.begin();
        DriverException e = failure(() -> start(dnd(), 2, 0));
        List<Throwable> uncaught = errors.end();
        assertThat(e.reason()).isEqualTo(Reason.CAPABILITY_GAP);
        assertThat(e.getMessage()).contains("the driver could not stand in for the drag");
        assertThat(uncaught).isEmpty();
        noDragAndDropLeftInProgress();
    }

    @Test
    void onlyAComponentWithADragGestureRecognizerIsADragAndDropSource() {
        show(Start.THROUGH_THE_GESTURE);
        assertThat(recognizer()).isNotNull();
        assertThat(recognizer().getComponent()).isSameAs(table);
        JSlider slider = Edt.call(() -> {
            JSlider s = new JSlider();
            s.setSize(120, 20);
            return s;
        });
        assertThat(Edt.call(() -> dnd().sourceAt(slider, new Point(1, 1)))).isNull();
        JTable plain = Edt.call(() -> {
            JTable t = new JTable(lines);
            t.setSize(120, 60);
            return t;
        });
        assertThat(Edt.call(() -> dnd().sourceAt(plain, new Point(1, 1)))).isNull();
    }

    // --- Swing's own drag and drop: setDragEnabled and a TransferHandler -------------------------------------------

    private JList<String> pickList;
    private DefaultListModel<String> picks;
    private final AtomicInteger exportedAs = new AtomicInteger(-1);

    /** A pick list (Apples, Pears, Plums) reordered by Swing's drag and drop: the dropped-on item takes the dragged one. */
    private void showPickList() {
        Edt.run(() -> {
            picks = new DefaultListModel<String>();
            for (String s : new String[] {"Apples", "Pears", "Plums"}) picks.addElement(s);
            pickList = new JList<String>(picks);
            pickList.setName("picks");
            pickList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            pickList.setDragEnabled(true);
            pickList.setDropMode(DropMode.ON);
            pickList.setTransferHandler(new TransferHandler() {
                private static final long serialVersionUID = 1L;
                private int from = -1;

                @Override public int getSourceActions(JComponent c) { return MOVE; }

                @Override protected Transferable createTransferable(JComponent c) {
                    from = pickList.getSelectedIndex();
                    return new StringSelection(pickList.getSelectedValue());
                }

                @Override public boolean canImport(TransferSupport support) {
                    return support.isDrop() && support.isDataFlavorSupported(DataFlavor.stringFlavor);
                }

                @Override public boolean importData(TransferSupport support) {
                    if (!canImport(support) || from < 0) return false;
                    int to = ((JList.DropLocation) support.getDropLocation()).getIndex();
                    if (to < 0) return false;
                    String moved = picks.remove(from);
                    picks.add(to, moved);
                    return true;
                }

                @Override protected void exportDone(JComponent source, Transferable data, int action) {
                    exportedAs.set(action);
                }
            });
            JPanel content = new JPanel(new GridLayout(1, 1));
            content.add(new JScrollPane(pickList));
            frame = new JFrame("Pick list");
            frame.setContentPane(content);
            frame.setSize(220, 160);
            frame.setLocation(60, 60);
            frame.setVisible(true);
        });
    }

    private Point item(int index) {
        return Edt.call(() -> {
            Rectangle r = pickList.getCellBounds(index, index);
            return new Point(r.x + r.width / 2, r.y + r.height / 2);
        });
    }

    private List<String> pickOrder() {
        return Edt.call(() -> Collections.list(picks.elements()));
    }

    @Test
    void aSwingDragOfAListItemRunsInTheAppThroughItsTransferHandler() {
        showPickList();
        DragAndDrop dnd = dnd();
        Point from = item(2);
        Point to = item(0);
        DragGestureRecognizer r = Edt.call(() -> dnd.sourceAt(pickList, from));
        assertThat(r).isNotNull();
        DragAndDrop.Gesture gesture = dnd.start(poster, r, pickList, from, to);
        assertThat(gesture.started()).isTrue();
        String outcome = Edt.call(() -> gesture.drop(pickList, to));
        assertThat(pickOrder()).containsExactly("Plums", "Apples", "Pears");
        assertThat(outcome).contains("picks").contains("accepted the drop (move)");
        assertThat(exportedAs.get()).isEqualTo(TransferHandler.MOVE);
        noDragAndDropLeftInProgress();
    }

    @Test
    void aSwingDragShorterThanTheThresholdPassesAsThePlainMouseDragItWas() {
        showPickList();
        DragAndDrop dnd = dnd();
        Point from = item(2);
        Point to = new Point(from.x + 1, from.y);
        DragGestureRecognizer r = Edt.call(() -> dnd.sourceAt(pickList, from));
        DragAndDrop.Gesture gesture = dnd.start(poster, r, pickList, from, to);
        assertThat(gesture.started()).isFalse();
        assertThat(gesture.note()).contains("picks").contains("the mouse drag was posted as is");
        assertThat(pickOrder()).containsExactly("Apples", "Pears", "Plums");
        noDragAndDropLeftInProgress();
    }

    @Test
    void withoutTheSwingDragCapabilityASwingDragIsACapabilityGap() {
        showPickList();
        seam.without(Capability.SWING_DRAG, "javax.swing is closed");
        Point from = item(2);
        DriverException e = failure(() -> Edt.call(() -> dnd().sourceAt(pickList, from)));
        assertThat(e.reason()).isEqualTo(Reason.CAPABILITY_GAP);
        assertThat(e.getMessage()).contains("Swing").contains("javax.swing is closed");
    }

    @Test
    void aListWithoutDragEnabledIsNoDragAndDropSource() {
        showPickList();
        Edt.run(() -> pickList.setDragEnabled(false));
        assertThat(Edt.call(() -> dnd().sourceAt(pickList, item(2)))).isNull();
    }
}
