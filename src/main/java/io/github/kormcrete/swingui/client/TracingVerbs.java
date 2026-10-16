package io.github.kormcrete.swingui.client;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Expectation;
import io.github.kormcrete.swingui.driver.FileChooserState;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.Shot;
import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Geometry;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Node;
import io.github.kormcrete.swingui.driver.platform.CapabilityReport;
import io.github.kormcrete.swingui.driver.platform.Watch;
import io.github.kormcrete.swingui.junit.ClientApp;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Verbs that run nothing: each verb call keeps the step JSON {@link RemoteVerbs} would send as one do_action step,
 * so Java step code written against a {@code Client} becomes a list of steps. {@link #app()} keeps the app steps
 * ({@code navigate}, {@code close}, {@code loginAs}) the same way.
 *
 * <p>Some calls make the trace not derivable: what the steps are then depends on the app, not only on the code. A read
 * ({@code read}, {@code snapshot}, a screenshot, the clipboard, the file chooser, a menu's items, a geometry) has no
 * answer without the app: it throws CAPABILITY_GAP, which ends the body. An {@code expect} is kept as a step (a check
 * is no pattern to fold by) and the body goes on. A call with no step form (hooks, a login by name and password,
 * relogin, a recorded test run, a secret) and a step past {@link #MAX_STEPS} throw CAPABILITY_GAP too and end the
 * body. The first reason is kept ({@link #notDerivable()}).
 */
public final class TracingVerbs implements Verbs {

    /** A body that makes more steps than this is stopped: a loop on a value, most likely. */
    public static final int MAX_STEPS = 500;

    private final StepJson json = new StepJson(AgentClient.mapper());
    private final List<ObjectNode> steps = new ArrayList<ObjectNode>();
    private String notDerivable;

    /** The steps so far, in call order. */
    public List<ObjectNode> steps() { return Collections.unmodifiableList(steps); }

    /** True while nothing the body did made the trace depend on the app. */
    public boolean derivable() { return notDerivable == null; }

    /** Why the trace is not derivable, e.g. "the body reads the app (read), which a trace cannot answer"; else null. */
    public String notDerivable() { return notDerivable; }

    /** The app side for a {@code Client} over these verbs: app steps are kept with the verbs' steps. */
    public ClientApp app() {
        return new ClientApp() {
            @Override public void navigate(String target) { add(json.navigate(target)); }
            @Override public void closeActiveTab() { add(json.close()); }
            @Override public void loginAs(String alias) { add(json.loginAs(alias)); }
            @Override public void loginAs(String user, String password) {
                throw noStepForm("the body logs in by user name and password, which has no step form");
            }
            @Override public void relogin() { throw noStepForm("the body logs in again, which has no step form"); }
            @Override public <T> T hooks(Class<T> type) {
                throw noStepForm("the body calls the app plugin's hooks, which have no step form");
            }
            @Override public String runRecordedTest(String testJson, String evidenceDir) {
                throw noStepForm("the body runs a recorded test, which has no step form");
            }
            @Override public String secret(String alias, String name) {
                throw noStepForm("the body reads a secret, which a trace must not keep");
            }
        };
    }

    @Override public CapabilityReport capabilities() { throw Verbs.missing("capabilities"); }
    @Override public Watch watch() { throw Verbs.missing("watch"); }

    // ---- reads: no answer without the app

    @Override public Node snapshot(String window, String ref, Integer maxNodes, Integer maxRows) { throw read("snapshot"); }

    @Override public Node snapshot(String window, String ref, Locator target, Integer depth, Integer maxNodes, Integer maxRows) {
        throw read("snapshot");
    }

    @Override public Node read(Locator target, Integer maxRows) { throw read("read"); }
    @Override public List<Node> readAll(Locator target, Integer maxRows) { throw read("read"); }
    @Override public Geometry geometry(Locator target) { throw read("geometry"); }
    @Override public List<String> menuItems(List<String> path) { throw read("menu items"); }
    @Override public Shot screenshot(Locator target, double[] region, Integer maxSize, Double scale, boolean palette) {
        throw read("screenshot");
    }
    @Override public String clipboardText() { throw read("clipboard"); }
    @Override public FileChooserState fileChooser(Long timeout) { throw read("file chooser"); }

    /** Asked only after a failed step, which a trace has none of: nothing to suggest. */
    @Override public List<String> candidates(Locator target) { return Collections.emptyList(); }

    // ---- verbs: one step each

    @Override public VerbOutcome click(Locator target, String button, Integer count, List<String> modifiers, Long timeout) {
        return add(json.click(target, button, count, modifiers, timeout));
    }

    @Override public VerbOutcome type(Locator target, String text, Boolean replace, Boolean commit, Long timeout) {
        return add(json.type(target, text, replace, commit, timeout));
    }

    @Override public VerbOutcome press(String keys, Locator target, Long timeout) {
        return add(json.press(keys, target, timeout));
    }

    @Override public VerbOutcome check(Locator target, Boolean checked, Long timeout) {
        return add(json.check(target, checked, timeout));
    }

    @Override public VerbOutcome select(Locator target, List<String> items, Long timeout) {
        return add(json.select(target, items, timeout));
    }

    @Override public VerbOutcome pick(List<String> path, Long timeout) {
        return add(json.pick(path, timeout));
    }

    @Override public VerbOutcome menu(List<String> path, Long timeout) {
        return add(json.menu(path, timeout));
    }

    @Override public VerbOutcome contextMenu(Locator target, List<String> path, Long timeout) {
        return add(json.contextMenu(target, path, timeout));
    }

    @Override public VerbOutcome answer(String button, Long timeout) {
        return add(json.answer(button, timeout));
    }

    @Override public VerbOutcome chooseFile(String path, Boolean approve, Long timeout) {
        return add(json.chooseFile(path, approve, timeout));
    }

    @Override public VerbOutcome focusFileChooserName(Long timeout) {
        return add(json.focusFileChooserName(timeout));
    }

    @Override public VerbOutcome drag(Locator from, Locator to, Long timeout) {
        return add(json.drag(from, to, timeout));
    }

    @Override public VerbOutcome drop(Locator target, List<String> files, String text, Long timeout) {
        return add(json.drop(target, files, text, timeout));
    }

    @Override public VerbOutcome resizeColumn(Locator column, int delta, Long timeout) {
        return add(json.resizeColumn(column, delta, timeout));
    }

    @Override public VerbOutcome resizeWindow(String window, int width, int height, Long timeout) {
        return add(json.resizeWindow(window, width, height, timeout));
    }

    @Override public VerbOutcome closeWindow(String window, Long timeout) {
        return add(json.closeWindow(window, timeout));
    }

    @Override public VerbOutcome hover(Locator target, Boolean inside, Long timeout) {
        return add(json.hover(target, inside, timeout));
    }

    /** Kept as a step; the trace is no longer derivable. */
    @Override public VerbOutcome expect(Locator target, Expectation expectation, Long timeout) {
        VerbOutcome out = add(json.expect(target, expectation, timeout));
        mark("the body checks the app (expect)");
        return out;
    }

    @Override public VerbOutcome scroll(Locator target, String to, Integer pages, Long timeout) {
        return add(json.scroll(target, to, pages, timeout));
    }

    @Override public VerbOutcome setClipboard(String text, Long timeout) {
        return add(json.setClipboard(text, timeout));
    }

    // resetPointer stays the no-op default: nothing to move.

    private VerbOutcome add(ObjectNode step) {
        if (steps.size() >= MAX_STEPS) {
            mark("the body made more than " + MAX_STEPS + " steps");
            throw new DriverException(Reason.CAPABILITY_GAP, "tracing a step body: more than " + MAX_STEPS + " steps");
        }
        steps.add(step);
        return new VerbOutcome();
    }

    private DriverException read(String what) {
        mark("the body reads the app (" + what + "), which a trace cannot answer");
        return new DriverException(Reason.CAPABILITY_GAP, "tracing a step body: a " + what + " has no answer without the app");
    }

    private DriverException noStepForm(String why) {
        mark(why);
        return new DriverException(Reason.CAPABILITY_GAP, "tracing a step body: " + why);
    }

    private void mark(String why) {
        if (notDerivable == null) notDerivable = why;
    }
}
