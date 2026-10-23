package io.github.kormcrete.swingui.client;

import io.github.kormcrete.swingui.driver.DomainStepFailed;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Expectation;
import io.github.kormcrete.swingui.driver.FileChooserState;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.Shot;
import io.github.kormcrete.swingui.driver.StepCall;
import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Geometry;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Node;
import io.github.kormcrete.swingui.driver.outcome.UiReading;
import io.github.kormcrete.swingui.driver.platform.CapabilityReport;
import io.github.kormcrete.swingui.driver.platform.Watch;
import io.github.kormcrete.swingui.flow.Action;
import io.github.kormcrete.swingui.flow.FlowJson;
import io.github.kormcrete.swingui.junit.ClientApp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Verbs that run every call on other verbs and keep each step they make as a {@link StepCall}: a verb, or an app step
 * of {@link #app} ({@code navigate}, {@code close}, {@code loginAs}), with its step JSON as do_action takes it (the
 * encoding {@link TracingVerbs} keeps), how long it took and how it went. Reads pass through and are not kept. A
 * plugin's domain step runs its body on a {@code Client} over these, so its result lists the steps the body ran. A
 * secret the body reads through {@link #app} is masked ({@value #MASK}) in what is kept, never in what runs.
 * <p>
 * With a {@link Sink} it keeps no list: each step goes to the sink as it ends, and so does each secret read.
 */
public final class StepCallVerbs implements Verbs {

    /** What a secret becomes in a kept step. */
    public static final String MASK = "***";

    /** Where a step goes as it ends, instead of the list. */
    public interface Sink {
        /** {@code call} ended, passed or failed; it started at {@code startedAtMillis} (wall clock). */
        void ended(StepCall call, long startedAtMillis);

        /** A secret read through {@link #app}, or a password a login was given: mask it in all that is kept. */
        default void secret(String value) {}
    }

    private final Verbs verbs;
    /** Gets each step as it ends; null: the steps are kept in {@link #calls}. */
    private final Sink sink;
    private final StepJson json = new StepJson(AgentClient.mapper());
    private final List<StepCall> calls = new ArrayList<StepCall>();
    private final Set<String> secrets = new LinkedHashSet<String>();
    /** The failure the last failed step threw on; null while none failed. */
    private Throwable rethrown;

    public StepCallVerbs(Verbs verbs) {
        this(verbs, null);
    }

    /**
     * Verbs that hand each step to {@code sink} as it ends and keep none ({@link #calls} stays empty). A sink that
     * throws is ignored: it never hides how the step went. App logins with a password are steps too (without it).
     */
    public StepCallVerbs(Verbs verbs, Sink sink) {
        this.verbs = verbs;
        this.sink = sink;
    }

    /** The steps so far, in call order: the last one failed when the body stopped on a failed step; none with a sink. */
    public List<StepCall> calls() {
        return Collections.unmodifiableList(new ArrayList<StepCall>(calls));
    }

    /**
     * The failure of a domain step whose body threw {@code thrown} after these steps ({@link DomainStepFailed#of}): its last
     * step is blamed only when {@code thrown} is the failure that step threw, or caused by it; secrets read are masked.
     */
    public DomainStepFailed failed(VerbOutcome outcome, Throwable thrown) {
        boolean byLastStep = false;
        for (Throwable t = thrown; t != null && !byLastStep; t = t.getCause() == t ? null : t.getCause()) {
            byLastStep = t == rethrown;
        }
        return DomainStepFailed.of(outcome, thrown, byLastStep && rethrown != null, this::mask);
    }

    /** {@code app} with its app steps kept as steps, and the secrets read through it remembered for masking. */
    public ClientApp app(final ClientApp app) {
        return new ClientApp() {
            @Override public void navigate(final String target) {
                run(json.navigate(target), () -> { app.navigate(target); return null; });
            }
            @Override public void closeActiveTab() {
                run(json.close(), () -> { app.closeActiveTab(); return null; });
            }
            @Override public void loginAs(final String alias) {
                run(json.loginAs(alias), () -> { app.loginAs(alias); return null; });
            }
            @Override public void loginAs(final String user, final String password) {
                if (sink == null) {
                    app.loginAs(user, password);
                    return;
                }
                remember(password);
                run(json.loginAsUser(user), () -> { app.loginAs(user, password); return null; });
            }
            @Override public void relogin() {
                if (sink == null) {
                    app.relogin();
                    return;
                }
                run(json.relogin(), () -> { app.relogin(); return null; });
            }
            @Override public <T> T hooks(Class<T> type) { return app.hooks(type); }
            @Override public String secret(String alias, String name) {
                String s = app.secret(alias, name);
                remember(s);
                return s;
            }
            @Override public String runRecordedTest(String testJson, String evidenceDir) {
                return app.runRecordedTest(testJson, evidenceDir);
            }
        };
    }

    /** Masks {@code secret} from now on, and tells the sink. */
    private void remember(String secret) {
        if (secret == null || secret.isEmpty()) return;
        secrets.add(secret);
        if (sink == null) return;
        try {
            sink.secret(secret);
        } catch (RuntimeException | AssertionError ignored) {
            // the sink is a bystander: the step goes on
        }
    }

    // ---- passed through, not kept

    @Override public CapabilityReport capabilities() { return verbs.capabilities(); }
    @Override public Watch watch() { return verbs.watch(); }
    @Override public Node snapshot(String window, String ref, Integer maxNodes, Integer maxRows) {
        return verbs.snapshot(window, ref, maxNodes, maxRows);
    }
    @Override public Node snapshot(String window, String ref, Locator target, Integer depth, Integer maxNodes, Integer maxRows) {
        return verbs.snapshot(window, ref, target, depth, maxNodes, maxRows);
    }
    @Override public Node read(Locator target, Integer maxRows) { return verbs.read(target, maxRows); }
    @Override public List<Node> readAll(Locator target, Integer maxRows) { return verbs.readAll(target, maxRows); }
    @Override public List<String> candidates(Locator target) { return verbs.candidates(target); }
    @Override public Shot screenshot(Locator target, double[] region, Integer maxSize, Double scale, boolean palette) {
        return verbs.screenshot(target, region, maxSize, scale, palette);
    }
    @Override public Geometry geometry(Locator target) { return verbs.geometry(target); }
    @Override public List<String> menuItems(List<String> path) { return verbs.menuItems(path); }
    @Override public FileChooserState fileChooser(Long timeout) { return verbs.fileChooser(timeout); }
    @Override public String clipboardText() { return verbs.clipboardText(); }
    @Override public UiReading readUi() { return verbs.readUi(); }
    @Override public String remember(Locator target, boolean selected, Long timeout) {
        return verbs.remember(target, selected, timeout);
    }
    @Override public void resetPointer() { verbs.resetPointer(); }
    /** The verbs below read what {@code action} changed; its steps are still kept here, as it calls back into these. */
    @Override public VerbOutcome withChanges(Supplier<VerbOutcome> action) { return verbs.withChanges(action); }

    // ---- verbs: one kept step each

    @Override public VerbOutcome click(Locator target, String button, Integer count, List<String> modifiers, Long timeout) {
        return run(json.click(target, button, count, modifiers, timeout), () -> verbs.click(target, button, count, modifiers, timeout));
    }

    @Override public VerbOutcome type(Locator target, String text, Boolean replace, Boolean commit, Long timeout) {
        return run(json.type(target, text, replace, commit, timeout), () -> verbs.type(target, text, replace, commit, timeout));
    }

    @Override public VerbOutcome press(String keys, Locator target, Long timeout) {
        return run(json.press(keys, target, timeout), () -> verbs.press(keys, target, timeout));
    }

    @Override public VerbOutcome check(Locator target, Boolean checked, Long timeout) {
        return run(json.check(target, checked, timeout), () -> verbs.check(target, checked, timeout));
    }

    @Override public VerbOutcome select(Locator target, List<String> items, Long timeout) {
        return run(json.select(target, items, timeout), () -> verbs.select(target, items, timeout));
    }

    @Override public VerbOutcome pick(List<String> path, Long timeout) {
        return run(json.pick(path, timeout), () -> verbs.pick(path, timeout));
    }

    @Override public VerbOutcome menu(List<String> path, Long timeout) {
        return run(json.menu(path, timeout), () -> verbs.menu(path, timeout));
    }

    @Override public VerbOutcome contextMenu(Locator target, List<String> path, Long timeout) {
        return run(json.contextMenu(target, path, timeout), () -> verbs.contextMenu(target, path, timeout));
    }

    @Override public VerbOutcome answer(String button, Long timeout) {
        return run(json.answer(button, timeout), () -> verbs.answer(button, timeout));
    }

    @Override public VerbOutcome chooseFile(String path, Boolean approve, Long timeout) {
        return run(json.chooseFile(path, approve, timeout), () -> verbs.chooseFile(path, approve, timeout));
    }

    @Override public VerbOutcome focusFileChooserName(Long timeout) {
        return run(json.focusFileChooserName(timeout), () -> verbs.focusFileChooserName(timeout));
    }

    @Override public VerbOutcome drag(Locator from, Locator to, Long timeout) {
        return run(json.drag(from, to, timeout), () -> verbs.drag(from, to, timeout));
    }

    @Override public VerbOutcome drop(Locator target, List<String> files, String text, Long timeout) {
        return run(json.drop(target, files, text, timeout), () -> verbs.drop(target, files, text, timeout));
    }

    @Override public VerbOutcome resizeColumn(Locator column, int delta, Long timeout) {
        return run(json.resizeColumn(column, delta, timeout), () -> verbs.resizeColumn(column, delta, timeout));
    }

    @Override public VerbOutcome resizeWindow(String window, int width, int height, Long timeout) {
        return run(json.resizeWindow(window, width, height, timeout), () -> verbs.resizeWindow(window, width, height, timeout));
    }

    @Override public VerbOutcome closeWindow(String window, Long timeout) {
        return run(json.closeWindow(window, timeout), () -> verbs.closeWindow(window, timeout));
    }

    @Override public VerbOutcome hover(Locator target, Boolean inside, Long timeout) {
        return run(json.hover(target, inside, timeout), () -> verbs.hover(target, inside, timeout));
    }

    @Override public VerbOutcome expect(Locator target, Expectation expectation, Long timeout) {
        return run(json.expect(target, expectation, timeout), () -> verbs.expect(target, expectation, timeout));
    }

    @Override public VerbOutcome scroll(Locator target, String to, Integer pages, Long timeout) {
        return run(json.scroll(target, to, pages, timeout), () -> verbs.scroll(target, to, pages, timeout));
    }

    @Override public VerbOutcome setClipboard(String text, Long timeout) {
        return run(json.setClipboard(text, timeout), () -> verbs.setClipboard(text, timeout));
    }

    /**
     * Keeps {@code step} (or hands it to the sink as it ends), runs {@code call}, and notes how it went; a failure is
     * noted and thrown on unchanged.
     */
    private VerbOutcome run(ObjectNode step, Supplier<VerbOutcome> call) {
        JsonNode kept = mask(step);
        StepCall c = new StepCall(kept.toString(), label(kept));
        if (sink == null) calls.add(c);
        long startedAt = System.currentTimeMillis();
        long started = System.nanoTime();
        try {
            VerbOutcome out = call.get();
            c.passed = true;
            c.outcome = out;
            return out;
        } catch (DriverException e) {
            rethrown = e;
            c.reason = e.reason();
            c.message = mask(e.getMessage());
            if (!e.details().isEmpty()) {
                c.details = new ArrayList<String>();
                for (String d : e.details()) c.details.add(mask(d));
            }
            throw e;
        } catch (RuntimeException | AssertionError e) {
            rethrown = e;
            if (e instanceof UnsupportedOperationException) c.reason = Reason.CAPABILITY_GAP;
            c.message = mask(e.getMessage() != null ? e.getMessage() : e.toString());
            throw e;
        } finally {
            c.ms = (System.nanoTime() - started) / 1_000_000L;
            if (sink != null) {
                try {
                    sink.ended(c, startedAt);
                } catch (RuntimeException | AssertionError ignored) {
                    // the sink is a bystander: the step's own outcome stands
                }
            }
        }
    }

    /** The step's short label, as a step result names it ({@link Action#describe}); its action name if it does not parse. */
    private static String label(JsonNode step) {
        try {
            return FlowJson.mapper().treeToValue(step, Action.class).describe();
        } catch (Exception unreadable) {
            return step.path("action").asText("?");
        }
    }

    /** {@code text} with every secret read so far replaced by {@link #MASK}. */
    private String mask(String text) {
        if (text == null) return null;
        String s = text;
        for (String secret : secrets) s = s.replace(secret, MASK);
        return s;
    }

    /** {@code node} with every secret read so far replaced by {@link #MASK} in its text. */
    private JsonNode mask(JsonNode node) {
        if (secrets.isEmpty()) return node;
        if (node.isTextual()) return TextNode.valueOf(mask(node.asText()));
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> f = it.next();
                out.set(f.getKey(), mask(f.getValue()));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (JsonNode e : node) out.add(mask(e));
            return out;
        }
        return node;
    }
}
