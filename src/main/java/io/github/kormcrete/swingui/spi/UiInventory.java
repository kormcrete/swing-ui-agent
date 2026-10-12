package io.github.kormcrete.swingui.spi;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** The app facts {@code describe_ui} reports: what to navigate to and what the open view holds. */
public interface UiInventory {
    String activeTabTitle();
    /** The friendly names {@code navigate} accepts besides entry-point ids; none by default. */
    default List<String> navTargets() { return Collections.emptyList(); }
    /** Every entry-point id the running client declares, sorted; each one is a navigate target. */
    List<String> entryPointIds();
    /** The toolbar's actions: id, label, enabled, and a combo's or menu's items. */
    List<Map<String, Object>> toolbarActions();
    /** The menu bar's leaf paths ("File > Export > Orders"), a disabled leaf marked "[disabled]". */
    List<String> menuPaths();
    /** The nav bar: "roots" in bar order and "children" of the selected root. */
    Map<String, List<String>> navEntryPoints();
    /** The open view's tab titles, keyed by tab pane; {@code describe_ui} reports them as {@code viewTabs}. */
    Map<String, List<String>> viewTabs();
    /**
     * The open view's registered sections, one row each (id, name, state), as the snapshot's sections;
     * {@code describe_ui} reports them as {@code sections}.
     */
    List<Map<String, Object>> viewSections();
    /** What went wrong collecting {@link #viewSections()}; reported as {@code sectionProblems}. */
    List<String> viewSectionProblems();
}
