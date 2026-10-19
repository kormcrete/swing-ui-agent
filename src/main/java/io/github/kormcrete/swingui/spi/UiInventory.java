package io.github.kormcrete.swingui.spi;

import io.github.kormcrete.swingui.driver.Driver;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The app facts {@code describe_ui} reports: what to navigate to and what the open view holds. The generic parts have
 * defaults read off the installed driver's main window ({@code Driver.setMainWindow}): the menu bar and the registered
 * sections. Override them to report more, or less.
 */
public interface UiInventory {
    String activeTabTitle();
    /** The friendly names {@code navigate} accepts besides entry-point ids; none by default. */
    default List<String> navTargets() { return Collections.emptyList(); }
    /** Every entry-point id the running client declares, sorted; each one is a navigate target. */
    List<String> entryPointIds();
    /** The toolbar's actions: id, label, enabled, and a combo's or menu's items. */
    List<Map<String, Object>> toolbarActions();
    /**
     * The menu bar's leaf paths ("File > Export > Orders"), a disabled leaf marked "[disabled]". By default the main
     * window's menu bar, as {@link io.github.kormcrete.swingui.driver.widgets.MenuWidget#leafPaths} walks it.
     */
    default List<String> menuPaths() { return InventoryDefaults.menuPaths(Driver.current()); }
    /** The nav bar: "roots" in bar order and "children" of the selected root. */
    Map<String, List<String>> navEntryPoints();
    /** The open view's tab titles, keyed by tab pane; {@code describe_ui} reports them as {@code viewTabs}. */
    Map<String, List<String>> viewTabs();
    /**
     * The open view's registered sections, one row each, as the snapshot's sections; {@code describe_ui} reports them
     * as {@code sections}. By default every section registered for the main window ({@code Driver.registerSections}),
     * also one whose content was never built: {@code id}, {@code name}, {@code created}, {@code state} (its flags) and
     * {@code details} when it has any.
     */
    default List<Map<String, Object>> viewSections() { return InventoryDefaults.viewSections(Driver.current()); }
    /** What went wrong collecting {@link #viewSections()}; reported as {@code sectionProblems}. By default the registry's. */
    default List<String> viewSectionProblems() { return InventoryDefaults.viewSectionProblems(Driver.current()); }
}
