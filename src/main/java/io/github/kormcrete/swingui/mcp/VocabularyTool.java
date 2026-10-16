package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.agent.Plugins;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.flow.FlowJson;
import io.github.kormcrete.swingui.spi.DomainStep;
import io.github.kormcrete.swingui.vocab.Pattern;
import io.github.kormcrete.swingui.vocab.PatternCompiler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The {@code vocabulary} tool and {@code describe_ui}'s {@code vocabulary}: what the ready plugins' domain steps are
 * called, say, take and fold from. Read-only. {@code list} gives every step with a one-line {@code folds} summary of its
 * patterns; {@code describe} one step in full, its params with the values that fit right now ({@link DomainStep.Param#choices},
 * asked on a thread of its own and given up after {@link #CHOICES_LIMIT_MS}); {@code match} (a dry run of a recording range)
 * is the session's ({@link UiMcpSession#vocabularyMatch}), as it reads the recording. No op runs a step's body: a body's steps are shown from the trace made when
 * the plugin became ready. A choices supplier that ignores the interrupt after the limit keeps its daemon thread.
 */
final class VocabularyTool {

    /** How long all the live choices of one describe may take together. */
    static final long CHOICES_LIMIT_MS = 5000;
    /** The most choices one param shows; {@code choicesTotal} says how many there were when cut. */
    static final int MAX_CHOICES = 200;

    private final Plugins plugins;
    private final ObjectMapper mapper;
    private final long choicesLimitMs;

    VocabularyTool(Plugins plugins, ObjectMapper mapper) {
        this(plugins, mapper, CHOICES_LIMIT_MS);
    }

    VocabularyTool(Plugins plugins, ObjectMapper mapper, long choicesLimitMs) {
        this.plugins = plugins;
        this.mapper = mapper;
        this.choicesLimitMs = choicesLimitMs;
    }

    /** The sentences, for {@code describe_ui}: one {@code {id, kind, sentence}} per step; empty without a vocabulary. */
    ArrayNode sentences() {
        ArrayNode out = mapper.createArrayNode();
        if (plugins == null) return out;
        for (Plugins.VocabularyStep v : plugins.vocabulary()) {
            ObjectNode o = out.addObject();
            o.put("id", v.name);
            o.put("kind", kind(v.step));
            o.put("sentence", v.step.sentence());
        }
        return out;
    }

    String run(JsonNode args) {
        String op = args == null ? "" : args.path("op").asText("");
        switch (op) {
            case "list": return list().toString();
            case "describe": return describe(args.path("step").asText("")).toString();
            case "match":
                throw new IllegalStateException("match reads the recording: the session answers it");
            default:
                throw new DriverException(Reason.BAD_INPUT, "vocabulary needs 'op': list, describe or match"
                        + (op.isEmpty() ? "" : " (got '" + op + "')"));
        }
    }

    private ObjectNode list() {
        ObjectNode r = mapper.createObjectNode();
        ArrayNode steps = r.putArray("steps");
        List<Plugins.VocabularyStep> all = plugins == null ? new ArrayList<Plugins.VocabularyStep>() : plugins.vocabulary();
        for (Plugins.VocabularyStep v : all) {
            ObjectNode o = steps.addObject();
            head(o, v);
            o.set("params", params(v.step, false, 0));
            o.put("folds", folds(plugins.patternsOf(v)));
        }
        if (all.isEmpty()) r.put("hint", emptyHint());
        problems(r);
        return r;
    }

    private ObjectNode describe(String id) {
        if (id.trim().isEmpty()) throw new DriverException(Reason.BAD_INPUT, "describe needs 'step': a step id from list");
        List<Plugins.VocabularyStep> all = plugins == null ? new ArrayList<Plugins.VocabularyStep>() : plugins.vocabulary();
        List<Plugins.VocabularyStep> found = new ArrayList<Plugins.VocabularyStep>();
        List<String> names = new ArrayList<String>();
        for (Plugins.VocabularyStep v : all) {
            names.add(v.name);
            if (v.name.equals(id) || v.step.id().equals(id) || (v.plugin + ":" + v.step.id()).equals(id)) found.add(v);
        }
        if (found.size() != 1) {
            throw new DriverException(Reason.BAD_INPUT, found.isEmpty()
                    ? "no vocabulary step '" + id + "'" + (names.isEmpty() ? ": " + emptyHint() : " (steps: " + String.join(", ", names) + ")")
                    : "'" + id + "' is offered by more than one plugin; name one as <plugin>:<id>");
        }
        Plugins.VocabularyStep v = found.get(0);
        DomainStep step = v.step;
        PatternCompiler.Compiled compiled = plugins.patternsOf(v);
        ObjectNode r = mapper.createObjectNode();
        head(r, v);
        r.set("params", params(step, true, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(choicesLimitMs)));
        ArrayNode patterns = r.putArray("patterns");
        if (compiled != null) {
            for (Pattern p : compiled.patterns()) {
                ObjectNode o = patterns.addObject();
                o.put("derived", p.derived());
                ArrayNode steps = o.putArray("steps");
                for (ObjectNode s : p.source()) steps.add(s);
            }
            if (compiled.why() != null) r.put("why", compiled.why());
        }
        r.set("body", body(step, compiled));
        r.put("autoFold", step.autoFold());
        if (step.javaClass() != null) {
            r.putObject("java").put("class", step.javaClass()).put("call", step.javaCall());
        }
        return r;
    }

    private void head(ObjectNode o, Plugins.VocabularyStep v) {
        o.put("id", v.name);
        o.put("kind", kind(v.step));
        o.put("sentence", v.step.sentence());
        if (v.step.about() != null) o.put("about", v.step.about());
        o.put("plugin", v.plugin);
    }

    private static String kind(DomainStep step) {
        return step.kind() == DomainStep.Kind.CHECK ? "check" : "act";
    }

    private ArrayNode params(DomainStep step, boolean withChoices, long deadline) {
        ArrayNode out = mapper.createArrayNode();
        for (DomainStep.Param p : step.params()) {
            ObjectNode o = out.addObject();
            o.put("name", p.name());
            o.put("type", p.type().name());
            o.put("optional", p.optional());
            if (p.optional()) o.put("default", p.defaultValue());
            if (withChoices && p.choices() != null) choices(o, p, deadline);
        }
        return out;
    }

    /** The values that fit now: asked on a thread of its own so a supplier that blocks or throws cannot hold the session. */
    private void choices(ObjectNode into, DomainStep.Param p, long deadline) {
        final java.util.function.Supplier<List<String>> supplier = p.choices();
        List<String> values;
        try {
            values = bounded(new Callable<List<String>>() {
                @Override public List<String> call() { return supplier.get(); }
            }, Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
        } catch (TimeoutException e) {
            into.put("choicesProblem", "the choices were not ready within " + choicesLimitMs + " ms");
            return;
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            into.put("choicesProblem", "the choices failed: " + (c.getMessage() != null ? c.getMessage() : c.getClass().getName()));
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            into.put("choicesProblem", "the choices were interrupted");
            return;
        }
        if (values == null) return;
        ArrayNode out = into.putArray("choices");
        int n = 0;
        for (String v : values) {
            if (n++ == MAX_CHOICES) break;
            out.add(v);
        }
        if (values.size() > MAX_CHOICES) into.put("choicesTotal", values.size());
    }

    private static <T> T bounded(Callable<T> call, long limitMs) throws TimeoutException, ExecutionException, InterruptedException {
        FutureTask<T> task = new FutureTask<T>(call);
        Thread t = new Thread(task, "swing-ui-agent vocabulary choices");
        t.setDaemon(true);
        t.setContextClassLoader(Thread.currentThread().getContextClassLoader());
        t.start();
        try {
            return task.get(limitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException e) {
            t.interrupt();
            throw e;
        }
    }

    /** The step's body as steps with {@code {param}} placeholders: the declarative steps, or the Java body traced. */
    private ObjectNode body(DomainStep step, PatternCompiler.Compiled compiled) {
        ObjectNode o = mapper.createObjectNode();
        ArrayNode steps = o.putArray("steps");
        if (step.body() == null) {
            o.put("kind", "steps");
            for (String json : step.steps()) {
                try {
                    steps.add(FlowJson.mapper().readTree(json));
                } catch (IOException e) {
                    steps.add(json);
                }
            }
            return o;
        }
        o.put("kind", "code");
        List<Pattern> own = compiled == null ? new ArrayList<Pattern>() : compiled.patterns();
        if (!own.isEmpty() && own.get(0).derived()) {
            for (ObjectNode s : own.get(0).source()) steps.add(s);
            return o;
        }
        // never run the body here: it may have side effects; show the trace made at ready, or why there is none
        o.put("why", !step.match().isEmpty() ? "the body is not traced: its pattern is written with match(...)"
                : compiled != null && compiled.why() != null ? compiled.why() : "the body was not traced");
        return o;
    }

    /** One line: the patterns, each as its steps' compact JSON, or why the step has none. */
    private static String folds(PatternCompiler.Compiled compiled) {
        if (compiled == null) return "no pattern";
        List<String> parts = new ArrayList<String>();
        for (Pattern p : compiled.patterns()) {
            List<String> steps = new ArrayList<String>();
            for (ObjectNode s : p.source()) steps.add(s.toString());
            parts.add((p.derived() ? "derived: " : "written: ") + String.join(" > ", steps));
        }
        if (parts.isEmpty()) return "no pattern: " + (compiled.why() != null ? compiled.why() : "none given");
        String line = String.join(" | ", parts);
        return compiled.why() != null ? line + " | own pattern dropped: " + compiled.why() : line;
    }

    private void problems(ObjectNode r) {
        if (plugins == null) return;
        List<String> problems = plugins.vocabularyProblems();
        if (!problems.isEmpty()) r.set("problems", mapper.valueToTree(problems));
    }

    private String emptyHint() {
        return plugins == null || plugins.slots().isEmpty()
                ? "no plugin configured (agent argument plugins=...): a plugin may offer a vocabulary of domain steps"
                : "no ready plugin offers a vocabulary (see session for the plugins' states and problems)";
    }
}
