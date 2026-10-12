package io.github.kormcrete.swingui.driver.platform;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Runs at install: reports every capability and refuses to start without a required one. */
public final class SelfCheck {

    private SelfCheck() {}

    public static CapabilityReport run(Seam seam) {
        Map<Capability, String> missing = new EnumMap<Capability, String>(Capability.class);
        Map<Capability, String> notes = new EnumMap<Capability, String>(Capability.class);
        for (Capability c : Capability.values()) {
            if (!seam.available().contains(c)) {
                String because = seam.missingBecause(c);
                missing.put(c, because == null ? "not found" : because);
            } else if (seam.note(c) != null) {
                notes.put(c, seam.note(c));
            }
        }
        CapabilityReport report = new CapabilityReport(seam.name(), missing, notes);
        List<Capability> required = report.missingRequired();
        if (!required.isEmpty()) {
            StringBuilder why = new StringBuilder();
            for (Capability c : required) {
                if (why.length() > 0) why.append("; ");
                why.append(c.what).append(" (").append(missing.get(c)).append(")");
            }
            throw new DriverException(Reason.CAPABILITY_GAP, "the UI driver cannot start on " + seam.name()
                    + ": missing " + why + ". Fix: " + seam.fix(), report.lines());
        }
        return report;
    }
}
