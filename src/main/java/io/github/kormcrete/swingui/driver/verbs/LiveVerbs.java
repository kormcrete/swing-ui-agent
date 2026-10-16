package io.github.kormcrete.swingui.driver.verbs;

import io.github.kormcrete.swingui.driver.Driver;
import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Edt;
import io.github.kormcrete.swingui.driver.Expectation;
import io.github.kormcrete.swingui.driver.FileChooserState;
import io.github.kormcrete.swingui.driver.PendingDialog;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.Shot;
import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.dnd.DropInjector;
import io.github.kormcrete.swingui.driver.focus.FocusEmulator;
import io.github.kormcrete.swingui.driver.input.Bindings;
import io.github.kormcrete.swingui.driver.input.HitCheck;
import io.github.kormcrete.swingui.driver.input.KeyChord;
import io.github.kormcrete.swingui.driver.input.Poster;
import io.github.kormcrete.swingui.driver.model.Geometry;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Node;
import io.github.kormcrete.swingui.driver.model.Recorder;
import io.github.kormcrete.swingui.driver.model.RowChoice;
import io.github.kormcrete.swingui.driver.model.Resolved;
import io.github.kormcrete.swingui.driver.model.Resolver;
import io.github.kormcrete.swingui.driver.model.RowSpec;
import io.github.kormcrete.swingui.driver.model.Section;
import io.github.kormcrete.swingui.driver.model.Visibility;
import io.github.kormcrete.swingui.driver.model.WindowRef;
import io.github.kormcrete.swingui.driver.platform.CapabilityReport;
import io.github.kormcrete.swingui.driver.platform.Watch;
import io.github.kormcrete.swingui.driver.widgets.ClipboardText;
import io.github.kormcrete.swingui.driver.widgets.ComboWidget;
import io.github.kormcrete.swingui.driver.widgets.Dialogs;
import io.github.kormcrete.swingui.driver.widgets.Expectations;
import io.github.kormcrete.swingui.driver.widgets.Geometries;
import io.github.kormcrete.swingui.driver.widgets.ListWidget;
import io.github.kormcrete.swingui.driver.widgets.MenuWidget;
import io.github.kormcrete.swingui.driver.widgets.Popups;
import io.github.kormcrete.swingui.driver.widgets.Screenshots;
import io.github.kormcrete.swingui.driver.widgets.Scrolls;
import io.github.kormcrete.swingui.driver.widgets.TabsWidget;
import io.github.kormcrete.swingui.driver.widgets.TableWidget;
import io.github.kormcrete.swingui.driver.widgets.Widgets;
import io.github.kormcrete.swingui.driver.window.AppWindows;
import io.github.kormcrete.swingui.driver.window.Modality;
import io.github.kormcrete.swingui.driver.window.WindowRoot;
import io.github.kormcrete.swingui.driver.window.WindowRoots;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.MenuSelectionManager;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.table.TableCellEditor;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.Transferable;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * The live {@link Verbs}. Every acting verb runs one pipeline: resolve (waiting), refuse
 * when blocked, activate, aim, check, record, act, settle, classify the modal it left, restore focus.
 */
public final class LiveVerbs implements Verbs {

    private static final long POLL_MS = 100;
    /** An AMBIGUOUS with the same match set for this long is final: waiting longer cannot make it one match. */
    static final long AMBIGUOUS_STABLE_MS = 1000;
    private static final String POSTED = "posted";
    private static final String API = "api";

    private final Driver driver;

    public LiveVerbs(Driver driver) {
        this.driver = driver;
    }

    // ---------------------------------------------------------------- reads

    @Override
    public CapabilityReport capabilities() {
        return driver.capabilities();
    }

    @Override
    public Watch watch() {
        return driver.watch();
    }

    @Override
    public Node snapshot(final String window, final String ref, Integer maxNodes, Integer maxRows) {
        return snapshot(window, ref, null, null, maxNodes, maxRows);
    }

    @Override
    public Node snapshot(final String window, final String ref, final Locator target, Integer depth,
                         Integer maxNodes, Integer maxRows) {
        int scopes = (window != null ? 1 : 0) + (ref != null ? 1 : 0) + (target != null ? 1 : 0);
        if (scopes > 1) throw bad("snapshot takes one of 'window', 'ref' and 'target', not both (or all three)");
        final int nodes = maxNodes == null ? driver.settings().maxNodes : maxNodes;
        final int rows = maxRows == null ? driver.settings().maxRows : maxRows;
        final int levels = depth == null ? Integer.MAX_VALUE : depth;
        if (nodes < 1 || rows < 0) throw bad("maxNodes must be at least 1 and maxRows at least 0");
        if (levels < 1) throw bad("depth must be at least 1");
        return Edt.call(() -> {
            Component under = ref != null ? driver.refs().get(ref).component()
                    : target != null ? subtreeOf(driver.resolver().resolve(target)) : null;
            return driver.snapshot().build(window, under, nodes, rows, levels);
        });
    }

    /** The component whose subtree a scoped snapshot shows: a tab's content for a tab. */
    private static Component subtreeOf(Resolved r) {
        if (r.tab != null && r.component instanceof JTabbedPane) {
            Component content = ((JTabbedPane) r.component).getComponentAt(r.tab);
            if (content != null) return content;
        }
        return r.component;
    }

    @Override
    public List<String> candidates(final Locator target) {
        if (target == null) return Collections.emptyList();
        return Edt.call(() -> {
            if (target.ref != null) {
                try {
                    return Collections.singletonList(driver.resolver().resolve(target).candidate());
                } catch (DriverException e) {
                    return Collections.singletonList(target.ref + ": " + e.getMessage());
                }
            }
            List<String> out = new ArrayList<String>();
            for (Resolved r : driver.resolver().matches(target)) {
                if (out.size() == Resolver.MAX_CANDIDATES) break;
                out.add(r.candidate());
            }
            return out;
        });
    }

    @Override
    public Node read(final Locator target, Integer maxRows) {
        if (target == null) throw bad("read needs a 'target'");
        final int rows = maxRows == null ? driver.settings().maxRows : maxRows;
        return Edt.call(() -> {
            Resolved r = driver.resolver().resolve(target);
            return driver.snapshot().describe(r.component, r.tab, r.row(), r.column(), r.item(), rows);
        });
    }

    @Override
    public List<Node> readAll(final Locator target, Integer maxRows) {
        if (target == null) throw bad("readAll needs a 'target'");
        if (target.ref != null || target.nth != null) throw bad("readAll reads every match: a 'ref' or 'nth' names one");
        target.validate();
        final int rows = maxRows == null ? driver.settings().maxRows : maxRows;
        return Edt.call(() -> {
            List<Node> out = new ArrayList<Node>();
            for (Resolved r : driver.resolver().matches(target)) {
                out.add(driver.snapshot().describe(r.component, r.tab, r.row(), r.column(), r.item(), rows));
            }
            return out;
        });
    }

    /**
     * Paints the target - the key window when there is none - into a PNG. A read: it posts no input,
     * activates nothing and is not recorded.
     */
    @Override
    public Shot screenshot(final Locator target, final double[] region, final Integer maxSize,
                           final Double scale, final boolean palette) {
        driver.settle().drain();   // a panel swapped in just before (a new tab after a dialog closed) is laid out
        return Edt.call(() -> {
            Component c = target == null ? keyWindow()
                    : isWindowOnly(target) ? windowRoot(target.window)
                    : driver.resolver().resolve(target).component;
            return Screenshots.of(c, region, maxSize, scale, palette);
        });
    }

