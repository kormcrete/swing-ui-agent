package io.github.kormcrete.swingui.driver.model;

import io.github.kormcrete.swingui.driver.widgets.Cells;
import io.github.kormcrete.swingui.driver.widgets.TableWidget;
import io.github.kormcrete.swingui.driver.widgets.TreeWidget;
import io.github.kormcrete.swingui.driver.widgets.Widgets;
import io.github.kormcrete.swingui.driver.window.WindowRoot;
import io.github.kormcrete.swingui.driver.window.WindowRoots;
import io.github.kormcrete.swingui.driver.window.WindowSource;

import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTree;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The agent's view of the app: every showing root with refs. Containers appear only when
 * titled (tabs, titled borders) or a registered {@link SectionSource} claims them; budgets cap the
 * output and mark what they cut. EDT.
 */
public final class Snapshot {

    private final Visibility visibility;
    private final Names names;
    private final Refs refs;
    private final WindowSource windows;
    private final Sections sections;
    private final Parts parts;

    public Snapshot(Visibility visibility, Names names, Refs refs, WindowSource windows) {
        this(visibility, names, refs, windows, new Sections());
    }

    public Snapshot(Visibility visibility, Names names, Refs refs, WindowSource windows, Sections sections) {
        this(visibility, names, refs, windows, sections, new Parts());
    }

    public Snapshot(Visibility visibility, Names names, Refs refs, WindowSource windows, Sections sections,
                    Parts parts) {
        this.visibility = visibility;
        this.names = names;
        this.refs = refs;
        this.windows = windows;
        this.sections = sections;
        this.parts = parts;
    }

    /**
     * {@code window} filters roots by token; {@code under} limits the snapshot to one element's
     * subtree. Refs and names are reset only once there is at least one root to render, so a filter
     * that matches nothing (e.g. {@code window:"dialog"} while none is showing) keeps every ref a
     * caller holds.
     */
    public Node build(String window, Component under, int maxNodes, int maxRows) {
        return build(window, under, maxNodes, maxRows, Integer.MAX_VALUE);
    }

    /**
     * {@link #build(String, Component, int, int)} to at most {@code maxDepth} levels of nodes below the
     * app node: 1 is the windows (or the {@code under} element) alone, 2 adds their child nodes, and so
     * on. A node whose children were cut by the depth is marked {@code truncated}, as a budget cut is.
     * The depth is spent before the node budget, so a shallow snapshot shows breadth rather than the
     * first deep branch.
     */
    public Node build(String window, Component under, int maxNodes, int maxRows, int maxDepth) {
        Budget budget = new Budget(maxNodes, maxDepth);
        Node app = new Node("app", null);
        if (under != null) {
            refs.clear();
            names.reset();
            Container top = topOf(under);
            walk(under, top, app, budget, maxRows, sections.forWindow(windowOf(top)), 1);
            return app;
        }
        List<WindowRoot> roots = window == null
                ? windows.roots()
                : WindowRoots.select(WindowRef.parse(window), windows.roots(), windows.main());
        if (roots.isEmpty()) return app;
        refs.clear();
        names.reset();
        Map<Window, Sections.Lookup> lookups = new HashMap<Window, Sections.Lookup>();
        for (WindowRoot root : roots) {
            if (!budget.take()) {
                app.truncated = Boolean.TRUE;
                break;
            }
            Node node = new Node(root.role, root.title);
            node.ref = refs.add(root.content, null);
            if (root.modal) node.modal = Boolean.TRUE;
            node.blockedBy = root.blockedBy;
            if (root.focused) node.addState("focused");
            app.addChild(node);
            Container labelRoot = root.content instanceof Container ? (Container) root.content : null;
            walkChildren(root.content, labelRoot, node, budget, maxRows, lookupFor(windowOf(root.content), lookups), 2);
        }
        return app;
    }

    /** {@code null} for anything that is not itself a top-level window - a popup, or an unattached fixture. */
    private static Window windowOf(Component c) {
        return c instanceof Window ? (Window) c : null;
    }

    /** Resolves {@code window} at most once per {@link #build} call, however many roots share it. */
    private Sections.Lookup lookupFor(Window window, Map<Window, Sections.Lookup> cache) {
        Sections.Lookup lookup = cache.get(window);
        if (lookup == null) {
            lookup = sections.forWindow(window);
            cache.put(window, lookup);
        }
        return lookup;
    }

