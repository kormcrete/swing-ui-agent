package io.github.kormcrete.swingui.spi;

import io.github.kormcrete.swingui.driver.Driver;
import io.github.kormcrete.swingui.driver.Edt;
import io.github.kormcrete.swingui.driver.model.Section;
import io.github.kormcrete.swingui.driver.model.Sections;
import io.github.kormcrete.swingui.driver.widgets.MenuWidget;

import java.awt.Component;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What {@link UiInventory}'s defaults read off the installed driver's main window. Empty without a driver. */
final class InventoryDefaults {

    private InventoryDefaults() {}

    static List<String> menuPaths(Driver driver) {
        if (driver == null) return Collections.emptyList();
        return Edt.call(() -> MenuWidget.leafPaths(driver.windows().main()));
    }

    static List<Map<String, Object>> viewSections(Driver driver) {
        if (driver == null) return Collections.emptyList();
        return Edt.call(() -> rows(lookup(driver).sections()));
    }

    static List<String> viewSectionProblems(Driver driver) {
        if (driver == null) return Collections.emptyList();
        return Edt.call(() -> new ArrayList<String>(lookup(driver).problems()));
    }

    /** The registered sections of the main window. EDT. */
    private static Sections.Lookup lookup(Driver driver) {
        Component main = driver.windows().main();
        return driver.sections().forWindow(main instanceof Window ? (Window) main : null);
    }

    /**
     * One row per section: {@code id}, {@code name}, {@code created} (false while the app has not built its content),
     * {@code state} (its flags, none when it has none) and {@code details} when it has any.
     */
    static List<Map<String, Object>> rows(List<Section> sections) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Section s : sections) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", s.id);
            row.put("name", s.name);
            row.put("created", s.content != null);
            row.put("state", s.state == null ? new ArrayList<String>() : new ArrayList<String>(s.state));
            if (s.details != null && !s.details.isEmpty()) row.put("details", new LinkedHashMap<String, String>(s.details));
            out.add(row);
        }
        return out;
    }
}
