package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.agent.BodyTrace;
import io.github.kormcrete.swingui.agent.Plugins;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.flow.Action;
import io.github.kormcrete.swingui.flow.DoAction;
import io.github.kormcrete.swingui.flow.FlowJson;
import io.github.kormcrete.swingui.spi.DomainStep;
import io.github.kormcrete.swingui.spi.ParamType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The Java form of a {@code do} step, from the vocabulary of the plugin that offers it: the typed call when the step
 * names one ({@code java(...)}), else the steps it makes for its values (a declarative body filled in, a Java body
 * traced, see {@link Plugins#steps}); a step with neither, or one no ready plugin offers, is refused. A trace runs the
 * body's code, so each form is kept per domain step and values: a recording asks for it on every change.
 */
public final class DoStepForms implements JavaTestWriter.DoForms {

    private final Plugins plugins;
    /** Forms already made, by domain step (gone with it when its plugin goes) and values. */
    private final Map<DomainStep, Map<Map<String, String>, JavaTestWriter.DoForm>> made =
            new WeakHashMap<DomainStep, Map<Map<String, String>, JavaTestWriter.DoForm>>();

    public DoStepForms(Plugins plugins) {
        this.plugins = plugins;
    }

    @Override public JavaTestWriter.DoForm of(DoAction d) {
        Plugins.DoStep found;
        try {
            found = plugins.lookUp(d.step, d.params == null ? Collections.<String, String>emptyMap() : d.params);
        } catch (DriverException e) {
            return JavaTestWriter.DoForm.refused(d.describe() + " has no Java form: "
                    + DriverException.stripReason(e.getMessage(), e.reason()));
        }
        DomainStep step = found.found.step;
        synchronized (made) {
            Map<Map<String, String>, JavaTestWriter.DoForm> byValues = made.get(step);
            if (byValues == null) {
                byValues = new HashMap<Map<String, String>, JavaTestWriter.DoForm>();
                made.put(step, byValues);
            }
            JavaTestWriter.DoForm form = byValues.get(found.values);
            if (form == null) {
                form = form(found);
                byValues.put(found.values, form);
            }
            return form;
        }
    }

    private static JavaTestWriter.DoForm form(Plugins.DoStep found) {
        DomainStep step = found.found.step;
        if (step.javaClass() != null) {
            Set<String> numbers = new HashSet<String>();
            for (DomainStep.Param p : step.params()) if (p.type() == ParamType.INT) numbers.add(p.name());
            return JavaTestWriter.DoForm.call(found.sentence, step.javaClass(), step.javaCall(), found.values, numbers);
        }
        String named = found.sentence + " (" + found.qualified() + ")";
        BodyTrace.Result traced = Plugins.steps(found);
        if (!traced.derivable()) {
            return JavaTestWriter.DoForm.refused(named + " has no Java form: the step names no java(...) call, and its "
                    + "body cannot be written out as steps (" + traced.why() + "); give the step a java(...) call");
        }
        List<Action> steps = new ArrayList<Action>();
        for (int k = 0; k < traced.steps().size(); k++) {
            ObjectNode json = traced.steps().get(k);
            try {
                steps.add(FlowJson.mapper().treeToValue(json, Action.class));
            } catch (JsonProcessingException | IllegalArgumentException e) {
                return JavaTestWriter.DoForm.refused(named + " has no Java form: its step " + (k + 1) + " is no step ("
                        + json + ")");
            }
        }
        return JavaTestWriter.DoForm.steps(found.sentence, steps);
    }
}
