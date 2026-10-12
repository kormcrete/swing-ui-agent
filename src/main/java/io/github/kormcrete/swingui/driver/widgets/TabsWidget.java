package io.github.kormcrete.swingui.driver.widgets;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.model.Names;

import javax.swing.JTabbedPane;
import java.util.ArrayList;
import java.util.List;

/** Tab strips: lookup by title. EDT. */
public final class TabsWidget {

    private TabsWidget() {}

    public static int index(JTabbedPane tabs, String title) {
        List<String> titles = new ArrayList<String>();
        int hit = -1;
        for (int i = 0; i < tabs.getTabCount(); i++) {
            String t = Names.tabTitle(tabs, i);
            titles.add(t);
            if (title.equals(t)) {
                if (hit >= 0) throw new DriverException(Reason.AMBIGUOUS, "two tabs are titled '" + title + "'");
                hit = i;
            }
        }
        if (hit < 0) throw new DriverException(Reason.NOT_FOUND, "no tab '" + title + "'; tabs are " + titles);
        return hit;
    }
}
