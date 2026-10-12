package io.github.kormcrete.swingui.driver.widgets;

import org.junit.jupiter.api.Test;

import javax.swing.DefaultListModel;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import java.awt.BorderLayout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A renderer that builds a small panel is read through the text its visible children render; only a renderer with
 * no text at all falls back to {@code toString()}, marked approximate.
 */
class CellsTest {

    /** A value whose toString() is useless. */
    private static final class Entry {
        private final String label;
        private final String count;

        Entry(String label, String count) {
            this.label = label;
            this.count = count;
        }
    }

    private static JList<Entry> listRenderedAs(final ListCellRenderer<Object> renderer, Entry... entries) {
        DefaultListModel<Entry> model = new DefaultListModel<Entry>();
        for (Entry e : entries) model.addElement(e);
        JList<Entry> list = new JList<Entry>(model);
        list.setCellRenderer(renderer);
        return list;
    }

    @Test
    void aPanelRendererIsReadThroughTheTextItBuilt() {
        JList<Entry> list = listRenderedAs((l, value, index, selected, focused) -> {
            Entry e = (Entry) value;
            JPanel row = new JPanel(new BorderLayout());
            row.add(new JCheckBox(), BorderLayout.WEST);
            row.add(new JLabel(e.label), BorderLayout.CENTER);
            row.add(new JLabel(e.count), BorderLayout.EAST);
            return row;
        }, new Entry("Hardware", "42"));

        Cells.Text text = Cells.list(list, 0);

        assertThat(text.text).isEqualTo("false Hardware 42");
        assertThat(text.approx).as("read from the rendered text, so not a guess").isFalse();
    }

    @Test
    void aNestedPanelRendererIsStillRead() {
        JList<Entry> list = listRenderedAs((l, value, index, selected, focused) -> {
            JPanel inner = new JPanel();
            inner.add(new JLabel(((Entry) value).label));
            JPanel outer = new JPanel();
            outer.add(inner);
            return outer;
        }, new Entry("Software", "7"));

        assertThat(Cells.list(list, 0).text).isEqualTo("Software");
    }

    @Test
    void aRendererWithNoTextAtAllStillFallsBackAndSaysItIsApproximate() {
        JList<Entry> list = listRenderedAs(
                (l, value, index, selected, focused) -> new JPanel(), new Entry("x", "1"));

        Cells.Text text = Cells.list(list, 0);

        assertThat(text.approx).as("nothing was rendered to read, so the value is a guess").isTrue();
        assertThat(text.text).contains("Entry");
    }

    @Test
    void aPlainLabelRendererIsUnchanged() {
        JList<Entry> list = listRenderedAs(
                (l, value, index, selected, focused) -> new JLabel(((Entry) value).label),
                new Entry("Text only", "0"));

        Cells.Text text = Cells.list(list, 0);

        assertThat(text.text).isEqualTo("Text only");
        assertThat(text.approx).isFalse();
    }

    @Test
    void anInvisibleChildContributesNothing() {
        JList<Entry> list = listRenderedAs((l, value, index, selected, focused) -> {
            JPanel row = new JPanel();
            JLabel hidden = new JLabel("not visible");
            hidden.setVisible(false);
            row.add(hidden);
            row.add(new JLabel(((Entry) value).label));
            return row;
        }, new Entry("Visible", "1"));

        assertThat(Cells.list(list, 0).text).isEqualTo("Visible");
    }
}
