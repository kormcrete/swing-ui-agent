package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.CheckpointShot;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Edt;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.Shot;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Resolved;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.DefaultCaret;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** A checkpoint picture is just the part it names, at scale 1, with its masks' rectangles relative to it. */
class CheckpointsTest {

    private final JTable table = lines();
    private final JScrollPane pane = new JScrollPane(table);

    CheckpointsTest() {
        pane.setColumnHeaderView(table.getTableHeader());   // JTable does this in addNotify, which an unshown tree never gets
        pane.setSize(300, 120);
        layout(pane);
        layout(pane);   // the second pass sees the scroll bars the first one decided on
    }

    private static JTable lines() {
        DefaultTableModel model = new DefaultTableModel(new Object[] {"Product", "Qty", "Code", "Ref"}, 0);
        for (int i = 0; i < 30; i++) model.addRow(new Object[] {"Espresso beans", i + 1, "C-" + i, "ORD-" + (1640 + i)});
        JTable t = new JTable(model);
        t.setName("Lines");
        return t;
    }

    private static void layout(Component c) {
        if (!(c instanceof Container)) return;
        ((Container) c).doLayout();
        for (Component child : ((Container) c).getComponents()) layout(child);
    }

    private Resolved part(Integer row, String column) {
        Locator l = Locator.of("table", "Lines");
        if (row != null) l.row = Collections.<String, Object>singletonMap("index", row);
        l.column = column;
        return new Resolved(table, null, "table", "Lines", "main", Collections.<String>emptyList(), l);
    }

    private static Resolved whole(Component c, String role) {
        return new Resolved(c, null, role, null, "main", Collections.<String>emptyList(), Locator.of(role, null));
    }

    private static CheckpointShot take(Resolved target, Resolved... masks) {
        return Edt.call(() -> {
            List<Checkpoints.Area> areas = new java.util.ArrayList<Checkpoints.Area>();
            List<String> words = new java.util.ArrayList<String>();
            for (Resolved m : masks) {
                areas.add(Checkpoints.area(m));
                words.add(String.valueOf(m.locator));
            }
            return Checkpoints.take(Checkpoints.area(target), areas, String.valueOf(target.locator), words);
        });
    }

