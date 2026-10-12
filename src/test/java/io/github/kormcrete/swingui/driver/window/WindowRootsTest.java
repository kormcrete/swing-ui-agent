package io.github.kormcrete.swingui.driver.window;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.util.Arrays;
import java.util.List;

import static io.github.kormcrete.swingui.driver.model.WindowRef.parse;
import static org.assertj.core.api.Assertions.assertThat;

class WindowRootsTest {

    private final JPanel mainContent = new JPanel();
    private final WindowRoot main = new WindowRoot(mainContent, "window", "OrderDesk", false, "Order editor – Order 1", false);
    private final WindowRoot tools = new WindowRoot(new JPanel(), "dialog", "Tools", false, "Order editor – Order 1", false);
    private final WindowRoot modal = new WindowRoot(new JPanel(), "dialog", "Order editor – Order 1", true, null, true);
    private final WindowRoot menu = new WindowRoot(new JPanel(), "menu", "Duplicate", false, null, false);
    private final WindowRoot submenu = new WindowRoot(new JPanel(), "menu", "With options", false, null, false);
    private final List<WindowRoot> roots = Arrays.asList(modal, main, tools, menu, submenu);

    @Test
    void mainIsTheAppLayersMainWindow() {
        assertThat(WindowRoots.select(parse("main"), roots, mainContent)).containsExactly(main);
    }

    @Test
    void aBareDialogIsTheModalNothingBlocks() {
        assertThat(WindowRoots.select(parse("dialog"), roots, mainContent)).containsExactly(modal);
        assertThat(WindowRoots.select(parse("dialog"), Arrays.asList(main, tools), mainContent)).containsExactly(tools);
    }

    @Test
    void titledDialogsMatchExactlyOrByPrefix() {
        assertThat(WindowRoots.select(parse("dialog:Tools"), roots, mainContent)).containsExactly(tools);
        assertThat(WindowRoots.select(parse("dialog:Order editor*"), roots, mainContent)).containsExactly(modal);
        assertThat(WindowRoots.select(parse("window:OrderDesk"), roots, mainContent)).containsExactly(main);
    }

    @Test
    void popupIsTheInnermostPopup() {
        assertThat(WindowRoots.select(parse("popup"), roots, mainContent)).containsExactly(submenu);
    }

    @Test
    void tokensNameEachKind() {
        assertThat(WindowRoots.token(main, mainContent)).isEqualTo("main");
        assertThat(WindowRoots.token(menu, mainContent)).isEqualTo("popup");
        assertThat(WindowRoots.token(tools, mainContent)).isEqualTo("dialog:Tools");
    }
}
