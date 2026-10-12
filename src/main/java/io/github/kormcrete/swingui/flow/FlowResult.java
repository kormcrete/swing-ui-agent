package io.github.kormcrete.swingui.flow;

import java.util.ArrayList;
import java.util.List;

public class FlowResult {
    public String flowName;
    public List<StepResult> steps = new ArrayList<>();
    /** The driver's capability report; null without a driver. */
    public List<String> capabilities;

    public boolean passed() {
        for (StepResult s : steps) if (!s.passed) return false;
        return true;
    }

    public List<StepResult> failures() {
        List<StepResult> f = new ArrayList<>();
        for (StepResult s : steps) if (!s.passed) f.add(s);
        return f;
    }
}
