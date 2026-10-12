package io.github.kormcrete.swingui.driver.focus;

import org.junit.jupiter.api.Test;

import javax.swing.InputVerifier;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextField;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The InputVerifier rule a synthetic FOCUS_GAINED would otherwise bypass. */
class FocusEmulatorTest {

    private final AtomicInteger calls = new AtomicInteger();

    private JTextField guarded(final boolean accepts) {
        JTextField f = new JTextField();
        f.setInputVerifier(new InputVerifier() {
            @Override
            public boolean verify(JComponent input) {
                calls.incrementAndGet();
                return accepts;
            }
        });
        return f;
    }

    @Test
    void aRefusingVerifierKeepsFocus() {
        assertThat(FocusEmulator.verifierYields(guarded(false), new JTextField())).isFalse();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void anAcceptingVerifierLetsFocusGo() {
        assertThat(FocusEmulator.verifierYields(guarded(true), new JTextField())).isTrue();
    }

    @Test
    void aTargetThatSkipsVerificationIsNotAsked() {
        JTextField target = new JTextField();
        target.setVerifyInputWhenFocusTarget(false);
        assertThat(FocusEmulator.verifierYields(guarded(false), target)).isTrue();
        assertThat(calls.get()).isZero();
    }

    @Test
    void noOwnerOrNoVerifierYields() {
        assertThat(FocusEmulator.verifierYields(null, new JTextField())).isTrue();
        assertThat(FocusEmulator.verifierYields(new JTextField(), new JTextField())).isTrue();
        assertThat(FocusEmulator.verifierYields(guarded(false), new java.awt.Label())).isTrue();
        assertThat(FocusEmulator.verifierYields(guarded(false), new JLabel())).isFalse();
    }

    @Test
    void theOwnerItselfIsNotVerified() {
        JTextField f = guarded(false);
        assertThat(FocusEmulator.verifierYields(f, f)).isTrue();
        assertThat(calls.get()).isZero();
    }
}
