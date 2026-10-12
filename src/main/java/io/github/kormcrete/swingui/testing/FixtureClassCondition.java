package io.github.kormcrete.swingui.testing;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Enables a {@link FixtureClass} only for the launch of its owning test. */
public final class FixtureClassCondition implements ExecutionCondition {

    public static final String PARAMETER = "swingui.fixtures";
    public static final String VALUE = "run";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext c) {
        return VALUE.equals(c.getConfigurationParameter(PARAMETER).orElse(null))
                ? ConditionEvaluationResult.enabled("launched by its owning test")
                : ConditionEvaluationResult.disabled("a fixture class: it runs only from the test that owns it");
    }
}
