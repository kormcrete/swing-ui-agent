package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Locator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** An action could not complete. JUnit reports it as an error, not an assertion failure. */
public final class UiActionFailed extends RuntimeException {
    private final Reason reason;
    private final Locator target;
    private final List<String> candidates;

    public UiActionFailed(String verb, Locator target, DriverException cause, List<String> candidates) {
        super(message(verb, target, cause, candidates), cause);
        this.reason = cause.reason();
        this.target = target == null ? null : target.copy();
        this.candidates = Collections.unmodifiableList(new ArrayList<String>(candidates));
    }

    private static String message(String verb, Locator target, DriverException cause, List<String> candidates) {
        StringBuilder b = new StringBuilder(verb);
        if (target != null) b.append(' ').append(target);
        b.append(": ").append(cause.getMessage());
        for (String d : cause.details()) b.append("\n  ").append(d);
        if (!candidates.isEmpty()) {
            b.append("\nnearest candidates:");
            for (String c : candidates) b.append("\n  ").append(c);
        }
        return b.toString();
    }

    public Reason reason() { return reason; }
    public Locator target() { return target == null ? null : target.copy(); }
    public List<String> candidates() { return candidates; }
}
