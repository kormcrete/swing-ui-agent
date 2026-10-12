package io.github.kormcrete.swingui.driver.platform;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.event.KeyEvent;

import static org.assertj.core.api.Assertions.assertThat;

class WatchTest {

    private final FakeSeam seam = new FakeSeam();
    private final Watch watch = new Watch(seam);
    private final JPanel source = new JPanel();

    private KeyEvent key(int id) {
        return new KeyEvent(source, id, 0L, 0, KeyEvent.VK_A, 'a');
    }

    @Test
    void onlyPressesTheOsDeliveredCountAsRealInput() {
        Watch.Mark start = watch.mark();
        watch.onEvent(key(KeyEvent.KEY_PRESSED));
        assertThat(watch.realInputSince(start)).isEmpty();
        seam.systemGenerated = true;
        watch.onEvent(key(KeyEvent.KEY_RELEASED));
        watch.onEvent(key(KeyEvent.KEY_PRESSED));
        assertThat(watch.realInputSince(start)).hasSize(1).allMatch(s -> s.startsWith("key "));
    }

    @Test
    void anActivationIsAFalseToTrueTransition() {
        Watch.Mark start = watch.mark();
        watch.onActiveSample(Boolean.TRUE);
        watch.onActiveSample(null);
        watch.onActiveSample(Boolean.FALSE);
        watch.onActiveSample(Boolean.TRUE);
        watch.onActiveSample(Boolean.TRUE);
        assertThat(watch.activationsSince(start)).containsExactly("the app became active");
    }

    @Test
    void aMarkHidesWhatCameBefore() {
        seam.systemGenerated = true;
        watch.onEvent(key(KeyEvent.KEY_PRESSED));
        Watch.Mark later = watch.mark();
        assertThat(watch.realInputSince(later)).isEmpty();
    }
}
