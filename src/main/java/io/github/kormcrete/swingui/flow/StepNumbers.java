package io.github.kormcrete.swingui.flow;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.flow.verb.ExpectVerb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The steps of a flat list of actions and checks, derived and never stored: each action starts a step, each check
 * belongs to the step before it, and checks before any action form step 1, a check-only step. Labels are
 * {@code "7"} (a step, or its action) and {@code "7.2"} (its second check).
 */
public final class StepNumbers {

    private StepNumbers() {}

    /** One step: its action and the checks under it, as flat indexes. */
    public static final class Step {
        /** 1-based. */
        public final int number;
        /** The flat index of the action; -1 for a check-only step. */
        public final int action;
        /** The flat indexes of the checks, in order. */
        public final List<Integer> checks;

        Step(int number, int action, List<Integer> checks) {
            this.number = number;
            this.action = action;
            this.checks = Collections.unmodifiableList(checks);
        }

        /** The flat index of the step's first record. */
        public int first() {
            return action >= 0 ? action : checks.get(0);
        }

        /** The flat index of the step's last record. */
        public int last() {
            return checks.isEmpty() ? action : checks.get(checks.size() - 1);
        }

        /** True for the step made only of checks: only step 1 can be one. */
        public boolean checkOnly() {
            return action < 0;
        }
    }

    /** True for a check: an {@code expect}, or a domain check ({@code do} marked {@link DoAction#check}). Every other step is an action. */
    public static boolean isCheck(Action a) {
        return a instanceof ExpectVerb || a instanceof DoAction && Boolean.TRUE.equals(((DoAction) a).check);
    }

    /** The steps of {@code flat}, in order. */
    public static List<Step> of(List<Action> flat) {
        List<Step> steps = new ArrayList<Step>();
        int action = -1;
        List<Integer> checks = null;
        for (int i = 0; i < flat.size(); i++) {
            if (isCheck(flat.get(i))) {
                if (checks == null) checks = new ArrayList<Integer>();   // leading checks: the check-only step
                checks.add(i);
            } else {
                if (checks != null) steps.add(new Step(steps.size() + 1, action, checks));
                action = i;
                checks = new ArrayList<Integer>();
            }
        }
        if (checks != null) steps.add(new Step(steps.size() + 1, action, checks));
        return steps;
    }

    /** The label of every flat index: {@code "7"} for an action, {@code "7.2"} for a check. */
    public static List<String> labels(List<Action> flat) {
        String[] out = new String[flat.size()];
        for (Step s : of(flat)) {
            if (s.action >= 0) out[s.action] = String.valueOf(s.number);
            for (int k = 0; k < s.checks.size(); k++) out[s.checks.get(k)] = s.number + "." + (k + 1);
        }
        return new ArrayList<String>(java.util.Arrays.asList(out));
    }

    /** The label of the record at flat index {@code i}. */
    public static String label(List<Action> flat, int i) {
        return labels(flat).get(i);
    }

    /** The flat index of step {@code step}'s action ({@code check} 0) or of its {@code check}th check; BAD_INPUT names what is missing. */
    public static int flatIndex(List<Action> flat, int step, int check) {
        List<Step> steps = of(flat);
        if (step < 1 || step > steps.size()) {
            throw bad("no step " + step + ": the recording has " + (steps.isEmpty() ? "no steps"
                    : steps.size() + (steps.size() == 1 ? " step" : " steps")));
        }
        Step s = steps.get(step - 1);
        if (check == 0) {
            if (s.checkOnly()) throw bad("step " + step + " holds only checks: name one as \"" + step + ".1\"");
            return s.action;
        }
        if (check < 1 || check > s.checks.size()) {
            throw bad("no check " + step + "." + check + ": step " + step + " has "
                    + (s.checks.isEmpty() ? "no checks" : s.checks.size() + (s.checks.size() == 1 ? " check" : " checks")));
        }
        return s.checks.get(check - 1);
    }

    private static DriverException bad(String message) {
        return new DriverException(Reason.BAD_INPUT, message);
    }
}
