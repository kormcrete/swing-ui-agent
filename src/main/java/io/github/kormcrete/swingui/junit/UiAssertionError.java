package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.driver.model.Locator;

/** A UI assertion that never came true within its wait. Carries the target for the failure evidence. */
public final class UiAssertionError extends AssertionError {
    private final Locator target;

    UiAssertionError(String message, Locator target, Throwable cause) {
        super(message);
        if (cause != null) initCause(cause);
        this.target = target == null ? null : target.copy();
    }

    public Locator target() { return target == null ? null : target.copy(); }
}