    @Override
    public Geometry geometry(final Locator target) {
        if (target == null) throw bad("geometry needs a 'target'");
        return Edt.call(() -> {
            if (isWindowOnly(target)) {
                Component root = windowRoot(target.window);
                return Geometries.ofWindow(root instanceof Window ? (Window) root : SwingUtilities.getWindowAncestor(root));
            }
            Resolved r = driver.resolver().resolve(target);
            if (r.part != null) return Geometries.ofArea(r.component, r.part.bounds);
            return Geometries.of(r.component, r.tab, r.row(), r.column(), r.item());
        });
    }

    /** A locator that names a window and nothing inside it: the window itself. */
    private static boolean isWindowOnly(Locator l) {
        return l.window != null && l.ref == null && l.role == null && l.name == null && l.id == null
                && l.scope == null;
    }

    /** The one showing root {@code window} names. EDT. */
    private Component windowRoot(String window) {
        List<WindowRoot> roots = WindowRoots.select(WindowRef.parse(window), driver.windows().roots(),
                driver.windows().main());
        if (roots.isEmpty()) throw new DriverException(Reason.NOT_FOUND, "no showing window matches '" + window + "'");
        if (roots.size() > 1) {
            List<String> titles = new ArrayList<String>();
            for (WindowRoot r : roots) titles.add(r.role + " '" + r.title + "'");
            throw new DriverException(Reason.AMBIGUOUS, roots.size() + " windows match '" + window + "'", titles);
        }
        return roots.get(0).content;
    }

    // ---------------------------------------------------------------- element verbs

    @Override
    public VerbOutcome click(Locator target, String button, Integer count, List<String> modifiers, Long timeout) {
        final Poster.Button pressed = button(button);
        final int clicks = count == null ? 1 : count;
        if (clicks < 1 || clicks > 3) throw bad("count must be 1, 2 or 3");
        KeyChord.modifierMask(modifiers, 0);
        Map<String, Locator> targets = one("target", target);
        final int mask = KeyChord.modifierMask(modifiers, driver.poster().menuShortcutMask());
        return perform(targets, r -> Kind.POINTER, timeout, settleMillis(), t -> {
            Aimed a = t.get("target");
            driver.poster().click(a.component(), a.point, pressed, clicks, mask);
            return POSTED;
        });
    }

    @Override
    public VerbOutcome type(Locator target, final String text, Boolean replace, Boolean commit, Long timeout) {
        if (text == null) throw bad("type needs 'text'");
        final boolean rep = replace == null || replace;
        final boolean com = commit != null && commit;
        return perform(one("target", target), r -> isCell(r) ? Kind.POINTER : Kind.FOCUS, timeout, settleMillis(), t -> {
            Aimed a = t.get("target");
            return isCell(a.resolved) ? typeIntoCell(a, text, rep, com) : typeIntoField(a, text, rep, com);
        });
    }

    @Override
    public VerbOutcome press(final String keys, Locator target, Long timeout) {
        if (keys == null || keys.trim().isEmpty()) throw bad("press needs 'keys'");
        KeyChord.parse(keys, 0);
        if (target == null) {
            return perform(Collections.<String, Locator>emptyMap(), null, timeout, settleMillis(), t -> {
                final Window window = Edt.call(this::keyWindow);
                Edt.run(() -> driver.activation().activate(window));
                driver.poster().chord(window, chord(keys));
                return POSTED;
            });
        }
        return perform(one("target", target), r -> Kind.FOCUS, timeout, settleMillis(), t -> {
            final Component c = t.get("target").component();
            focusOrRefuse(c);
            driver.poster().chord(Edt.call(() -> FocusEmulator.windowOf(c)), chord(keys));
            return POSTED;
        });
    }

    /** Not a pipeline verb: the clipboard is no component, so there is nothing to aim at or settle. */
    @Override
    public VerbOutcome setClipboard(final String text, Long timeout) {
        if (text == null) throw bad("setClipboard needs 'text'");
        Edt.run(() -> ClipboardText.set(Toolkit.getDefaultToolkit().getSystemClipboard(), text));
        VerbOutcome out = new VerbOutcome();
        out.via = API;
        return out;
    }

    @Override
    public String clipboardText() {
        return Edt.call(() -> ClipboardText.get(Toolkit.getDefaultToolkit().getSystemClipboard()));
    }

    @Override
    public VerbOutcome check(Locator target, Boolean checked, Long timeout) {
        if (checked == null) throw bad("check needs 'checked' (true or false)");
        final boolean want = checked;
        return perform(one("target", target), r -> Kind.POINTER, timeout, settleMillis(), t -> {
            Aimed a = t.get("target");
            return isCell(a.resolved) ? checkCell(a, want) : checkButton(a, want);
        });
    }

    @Override
    public VerbOutcome select(Locator target, final List<String> items, Long timeout) {
        if (items == null || items.isEmpty()) throw bad("select needs 'item' or 'items'");
        Map<String, Locator> targets = one("target", target);
        final long wait = waitMillis(timeout);
        return perform(targets, r -> Kind.POINTER, timeout, settleMillis(), t -> {
            Aimed a = t.get("target");
            Component c = a.component();
            if (c instanceof JTabbedPane) return selectTab((JTabbedPane) c, items, wait);
            if (c instanceof JComboBox) return selectInCombo((JComboBox<?>) c, a, items, wait);
            if (c instanceof JList) return selectInList((JList<?>) c, items, wait);
            throw new DriverException(Reason.CAPABILITY_GAP,
                    "select works on combo boxes, lists and tab strips, not on " + a.resolved.describe());
        });
    }

    @Override
    public VerbOutcome pick(final List<String> path, Long timeout) {
        requirePath(path, 1, "pick");
        final long wait = waitMillis(timeout);
        return perform(Collections.<String, Locator>emptyMap(), null, timeout, driver.settings().jobActionMillis,
                t -> pickPath(path, wait));
    }

    @Override
    public VerbOutcome menu(final List<String> path, Long timeout) {
        requirePath(path, 2, "menu");
        final long wait = waitMillis(timeout);
        return perform(Collections.<String, Locator>emptyMap(), null, timeout, driver.settings().jobActionMillis, t -> {
            final Window main = Edt.call(this::mainWindow);
            final JMenuBar[] barHolder = new JMenuBar[1];
            final JMenu top = retryUntil(wait, () -> {
                refuseIfBlocked(main);
                JMenuBar bar = main instanceof RootPaneContainer
                        ? ((RootPaneContainer) main).getRootPane().getJMenuBar() : null;
                if (bar == null) throw new DriverException(Reason.NOT_FOUND, "the main window has no menu bar");
                barHolder[0] = bar;
                return MenuWidget.topMenu(bar, path.get(0));
            });
            final JMenuBar bar = barHolder[0];
            Edt.run(() -> driver.activation().activate(main));
            boolean inWindow = Edt.call(() -> Visibility.SHOWING.isVisible(bar) && Visibility.SHOWING.isVisible(top));
            if (inWindow) {
                clickAt(top, Edt.call(() -> Targets.centre(top)), 0);
                if (waitFor(wait, () -> top.getPopupMenu().isShowing()) == null) {
                    throw notActionable("menu '" + path.get(0) + "' did not open");
                }
                return pickPath(path.subList(1, path.size()), wait);
            }
            // The native macOS screen menu bar (apple.laf.useScreenMenuBar): no posted input reaches it.
            final JMenuItem item = retryUntil(wait, () -> MenuWidget.walk(top, path.subList(1, path.size())));
            // Queued: doClick runs the item's own ActionListener, which may open a modal dialog
            // synchronously and never return while invokeAndWait waits on it — settle must be free to see
            // that dialog as pending instead of the driver thread hanging inside Edt.run.
            EventQueue.invokeLater(() -> {
                MenuSelectionManager.defaultManager().clearSelectedPath();
                item.doClick(0);
            });
            return API;
        });
    }

