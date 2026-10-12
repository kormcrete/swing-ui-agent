package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Node;
import io.github.kormcrete.swingui.spi.AppHooks;
import io.github.kormcrete.swingui.spi.ResetSpec;

/**
 * A test's reset: the pointer goes home, the app plugin recovers an expired session ({@link AppHooks#recoverSession};
 * first, because Escape on its sign-in dialog may only ask whether to exit), every modal dialog gets Escape, then the
 * app plugin resets ({@link AppHooks#reset}). Without an app plugin only the pointer and the dialogs are reset. A dialog that survives
 * {@link #MAX_ESCAPES} escapes gets one window close; if it still shows, the reset fails with its title.
 */
final class AgentReset {

    static final int MAX_ESCAPES = 5;
    static final long ESCAPE_TIMEOUT_MS = 2_000L;

    private AgentReset() {}

    /** {@code app} and {@code spec} are null without an app plugin. */
    static void run(Verbs verbs, AppHooks app, ResetSpec spec) {
        verbs.resetPointer();
        if (app != null) app.recoverSession();
        escapeDialogs(verbs);
        if (app != null) app.reset(spec);
    }

    static void escapeDialogs(Verbs verbs) {
        escaping:
        for (int i = 0; i < MAX_ESCAPES; i++) {
            if (modalTitle(verbs) == null) return;
            try {
                verbs.answer("escape", ESCAPE_TIMEOUT_MS);
            } catch (DriverException e) {
                if (e.reason() == Reason.NOT_FOUND) return;
                // the live driver says "Escape did not close dialog": more escapes will not help, close it instead
                if (e.reason() == Reason.NOT_ACTIONABLE) break escaping;
                throw e;
            }
        }
        String left = modalTitle(verbs);
        if (left == null) return;
        try {
            verbs.closeWindow("dialog:" + left, ESCAPE_TIMEOUT_MS);
        } catch (DriverException e) {
            // the failure below names the dialog; why the close failed adds nothing
        }
        left = modalTitle(verbs);
        if (left != null) {
            throw new DriverException(Reason.APP_ERROR,
                    "reset: a modal dialog would not close after Escape and a window close: " + left);
        }
    }

    /** The title of a showing modal dialog, "(untitled dialog)" without one; null when none shows. */
    static String modalTitle(Verbs verbs) {
        Node dialogs;
        try {
            dialogs = verbs.snapshot("dialog", null, 1, 0);
        } catch (DriverException e) {
            if (e.reason() == Reason.NOT_FOUND) return null;
            throw e;
        }
        if (dialogs == null || dialogs.children == null) return null;
        for (Node c : dialogs.children) {
            if (Boolean.TRUE.equals(c.modal)) return c.name == null || c.name.isEmpty() ? "(untitled dialog)" : c.name;
        }
        return null;
    }
}
