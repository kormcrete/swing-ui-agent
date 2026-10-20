package io.github.kormcrete.swingui.driver.model;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.widgets.TableWidget;
import io.github.kormcrete.swingui.driver.window.WindowRoot;
import io.github.kormcrete.swingui.driver.window.WindowRoots;
import io.github.kormcrete.swingui.driver.window.WindowSource;

import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumnModel;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

/** Strict resolution: exactly one visible match, or NOT_FOUND / AMBIGUOUS with candidates. EDT. */
public final class Resolver {

    public static final int MAX_CANDIDATES = 10;

    private final Visibility visibility;
    private final Names names;
    private final Refs refs;
    private final WindowSource windows;
    private final Sections sections;
    private final Parts parts;

    public Resolver(Visibility visibility, Names names, Refs refs, WindowSource windows) {
        this(visibility, names, refs, windows, new Sections());
    }

    public Resolver(Visibility visibility, Names names, Refs refs, WindowSource windows, Sections sections) {
        this(visibility, names, refs, windows, sections, new Parts());
    }

    public Resolver(Visibility visibility, Names names, Refs refs, WindowSource windows, Sections sections,
                    Parts parts) {
        this.visibility = visibility;
        this.names = names;
        this.refs = refs;
        this.windows = windows;
        this.sections = sections;
        this.parts = parts;
    }

    public Resolved resolve(Locator locator) {
        locator.validate();
        if (locator.ref != null) return fromRef(locator);
        List<Resolved> found = matches(locator);
        if (locator.nth != null) {
            if (locator.nth < found.size()) return found.get(locator.nth);
            throw new DriverException(Reason.NOT_FOUND, "no match number " + locator.nth + " for " + locator
                    + " (" + found.size() + " found)", describe(found));
        }
        if (found.size() == 1) return found.get(0);
        if (found.isEmpty()) {
            throw new DriverException(Reason.NOT_FOUND, "nothing matches " + locator + windowHint(locator),
                    nearMisses(locator));
        }
        throw new DriverException(Reason.AMBIGUOUS, found.size() + " elements match " + locator
                + "; add a scope, a window or nth", describe(found));
    }

    /** Windows are not nodes: a locator with role window or dialog finds none; say how they are addressed. */
    private static String windowHint(Locator locator) {
        if (!"window".equals(locator.role) && !"dialog".equals(locator.role)) return "";
        return " (windows are not elements: address them by the 'window' argument - main, popup, dialog, "
                + "dialog:<title> or window:<title>)";
    }

    /** Every visible match in tree order. */
    public List<Resolved> matches(Locator locator) {
        names.reset();
        List<Resolved> out = new ArrayList<Resolved>();
        List<WindowRoot> roots = locator.window == null
                ? windows.roots()
                : WindowRoots.select(WindowRef.parse(locator.window), windows.roots(), windows.main());
        for (WindowRoot root : roots) {
            Container labelRoot = root.content instanceof Container ? (Container) root.content : null;
            Sections.Lookup lookup = sections.forWindow(windowOf(root));
            children(root.content, root, labelRoot, new ArrayList<String>(), locator, out, lookup);
        }
        return preferRowHolders(out, locator);
    }

    /**
     * The section a registered source currently claims for {@code r.component}, asked fresh - never
     * from a stale snapshot. Null when nothing claims it, including every ordinary,
     * non-section match.
     */
    public Section liveSection(Resolved r) {
        WindowRoot root = rootOf(r.component);
        return sections.forWindow(root == null ? null : windowOf(root)).sectionFor(r.component);
    }

    private static Window windowOf(WindowRoot root) {
        return root.content instanceof Window ? (Window) root.content : null;
    }

