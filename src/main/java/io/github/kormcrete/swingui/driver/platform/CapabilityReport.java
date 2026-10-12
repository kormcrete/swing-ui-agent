package io.github.kormcrete.swingui.driver.platform;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** What the self-check found. Its lines head every flow result. */
public final class CapabilityReport {

    private final String seam;
    private final Map<Capability, String> missing;
    private final Map<Capability, String> notes;

    public CapabilityReport(String seam, Map<Capability, String> missing) {
        this(seam, missing, new EnumMap<Capability, String>(Capability.class));
    }

    /** {@code notes}: available capabilities this platform needs nothing for, with why. */
    public CapabilityReport(String seam, Map<Capability, String> missing, Map<Capability, String> notes) {
        this.seam = seam;
        this.missing = new EnumMap<Capability, String>(Capability.class);
        this.missing.putAll(missing);
        this.notes = new EnumMap<Capability, String>(Capability.class);
        this.notes.putAll(notes);
    }

    public boolean has(Capability capability) {
        return !missing.containsKey(capability);
    }

    public List<Capability> missingRequired() {
        List<Capability> out = new ArrayList<Capability>();
        for (Capability c : missing.keySet()) {
            if (c.required) out.add(c);
        }
        return out;
    }

    public List<String> lines() {
        List<String> out = new ArrayList<String>();
        out.add("seam: " + seam);
        for (Capability c : Capability.values()) {
            if (has(c)) {
                out.add(c.what + ": " + (notes.containsKey(c) ? notes.get(c) : "ok"));
            } else {
                out.add(c.what + ": " + (c.required ? "MISSING" : "unavailable") + " (" + missing.get(c) + ")");
            }
        }
        return out;
    }
}
