package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class MenuWidgetTest {

    private final JMenuBar bar = new JMenuBar();
    private final JMenu file = new JMenu("File");
    private final JMenuItem open = new JMenuItem("Open");
    private final JMenuItem pdf = new JMenuItem("PDF");

    MenuWidgetTest() {
        JMenu export = new JMenu("Export");
        export.add(pdf);
        file.add(open);
        file.add(export);
        bar.add(file);
    }

    @Test
    void aPathWalksMenusAndSubmenus() {
        assertThat(MenuWidget.topMenu(bar, "File")).isSameAs(file);
        assertThat(MenuWidget.walk(file, Collections.singletonList("Open"))).isSameAs(open);
        assertThat(MenuWidget.walk(file, Arrays.asList("Export", "PDF"))).isSameAs(pdf);
    }

    @Test
    void wrongPathsSayWhatIsThere() {
        DriverException menu = catchThrowableOfType(() -> MenuWidget.topMenu(bar, "Help"), DriverException.class);
        assertThat(menu.reason()).isEqualTo(Reason.NOT_FOUND);
        assertThat(menu.getMessage()).contains("[File]");
        assertThat(catchThrowableOfType(() -> MenuWidget.walk(file, Collections.singletonList("Export")),
                DriverException.class).getMessage()).contains("opens a submenu");
        assertThat(catchThrowableOfType(() -> MenuWidget.walk(file, Arrays.asList("Open", "PDF")),
                DriverException.class).getMessage()).contains("has no submenu");
    }

    @Test
    void hiddenItemsAreSkippedAndDuplicatesAreAmbiguous() {
        JMenu hiddenFile = new JMenu("File");
        hiddenFile.setVisible(false);
        bar.add(hiddenFile, 0);
        assertThat(MenuWidget.topMenu(bar, "File")).isSameAs(file);

        JMenu secondFile = new JMenu("File");
        bar.add(secondFile);
        assertThat(catchThrowableOfType(() -> MenuWidget.topMenu(bar, "File"), DriverException.class).reason())
                .isEqualTo(Reason.AMBIGUOUS);

        JMenuItem hiddenOpen = new JMenuItem("Open");
        hiddenOpen.setVisible(false);
        file.insert(hiddenOpen, 0);
        assertThat(MenuWidget.walk(file, Collections.singletonList("Open"))).isSameAs(open);

        JMenuItem secondOpen = new JMenuItem("Open");
        file.add(secondOpen);
        assertThat(catchThrowableOfType(() -> MenuWidget.walk(file, Collections.singletonList("Open")),
                DriverException.class).reason()).isEqualTo(Reason.AMBIGUOUS);
    }

    @Test
    void itemsOfTheTopMenuListASubmenuByItsLabel() {
        assertThat(MenuWidget.items(file, Collections.<String>emptyList())).containsExactly("Open", "Export");
    }

    @Test
    void itemsOfASubmenuAreItsVisibleEntries() {
        JMenuItem hidden = new JMenuItem("Hidden");
        hidden.setVisible(false);
        ((JMenu) file.getMenuComponent(1)).add(hidden);
        assertThat(MenuWidget.items(file, Collections.singletonList("Export"))).containsExactly("PDF");
    }

    @Test
    void aLeafOnThePathIsNotASubmenu() {
        assertThatThrownBy(() -> MenuWidget.items(file, Collections.singletonList("Open")))
                .isInstanceOf(DriverException.class)
                .hasMessageContaining("has no submenu");
    }

    @Test
    void leafPathsListEveryItemAMenuPathReaches() {
        JMenu orders = new JMenu("Orders");
        JMenuItem archive = new JMenuItem("Archive");
        archive.setEnabled(false);
        JMenu recent = new JMenu("Recent");
        JMenuItem hidden = new JMenuItem("Hidden");
        hidden.setVisible(false);
        JMenu locked = new JMenu("Locked");
        locked.setEnabled(false);
        locked.add(new JMenuItem("Purge"));
        orders.add(new JMenuItem("<html>New <b>order</b></html>"));
        orders.addSeparator();
        orders.add(archive);
        orders.add(recent);
        orders.add(hidden);
        orders.add(locked);
        orders.add(new JMenuItem(""));
        bar.add(orders);
        JFrame frame = new JFrame();
        try {
            frame.setJMenuBar(bar);
            assertThat(MenuWidget.leafPaths(frame)).containsExactly("File > Open", "File > Export > PDF", "Orders > New order",
                    "Orders > Archive [disabled]", "Orders > Recent", "Orders > Locked > Purge [disabled]");
        } finally {
            frame.dispose();
        }
    }

    @Test
    void noWindowOrNoMenuBarHasNoPaths() {
        assertThat(MenuWidget.leafPaths(null)).isEmpty();
        assertThat(MenuWidget.leafPaths(new JPanel())).isEmpty();
        JFrame frame = new JFrame();
        try {
            assertThat(MenuWidget.leafPaths(frame)).isEmpty();
        } finally {
            frame.dispose();
        }
    }
}
