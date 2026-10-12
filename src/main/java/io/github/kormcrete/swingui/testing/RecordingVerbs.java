package io.github.kormcrete.swingui.testing;

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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A Verbs that records each call as one line and answers from public knobs; for unit tests of code built on the driver.
 * No AWT. Answers with {@link #readAnswer}, or throws {@link #failNext} once.
 */
public final class RecordingVerbs implements Verbs {

    public final List<String> calls = new ArrayList<String>();
    public DriverException failNext;
    /** expect() throws EXPECTATION this many times, then passes. -1: never passes. */
    public int expectFailuresBeforePass = 0;
    public String expectFailureMessage = "expected text 'X' but was 'Y'";
    public Node readAnswer = new Node();
    public List<String> candidates = new ArrayList<String>();
    public VerbOutcome outcome = new VerbOutcome();
    /**
     * Modal dialogs an {@code answer} can still dismiss: above 0 any answer dismisses one; at 0
     * {@code answer("escape")} throws NOT_FOUND. {@code snapshot("dialog", ..)} shows one modal dialog
     * while any are left, none at 0.
     * -1 (the default): {@code answer} and {@code snapshot} stay plain.
     */
    public int modalsShowing = -1;
    /**
     * The message text inside the scripted modal dialog ({@link #modalsShowing}); none when null. Shaped as
     * the live driver shows a {@code JOptionPane}'s message: a label NAMED "OptionPane.label" (its component
     * name), the text in its {@code value}.
     */
    public String modalText;
    /** When set, {@code read} answers from it instead of {@link #readAnswer}; it may throw a DriverException. */
    public Function<Locator, Node> reads;

    private VerbOutcome verb(String line) {
        calls.add(line);
        if (failNext != null) { DriverException e = failNext; failNext = null; throw e; }
        return outcome;
    }

    @Override public CapabilityReport capabilities() { throw Verbs.missing("capabilities"); }
    @Override public Watch watch() { throw Verbs.missing("watch"); }
    @Override public Node snapshot(String window, String ref, Integer maxNodes, Integer maxRows) {
        calls.add("snapshot " + window);
        if (modalsShowing >= 0 && "dialog".equals(window)) {
            Node app = new Node("app", null);
            if (modalsShowing > 0) {
                Node dialog = new Node("dialog", "Question");
                dialog.modal = Boolean.TRUE;
                if (modalText != null) {
                    Node message = new Node("label", "OptionPane.label");
                    message.value = modalText;
                    dialog.addChild(message);
                }
                app.addChild(dialog);
            }
            return app;
        }
        return readAnswer;
    }
    @Override public Node read(Locator target, Integer maxRows) {
        if (reads != null) { calls.add("read " + target); return reads.apply(target); }
        calls.add("read " + target);
        if (failNext != null) { DriverException e = failNext; failNext = null; throw e; }
        return readAnswer;
    }
    public List<Node> readAllAnswer = new ArrayList<Node>();
    @Override public List<Node> readAll(Locator target, Integer maxRows) {
        calls.add("readAll " + target);
        if (failNext != null) { DriverException e = failNext; failNext = null; throw e; }
        return readAllAnswer;
    }
    public Geometry geometryAnswer = new Geometry();
    @Override public Geometry geometry(Locator t) {
        calls.add("geometry " + t);
        if (failNext != null) { DriverException e = failNext; failNext = null; throw e; }
        return geometryAnswer;
    }
    @Override public List<String> candidates(Locator target) { calls.add("candidates " + target); return candidates; }
    @Override public Shot screenshot(Locator target, double[] region, Integer maxSize, Double scale) {
        calls.add("screenshot " + target); return new Shot(new byte[] {1, 2, 3}, 1, 1, 1, 1, false);
    }
    @Override public VerbOutcome click(Locator t, String button, Integer count, List<String> modifiers, Long timeout) {
        return verb("click " + t + " " + button + " " + count + " " + modifiers + " " + timeout);
    }
    @Override public VerbOutcome type(Locator t, String text, Boolean replace, Boolean commit, Long timeout) {
        return verb("type " + t + " " + text + " " + replace + " " + commit + " " + timeout);
    }
    @Override public VerbOutcome press(String keys, Locator t, Long timeout) { return verb("press " + keys + " " + t + " " + timeout); }
    @Override public VerbOutcome check(Locator t, Boolean checked, Long timeout) { return verb("check " + t + " " + checked + " " + timeout); }
    @Override public VerbOutcome select(Locator t, List<String> items, Long timeout) { return verb("select " + t + " " + items + " " + timeout); }
    @Override public VerbOutcome pick(List<String> path, Long timeout) { return verb("pick " + path + " " + timeout); }
    @Override public VerbOutcome menu(List<String> path, Long timeout) { return verb("menu " + path + " " + timeout); }
    public List<String> menuItemsAnswer = new ArrayList<String>();
    @Override public List<String> menuItems(List<String> path) {
        calls.add("menuItems " + path);
        if (failNext != null) { DriverException e = failNext; failNext = null; throw e; }
        return menuItemsAnswer;
    }
    @Override public VerbOutcome contextMenu(Locator t, List<String> path, Long timeout) { return verb("contextMenu " + t + " " + path + " " + timeout); }
    @Override public VerbOutcome answer(String button, Long timeout) {
        VerbOutcome out = verb("answer " + button + " " + timeout);
        if (modalsShowing > 0) {
            modalsShowing--;
        } else if (modalsShowing == 0 && "escape".equals(button)) {
            throw new DriverException(Reason.NOT_FOUND, "no modal dialog is showing");
        }
        return out;
    }
    @Override public VerbOutcome chooseFile(String path, Boolean approve, Long timeout) { return verb("chooseFile " + path + " " + approve + " " + timeout); }
    @Override public VerbOutcome drag(Locator from, Locator to, Long timeout) { return verb("drag " + from + " -> " + to + " " + timeout); }
    @Override public VerbOutcome resizeColumn(Locator t, int delta, Long timeout) { return verb("resizeColumn " + t + " " + delta + " " + timeout); }
    @Override public VerbOutcome closeWindow(String window, Long timeout) {
        return verb("closeWindow " + window + " " + timeout);
    }
    @Override public VerbOutcome resizeWindow(String window, int w, int h, Long timeout) {
        return verb("resizeWindow " + window + " " + w + "x" + h + " " + timeout);
    }
    @Override public VerbOutcome hover(Locator t, Boolean inside, Long timeout) { return verb("hover " + t + " " + inside + " " + timeout); }
    @Override public void resetPointer() { calls.add("resetPointer"); }
    @Override public VerbOutcome drop(Locator t, List<String> files, String text, Long timeout) { return verb("drop " + t + " " + files + " " + text + " " + timeout); }
    public String clipboard;
    @Override public VerbOutcome setClipboard(String text, Long timeout) {
        VerbOutcome out = verb("setClipboard " + text + " " + timeout);
        clipboard = text;
        return out;
    }
    @Override public String clipboardText() { calls.add("clipboardText"); return clipboard; }
    public FileChooserState fileChooserAnswer;
    @Override public FileChooserState fileChooser(Long timeout) {
        calls.add("fileChooser " + timeout);
        if (failNext != null) { DriverException x = failNext; failNext = null; throw x; }
        if (fileChooserAnswer == null) throw new DriverException(Reason.NOT_FOUND, "no file chooser is showing");
        return fileChooserAnswer;
    }
    @Override public VerbOutcome focusFileChooserName(Long timeout) { return verb("focusFileChooserName " + timeout); }
    @Override public VerbOutcome scroll(Locator t, String to, Integer pages, Long timeout) { return verb("scroll " + t + " " + to + " " + pages + " " + timeout); }
    @Override public VerbOutcome expect(Locator t, Expectation e, Long timeout) {
        calls.add("expect " + t + " " + describe(e) + " " + timeout);
        if (failNext != null) { DriverException x = failNext; failNext = null; throw x; }
        if (expectFailuresBeforePass != 0) {
            if (expectFailuresBeforePass > 0) expectFailuresBeforePass--;
            throw new DriverException(Reason.EXPECTATION, expectFailureMessage);
        }
        return outcome;
    }

    /** One stable line per expectation, e.g. {@code state=[!disabled]}, {@code rows=gte 1}. */
    public static String describe(Expectation e) {
        if (e.state != null) return "state=" + e.state;
        if (e.text != null) return "text=" + e.text;
        if (e.value != null) return "value=" + e.value;
        if (e.rows != null) return "rows=" + e.rows.op + " " + e.rows.value;
        if (e.cell != null) return "cell=" + e.cell.row + "/" + e.cell.column + "=" + e.cell.expected;
        return "selected=" + e.selected;
    }
}
