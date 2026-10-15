package io.github.kormcrete.swingui.flow;

import java.util.ArrayList;
import java.util.List;

/**
 * What a failed replay step leaves behind so its recorded test can be fixed WITHOUT a live session: the
 * step's locators and what each matches right now, a small scoped tree of where it failed, and
 * optionally a picture. Built by {@link FailureDiagnostics}; printed by {@link FlowReport}.
 */
public final class StepDiagnostics {

    /** One of the step's locators ({@code target}, {@code from}, {@code to}) and its current matches. */
    public static final class Match {
        public final String field;
        public final String locator;
        /**
         * Each visible match (role, name, #id, window, trail), at most 10; empty when nothing matches, null when
         * they could not be listed.
         */
        public final List<String> candidates;

        public Match(String field, String locator, List<String> candidates) {
            this.field = field;
            this.locator = locator;
            this.candidates = candidates;
        }
    }

    public final List<Match> matches = new ArrayList<Match>();
    /** What {@link #tree} is a dump of, e.g. {@code tab 'Customer' (depth 4, compact, 9 nodes)}. */
    public String scope;
    /** The scoped tree, compact, one node per line, indented by depth. */
    public final List<String> tree = new ArrayList<String>();
    /** The absolute path of the window's PNG, or null when none was taken. */
    public String screenshot;
    /** A part of the diagnosis that could not be made, and why - never a reason to hide the rest. */
    public final List<String> problems = new ArrayList<String>();
}