    private static BufferedImage decode(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image).as("the bytes are a readable PNG").isNotNull();
        return image;
    }

    @Test
    void aCellCheckpointIsJustTheCell() throws IOException {
        CheckpointShot shot = take(part(0, "Qty"));

        java.awt.Rectangle cell = table.getCellRect(0, 1, true);
        assertThat(shot.width).isEqualTo(cell.width);
        assertThat(shot.height).isEqualTo(cell.height);
        assertThat(decode(shot.png).getWidth()).isEqualTo(cell.width);
        assertThat(shot.masks).isEmpty();
    }

    @Test
    void aRowCheckpointIsTheRowAcrossTheVisibleColumns() {
        CheckpointShot shot = take(part(0, null));

        assertThat(shot.width).isEqualTo(table.getVisibleRect().width).isEqualTo(pane.getViewport().getWidth());
        assertThat(shot.height).isEqualTo(table.getRowHeight());
    }

    @Test
    void aColumnCheckpointHasItsHeaderAndVisibleCells() {
        Checkpoints.Area area = Edt.call(() -> Checkpoints.area(part(null, "Qty")));
        CheckpointShot shot = take(part(null, "Qty"));

        assertThat(area.root).isSameAs(pane);
        assertThat(shot.width).isEqualTo(table.getColumnModel().getColumn(1).getWidth());
        assertThat(shot.height).isEqualTo(table.getTableHeader().getHeight() + pane.getViewport().getHeight());
    }

    @Test
    void aMaskIsRelativeToThePicture() {
        CheckpointShot shot = take(part(0, null), part(0, "Qty"));

        assertThat(shot.masks).hasSize(1);
        CheckpointShot.Mask m = shot.masks.get(0);
        assertThat(m.x).isEqualTo(table.getColumnModel().getColumn(0).getWidth());
        assertThat(m.y).isEqualTo(0);
        assertThat(m.w).isEqualTo(table.getColumnModel().getColumn(1).getWidth());
        assertThat(m.h).isEqualTo(table.getRowHeight());
        assertThat(m.clipped).isFalse();
    }

    @Test
    void aMaskPartlyOutsideIsClippedAndSaysSo() {
        CheckpointShot shot = take(part(1, "Qty"), part(1, null));

        CheckpointShot.Mask m = shot.masks.get(0);
        assertThat(new int[] {m.x, m.y, m.w, m.h}).containsExactly(0, 0, shot.width, shot.height);
        assertThat(m.clipped).isTrue();
    }

    @Test
    void aMaskThatDoesNotOverlapFails() {
        DriverException e = catchThrowableOfType(() -> take(part(0, "Qty"), part(2, "Ref")), DriverException.class);

        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).endsWith("mask " + part(2, "Ref").locator + " does not overlap checkpoint "
                + part(0, "Qty").locator);
    }

    @Test
    void aMaskInAnotherWindowFails() {
        JPanel elsewhere = new JPanel();
        elsewhere.setSize(80, 20);

        DriverException e = catchThrowableOfType(() -> take(part(0, null), whole(elsewhere, "panel")),
                DriverException.class);

        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("mask panel is in another window than checkpoint");
    }

    @Test
    void aCellScrolledOutOfViewFailsNamingScrollIntoView() {
        DriverException e = catchThrowableOfType(() -> take(part(29, "Qty")), DriverException.class);

        assertThat(e.reason()).isEqualTo(Reason.NOT_ACTIONABLE);
        assertThat(e.getMessage()).contains("nothing to capture: " + part(29, "Qty").locator + " is scrolled out of view")
                .contains("scroll it into view");
    }

    @Test
    void theCaretIsHiddenWhileItPaintsAndBackAfter() throws IOException {
        JTextField field = Edt.call(() -> {
            JTextField f = new JTextField("ORD-1640");
            f.setCaret(new DefaultCaret());   // the same caret under every look and feel
            f.getCaret().setBlinkRate(0);     // no timer turning it on and off under the test
            f.setCaretColor(Color.RED);
            f.setSize(200, 24);
            f.setCaretPosition(3);
            f.getCaret().setVisible(true);    // as when it has focus
            return f;
        });
        // positive control: a plain capture has the caret in it
        Shot plain = Edt.call(() -> Screenshots.of(field, null, null, null));
        assertThat(redPixels(decode(plain.png))).isGreaterThan(0);

        CheckpointShot shot = take(whole(field, "textfield"));

        assertThat(redPixels(decode(shot.png))).isZero();
        assertThat(Edt.call(() -> field.getCaret().isVisible())).isTrue();
    }

    private static int redPixels(BufferedImage image) {
        int n = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                Color c = new Color(image.getRGB(x, y));
                if (c.getRed() > 200 && c.getGreen() < 80 && c.getBlue() < 80) n++;
            }
        }
        return n;
    }

    @Test
    void aCheckpointIsNeverScaledDown() throws IOException {
        JPanel wide = new JPanel();
        wide.setSize(3000, 200);

        CheckpointShot shot = take(whole(wide, "panel"));

        assertThat(shot.width).isEqualTo(3000);
        assertThat(shot.height).isEqualTo(200);
        assertThat(decode(shot.png).getWidth()).isEqualTo(3000);
    }

    @Test
    void masksKeepTheirOrder() {
        CheckpointShot shot = take(part(0, null), part(0, "Ref"), part(0, "Product"));

        assertThat(Arrays.asList(shot.masks.get(0).x, shot.masks.get(1).x))
                .containsExactly(table.getCellRect(0, 3, true).x, 0);
    }
}
