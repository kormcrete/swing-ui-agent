package io.github.kormcrete.swingui.driver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A driver failure that carries its reason, so no layer above has to parse messages. */
public class DriverException extends RuntimeException {

    private final Reason reason;
    private final List<String> details;

    public DriverException(Reason reason, String message) {
        this(reason, message, Collections.<String>emptyList());
    }

    public DriverException(Reason reason, String message, List<String> details) {
        super(reason.name() + ": " + message);
        this.reason = reason;
        this.details = Collections.unmodifiableList(new ArrayList<String>(details));
    }

    /** The message without the "REASON: " prefix the constructor adds; for rebuilding an exception from its text. */
    public static String stripReason(String message, Reason reason) {
        String prefix = reason.name() + ": ";
        return message != null && message.startsWith(prefix) ? message.substring(prefix.length()) : message;
    }

    public Reason reason() {
        return reason;
    }

    /** Candidates, busy signals or capability lines, depending on the reason. */
    public List<String> details() {
        return details;
    }
}