    private void collect(Component c, WindowRoot root, Container labelRoot, List<String> trail,
                         Locator l, List<Resolved> out, Sections.Lookup lookup) {
        if (c instanceof JPopupMenu || Roles.skips(c) || !visibility.isVisible(c)) return;
        String role = Roles.of(c);
        // The same rule the snapshot walks by: a label that names an input is not a node of its own,
        // so it must not be addressable either - the tree is the contract.
        if ("label".equals(role) && names.namesAnInput(c, labelRoot)) return;
        // A source claiming c only widens the id clause in accepts; c keeps the role and name it derives,
        // so a claimed button stays "button" and a claimed titled panel a "section" by its caption.
        Section claimed = lookup.sectionFor(c);
        String sectionId = claimed == null ? null : claimed.id;
        String caption = Names.sectionTitle(c);
        if (role != null) {
            String name = names.nameOf(c, labelRoot);
            if (accepts(c, role, name, trail, l, sectionId)) out.add(found(c, null, role, name, root, trail, l));
            // A titled border makes a section whatever else the component is: a titled box that also
            // carries a mouse listener derives "clickable", and stays clickable by that role and name, but it
            // is a section target too and scopes what lies inside it, as Trail.of records it. The two role
            // gates never pass for one locator, so this adds no second match of the same component.
            if (caption != null && accepts(c, "section", caption, trail, l, sectionId)) {
                out.add(found(c, null, "section", caption, root, trail, l));
            }
            if ("clickable".equals(role)) {
                for (Part part : parts.of(c)) {
                    if (acceptsPart(c, part, trail, l)) {
                        out.add(Resolved.ofPart(c, part, WindowRoots.token(root, windows.main()),
                                new ArrayList<String>(trail), l));
                    }
                }
            }
            boolean descends = c instanceof JTabbedPane || Roles.descends(role);
            if (descends && caption != null) trail.add(ScopeStep.section(caption));
            // What a text field holds (its clear X) lies inside it: a text:<name> scope step reaches it. The step
            // is only walked through; found() leaves it out of what a match records.
            boolean field = "text".equals(role) && name != null;
            if (field) trail.add(ScopeStep.text(name));
            if (c instanceof JTabbedPane) {
                tabs((JTabbedPane) c, root, labelRoot, trail, l, out, lookup);
            } else if (Roles.descends(role)) {
                children(c, root, labelRoot, trail, l, out, lookup);
            }
            if (field) trail.remove(trail.size() - 1);
            if (descends && caption != null) trail.remove(trail.size() - 1);
            return;
        }
        if (caption == null) {
            // A claimed component with neither a role nor a caption (e.g. a plain, uncaptioned JPanel) is
            // reachable only through its claim. `accepts` gets its true role, null, not "section": the role
            // gate (`l.role == null ? "section".equals(role) : ...`) would reject "section" for a role-less
            // locator. The match still reports role "section" and the section's name, as the snapshot does.
            //
            // So {role:"section"} alone does not match an uncaptioned contributed section: as a role,
            // "section" means a titled border; such a section is reached by id only.
            if (claimed != null && accepts(c, null, claimed.name, trail, l, sectionId)) {
                out.add(found(c, null, "section", claimed.name, root, trail, l));
            }
            children(c, root, labelRoot, trail, l, out, lookup);
            return;
        }
        if (accepts(c, "section", caption, trail, l, sectionId)) {
            out.add(found(c, null, "section", caption, root, trail, l));
        }
        trail.add(ScopeStep.section(caption));
        children(c, root, labelRoot, trail, l, out, lookup);
        trail.remove(trail.size() - 1);
    }

    private void children(Component c, WindowRoot root, Container labelRoot, List<String> trail,
                          Locator l, List<Resolved> out, Sections.Lookup lookup) {
        if (!(c instanceof Container)) return;
        for (Component child : ((Container) c).getComponents()) {
            collect(child, root, labelRoot, trail, l, out, lookup);
        }
    }

