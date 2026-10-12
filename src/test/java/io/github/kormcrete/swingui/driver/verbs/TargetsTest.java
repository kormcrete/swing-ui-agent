package io.github.kormcrete.swingui.driver.verbs;

import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Resolved;
import org.junit.jupiter.api.Test;

import javax.swing.JTable;
import javax.swing.table.JTableHeader;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class TargetsTest {

    private final JTable table = new JTable(new Object[][] {{"a", "b"}, {"c", "d"}}, new Object[] {"Name", "Info"});

    TargetsTest() {
        table.setBounds(0, 0, 200, 32);
        table.getColumnModel().getColumn(0).setWidth(120);
        table.getColumnModel().getColumn(1).setWidth(80);
        table.getTableHeader().setBounds(0, 0, 200, 18);
    }

    private Resolved resolved(Locator l) {
        return new Resolved(table, null, "table", null, "main", Collections.<String>emptyList(), l);
    }

    @Test
    void aColumnWithoutARowAimsAtItsHeaderCell() {
        Locator l = Locator.of("table", null);
        l.column = "Info";
        Resolved r = resolved(l);
        assertThat(Targets.surface(r)).isInstanceOf(JTableHeader.class);
        Rectangle cell = table.getTableHeader().getHeaderRect(1);
        assertThat(Targets.aim(r)).isEqualTo(new Point(cell.x + cell.width / 2, cell.y + cell.height / 2));
        assertThat(Targets.rightBorder(r)).isEqualTo(new Point(cell.x + cell.width - 1, cell.y + cell.height / 2));
    }

    @Test
    void aRowAndAColumnStillAimAtTheCell() {
        Locator l = Locator.of("table", null);
        l.row = Collections.<String, Object>singletonMap("index", 1);
        l.column = "Info";
        Resolved r = resolved(l);
        assertThat(Targets.surface(r)).isSameAs(table);
        Rectangle cell = table.getCellRect(1, 1, false);
        assertThat(Targets.aim(r)).isEqualTo(new Point(cell.x + cell.width / 2, cell.y + cell.height / 2));
    }
}
