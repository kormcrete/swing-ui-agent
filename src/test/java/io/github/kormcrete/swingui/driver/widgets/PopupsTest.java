package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class PopupsTest {

    private final JPopupMenu popup = new JPopupMenu();
    private final JMenuItem with = new JMenuItem("incl. line items");
    private final JMenuItem hidden = new JMenuItem("Hidden");

    PopupsTest() {
        popup.add(with);
        popup.add(new JMenuItem("without line items"));
        hidden.setVisible(false);
        popup.add(hidden);
    }

    @Test
    void anItemIsFoundByItsLabel() {
        assertThat(Popups.item(popup, "incl. line items")).isSameAs(with);
    }

    @Test
    void aHiddenOrMissingItemIsNotFoundAndTheItemsAreListed() {
        DriverException e = catchThrowableOfType(() -> Popups.item(popup, "Hidden"), DriverException.class);
        assertThat(e.reason()).isEqualTo(Reason.NOT_FOUND);
        assertThat(e.getMessage()).contains("incl. line items").contains("without line items").doesNotContain("Hidden]");
    }

    @Test
    void twinItemsAreAmbiguous() {
        popup.add(new JMenuItem("incl. line items"));
        assertThat(catchThrowableOfType(() -> Popups.item(popup, "incl. line items"), DriverException.class).reason())
                .isEqualTo(Reason.AMBIGUOUS);
    }
}