    @Override
    public List<String> menuItems(final List<String> path) {
        requirePath(path, 1, "menuItems");
        return Edt.call(() -> {
            Window main = mainWindow();
            JMenuBar bar = main instanceof RootPaneContainer ? ((RootPaneContainer) main).getRootPane().getJMenuBar() : null;
            if (bar == null) throw new DriverException(Reason.NOT_FOUND, "the main window has no menu bar");
            return MenuWidget.items(MenuWidget.topMenu(bar, path.get(0)), path.subList(1, path.size()));
        });
    }

    @Override
    public VerbOutcome contextMenu(Locator target, final List<String> path, Long timeout) {
        requirePath(path, 1, "contextMenu");
        Map<String, Locator> targets = one("target", target);
        final long wait = waitMillis(timeout);
        return perform(targets, r -> Kind.POINTER, timeout, driver.settings().jobActionMillis, t -> {
            Aimed a = t.get("target");
            final List<JPopupMenu> before = Edt.call(() -> driver.windows().popups());
            driver.poster().click(a.component(), a.point, Poster.Button.RIGHT, 1, 0);
            if (waitFor(wait, () -> newest(before)) == null) {
                throw notActionable("no context menu opened on " + a.resolved.describe());
            }
            return withPopupClosedOnFailure(() -> pickPath(path, wait));
        });
    }

    @Override
    public VerbOutcome answer(final String button, Long timeout) {
        if (button == null || button.trim().isEmpty()) throw bad("answer needs a 'button' label or \"escape\"");
        final long wait = waitMillis(timeout);
        return perform(Collections.<String, Locator>emptyMap(), null, timeout, settleMillis(), t -> {
            final Dialog dialog = waitFor(wait, Modality::top);
            if (dialog == null) throw new DriverException(Reason.NOT_FOUND, "no modal dialog is showing");
            Edt.run(() -> driver.activation().activate(dialog));
            if ("escape".equalsIgnoreCase(button.trim())) {
                driver.poster().chord(dialog, chord("Escape"));
                if (waitFor(wait, () -> !dialog.isShowing()) == null) {
                    throw notActionable("Escape did not close dialog " + AppWindows.titleOf(dialog));
                }
                return POSTED;
            }
            final AbstractButton b = Edt.call(() -> Dialogs.button(dialog, button.trim()));
            clickAt(b, Edt.call(() -> Targets.centre(b)), 0);
            return POSTED;
        });
    }

    @Override
    public VerbOutcome chooseFile(final String path, Boolean approve, Long timeout) {
        final boolean ok = approve == null || approve;
        if (ok && (path == null || path.trim().isEmpty())) throw bad("chooseFile needs a 'path'");
        final long wait = waitMillis(timeout);
        return perform(Collections.<String, Locator>emptyMap(), null, timeout, settleMillis(), t -> {
            final JFileChooser chooser = waitFor(wait, Dialogs::showingFileChooser);
            if (chooser == null) throw new DriverException(Reason.NOT_FOUND, "no file chooser is showing");
            final Window window = Edt.call(() -> FocusEmulator.windowOf(chooser));
            Edt.run(() -> driver.activation().activate(window));
            final boolean ownWindow = window instanceof Dialog;
            if (!ok) {
                driver.poster().chord(window, chord("Escape"));
                if (ownWindow && waitFor(wait, () -> !window.isShowing()) == null) {
                    throw notActionable("Escape did not cancel the file chooser");
                }
                return POSTED;
            }
            final JTextField name = Edt.call(() -> Dialogs.fileNameField(chooser));
            if (name != null) {
                focusOrRefuse(name);
                selectAll(name);
                driver.poster().text(window, path);
                driver.poster().chord(window, chord("Enter"));
                if (!ownWindow || waitFor(2_000, () -> !window.isShowing()) != null) return POSTED;
            }
            // No file-name field, or Enter did not approve: the chooser's own API is the only path left.
            // Queued: approveSelection runs the chooser's own listeners, which may open a modal dialog
            // synchronously — settle must be free to see it as pending instead of the driver
            // thread hanging inside Edt.run.
            EventQueue.invokeLater(() -> {
                if (!window.isShowing()) return;
                chooser.setSelectedFile(new File(path));
                chooser.approveSelection();
            });
            return API;
        });
    }

    /** Not a pipeline verb: a read, so nothing is aimed, activated or settled. */
    @Override
    public FileChooserState fileChooser(Long timeout) {
        final JFileChooser chooser = waitFor(waitMillis(timeout), Dialogs::showingFileChooser);
        if (chooser == null) throw new DriverException(Reason.NOT_FOUND, "no file chooser is showing");
        return Edt.call(() -> Dialogs.fileChooserState(chooser, driver.focus().owner()));
    }

    /** Not a pipeline verb: the field is no locator's to aim at, and moving focus raises nothing to settle. */
    @Override
    public VerbOutcome focusFileChooserName(Long timeout) {
        final JFileChooser chooser = waitFor(waitMillis(timeout), Dialogs::showingFileChooser);
        if (chooser == null) throw new DriverException(Reason.NOT_FOUND, "no file chooser is showing");
        final JTextField name = Edt.call(() -> Dialogs.fileNameField(chooser));
        if (name == null) throw new DriverException(Reason.NOT_FOUND, "the file chooser has no file-name field");
        Edt.run(() -> driver.activation().activate(FocusEmulator.windowOf(chooser)));
        focusOrRefuse(name);
        VerbOutcome out = new VerbOutcome();
        out.via = API;
        return out;
    }

    @Override
    public VerbOutcome drag(Locator from, Locator to, Long timeout) {
        if (from == null || to == null) throw bad("drag needs 'from' and 'to'");
        Map<String, Locator> targets = new LinkedHashMap<String, Locator>();
        targets.put("from", from);
        targets.put("to", to);
        return perform(targets, r -> Kind.POINTER, timeout, settleMillis(), t -> {
            final Aimed a = t.get("from");
            final Aimed b = t.get("to");
            Point end = Edt.call(() -> {
                if (FocusEmulator.windowOf(a.component()) != FocusEmulator.windowOf(b.component())) {
                    throw new DriverException(Reason.CAPABILITY_GAP, "drag between windows is not supported");
                }
                return SwingUtilities.convertPoint(b.component(), b.point, a.component());
            });
            driver.poster().drag(a.component(), a.point, end);
            return POSTED;
        });
    }

    @Override
    public VerbOutcome resizeColumn(Locator column, final int delta, Long timeout) {
        if (column == null || column.column == null || column.row != null) {
            throw bad("resizeColumn needs a table column target: a table with 'column' and no 'row'");
        }
        return perform(one("target", column), r -> Kind.POINTER, timeout, settleMillis(), t -> {
            Aimed a = t.get("target");
            Point border = Edt.call(() -> Targets.rightBorder(a.resolved));
            driver.poster().drag(a.component(), border, new Point(border.x + delta, border.y));
            return POSTED;
        });
    }

    @Override
    public VerbOutcome resizeWindow(final String window, final int width, final int height, Long timeout) {
        if (window == null) throw bad("resizeWindow needs a 'window'");
        if (width < 1 || height < 1) throw bad("resizeWindow needs a positive width and height");
        return perform(Collections.<String, Locator>emptyMap(), null, timeout, settleMillis(), t -> {
            Edt.run(() -> {
                Component root = windowRoot(window);
                Window w = root instanceof Window ? (Window) root : SwingUtilities.getWindowAncestor(root);
                w.setSize(width, height);
                w.validate();
            });
            return API;
        });
    }

