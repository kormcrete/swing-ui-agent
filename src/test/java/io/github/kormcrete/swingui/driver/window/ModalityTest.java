package io.github.kormcrete.swingui.driver.window;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order tie-break in {@link Modality#blocksInOrder}, without constructing any window: two showing
 * modal dialogs of the same modality type, neither owning the other, block each other one-way in AWT
 * (the later dialog wins), which this helper resolves from creation order.
 */
class ModalityTest {

    @Test
    void aOneWayBlockHolds() {
        assertThat(Modality.blocksInOrder(true, false, 0, 9)).isTrue();
        assertThat(Modality.blocksInOrder(true, false, 9, 0)).isTrue();
    }

    @Test
    void aMutualBlockGoesToTheLaterDialog() {
        assertThat(Modality.blocksInOrder(true, true, 5, 3)).isTrue();
    }

    @Test
    void theEarlierDialogDoesNotBlockTheLater() {
        assertThat(Modality.blocksInOrder(true, true, 3, 5)).isFalse();
    }

    @Test
    void noBlockStaysNoBlock() {
        assertThat(Modality.blocksInOrder(false, true, 0, 9)).isFalse();
        assertThat(Modality.blocksInOrder(false, false, 9, 0)).isFalse();
    }
}
