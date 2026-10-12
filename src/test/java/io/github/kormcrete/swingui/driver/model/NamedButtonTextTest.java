package io.github.kormcrete.swingui.driver.model;

import io.github.kormcrete.swingui.driver.widgets.Widgets;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JCheckBox;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A named tick box is named by its component name and carries the text the reader sees as its value, as a named
 * label does; a button named by its own text carries no value.
 */
class NamedButtonTextTest {

    @Test
    void aNamedTickBoxCarriesItsLabelAsValue() {
        JCheckBox box = new JCheckBox("Express delivery");
        box.setName("orderOption.express");
        Node n = new Node("checkbox", "orderOption.express");

        Widgets.describe(box, "checkbox", n, 0);

        assertThat(n.value).isEqualTo("Express delivery");
    }

    @Test
    void aButtonNamedByItsTextHasNoValue() {
        Node n = new Node("button", "OK");

        Widgets.describe(new JButton("OK"), "button", n, 0);

        assertThat(n.value).isNull();
    }
}
