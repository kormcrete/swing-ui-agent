package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.testing.RecordingVerbs;

import io.github.kormcrete.swingui.driver.model.Geometry;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class TargetBoundsTest {
    private final RecordingVerbs verbs = new RecordingVerbs();
    private final Client ui = new Client(verbs, null, "UIT-test");

    @Test
    void boundsCopiesTheGeometry() {
        Geometry g = new Geometry();
        g.x = 5; g.y = 6; g.width = 7; g.height = 8;
        g.columnWidths = Arrays.asList(70, 85);
        verbs.geometryAnswer = g;

        Bounds b = ui.table("Orders").bounds();
        g.columnWidths.set(0, 1);   // the Bounds must not alias the driver's list

        assertThat(new int[] {b.x(), b.y(), b.width(), b.height()}).containsExactly(5, 6, 7, 8);
        assertThat(b.columnWidths()).containsExactly(70, 85);
        assertThat(b.headerTextWidths()).isEmpty();
        assertThat(verbs.calls).containsExactly("geometry table 'Orders'");
    }
}
