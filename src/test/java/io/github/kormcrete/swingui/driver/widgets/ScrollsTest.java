package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Node;
import io.github.kormcrete.swingui.driver.model.RowSpec;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JViewport;
import java.awt.Dimension;
import java.awt.Point;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Reading a scroll position, and scrolling to an end, by pages or to a row. */
class ScrollsTest {

    private final JTable table = table(200);
    private final JScrollPane pane = new JScrollPane(table);

    ScrollsTest() {
        // No window here: size the viewport by hand so the geometry is exact and the test is headless.
        table.setPreferredSize(new Dimension(300, 4000));
        pane.getViewport().setSize(300, 400);
        pane.getViewport().setExtentSize(new Dimension(300, 400));
        pane.setSize(300, 400);
        table.setSize(300, 4000);
    }

    private static JTable table(int rows) {
        Object[][] data = new Object[rows][];
        for (int i = 0; i < rows; i++) data[i] = new Object[] {"26-" + i, i % 2 == 0 ? "open" : "done"};
        return new JTable(data, new Object[] {"No.", "Status"});
    }

    @Test
    void aScrolledElementReportsWhereItIs() {
        Node n = new Node("table", null);

        Scrolls.describe(table, n);

        assertThat(n.scroll).isNotNull();
        assertThat(n.scroll.y).isZero();
        assertThat(n.scroll.height).isEqualTo(400);
        assertThat(n.scroll.maxY).isEqualTo(3600);
        assertThat(n.hasState("atTop")).isTrue();
        assertThat(n.hasState("atBottom")).isFalse();
    }

    @Test
    void theEndsAreStatesSoTheyCanBeAsserted() {
        Scrolls.scroll(table, "bottom", null, null);

        Node n = new Node("table", null);
        Scrolls.describe(table, n);

        assertThat(n.scroll.y).isEqualTo(3600);
        assertThat(n.hasState("atBottom")).isTrue();
        assertThat(n.hasState("atTop")).isFalse();
    }

    @Test
    void anElementThatDoesNotScrollCarriesNoScrollBlock() {
        Node n = new Node("button", "OK");

        Scrolls.describe(new JButton("OK"), n);

        assertThat(n.scroll).isNull();
    }

    /**
     * The scroll block hangs off the scrolled content, never off a widget that merely sits inside a
     * scrolled panel - otherwise its numbers would describe something other than the element.
     */
    @Test
    void onlyTheViewportsOwnViewCarriesTheNumbers() {
        JButton inside = new JButton("Inside");
        Node n = new Node("button", "Inside");

        Scrolls.describe(inside, n);

        assertThat(Scrolls.viewportOf(table)).isSameAs(pane.getViewport());
        assertThat(Scrolls.viewportOf(inside)).isNull();
        assertThat(n.scroll).isNull();
    }

    @Test
    void pagesMoveOneScreenAtATimeAndStopAtTheEnd() {
        Scrolls.scroll(table, null, 2, null);
        assertThat(position().y).isEqualTo(800);

        Scrolls.scroll(table, null, -1, null);
        assertThat(position().y).isEqualTo(400);

        Scrolls.scroll(table, null, -9, null);
        assertThat(position().y).as("clamped at the top").isZero();

        Scrolls.scroll(table, null, 99, null);
        assertThat(position().y).as("clamped at the bottom").isEqualTo(3600);
    }

    @Test
    @Disabled("flaky: view width races a viewport layout off the EDT")
    void horizontalEndsMoveTheOtherAxis() {
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setSize(1200, 4000);
        table.setPreferredSize(new Dimension(1200, 4000));

        Scrolls.scroll(table, "right", null, null);
        assertThat(position().x).isEqualTo(900);

        Scrolls.scroll(table, "left", null, null);
        assertThat(position().x).isZero();
    }

    @Test
    void aRowScrollsItselfIntoView() {
        Scrolls.scroll(table, null, null, RowSpec.of(Collections.<String, Object>singletonMap("index", 150)));

        assertThat(position().y).isGreaterThan(0);
    }

    @Test
    void aRowNeedsATable() {
        DriverException e = catchThrowableOfType(
                () -> Scrolls.scroll(new JButton("OK"), null, null,
                        RowSpec.of(Collections.<String, Object>singletonMap("index", 0))),
                DriverException.class);

        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("scrolls a table");
    }

    @Test
    void anElementOutsideAScrollPaneSaysSoRatherThanDoingNothing() {
        DriverException e = catchThrowableOfType(
                () -> Scrolls.scroll(new JButton("OK"), "bottom", null, null), DriverException.class);

        assertThat(e.reason()).isEqualTo(Reason.BAD_INPUT);
        assertThat(e.getMessage()).contains("does not scroll");
    }

    /**
     * A row scroll resolves its element WITHOUT the row (aiming at a row scrolls it into view by itself, making the
     * verb a no-op), so {@code element()} strips the row from a copy and leaves the caller's locator, which is what
     * gets recorded, intact.
     */
    @Test
    void aRecordedRowScrollKeepsItsRow() {
        Locator target = Locator.of("table", null);
        target.row = Collections.<String, Object>singletonMap("index", 150);

        Locator element = target.element();

        assertThat(element.row).as("stripped for resolution").isNull();
        assertThat(target.row).as("the caller's locator is untouched").isNotNull();
    }

    private Point position() {
        JViewport vp = pane.getViewport();
        return vp.getViewPosition();
    }
}