    /** One element for {@code read}: a tab, a table cell or row, or the element's subtree. Keeps existing refs. */
    public Node describe(Component c, Integer tab, RowSpec row, String column, int maxRows) {
        return describe(c, tab, row, column, null, maxRows);
    }

    /**
     * {@link #describe(Component, Integer, RowSpec, String, int)}, plus a tree row by its {@code item}
     * path: a node of role {@code item}, named by the row's path text ("A &gt; B"), with the row's own
     * states ({@code selected}, {@code expanded}, {@code leaf}). {@code item} on anything but a tree
     * reads the element as a whole.
     */
    public Node describe(Component c, Integer tab, RowSpec row, String column, List<String> item, int maxRows) {
        names.reset();
        if (tab != null && !(c instanceof JTabbedPane)) {
            Part part = parts.at(c, tab);
            if (part == null) return new Node("group", null);
            return partNode(c, part);
        }
        if (tab != null) {
            JTabbedPane tabs = (JTabbedPane) c;
            Node n = new Node("tab", Names.tabTitle(tabs, tab));
            n.ref = refs.add(tabs, tab);
            if (tabs.getSelectedIndex() == tab) n.addState("selected");
            if (!tabs.isEnabledAt(tab)) n.addState("disabled");
            n.tooltip = Names.clean(tabs.getToolTipTextAt(tab));
            return n;
        }
        if (c instanceof JTable && row != null) {
            JTable table = (JTable) c;
            int r = TableWidget.row(table, row);
            if (column != null) {
                int col = TableWidget.column(table, column);
                Cells.Text text = Cells.table(table, r, col);
                Node cell = new Node("cell", column);
                cell.value = text.text;
                cell.tooltip = Cells.tableTooltip(table, r, col);
                if (text.approx) cell.approx = Boolean.TRUE;
                return cell;
            }
            Node n = new Node("row", null);
            n.columns = TableWidget.headers(table);
            n.rows = Collections.singletonList(TableWidget.rowTexts(table, r));
            return n;
        }
        if (c instanceof JTree && item != null) {
            JTree tree = (JTree) c;
            int r = TreeWidget.row(tree, item);
            Node n = new Node("item", TreeWidget.pathText(tree, r));
            TreeWidget.itemState(tree, r, n);
            return n;
        }
        Node holder = new Node("group", null);
        Container top = topOf(c);
        walk(c, top, holder, new Budget(Integer.MAX_VALUE, Integer.MAX_VALUE), maxRows, sections.forWindow(windowOf(top)), 1);
        return holder.children != null && holder.children.size() == 1 ? holder.children.get(0) : holder;
    }