    @Override
    public VerbOutcome closeWindow(final String window, Long timeout) {
        if (window == null || window.trim().isEmpty()) throw bad("closeWindow needs a 'window'");
        if ("main".equals(window.trim())) throw bad("closeWindow does not close the main window");
        final long wait = waitMillis(timeout);
        return perform(Collections.<String, Locator>emptyMap(), null, timeout, settleMillis(), t -> {
            final Window w = Edt.call(() -> {
                Component root = windowRoot(window);
                return root instanceof Window ? (Window) root : SwingUtilities.getWindowAncestor(root);
            });
            // posted, as the title bar's close button posts it: a modal dialog's own loop takes it
            Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING));
            if (waitFor(wait, () -> !w.isShowing()) == null) {
                throw notActionable("closing did not close window " + AppWindows.titleOf(w));
            }
            return POSTED;
        });
    }

    @Override
    public VerbOutcome hover(Locator target, Boolean inside, Long timeout) {
        if (inside != null && !inside) {
            // cleanup, often in a finally after the screen changed: the target need not be there any more
            driver.poster().leaveHovered();
            Edt.run(() -> { });   // queued behind the posted exit: the rollover is cleared on return
            VerbOutcome out = new VerbOutcome();
            out.via = POSTED;
            return out;
        }
        return perform(one("target", target), r -> Kind.VISIBLE, timeout, settleMillis(), t -> {
            Aimed a = t.get("target");
            driver.poster().move(a.component(), a.point);
            return POSTED;
        });
    }

    @Override
    public void resetPointer() {
        Component main = Edt.call(() -> driver.windows().main());
        if (!(main instanceof Window) || !Edt.call(main::isDisplayable)) return;
        driver.poster().leave(main);
        Edt.run(() -> { });
    }

    @Override
    public VerbOutcome drop(Locator target, List<String> files, String text, Long timeout) {
        boolean hasFiles = files != null && !files.isEmpty();
        if (hasFiles == (text != null)) throw bad("drop takes 'files' or 'text', exactly one");
        final Transferable data;
        if (hasFiles) {
            List<File> list = new ArrayList<File>();
            for (String f : files) {
                File file = new File(f);
                if (!file.isFile()) throw bad("no such file: " + f);
                list.add(file);
            }
            data = DropInjector.files(list);
        } else {
            data = DropInjector.text(text);
        }
        return perform(one("target", target), r -> Kind.VISIBLE, timeout, settleMillis(), t -> {
            final Aimed a = t.get("target");
            // Queued: DropInjector.drop runs the target's importData, which may open a modal dialog
            // synchronously — settle must be free to see it as pending instead of the driver
            // thread hanging on an unbounded invokeAndWait. Drain lets a synchronous import finish (and
            // set outcome, or refusal) before we check it; if neither is set, a dialog is open inside
            // importData and the caller's settle (right after this act returns) reports it as pending.
            final AtomicReference<String> outcome = new AtomicReference<String>();
            final AtomicReference<DriverException> refusal = new AtomicReference<DriverException>();
            EventQueue.invokeLater(() -> runDrop(() -> driver.drops().drop(a.component(), a.point, data), outcome, refusal));
            driver.settle().drain();
            if (refusal.get() != null) throw refusal.get();
            String result = outcome.get();
            if (result != null) requireDropSucceeded(result, a.resolved.describe());
            return API;
        });
    }

    /**
     * Runs the queued drop injection. A DriverException from DropInjector (no drop target, rejected by the
     * target's TransferHandler, a capability gap) is the driver's refusal, not an app error: it goes into
     * {@code refusal} for the caller to rethrow on the driver thread, instead of reaching the EDT's
     * uncaught-exception path as an APP_ERROR. Any other exception is the app's and propagates.
     */
    static void runDrop(Callable<String> injection, AtomicReference<String> outcome, AtomicReference<DriverException> refusal) {
        try {
            outcome.set(injection.call());
        } catch (DriverException e) {
            refusal.set(e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public VerbOutcome expect(final Locator target, final Expectation expectation, Long timeout) {
        if (target == null) throw bad("expect needs a 'target'");
        if (expectation == null) throw bad("expect needs one of state, text, value, tooltip, rows, cell, selected, absent");
        expectation.validate();
        if (expectation.absent != null && target.ref != null) {
            throw bad("absent needs a locator, not a ref: a ref names an element that is there");
        }
        long deadline = System.currentTimeMillis() + waitMillis(timeout);
        String lastFailure = null;
        DriverException lastMiss = null;
        while (true) {
            try {
                final Resolved[] found = new Resolved[1];
                String failure = Edt.call(() -> {
                    if (expectation.absent != null) {
                        try {
                            found[0] = driver.resolver().resolve(target);
                        } catch (DriverException gone) {
                            if (gone.reason() == Reason.NOT_FOUND) return null;
                            if (gone.reason() != Reason.AMBIGUOUS) throw gone;
                            return "expected " + target + " to be absent but several of it are still there";
                        }
                        return Expectations.check(found[0], expectation, null);
                    }
                    found[0] = driver.resolver().resolve(target);
                    // A section's state is asked fresh from its source, by component identity, not role:
                    // a claimed button keeps role "button". Only a state assertion uses it.
                    Section liveSection = expectation.state != null
                            ? driver.resolver().liveSection(found[0]) : null;
                    return Expectations.check(found[0], expectation, liveSection);
                });
                if (failure == null) {
                    VerbOutcome out = new VerbOutcome();
                    // an absent element has nothing to aim at: the step is recorded as the locator the caller gave
                    Map<String, Aimed> aimed = found[0] == null ? Collections.<String, Aimed>emptyMap()
                            : Collections.singletonMap("target", new Aimed(found[0], new Point(), found[0].component));
                    out.recorded.putAll(record(one("target", target), aimed));
                    if (expectation.cell != null) out.row = cellRow(found[0], expectation.cell.row);
                    return out;
                }
                lastFailure = failure;
                lastMiss = null;
            } catch (DriverException e) {
                if (e.reason() == Reason.BAD_INPUT || e.reason() == Reason.CAPABILITY_GAP) throw e;
                lastMiss = e;
            }
            if (System.currentTimeMillis() >= deadline) {
                if (lastMiss != null) throw lastMiss;
                throw new DriverException(Reason.EXPECTATION, lastFailure);
            }
            pause(POLL_MS);
        }
    }

    // ---------------------------------------------------------------- lists, tabs, combos, popups

    /**
     * Moves a viewport. The element is resolved without its row sub-target: aiming at a row would scroll
     * it into view as a side effect, and a row below the fold has no visible point for the hit check.
     */
    @Override
    public VerbOutcome scroll(final Locator target, final String to, final Integer pages, Long timeout) {
        if (target == null) throw bad("scroll needs a 'target'");
        target.validate();
        final RowSpec row = target.rowSpec();
        int given = (to != null ? 1 : 0) + (pages != null ? 1 : 0) + (row != null ? 1 : 0);
        if (given != 1) {
            throw bad("scroll takes exactly one of 'to', 'pages' or a 'row' on the target (" + given + " given)");
        }
        if (to != null && !Scrolls.ENDS.contains(to)) {
            throw bad("'to' must be one of " + Scrolls.ENDS + ", was '" + to + "'");
        }
        if (pages != null && pages == 0) throw bad("'pages' must not be zero; it is a signed page count");
        Map<String, Locator> targets = one("target", row == null ? target : target.element());
        VerbOutcome out = perform(targets, r -> Kind.VISIBLE, timeout, settleMillis(), t -> {
            // The element's own viewport: a column target's header sits in the column-header viewport instead.
            Scrolls.scroll(t.get("target").resolved.component, to, pages, row);
            return API;
        });
        // The row was stripped for resolution, but the recorded step is what export_test writes: put it
        // back, or a row scroll replays as a scroll with none of its three arguments and is refused.
        if (row != null) {
            Recorder.Recorded recorded = out.recorded.get("target");
            if (recorded != null && recorded.locator != null) recorded.locator.row = target.row;
        }
        return out;
    }

    private String selectTab(final JTabbedPane tabs, final List<String> items, long wait) {
        if (items.size() != 1) throw bad("a tab strip selects one tab");
        final int index = retryUntil(wait, () -> TabsWidget.index(tabs, items.get(0)));
        if (Edt.call(tabs::getSelectedIndex) == index) return null;
        clickAt(tabs, Edt.call(() -> Targets.centre(tabs.getBoundsAt(index))), 0);
        if (Edt.call(tabs::getSelectedIndex) != index) throw notActionable("the click did not select tab '" + items.get(0) + "'");
        return POSTED;
    }

    private String selectInList(final JList<?> list, List<String> items, long wait) {
        int mask = driver.poster().menuShortcutMask();
        List<Integer> wanted = new ArrayList<Integer>();
        for (int k = 0; k < items.size(); k++) {
            final String item = items.get(k);
            final int index = retryUntil(wait, () -> ListWidget.index(list, item));
            wanted.add(index);
            Point p = Edt.call(() -> {
                list.ensureIndexIsVisible(index);
                return Targets.centre(list.getCellBounds(index, index));
            });
            clickAt(list, p, k == 0 ? 0 : mask);
        }
        List<Integer> selected = Edt.call(() -> Widgets.indices(list.getSelectedIndices()));
        if (!selected.containsAll(wanted)) throw notActionable("the clicks selected rows " + selected + ", not " + wanted);
        return POSTED;
    }

    private String selectInCombo(final JComboBox<?> combo, Aimed a, List<String> items, long wait) {
        if (items.size() != 1) throw bad("a combo box selects one item");
        final String item = items.get(0);
        final int index = retryUntil(wait, () -> ComboWidget.index(combo, item));
        if (item.equals(Edt.call(() -> ComboWidget.selectedText(combo)))) return null;
        final Component opener = Edt.call(() -> opener(combo));
        clickAt(opener, Edt.call(() -> Targets.centre(opener)), 0);
        final JList<?> list = waitFor(wait, () -> Popups.comboList(combo, driver.windows().popups()));
        if (list == null) {
            throw new DriverException(Reason.CAPABILITY_GAP, "the popup of " + a.resolved.describe()
                    + " did not open (look and feel " + Edt.call(() -> UIManager.getLookAndFeel().getName()) + ")");
        }
        Point p = Edt.call(() -> {
            list.ensureIndexIsVisible(index);
            return Targets.centre(list.getCellBounds(index, index));
        });
        clickAt(list, p, 0);
        if (!item.equals(Edt.call(() -> ComboWidget.selectedText(combo)))) {
            throw notActionable("the click did not select '" + item + "' in " + a.resolved.describe());
        }
        return POSTED;
    }

    /** An editable combo opens from its arrow button; a plain one from anywhere on it. EDT. */
    private static Component opener(JComboBox<?> combo) {
        if (!combo.isEditable()) return combo;
        for (Component c : combo.getComponents()) {
            if (c instanceof AbstractButton && c.isVisible()) return c;
        }
        return combo;
    }

    /** Clicks down {@code path} in the innermost showing popup; a hidden last item falls back to doClick. */
    private String pickPath(List<String> path, long wait) {
        JPopupMenu popup = waitFor(wait, this::innermostPopup);
        if (popup == null) throw new DriverException(Reason.NOT_FOUND, "no popup is showing");
        String via = POSTED;
        for (int i = 0; i < path.size(); i++) {
            final JPopupMenu current = popup;
            final String label = path.get(i);
            final boolean last = i == path.size() - 1;
            final JMenuItem item = retryUntil(wait, () -> {
                JMenuItem found = Popups.item(current, label);
                if (!found.isEnabled()) throw notActionable("disabled: menu item '" + label + "'");
                if (last && found instanceof JMenu) throw notActionable("'" + label + "' opens a submenu - add the item to the path");
                if (!last && !(found instanceof JMenu)) throw notActionable("'" + label + "' has no submenu");
                return found;
            });
            final Point p = Edt.call(() -> Targets.centre(item));
            String blocked = Edt.call(() -> hitProblem(item, p));
            if (blocked == null) {
                driver.poster().click(item, p, Poster.Button.LEFT, 1, 0);
                driver.settle().drain();
            } else if (last) {
                // Measured: clear the selection path first, or the menu stays open. Queued: doClick runs
                // the item's own ActionListener, which may open a modal dialog synchronously — settle
                // must be free to see it as pending instead of the driver thread hanging here.
                EventQueue.invokeLater(() -> {
                    MenuSelectionManager.defaultManager().clearSelectedPath();
                    item.doClick(0);
                });
                via = API;
            } else {
                throw notActionable("submenu '" + label + "' cannot be opened: " + blocked);
            }
            if (!last) {
                final JMenu menu = (JMenu) item;
                popup = waitFor(wait, () -> menu.getPopupMenu().isShowing() ? menu.getPopupMenu() : null);
                if (popup == null) throw notActionable("submenu '" + label + "' did not open");
            }
        }
        return via;
    }

    /**
     * Runs {@code act} and, if it throws, clears any menu/submenu selection so a popup {@code act}
     * opened is not left showing (the next {@code contextMenu} right-click would only close it). A
     * RuntimeException is rethrown unchanged, a checked one wrapped in IllegalStateException.
     */
    static <T> T withPopupClosedOnFailure(Callable<T> act) {
        try {
            return act.call();
        } catch (RuntimeException e) {
            Edt.run(() -> MenuSelectionManager.defaultManager().clearSelectedPath());
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JPopupMenu innermostPopup() {
        List<JPopupMenu> popups = driver.windows().popups();
        return popups.isEmpty() ? null : popups.get(popups.size() - 1);
    }

    private JPopupMenu newest(List<JPopupMenu> before) {
        List<JPopupMenu> now = driver.windows().popups();
        for (int i = now.size() - 1; i >= 0; i--) {
            if (!before.contains(now.get(i))) return now.get(i);
        }
        return null;
    }

    private String hitProblem(Component c, Point p) {
        try {
            HitCheck.check(c, p, true, this::nameOf);
            return null;
        } catch (DriverException e) {
            return e.getMessage();
        }
    }

    private Window mainWindow() {
        Component main = driver.windows().main();
        if (!(main instanceof Window)) throw new DriverException(Reason.NOT_FOUND, "the main window is not showing");
        return (Window) main;
    }

    // ---------------------------------------------------------------- the pipeline

    interface Act {
        String run(Map<String, Aimed> targets);
    }

    VerbOutcome perform(Map<String, Locator> locators, Function<Resolved, Kind> kind, Long timeout,
                        long settleDefault, Act act) {
        long settleWait = timeout != null ? timeout : settleDefault;
        Map<String, Aimed> targets = actionable(locators, kind, waitMillis(timeout));
        VerbOutcome out = new VerbOutcome();
        out.recorded.putAll(record(locators, targets));
        List<Window> modalsBefore = driver.settle().showingModals();
        Window acting = actingWindow(targets);
        driver.edtErrors().begin();
        Window pending = null;
        List<Throwable> errors;
        try {
            out.via = act.run(targets);
        } catch (RuntimeException e) {
            throw failure(e, driver.edtErrors().end());
        }
        try {
            // No EDT call before the bounded settle: a stuck EDT must end as its TIMEOUT, not hang here.
            // A scope the action closed is the signals' own business (they fall back to the global answer).
            pending = driver.settle().settle(modalsBefore, settleWait, acting);
        } catch (DriverException e) {
            if (e.reason() != Reason.TIMEOUT) throw failure(e, driver.edtErrors().end());
            out.warning = "the action ran, but the app did not settle within " + settleWait + " ms: " + e.getMessage();
        } catch (RuntimeException e) {
            throw failure(e, driver.edtErrors().end());
        }
        errors = driver.edtErrors().end();
        if (!errors.isEmpty()) {
            Throwable first = errors.get(0);
            throw new DriverException(Reason.APP_ERROR, "uncaught exception on the event dispatch thread: " + first,
                    stack(first));
        }
        if (pending != null) {
            final Window dialog = pending;
            PendingDialog described = Edt.call(() -> Dialogs.describe(dialog, driver.names()));
            if (Edt.call(() -> driver.errorDialogs().isError(dialog))) {
                throw new DriverException(Reason.APP_ERROR, "the app reported an error: " + described.summary());
            }
            out.pending = described;
        }
        restoreFocus(targets);
        return out;
    }

    /** The window the first target sits in; null when the verb had no target. */
    private static Window actingWindow(Map<String, Aimed> targets) {
        for (Aimed a : targets.values()) {
            if (a != null && a.resolved != null && a.resolved.component != null) {
                final Component c = a.resolved.component;
                return Edt.call(() -> FocusEmulator.windowOf(c));
            }
        }
        return null;
    }

    private Map<String, Aimed> actionable(final Map<String, Locator> locators, final Function<Resolved, Kind> kind,
                                          long waitMillis) {
        return retryUntil(waitMillis, () -> {
            Map<String, Aimed> out = new LinkedHashMap<String, Aimed>();
            for (Map.Entry<String, Locator> e : locators.entrySet()) {
                Resolved r = driver.resolver().resolve(e.getValue());
                refuseIfBlocked(r.component);
                activate(r.component);
                Component on = Targets.surface(r);
                Point p = Targets.aim(r);
                check(r, on, p, kind.apply(r));
                out.put(e.getKey(), new Aimed(r, p, on));
            }
            return out;
        });
    }

    /**
     * Retries {@code probe} on the EDT until it succeeds or {@code millis} elapses, {@code POLL_MS}
     * apart: NOT_FOUND, AMBIGUOUS and NOT_ACTIONABLE (resolve, the modal refusal,
     * the hit check) are retried, except an AMBIGUOUS whose match set stays the same for {@link #AMBIGUOUS_STABLE_MS}; any other reason propagates at once, and once the deadline passes the
     * last DriverException propagates.
     */
    static <T> T retryUntil(long millis, Callable<T> probe) {
        long deadline = System.currentTimeMillis() + millis;
        String ambiguous = null;
        long ambiguousSince = 0;
        while (true) {
            try {
                return Edt.call(probe);
            } catch (DriverException e) {
                boolean retry = e.reason() == Reason.NOT_FOUND || e.reason() == Reason.AMBIGUOUS
                        || e.reason() == Reason.NOT_ACTIONABLE;
                long now = System.currentTimeMillis();
                if (e.reason() == Reason.AMBIGUOUS) {
                    String set = e.getMessage() + "\n" + e.details();
                    if (!set.equals(ambiguous)) {
                        ambiguous = set;
                        ambiguousSince = now;
                    } else if (now - ambiguousSince >= AMBIGUOUS_STABLE_MS) {
                        throw e;
                    }
                } else {
                    ambiguous = null;
                }
                if (!retry || now >= deadline) throw e;
                pause(POLL_MS);
            }
        }
    }

    /** {@code c} is the component {@code p} is in: the target, or a table's header for a column with no row. */
    private void check(Resolved r, Component c, Point p, Kind kind) {
        switch (kind) {
            case POINTER:
                HitCheck.check(c, p, true, this::nameOf);
                break;
            case FOCUS:
                if (!c.isShowing()) throw notActionable("hidden: " + r.describe());
                if (!c.isEnabled()) throw notActionable("disabled: " + r.describe());
                break;
            case VISIBLE:
                if (!c.isShowing() || !HitCheck.visibleAt(c, p)) throw notActionable("hidden: " + r.describe());
                break;
            default:
                break;
        }
    }

    Map<String, Recorder.Recorded> record(final Map<String, Locator> locators, final Map<String, Aimed> targets) {
        return Edt.call(() -> {
            Map<String, Recorder.Recorded> out = new LinkedHashMap<String, Recorder.Recorded>();
            for (Map.Entry<String, Locator> e : locators.entrySet()) {
                Locator given = e.getValue();
                Aimed aimed = targets.get(e.getKey());
                // both ways to name the row a step means: its position, and its value when a column tells rows apart
                RowChoice row = aimed == null ? null : Recorder.rowChoice(aimed.resolved);
                // the element as seen, whichever locator is recorded: what a vocabulary's patterns match
                Locator seen = aimed == null ? null : Recorder.seen(aimed.resolved);
                if (given.ref != null) {
                    Recorder.Recorded rec = driver.recorder().record(aimed.resolved);
                    out.put(e.getKey(), new Recorder.Recorded(rec.locator, rec.fragile, row, seen));
                } else {
                    out.put(e.getKey(), new Recorder.Recorded(given.copy(), given.nth != null, row, seen));
                }
            }
            return out;
        });
    }

    /** The row a cell check names, both ways; null when the target is no table. */
    private static RowChoice cellRow(Resolved table, Map<String, Object> row) {
        if (!(table.component instanceof JTable) || row == null) return null;
        return Edt.call(() -> {
            JTable t = (JTable) table.component;
            return Recorder.rowChoice(t, TableWidget.row(t, RowSpec.of(row)));
        });
    }

    /** EDT. */
    void refuseIfBlocked(Component c) {
        Window w = FocusEmulator.windowOf(c);
        Dialog blocker = w == null ? null : Modality.blocker(w);
        if (blocker != null) throw notActionable("blocked by dialog " + AppWindows.titleOf(blocker));
    }

    /** EDT. A popup's window is never focusable; its owner is what gets activated. */
    private void activate(Component c) {
        Window w = FocusEmulator.windowOf(c);
        while (w != null && !w.isFocusableWindow()) w = w.getOwner();
        if (w != null) driver.activation().activate(w);
    }

    private void restoreFocus(final Map<String, Aimed> targets) {
        Edt.run(() -> {
            Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (owner != null && owner.isShowing()) return;
            if (Modality.top() != null) return;
            for (Aimed a : targets.values()) {
                Window w = FocusEmulator.windowOf(a.component());
                while (w != null && (!w.isShowing() || !w.isFocusableWindow())) w = w.getOwner();
                if (w != null) {
                    driver.activation().restoreAfterClose(w);
                    return;
                }
            }
            Component main = driver.windows().main();
            if (main instanceof Window) driver.activation().restoreAfterClose((Window) main);
        });
    }

    // ---------------------------------------------------------------- typing and checking

    private static boolean isCell(Resolved r) {
        return r.component instanceof JTable && r.row() != null;
    }

    /**
     * "not editable: row 3 column 'Notes' of table in main [...]": names the cell, so one read-only cell
     * does not read as a read-only table.
     */
    static DriverException notEditableCell(JTable table, int row, int column, Resolved r) {
        return notActionable("not editable: row " + row + " column '" + TableWidget.headers(table).get(column)
                + "' of " + r.describe());
    }

    private String typeIntoField(Aimed a, String text, boolean replace, boolean commit) {
        final Resolved r = a.resolved;
        final JTextComponent field = Edt.call(() -> textField(r));
        focusOrRefuse(field);
        String via = replace ? selectAll(field) : POSTED;
        Window window = Edt.call(() -> FocusEmulator.windowOf(field));
        if (replace && text.isEmpty()) {
            // Nothing to type over the selected text would leave it standing: clearing a field is deleting it.
            if (!Edt.call(() -> field.getText().isEmpty())) driver.poster().chord(window, chord("Backspace"));
        } else {
            driver.poster().text(window, text);
        }
        if (commit) driver.poster().chord(window, chord("Enter"));
        return via;
    }

    private static JTextComponent textField(Resolved r) {
        Component c = r.component;
        if (c instanceof JComboBox && ((JComboBox<?>) c).isEditable()) {
            c = ((JComboBox<?>) c).getEditor().getEditorComponent();
        }
        if (!(c instanceof JTextComponent)) throw notActionable("not editable: " + r.describe() + " is not a text field");
        JTextComponent field = (JTextComponent) c;
        if (!field.isEditable()) throw notActionable("not editable: " + r.describe());
        return field;
    }

    private String typeIntoCell(final Aimed a, String text, boolean replace, boolean commit) {
        final JTable table = (JTable) a.component();
        final int[] cell = Edt.call(() -> Targets.cell(table, a.resolved));
        Edt.run(() -> {
            if (Boolean.class.equals(table.getColumnClass(cell[1]))) {
                throw notActionable("a check box cell, use check: " + a.resolved.describe());
            }
            if (!table.isCellEditable(cell[0], cell[1])) throw notEditableCell(table, cell[0], cell[1], a.resolved);
        });
        final Window window = Edt.call(() -> FocusEmulator.windowOf(table));
        // A cell already editing through a composite editor (a panel around a text area): a click would land
        // on the panel, and startEditing has nothing to start — type into the editor's one text component.
        final JTextComponent inner = Edt.call(() -> table.isEditing() && table.getEditingRow() == cell[0]
                && table.getEditingColumn() == cell[1] ? compositeEditorText(table.getEditorComponent()) : null);
        if (inner != null) {
            focusOrRefuse(inner);
            String via = replace ? selectAll(inner) : POSTED;
            driver.poster().text(window, text);
            if (commit) {
                driver.poster().chord(window, chord("Enter"));
                if (stopEditingLeftOpen(table, cell, window)) via = API;
            }
            return via;
        }
        driver.poster().click(table, a.point, Poster.Button.LEFT, 1, 0);
        driver.settle().drain();
        // the click asked another cell's editor to stop, and its refusal came as a dialog: the test answers it
        Edt.run(() -> refuseIfAnswered(table, a.resolved));
        String via = POSTED;
        if (replace) {
            // An editor that starts on one click (most custom ones) is already open, and its field holds the focus:
            // startEditing then sees a table without focus and ends the edit instead (Metal, Windows)
            if (!Edt.call(() -> editingCell(table, cell))) {
                KeyStroke start = Edt.call(() ->
                        Bindings.find(table, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, "startEditing"));
                if (start != null) {
                    driver.poster().stroke(window, start);
                    driver.settle().drain();
                    Edt.run(() -> refuseIfAnswered(table, a.resolved));
                }
                if (!Edt.call(() -> editingCell(table, cell))) {
                    // queued: editCellAt first stops another cell's edit, whose validation may open a dialog
                    EventQueue.invokeLater(() -> table.editCellAt(cell[0], cell[1]));
                    driver.settle().drain();
                    via = API;
                }
                Edt.run(() -> {
                    refuseIfAnswered(table, a.resolved);
                    refuseAnotherCell(table, cell, a.resolved);
                });
            }
            JTextComponent field = Edt.call(() -> editorText(table.getEditorComponent()));
            if (field == null) {
                throw notActionable("the editor of " + a.resolved.describe() + " is not a text field and holds no single one");
            }
            focusOrRefuse(field);
            if (API.equals(selectAll(field))) via = API;
        }
        driver.poster().text(window, text);
        if (commit) {
            driver.poster().chord(window, chord("Enter"));
            if (stopEditingLeftOpen(table, cell, window)) via = API;
        }
        return via;
    }

    /** The text component typing goes into: the editor itself, or the one inside a composite editor (a spinner). EDT. */
    static JTextComponent editorText(Component editor) {
        return editor instanceof JTextComponent ? (JTextComponent) editor : compositeEditorText(editor);
    }

    /**
     * Enter ends the edit of a plain text cell; an editor that holds its text field (a spinner) can take the Enter
     * itself and stay open. Then the edit is stopped through the editor, as a click elsewhere would. An editor that
     * refuses the text is cancelled, so the cell keeps its last valid value; a modal dialog the app raised for the
     * value, now blocking the table's {@code window}, is left to the caller. True when the driver had to end the edit.
     * Not run inside invokeAndWait: stopping runs app code (listeners, validation) that may open a modal dialog,
     * which settle must see as pending.
     */
    private boolean stopEditingLeftOpen(final JTable table, final int[] cell, final Window window) {
        driver.settle().drain();
        // the app answered Enter with a dialog (a validating editor explaining a refusal): a second stop would
        // only raise it again
        if (Edt.call(() -> blocked(window))) return false;
        if (!Edt.call(() -> editingCell(table, cell))) return false;
        EventQueue.invokeLater(() -> {
            if (!editingCell(table, cell)) return;
            TableCellEditor editor = table.getCellEditor();
            if (editor != null) editor.stopCellEditing();
        });
        driver.settle().drain();
        if (Edt.call(() -> blocked(window))) return true;
        if (Edt.call(() -> editingCell(table, cell))) {
            EventQueue.invokeLater(() -> {
                if (!editingCell(table, cell)) return;
                TableCellEditor editor = table.getCellEditor();
                if (editor != null) editor.cancelCellEditing();
            });
            driver.settle().drain();
        }
        return true;
    }

    /**
     * The app answered the click into a cell (or the start of its edit) with a modal dialog, typically another
     * cell's editor explaining why it refused to stop: the dialog is the test's to answer, and nothing more is
     * asked of the editor under it. EDT.
     */
    private void refuseIfAnswered(JTable table, Resolved target) {
        Window w = FocusEmulator.windowOf(table);
        Dialog blocker = w == null ? null : Modality.blocker(w);
        if (blocker != null) {
            throw notActionable("blocked by dialog " + AppWindows.titleOf(blocker) + ", which the app opened when "
                    + target.describe() + " was clicked; answer it");
        }
    }

    /**
     * Typing goes into the target cell's editor only: an editor that refused to stop keeps its own cell editing,
     * and its field would take the text. EDT.
     */
    private static void refuseAnotherCell(JTable table, int[] cell, Resolved target) {
        if (editingCell(table, cell)) return;
        List<String> headers = TableWidget.headers(table);
        String why = "the edit of row " + cell[0] + " column '" + headers.get(cell[1]) + "' of " + target.describe()
                + " did not start";
        if (table.isEditing()) {
            why += ": row " + table.getEditingRow() + " column '" + headers.get(table.getEditingColumn())
                    + "' is still being edited, its editor refused to stop";
        }
        throw notActionable(why);
    }

    /** Whether a modal dialog now blocks {@code window}; one the table itself sits in does not. EDT. */
    private static boolean blocked(Window window) {
        return window != null && Modality.blocker(window) != null;
    }

    private static boolean editingCell(JTable table, int[] cell) {
        return table.isEditing() && table.getEditingRow() == cell[0] && table.getEditingColumn() == cell[1];
    }

    /**
     * The one editable, showing text component inside a cell editor that is not a text component itself (a panel
     * holding a text area and its buttons); null when the editor is a text component, holds none, or holds several.
     * EDT.
     */
    static JTextComponent compositeEditorText(Component editor) {
        if (editor == null || editor instanceof JTextComponent || !(editor instanceof Container)) return null;
        List<JTextComponent> found = new ArrayList<JTextComponent>();
        collectEditableText((Container) editor, found);
        return found.size() == 1 ? found.get(0) : null;
    }

    private static void collectEditableText(Container parent, List<JTextComponent> found) {
        for (Component c : parent.getComponents()) {
            if (!c.isVisible()) continue;
            if (c instanceof JTextComponent) {
                if (((JTextComponent) c).isEditable() && c.isEnabled()) found.add((JTextComponent) c);
            } else if (c instanceof Container) {
                collectEditableText((Container) c, found);
            }
        }
    }

    /** The look and feel's select-all key, posted; {@code selectAll()} only when no key is bound. */
    String selectAll(final JTextComponent field) {
        KeyStroke stroke = Edt.call(() -> Bindings.find(field, JComponent.WHEN_FOCUSED, DefaultEditorKit.selectAllAction));
        if (stroke != null) {
            driver.poster().stroke(Edt.call(() -> FocusEmulator.windowOf(field)), stroke);
            driver.settle().drain();
            return POSTED;
        }
        Edt.run(field::selectAll);
        return API;
    }

    private String checkButton(Aimed a, boolean want) {
        Component c = a.component();
        if (!(c instanceof JToggleButton)) throw notActionable("not a check box or toggle: " + a.resolved.describe());
        final AbstractButton b = (AbstractButton) c;
        if (Edt.call(b::isSelected) == want) return null;
        driver.poster().click(b, a.point, Poster.Button.LEFT, 1, 0);
        driver.settle().drain();
        if (Edt.call(b::isSelected) != want) throw notActionable("the click did not change " + a.resolved.describe());
        return POSTED;
    }

    private String checkCell(final Aimed a, boolean want) {
        final JTable table = (JTable) a.component();
        final int[] cell = Edt.call(() -> Targets.cell(table, a.resolved));
        Object before = Edt.call(() -> table.getValueAt(cell[0], cell[1]));
        boolean booleanCell = before instanceof Boolean
                || Edt.call(() -> Boolean.class.equals(table.getColumnClass(cell[1])));
        if (!booleanCell) throw notActionable("not a check box cell: " + a.resolved.describe());
        if (Boolean.valueOf(want).equals(before)) return null;
        if (!Edt.call(() -> table.isCellEditable(cell[0], cell[1]))) {
            throw notEditableCell(table, cell[0], cell[1], a.resolved);
        }
        driver.poster().click(table, a.point, Poster.Button.LEFT, 1, 0);
        driver.settle().drain();
        if (!Boolean.valueOf(want).equals(Edt.call(() -> table.getValueAt(cell[0], cell[1])))) {
            throw notActionable("the click did not change " + a.resolved.describe());
        }
        return POSTED;
    }

    // ---------------------------------------------------------------- shared helpers

    /** The window keys go to: the modal nothing blocks, else the focused window, else the main window. EDT. */
    private Window keyWindow() {
        return keyWindowOf(Modality.top(), KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow(),
                driver.windows().main());
    }

    /**
     * The focused window counts only while it is showing: the driver pins focus itself, so after a dialog closes
     * (no OS focus events while the app is in the background) the focused window can be that closed dialog.
     */
    static Window keyWindowOf(Dialog top, Window focused, Component main) {
        if (top != null) return top;
        if (focused != null && focused.isShowing() && focused.isFocusableWindow()) return focused;
        if (main instanceof Window) return (Window) main;
        throw new DriverException(Reason.NOT_FOUND, "no window to send keys to");
    }

    void focusOrRefuse(Component c) {
        if (!driver.focus().focus(c)) {
            throw notActionable("verifier of " + nameOf(driver.focus().owner()) + " refused");
        }
    }

    /** Hit-checks, posts a left click at {@code p}, and drains. */
    void clickAt(final Component c, final Point p, int modifiers) {
        Edt.run(() -> HitCheck.check(c, p, true, this::nameOf));
        driver.poster().click(c, p, Poster.Button.LEFT, 1, modifiers);
        driver.settle().drain();
    }

    /** Polls on the EDT until the probe answers non-null and not false; null on timeout. */
    <T> T waitFor(long millis, Callable<T> probe) {
        long deadline = System.currentTimeMillis() + millis;
        while (true) {
            T value = Edt.call(probe);
            if (value != null && !Boolean.FALSE.equals(value)) return value;
            if (System.currentTimeMillis() >= deadline) return null;
            pause(50);
        }
    }

    KeyChord chord(String keys) {
        return KeyChord.parse(keys, driver.poster().menuShortcutMask());
    }

    String nameOf(Component c) {
        if (c == null) return "nothing";
        String name = driver.names().nameOf(c, FocusEmulator.windowOf(c));
        return name != null ? name : c.getClass().getSimpleName();
    }

    long waitMillis(Long timeout) {
        return timeout != null ? timeout : driver.settings().expectMillis;
    }

    long settleMillis() {
        return driver.settings().settleMillis;
    }

    static Map<String, Locator> one(String field, Locator locator) {
        if (locator == null) throw bad("'" + field + "' is required");
        Map<String, Locator> out = new LinkedHashMap<String, Locator>();
        out.put(field, locator);
        return out;
    }

    static void requirePath(List<String> path, int min, String verb) {
        if (path == null || path.isEmpty()) throw bad(verb + " needs a 'path'");
        if (path.size() < min) throw bad(verb + " needs a path of at least " + min + " labels");
        for (String step : path) {
            if (step == null || step.trim().isEmpty()) throw bad(verb + " path has a blank label");
        }
    }

    /**
     * Swing's {@code DropHandler} swallows an {@code importData} exception and reports it only as a
     * trailing ", failed" on the outcome {@link DropInjector#drop} returns; a silently discarded outcome
     * would let a failed import pass as success.
     */
    static void requireDropSucceeded(String outcome, String target) {
        if (outcome.endsWith(", failed")) {
            throw new DriverException(Reason.APP_ERROR, "drop into " + target + " failed: " + outcome);
        }
    }

    private static Poster.Button button(String button) {
        if (button == null || "left".equals(button)) return Poster.Button.LEFT;
        if ("right".equals(button)) return Poster.Button.RIGHT;
        throw bad("button must be 'left' or 'right', was '" + button + "'");
    }

    static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DriverException(Reason.TIMEOUT, "interrupted");
        }
    }

    private static List<String> stack(Throwable t) {
        List<String> out = new ArrayList<String>();
        for (StackTraceElement e : t.getStackTrace()) {
            if (out.size() == 10) break;
            out.add(e.toString());
        }
        return out;
    }

    /**
     * What {@code perform} throws when the act or the settle failed. An EDT error caught
     * meanwhile is the real cause, so it wins, worded as the success path already words it; the caught
     * exception rides along as a suppressed exception. With no EDT error, {@code caught} is the cause.
     */
    static RuntimeException failure(RuntimeException caught, List<Throwable> edtErrors) {
        if (edtErrors.isEmpty()) return caught;
        Throwable first = edtErrors.get(0);
        DriverException e = new DriverException(Reason.APP_ERROR,
                "uncaught exception on the event dispatch thread: " + first, stack(first));
        e.addSuppressed(caught);
        return e;
    }

    static DriverException bad(String message) {
        return new DriverException(Reason.BAD_INPUT, message);
    }

    static DriverException notActionable(String message) {
        return new DriverException(Reason.NOT_ACTIONABLE, message);
    }
}