    private void tabs(JTabbedPane tabs, WindowRoot root, Container labelRoot, List<String> trail,
                      Locator l, List<Resolved> out, Sections.Lookup lookup) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            String title = Names.tabTitle(tabs, i);
            if (accepts(tabs, "tab", title, trail, l, null)) out.add(found(tabs, i, "tab", title, root, trail, l));
        }
        int selected = tabs.getSelectedIndex();
        Component content = selected < 0 ? null : tabs.getComponentAt(selected);
        if (content == null) return;
        String title = Names.tabTitle(tabs, selected);
        if (title == null) {
            // An untitled selected tab contributes no scope step, as in Trail.of: ScopeStep.tab(null)
            // would throw and abort resolution for every element under it.
            collect(content, root, labelRoot, trail, l, out, lookup);
            return;
        }
        trail.add(ScopeStep.tab(title));
        collect(content, root, labelRoot, trail, l, out, lookup);
        trail.remove(trail.size() - 1);
    }

    /**
     * {@code sectionId} is the id a registered source contributed for {@code c}, or null for an
     * ordinary match - an id locator accepts either {@code c.getName()} or that section id, with no
     * precedence between them.
     */
    private static boolean accepts(Component c, String role, String name, List<String> trail, Locator l,
                                   String sectionId) {
        if (l.role == null ? "section".equals(role) : !l.role.equals(role)) return false;
        if (l.id != null) {
            if ("tab".equals(role)) return false;
            boolean matchesName = l.id.equals(c.getName()) || isClearX(l.id, c);
            boolean matchesSection = sectionId != null && l.id.equals(sectionId);
            if (!matchesName && !matchesSection) return false;
        }
        if (l.name != null) {
            // A name locator accepts the name nameOf derives (what the match reports) or the component's
            // own visible text, so a button whose setName gives it the name "form.addItems" also resolves
            // by "Add items". The label-proximity fallback in nameOf is not widened.
            String ownText = Names.clean(Names.ownText(c));
            boolean matchesName = name != null
                    && (l.containsMatch() ? name.contains(l.name) : name.equals(l.name));
            boolean matchesOwnText = ownText != null
                    && (l.containsMatch() ? ownText.contains(l.name) : ownText.equals(l.name));
            if (!matchesName && !matchesOwnText) return false;
        }
        if (!hasColumn(c, l) || !hasRowKeys(c, l)) return false;
        if (l.scope == null) return true;
        // An id step is not part of the trail (Trail never derives one): it is checked against c's
        // own ancestors instead, and the remaining tab/section steps keep their subsequence rule.
        List<String> steps = new ArrayList<String>();
        for (String step : l.scope) {
            if (step.startsWith("id:")) {
                if (!insideNamed(c, step.substring(3))) return false;
            } else {
                steps.add(step);
            }
        }
        return isSubsequence(steps, trail);
    }

    /**
     * A painted {@link Part} of {@code c}: matched by its role and its own word, never by an id (a part has no name of
     * its own), and inside an {@code id:} scope when {@code c} itself or one of its ancestors carries that name - the
     * part lies inside its component, so {@code byId(switch).toggle(word)} reaches it.
     */
    private static boolean acceptsPart(Component c, Part part, List<String> trail, Locator l) {
        if (l.role == null || !l.role.equals(part.role)) return false;
        if (l.id != null || l.column != null) return false;
        if (l.name != null) {
            if (part.name == null) return false;
            if (!(l.containsMatch() ? part.name.contains(l.name) : part.name.equals(l.name))) return false;
        }
        if (l.scope == null) return true;
        List<String> steps = new ArrayList<String>();
        for (String step : l.scope) {
            if (step.startsWith("id:")) {
                String id = step.substring(3);
                if (!id.equals(c.getName()) && !insideNamed(c, id)) return false;
            } else {
                steps.add(step);
            }
        }
        return isSubsequence(steps, trail);
    }

    /**
     * Strictly inside a component whose getName() is {@code id}: an ancestor, never c itself and
     * never a sibling. It matches the component name only - a section id a registered source
     * contributes is not a name, so it cannot scope.
     */
    private static boolean insideNamed(Component c, String id) {
        for (Component p = c.getParent(); p != null; p = p.getParent()) {
            if (id.equals(p.getName())) return true;
        }
        return false;
    }

    /**
     * A locator that names a {@code column} only matches a table that has that column, so two unnamed
     * tables in one tab (a data grid and its row header) are told apart without a fragile {@code nth}.
     *
     * <p>This can only narrow a match, never widen one: a locator with no {@code column}, or a
     * candidate that is not a table, is untouched.</p>
     */
    private static boolean hasColumn(Component c, Locator l) {
        if (l.column == null || !(c instanceof JTable)) return true;
        JTableHeader header = ((JTable) c).getTableHeader();
        TableColumnModel columns = header != null ? header.getColumnModel() : ((JTable) c).getColumnModel();
        for (int i = 0; i < columns.getColumnCount(); i++) {
            Object value = columns.getColumn(i).getHeaderValue();
            if (value != null && l.column.equals(String.valueOf(value))) return true;
        }
        // "<header>@<identifier>": one of several columns that share a header (TableWidget.column).
        return TableWidget.identifiesColumn((JTable) c, l.column);
    }

    /**
     * A locator with a keyed {@code row} only matches a table that has every key column (its own or its row-header
     * table's), so the product table is told from the cart by the SKU column. An {@code index} row needs no column.
     * Like {@link #hasColumn}, this only narrows.
     */
    private static boolean hasRowKeys(Component c, Locator l) {
        if (l.row == null || !(c instanceof JTable)) return true;
        return TableWidget.hasKeyColumns((JTable) c, l.rowSpec());
    }

    /**
     * Of several tables that have the row's key columns, keep those that hold a matching row - when that leaves at
     * least one, so a tie-break never turns a match into NOT_FOUND.
     */
    private static List<Resolved> preferRowHolders(List<Resolved> found, Locator l) {
        if (l.row == null || found.size() < 2) return found;
        RowSpec spec = l.rowSpec();
        if (spec.index != null) return found;
        List<Resolved> kept = new ArrayList<Resolved>();
        boolean anyHolder = false;
        for (Resolved r : found) {
            if (!(r.component instanceof JTable)) {
                kept.add(r);
            } else if (TableWidget.holdsRow((JTable) r.component, spec)) {
                kept.add(r);
                anyHolder = true;
            }
        }
        return anyHolder ? kept : found;
    }

    private static boolean isSubsequence(List<String> wanted, List<String> trail) {
        int i = 0;
        for (String step : trail) {
            if (i < wanted.size() && scopeStepMatches(wanted.get(i), step)) i++;
        }
        return i == wanted.size();
    }

    /**
     * A wanted {@code tab:} or {@code section:} step whose title ends in {@code *} matches every title that starts with
     * the rest ({@code tab:New order*} reaches {@code tab:New order · Ada Shop Ltd}); any other step only itself. A
     * star elsewhere in the title, or on an {@code id:} step, is text. A title that really ends in {@code *} (a tab
     * {@code ORD-1640*} marked as changed) still matches itself, but is now a prefix too: it also matches
     * {@code ORD-1640} and {@code ORD-16401}.
     */
    static boolean scopeStepMatches(String wanted, String step) {
        if (wanted.equals(step)) return true;
        boolean glob = wanted.endsWith("*") && (wanted.startsWith("tab:") || wanted.startsWith("section:"));
        return glob && step.startsWith(wanted.substring(0, wanted.length() - 1));
    }

    private Resolved found(Component c, Integer tab, String role, String name, WindowRoot root,
                           List<String> trail, Locator l) {
        List<String> recorded = new ArrayList<String>();
        for (String step : trail) {
            if (!ScopeStep.isText(step)) recorded.add(step);
        }
        return new Resolved(c, tab, role, name, WindowRoots.token(root, windows.main()), recorded, l);
    }

    /** The id of a text field's clear X: an app's own X named so, and FlatLaf's built-in one. */
    static final String CLEAR_ID = "clear";
    private static final String FLATLAF_CLEAR_BUTTON = "TextField.clearButton";

    private static boolean isClearX(String id, Component c) {
        return CLEAR_ID.equals(id) && FLATLAF_CLEAR_BUTTON.equals(c.getName());
    }

    /**
     * The name a person reads for {@code r}: its name, unless that is only the component's code name ({@code setName},
     * which a locator goes by and the screen never shows) - then what names it on screen, a label or its own text
     * (null: nothing). A tab, a part or a window keeps its title. EDT.
     */
    public String seenName(Resolved r) {
        Component c = r.component;
        if (r.tab != null || r.name == null || !r.name.equals(Names.clean(c.getName()))) return r.name;
        names.reset();
        WindowRoot root = rootOf(c);
        if (root != null && root.content == c) return r.name;
        return names.seenName(c, root != null && root.content instanceof Container ? (Container) root.content : Snapshot.topOf(c));
    }

    private Resolved fromRef(Locator l) {
        Refs.Entry entry = refs.get(l.ref);
        Component c = entry.component();
        if (!visibility.isVisible(c)) {
            throw new DriverException(Reason.NOT_FOUND, "ref " + l.ref + " is no longer visible - take a new snapshot");
        }
        names.reset();
        WindowRoot root = rootOf(c);
        Container labelRoot = root != null && root.content instanceof Container ? (Container) root.content : Snapshot.topOf(c);
        String role;
        String name;
        if (entry.tab != null && !(c instanceof JTabbedPane)) {
            Part part = parts.at(c, entry.tab);
            if (part == null) {
                throw new DriverException(Reason.NOT_FOUND, "ref " + l.ref + " names a part that is gone - take a new snapshot");
            }
            WindowRoot partRoot = rootOf(c);
            return Resolved.ofPart(c, part, partRoot == null ? null : WindowRoots.token(partRoot, windows.main()),
                    Trail.of(c), l);
        }
        if (entry.tab != null) {
            role = "tab";
            name = Names.tabTitle((JTabbedPane) c, entry.tab);
        } else if (root != null && root.content == c) {
            role = root.role;
            name = root.title;
        } else {
            role = Roles.of(c);
            if (role == null) {
                role = "section";
                name = Names.sectionTitle(c);
            } else {
                name = names.nameOf(c, labelRoot);
            }
        }
        String window = root == null ? null : WindowRoots.token(root, windows.main());
        return new Resolved(c, entry.tab, role, name, window, Trail.of(c), l);
    }

    /**
     * The innermost root holding {@code c}: the first root met walking up from {@code c} to its own window. The walk
     * stops at that window, since AWT makes a dialog's owner its parent: the owner frame must not claim the dialog's
     * components (as {@code isAncestorOf} would), whatever order the roots are listed in.
     */
    private WindowRoot rootOf(Component c) {
        List<WindowRoot> roots = windows.roots();
        for (Component k = c; k != null; k = k instanceof Window ? null : k.getParent()) {
            for (WindowRoot r : roots) {
                if (r.content == k) return r;
            }
        }
        return null;
    }

    private List<String> nearMisses(Locator l) {
        Locator relaxed = new Locator();
        relaxed.role = l.role;
        relaxed.window = l.window;
        return describe(matches(relaxed));
    }

    private static List<String> describe(List<Resolved> found) {
        List<String> out = new ArrayList<String>();
        for (Resolved r : found) {
            if (out.size() == MAX_CANDIDATES) break;
            out.add(r.candidate());
        }
        return out;
    }
}
