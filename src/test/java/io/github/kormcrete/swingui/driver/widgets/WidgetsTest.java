package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.model.Node;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;

import static org.assertj.core.api.Assertions.assertThat;

class WidgetsTest {

    /**
     * A custom row whose selection lives only in the app and is exposed through a public {@code isSelected()},
     * which {@code Widgets} reads reflectively.
     */
    // Package-private (not private): Widgets.isSelectedRow reflects on it from this same package,
    // and reflection's access check follows Java's own visibility rules - a private nested class
    // would not be accessible to it even though both are in the same package.
    static final class SelectableRow extends JPanel {
        private boolean chosen;

        void choose(boolean chosen) {
            this.chosen = chosen;
        }

        public boolean isSelected() {
            return chosen;
        }
    }

    @Test
    void aRowWithAPublicIsSelectedMethodReportsItsSelectionAsState() {
        SelectableRow row = new SelectableRow();

        Node unselected = new Node("clickable", "folder:orders/all");
        Widgets.describe(row, "clickable", unselected, 0);
        assertThat(unselected.hasState("selected")).isFalse();

        row.choose(true);
        Node selected = new Node("clickable", "folder:orders/all");
        Widgets.describe(row, "clickable", selected, 0);
        assertThat(selected.hasState("selected")).isTrue();
    }

    @Test
    void aPlainClickableWithNoIsSelectedMethodGetsNoSelectedState() {
        JPanel plain = new JPanel();
        Node n = new Node("clickable", "folder:orders/all");
        Widgets.describe(plain, "clickable", n, 0);
        assertThat(n.hasState("selected")).isFalse();
    }

    @Test
    void aRolledOverButtonSaysSo() {
        javax.swing.JButton b = new javax.swing.JButton("alice");
        b.getModel().setRollover(true);
        Node n = new Node("button", "alice");
        Widgets.describe(b, "button", n, 0);
        assertThat(n.state).contains("rollover");
    }
}
