package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.client.StepCallVerbs;
import io.github.kormcrete.swingui.driver.DomainStepFailed;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.StepCall;
import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.flow.Action;
import io.github.kormcrete.swingui.flow.CloseAction;
import io.github.kormcrete.swingui.flow.FlowJson;
import io.github.kormcrete.swingui.flow.LoginAsAction;
import io.github.kormcrete.swingui.flow.NavigateAction;
import io.github.kormcrete.swingui.flow.verb.VerbAction;
import io.github.kormcrete.swingui.junit.Client;
import io.github.kormcrete.swingui.junit.ClientApp;
import io.github.kormcrete.swingui.spi.DomainStep;
import io.github.kormcrete.swingui.spi.ParamType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs one domain step for real, on the calling thread: a Java body on a {@link Client} over the app's verbs (as a
 * UI test's Client, in this JVM), declarative steps one by one. Every step either makes is kept
 * ({@link StepCallVerbs}): the outcome holds the filled sentence and those steps, a failure throws
 * {@link DomainStepFailed} with the steps up to it.
 */
final class StepRunner {

    private static final ObjectMapper JSON = FlowJson.mapper();

    private StepRunner() {}

    /** Runs {@code step} with {@code values} (checked by {@link MapParams#of}) on {@code verbs} and {@code app}. */
    static VerbOutcome run(DomainStep step, MapParams values, Verbs verbs, ClientApp app) {
        StepCallVerbs kept = new StepCallVerbs(verbs);
        ClientApp keptApp = kept.app(app);
        VerbOutcome out = new VerbOutcome();
        out.sentence = values.fill(step.sentence());
        try {
            if (step.body() != null) {
                step.body().run(new Client(kept, keptApp, runId()), values);
            } else {
                for (int i = 0; i < step.steps().size(); i++) runDeclared(step, i, values, kept, keptApp);
            }
        } catch (Exception | AssertionError | LinkageError | StackOverflowError e) {
            finish(out, kept.calls());
            throw kept.failed(out, e);
        }
        finish(out, kept.calls());
        return out;
    }

    /** The outcome's steps, the dialog the last one left waiting, and the first warning a step had. */
    private static void finish(VerbOutcome out, List<StepCall> calls) {
        out.calls = calls;
        for (int k = 0; k < calls.size(); k++) {
            VerbOutcome o = calls.get(k).outcome;
            if (o == null) continue;
            if (out.warning == null && o.warning != null) {
                out.warning = "step " + (k + 1) + " (" + calls.get(k).action + "): " + o.warning;
            }
        }
        StepCall last = calls.isEmpty() ? null : calls.get(calls.size() - 1);
        out.pending = last == null || last.outcome == null ? null : last.outcome.pending;
    }

    /** Runs entry {@code i} of a declarative body, its placeholders filled. */
    private static void runDeclared(DomainStep step, int i, MapParams values, Verbs verbs, ClientApp app) throws IOException {
        JsonNode filled = fill(JSON.readTree(step.steps().get(i)), values);
        Action a = JSON.treeToValue(filled, Action.class);
        if (a instanceof VerbAction) {
            ((VerbAction) a).run(verbs);
        } else if (a instanceof NavigateAction) {
            app.navigate(((NavigateAction) a).target);
        } else if (a instanceof CloseAction) {
            app.closeActiveTab();
        } else if (a instanceof LoginAsAction) {
            app.loginAs(((LoginAsAction) a).as);
        } else {
            throw new DriverException(Reason.BAD_INPUT, "steps entry " + (i + 1) + " of " + step.id() + " is a '"
                    + filled.path("action").asText() + "' step; a domain step's steps are verbs and app steps");
        }
    }

    /**
     * {@code node} with each {@code {name}} replaced by its value; a text that is only an INT param's placeholder
     * becomes the number (a row index, a count).
     */
    static JsonNode fill(JsonNode node, MapParams values) {
        if (node.isTextual()) {
            String s = node.asText();
            if (s.length() > 2 && s.startsWith("{") && s.endsWith("}")) {
                String name = s.substring(1, s.length() - 1);
                if (values.type(name) == ParamType.INT) return IntNode.valueOf(Integer.parseInt(values.text(name)));
            }
            return TextNode.valueOf(values.fill(s));
        }
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> f = it.next();
                out.set(f.getKey(), fill(f.getValue(), values));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (JsonNode e : node) out.add(fill(e, values));
            return out;
        }
        return node;
    }

    /** A run id like a UI test's, e.g. {@code UIT-20261016-141503-a3f9}: one per run of a step. */
    static String runId() {
        return "UIT-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date())
                + "-" + String.format("%04x", ThreadLocalRandom.current().nextInt(0x10000));
    }
}
