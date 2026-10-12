package io.github.kormcrete.swingui.mcp;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Expectation;
import io.github.kormcrete.swingui.driver.FileChooserState;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.Shot;
import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Node;
import io.github.kormcrete.swingui.driver.platform.Capability;
import io.github.kormcrete.swingui.driver.platform.CapabilityReport;
import io.github.kormcrete.swingui.driver.platform.FakeSeam;
import io.github.kormcrete.swingui.driver.platform.Watch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Records every call; answers with {@link #node} / {@link #outcome}, or throws {@link #failure}. */
public class FakeVerbs implements Verbs {
    public final FakeSeam seam = new FakeSeam();
    public final Watch watch = new Watch(seam);
    public final List<String> calls = new ArrayList<String>();
    public Node node = new Node("app", null);
    public RuntimeException failure;
    public VerbOutcome outcome = new VerbOutcome();
    /** What {@link #candidates} answers. */
    public List<String> candidates = new ArrayList<String>();
    /** What {@link #screenshot} answers. */
    public Shot shot = new Shot(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, 4, 3, 8, 6, true);
    /**
     * Thrown by the diagnostic reads (scoped snapshot, candidates, screenshot) instead of
     * {@link #failure}, so a failing step can still be diagnosed.
     */
    public RuntimeException readFailure;
    /** Thrown by a snapshot scoped to a target only - a scope that does not resolve. */
    public RuntimeException scopedSnapshotFailure;
    /** Runs inside every verb, e.g. to feed the watch as real input would. */
    public Runnable during = () -> { };

    @Override public CapabilityReport capabilities() {
        return new CapabilityReport("FakeSeam", Collections.<Capability, String>emptyMap());
    }
    @Override public Watch watch() { return watch; }
    @Override public Node snapshot(String window, String ref, Integer maxNodes, Integer maxRows) {
        calls.add("snapshot " + window + " " + ref + " " + maxNodes + " " + maxRows);
        fail();
        return node;
    }
    /** A scoped snapshot (a target or a depth); an unscoped one is recorded as the plain snapshot call. */
    @Override public Node snapshot(String window, String ref, Locator target, Integer depth, Integer maxNodes,
                                   Integer maxRows) {
        if (target == null && depth == null) return snapshot(window, ref, maxNodes, maxRows);
        calls.add("snapshot " + window + " " + ref + " " + target + " " + depth + " " + maxNodes + " " + maxRows);
        if (readFailure != null) throw readFailure;
        if (target != null && scopedSnapshotFailure != null) throw scopedSnapshotFailure;
        return node;
    }
    @Override public List<String> candidates(Locator target) {
        calls.add("candidates " + target);
        if (readFailure != null) throw readFailure;
        return candidates;
    }
    @Override public Shot screenshot(Locator target, double[] region, Integer maxSize, Double scale) {
        calls.add("screenshot " + target + " " + maxSize + (scale == null ? "" : " " + scale));
        if (readFailure != null) throw readFailure;
        return shot;
    }
    @Override public Node read(Locator target, Integer maxRows) {
        calls.add("read " + target + " " + maxRows);
        fail();
        return node;
    }
    @Override public VerbOutcome click(Locator target, String button, Integer count, List<String> modifiers, Long timeout) {
        return verb("click " + target + " " + button + " " + count + " " + modifiers + " " + timeout);
    }
    @Override public VerbOutcome type(Locator target, String text, Boolean replace, Boolean commit, Long timeout) {
        return verb("type " + target + " " + text + " " + replace + " " + commit);
    }
    @Override public VerbOutcome press(String keys, Locator target, Long timeout) {
        return verb("press " + keys + " " + target);
    }
    @Override public VerbOutcome check(Locator target, Boolean checked, Long timeout) {
        return verb("check " + target + " " + checked);
    }
    @Override public VerbOutcome select(Locator target, List<String> items, Long timeout) {
        return verb("select " + target + " " + items);
    }
    @Override public VerbOutcome pick(List<String> path, Long timeout) {
        return verb("pick " + path);
    }
    @Override public VerbOutcome menu(List<String> path, Long timeout) {
        return verb("menu " + path);
    }
    @Override public VerbOutcome contextMenu(Locator target, List<String> path, Long timeout) {
        return verb("contextMenu " + target + " " + path);
    }
    @Override public VerbOutcome answer(String button, Long timeout) {
        return verb("answer " + button);
    }
    public String clipboard;
    @Override public VerbOutcome setClipboard(String text, Long timeout) {
        clipboard = text;
        return verb("setClipboard " + text);
    }
    @Override public String clipboardText() { return clipboard; }
    /** What fileChooser answers; null: no chooser shows (NOT_FOUND, as the live driver says it). */
    public FileChooserState fileChooserState;
    @Override public FileChooserState fileChooser(Long timeout) {
        if (fileChooserState == null) throw new DriverException(Reason.NOT_FOUND, "no file chooser is showing");
        return fileChooserState;
    }
    @Override public VerbOutcome chooseFile(String path, Boolean approve, Long timeout) {
        return verb("chooseFile " + path + " " + approve);
    }
    @Override public VerbOutcome drag(Locator from, Locator to, Long timeout) {
        return verb("drag " + from + " -> " + to);
    }
    @Override public VerbOutcome drop(Locator target, List<String> files, String text, Long timeout) {
        return verb("drop " + target + " " + files + " " + text);
    }
    @Override public VerbOutcome expect(Locator target, Expectation expectation, Long timeout) {
        return verb("expect " + target);
    }
    @Override public VerbOutcome scroll(Locator target, String to, Integer pages, Long timeout) {
        return verb("scroll " + target + " " + to + " " + pages);
    }

    private VerbOutcome verb(String call) {
        calls.add(call);
        during.run();
        fail();
        return outcome;
    }

    private void fail() {
        if (failure != null) throw failure;
    }
}