    /**
     * {@code lookup} is resolved once per {@link #build}/{@link #describe} call (never per component)
     * and carried down through the recursion.
     *
     * <p>A section claim adds to the node the walk builds, never replaces it. A claimed component that
     * derives a role keeps that role and name and is described by {@link Widgets#describe} as if
     * unclaimed (a claimed {@code JButton} still reads {@code role:"button"} and carries {@code disabled});
     * the section's {@code id} and {@code state} are merged on. Only a component that derives <b>no</b>
     * role, such as a plain, uncaptioned {@code JPanel}, becomes a {@code section} node named by the
     * section. Either way the children are walked as without the claim.
     */
    private void walk(Component c, Container labelRoot, Node parent, Budget budget, int maxRows, Sections.Lookup lookup,
                      int depth) {
        if (c instanceof JPopupMenu || Roles.skips(c) || !visibility.isVisible(c)) return;
        String role = c instanceof Window ? null : Roles.of(c);
        // Dropped before the claim is even looked up, because the resolver drops it before it looks
        // one up too (Resolver.collect): a label that already serves as an input's name is not a node
        // of its own, and the tree has to stay the contract for what resolves.
        if ("label".equals(role) && names.namesAnInput(c, labelRoot)) return;
        Section section = lookup.sectionFor(c);
        if (role != null) {
            if (budget.tooDeep(depth)) {
                parent.truncated = Boolean.TRUE;
                return;
            }
            if (!budget.take()) {
                parent.truncated = Boolean.TRUE;
                return;
            }
            Node node = new Node(role, names.nameOf(c, labelRoot));
            node.ref = refs.add(c, null);
            Widgets.describe(c, role, node, maxRows);
            merge(section, node);
            parent.addChild(node);
            if ("clickable".equals(role)) {
                for (Part part : parts.of(c)) {
                    if (budget.tooDeep(depth + 1) || !budget.take()) {
                        node.truncated = Boolean.TRUE;
                        break;
                    }
                    node.addChild(partNode(c, part));
                }
            }
            if (c instanceof JTabbedPane) {
                walkTabs((JTabbedPane) c, labelRoot, node, budget, maxRows, lookup, depth + 1);
            } else if (Roles.descends(role)) {
                walkChildren(c, labelRoot, node, budget, maxRows, lookup, depth + 1);
            }
            return;
        }
        if (section != null) {
            // No role of its own: the section is all the identity this area has. Not a JTabbedPane -
            // that derives the role "tabs" and was handled above - so its children are plain children.
            if (budget.tooDeep(depth)) {
                parent.truncated = Boolean.TRUE;
                return;
            }
            if (!budget.take()) {
                parent.truncated = Boolean.TRUE;
                return;
            }
            Node node = new Node("section", section.name);
            node.ref = refs.add(c, null);
            merge(section, node);
            parent.addChild(node);
            walkChildren(c, labelRoot, node, budget, maxRows, lookup, depth + 1);
            return;
        }
        Node target = parent;
        int childDepth = depth;
        String caption = Names.sectionTitle(c);
        if (caption != null) {
            if (budget.tooDeep(depth)) {
                parent.truncated = Boolean.TRUE;
                return;
            }
            if (!budget.take()) {
                parent.truncated = Boolean.TRUE;
                return;
            }
            target = new Node("section", caption);
            target.ref = refs.add(c, null);
            parent.addChild(target);
            childDepth = depth + 1;
        }
        walkChildren(c, labelRoot, target, budget, maxRows, lookup, childDepth);
    }

    /** A painted part as a node of its own, addressable by a ref that carries the part's index. */
    private Node partNode(Component c, Part part) {
        Node n = new Node(part.role, part.name);
        n.ref = refs.add(c, part.index);
        part.addStates(n);
        return n;
    }

    /**
     * Adds a section's id and states to the node, never replacing what the component derives:
     * otherwise a claimed, disabled {@code JButton} would lose {@code disabled} and
     * {@code expect state ["!disabled"]} would pass on it.
     */
    private static void merge(Section section, Node node) {
        if (section == null) return;
        node.id = section.id;
        if (section.state == null) return;
        for (String s : section.state) node.addState(s);
    }

    private void walkChildren(Component c, Container labelRoot, Node parent, Budget budget, int maxRows,
                              Sections.Lookup lookup, int depth) {
        if (!(c instanceof Container)) return;
        for (Component child : ((Container) c).getComponents()) {
            walk(child, labelRoot, parent, budget, maxRows, lookup, depth);
        }
    }

    /** {@code depth} is the depth of the tab nodes; a selected tab's content sits one below. */
    private void walkTabs(JTabbedPane tabs, Container labelRoot, Node node, Budget budget, int maxRows,
                          Sections.Lookup lookup, int depth) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (budget.tooDeep(depth)) {
                node.truncated = Boolean.TRUE;
                return;
            }
            if (!budget.take()) {
                node.truncated = Boolean.TRUE;
                return;
            }
            Node tab = new Node("tab", Names.tabTitle(tabs, i));
            tab.ref = refs.add(tabs, i);
            if (i == tabs.getSelectedIndex()) tab.addState("selected");
            if (!tabs.isEnabledAt(i)) tab.addState("disabled");
            node.addChild(tab);
            Component content = tabs.getComponentAt(i);
            if (i == tabs.getSelectedIndex() && content != null) {
                walk(content, labelRoot, tab, budget, maxRows, lookup, depth + 1);
            }
        }
    }

    static Container topOf(Component c) {
        Component top = c;
        while (top.getParent() != null) top = top.getParent();
        return top instanceof Container ? (Container) top : null;
    }

    private static final class Budget {
        private int left;
        private final int maxDepth;

        Budget(int left, int maxDepth) {
            this.left = left;
            this.maxDepth = maxDepth;
        }

        /** Whether a node at {@code depth} (1 = a window or the scoped element) is past the depth limit. */
        boolean tooDeep(int depth) {
            return depth > maxDepth;
        }

        boolean take() {
            if (left <= 0) return false;
            left--;
            return true;
        }
    }
}
