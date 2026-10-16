package io.github.kormcrete.swingui.spi;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.PendingDialog;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;

import java.util.Map;

/** What steps run against, live and in replay: the driver's verbs, the three app steps, and plugins' domain steps. */
public interface StepHost {
    /** The installed driver's verbs; null when none is installed. */
    Verbs verbs();
    /** The {@code navigate} step: opens what an entry-point id or a {@link UiInventory#navTargets} name names. */
    void navigate(String target);
    /** The {@code close} step: closes the active tab, answering its "close anyway?" confirm. */
    void closeActiveTab();
    /** The {@code loginAs} step: logs out and in as a user alias ({@link io.github.kormcrete.swingui.flow.UiUsers}). */
    void loginAs(String alias);
    /**
     * The message or confirmation (an option pane) waiting for an answer now, or null. App steps refuse to run
     * while one waits.
     */
    PendingDialog openDialog();

    /**
     * The {@code do} step: runs the domain step {@code id} (a {@link Vocabulary} step; {@code <plugin>:<id>} names the
     * plugin) with {@code params}, its values by param name. Returns the filled sentence and the steps its body ran
     * ({@link VerbOutcome#sentence}, {@link VerbOutcome#calls}); a failed body throws
     * {@link io.github.kormcrete.swingui.driver.DomainStepFailed} with the steps up to the failure. Bad values (an
     * unknown id or param, a required one left out, an INT that is no whole number) are BAD_INPUT, before the body runs.
     * The body runs on the calling thread (the agent's request thread), not on the EDT. Without a plugin that offers a
     * vocabulary: CAPABILITY_GAP.
     */
    default VerbOutcome runStep(String id, Map<String, String> params) {
        throw new DriverException(Reason.CAPABILITY_GAP, "the do step '" + id + "' " + NO_VOCABULARY);
    }

    /** Why a {@code do} step cannot run without a plugin that offers a vocabulary. */
    String NO_VOCABULARY = "needs a plugin with a vocabulary; none configured";
}
