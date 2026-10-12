package io.github.kormcrete.swingui.driver;

import io.github.kormcrete.swingui.driver.model.Geometry;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Node;
import io.github.kormcrete.swingui.driver.platform.CapabilityReport;
import io.github.kormcrete.swingui.driver.platform.Watch;

import java.util.List;

/**
 * What flows, MCP tools and JUnit tests drive: reads, and one method per verb. A null timeout means the
 * default for that verb. Verbs a driver lacks refuse with CAPABILITY_GAP.
 */
public interface Verbs {

    CapabilityReport capabilities();

    Watch watch();

    Node snapshot(String window, String ref, Integer maxNodes, Integer maxRows);

    /**
     * A scoped snapshot: {@code target} limits it to one element's subtree, resolved like a verb's
     * target (a tab's content for a tab), and {@code depth} to that many levels of nodes. At most one
     * of {@code window}, {@code ref} and {@code target}. Refs reset as with any snapshot.
     */
    default Node snapshot(String window, String ref, Locator target, Integer depth, Integer maxNodes, Integer maxRows) {
        if (target == null && depth == null) return snapshot(window, ref, maxNodes, maxRows);
        throw missing("scoped snapshot");
    }

    Node read(Locator target, Integer maxRows);

    /** Every visible match of {@code target}, in tree order, each as {@link #read} describes one. No wait. */
    default List<Node> readAll(Locator target, Integer maxRows) {
        throw missing("readAll");
    }

    /**
     * What {@code target} matches right now, without acting and without waiting: each visible match
     * described (role, name, id, window, trail), at most {@code Resolver.MAX_CANDIDATES}. For
     * diagnosing a failed step, so it must not throw for zero or several matches.
     */
    default List<String> candidates(Locator target) {
        throw missing("candidates");
    }

    /**
     * An image of one component of ours - the key window when {@code target} is null.
     * A read, not an action: nothing is recorded and no flow step exists for it. {@code region} is
     * {x, y, w, h} in fractions 0..1 of the target's bounds, for a snippet rather than the whole of
     * it; {@code maxSize} caps the longest side; {@code scale} (1 to 4, null for 1) paints it as a display
     * of that density would. A target with only a {@code window} is that window.
     */
    default Shot screenshot(Locator target, double[] region, Integer maxSize, Double scale) {
        throw missing("screenshot");
    }

    /** Where {@code target} is: its rectangle in its window's coordinates, and a table's column geometry. No wait. */
    default Geometry geometry(Locator target) {
        throw missing("geometry");
    }

    default VerbOutcome click(Locator target, String button, Integer count, List<String> modifiers, Long timeout) {
        throw missing("click");
    }

    default VerbOutcome type(Locator target, String text, Boolean replace, Boolean commit, Long timeout) {
        throw missing("type");
    }

    default VerbOutcome press(String keys, Locator target, Long timeout) {
        throw missing("press");
    }

    default VerbOutcome check(Locator target, Boolean checked, Long timeout) {
        throw missing("check");
    }

    default VerbOutcome select(Locator target, List<String> items, Long timeout) {
        throw missing("select");
    }

    default VerbOutcome pick(List<String> path, Long timeout) {
        throw missing("pick");
    }

    default VerbOutcome menu(List<String> path, Long timeout) {
        throw missing("menu");
    }

    /** The visible item labels of the menu-bar menu {@code path} names; a read, nothing is opened or recorded. */
    default List<String> menuItems(List<String> path) {
        throw missing("menuItems");
    }

    default VerbOutcome contextMenu(Locator target, List<String> path, Long timeout) {
        throw missing("contextMenu");
    }

    default VerbOutcome answer(String button, Long timeout) {
        throw missing("answer");
    }

    default VerbOutcome chooseFile(String path, Boolean approve, Long timeout) {
        throw missing("chooseFile");
    }

    /** The showing file chooser's folder and file-name field, waiting up to {@code timeout} for one. A read. */
    default FileChooserState fileChooser(Long timeout) {
        throw missing("fileChooser");
    }

    /**
     * Puts keyboard focus in the showing file chooser's file-name field, so a {@link #press} with no target lands
     * there. The field is the look and feel's own class, which no locator reaches; some look and feels open a
     * chooser without focus in it.
     */
    default VerbOutcome focusFileChooserName(Long timeout) {
        throw missing("focusFileChooserName");
    }

    default VerbOutcome drag(Locator from, Locator to, Long timeout) {
        throw missing("drag");
    }

    default VerbOutcome drop(Locator target, List<String> files, String text, Long timeout) {
        throw missing("drop");
    }

    /**
     * Drags a table column's header border by {@code delta} pixels (negative narrows). {@code column} is a
     * table target with a {@code column} and no {@code row}: its header cell.
     */
    default VerbOutcome resizeColumn(Locator column, int delta, Long timeout) {
        throw missing("resizeColumn");
    }

    /** Resizes a top-level window and lays it out. */
    default VerbOutcome resizeWindow(String window, int width, int height, Long timeout) {
        throw missing("resizeWindow");
    }

    /**
     * Closes a window as its title bar's close button does: the window is sent WINDOW_CLOSING and does what it
     * does with that. For a dialog with neither a button nor Escape to close it, e.g. a document viewer.
     * Refuses {@code main}.
     */
    default VerbOutcome closeWindow(String window, Long timeout) {
        throw missing("closeWindow");
    }

    /**
     * The pointer rests on {@code target} ({@code inside} null or true: a posted move, so the element
     * sees MOUSE_ENTERED) or leaves the window the last hover was in ({@code inside} false: the element
     * sees MOUSE_EXITED). Leaving is cleanup: it neither waits for nor aims at {@code target}, and is a
     * no-op when that window is gone.
     */
    default VerbOutcome hover(Locator target, Boolean inside, Long timeout) {
        throw missing("hover");
    }

    /**
     * The pointer leaves the main window, so a hover a failed test left behind (a rollover, an armed
     * tooltip) does not reach the next one. No wait; a no-op when the main window is not displayable, and in
     * a driver with no pointer of its own.
     */
    default void resetPointer() {
    }

    default VerbOutcome expect(Locator target, Expectation expectation, Long timeout) {
        throw missing("expect");
    }

    /**
     * Moves a scrollable element's viewport: to an end, by whole pages, or until a
     * table row is in view. Exactly one of {@code to}, {@code pages} and the target's {@code row}.
     */
    default VerbOutcome scroll(Locator target, String to, Integer pages, Long timeout) {
        throw missing("scroll");
    }

    /**
     * Puts {@code text} on the clipboard the app pastes from, as a copy in another application would -
     * the system clipboard, shared with the user. Nothing is aimed or settled: the paste itself is a
     * {@link #press} of {@code Mod+V} on the focused element.
     */
    default VerbOutcome setClipboard(String text, Long timeout) {
        throw missing("setClipboard");
    }

    /** The clipboard's text - e.g. what a copy in the app left there; null when it holds none. A read. */
    default String clipboardText() {
        throw missing("clipboardText");
    }

    static DriverException missing(String verb) {
        return new DriverException(Reason.CAPABILITY_GAP, "this driver has no '" + verb + "' verb");
    }
}
