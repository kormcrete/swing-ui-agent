package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.PendingDialog;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.spi.DomainStep;
import io.github.kormcrete.swingui.spi.ExportDefaults;
import io.github.kormcrete.swingui.spi.PluginParts;
import io.github.kormcrete.swingui.spi.StepHost;
import io.github.kormcrete.swingui.spi.UiInventory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every {@code plugins=} entry, in order. The first READY one whose parts carry a step host is the app plugin: it
 * serves app steps (navigate, close, loginAs), describe_ui, session reset and login, and the export defaults.
 * Every ready plugin serves plugin_call and adds its vocabulary; the others serve only that.
 */
public final class Plugins implements StepHost, UiInventory {

    public static final ExportDefaults GENERIC_EXPORT = new ExportDefaults(null, "recorded");

    private final Verbs verbs;
    private final List<PluginSlot> slots = new CopyOnWriteArrayList<PluginSlot>();

    public Plugins(Verbs verbs) {
        this.verbs = verbs;
    }

    public PluginSlot add(String name, Path location) {
        PluginSlot slot = new PluginSlot(name, location);
        slots.add(slot);
        return slot;
    }

    public List<PluginSlot> slots() {
        return Collections.unmodifiableList(new ArrayList<PluginSlot>(slots));
    }

    /** The app plugin's parts; null while none is ready. */
    public PluginParts app() {
        for (PluginSlot s : slots) {
            PluginParts p = s.parts();
            if (p != null && p.host != null) return p;
        }
        return null;
    }

    /**
     * The slot that stands for the app: the first READY one with a step host; else the first one not READY (the app
     * plugin is still loading or failed); else, all READY and none with a host, the first. Null with no slots.
     */
    public PluginSlot appSlot() {
        for (PluginSlot s : slots) {
            PluginParts p = s.parts();
            if (p != null && p.host != null) return s;
        }
        for (PluginSlot s : slots) {
            if (s.state() != PluginSlot.State.READY) return s;
        }
        return slots.isEmpty() ? null : slots.get(0);
    }

    /** The slot called {@code name}; with null, the only slot. */
    public PluginSlot slot(String name) {
        List<String> names = new ArrayList<String>();
        for (PluginSlot s : slots) names.add(s.name());
        if (name == null) {
            if (slots.size() == 1) return slots.get(0);
            if (slots.isEmpty()) throw new DriverException(Reason.CAPABILITY_GAP, "no plugin configured (agent argument plugins=…)");
            throw new DriverException(Reason.BAD_INPUT, "name the plugin: " + String.join(", ", names));
        }
        for (PluginSlot s : slots) if (s.name().equals(name)) return s;
        throw new DriverException(Reason.BAD_INPUT, "no plugin named '" + name + "' (known: " + String.join(", ", names) + ")");
    }

    /** A vocabulary step, with the name it is reachable by and the plugin that offers it. */
    public static final class VocabularyStep {
        /** The step id; {@code <plugin>:<id>} when two plugins define the id. */
        public final String name;
        public final String plugin;
        public final DomainStep step;

        VocabularyStep(String name, String plugin, DomainStep step) {
            this.name = name;
            this.plugin = plugin;
            this.step = step;
        }
    }

    /**
     * Every ready plugin's vocabulary, in plugin order, then step order. An id that two plugins define is reachable
     * only as {@code <plugin>:<id>}, in both.
     */
    public List<VocabularyStep> vocabulary() {
        List<PluginSlot> ready = new ArrayList<PluginSlot>();
        Map<String, Integer> defined = new HashMap<String, Integer>();
        for (PluginSlot s : slots) {
            if (s.parts() == null) continue;
            ready.add(s);
            for (DomainStep d : s.vocabulary()) {
                Integer n = defined.get(d.id());
                defined.put(d.id(), n == null ? 1 : n + 1);
            }
        }
        List<VocabularyStep> all = new ArrayList<VocabularyStep>();
        for (PluginSlot s : ready) {
            for (DomainStep d : s.vocabulary()) {
                String name = defined.get(d.id()) > 1 ? s.name() + ":" + d.id() : d.id();
                all.add(new VocabularyStep(name, s.name(), d));
            }
        }
        return all;
    }

    /** Every ready plugin's vocabulary problems, in plugin order. */
    public List<String> vocabularyProblems() {
        List<String> all = new ArrayList<String>();
        for (PluginSlot s : slots) all.addAll(s.vocabularyProblems());
        return all;
    }

    public ExportDefaults exportDefaults() {
        PluginParts p = app();
        return p != null && p.export != null ? p.export : GENERIC_EXPORT;
    }

    /** Why {@code what} cannot run: no plugin, or each plugin's state. */
    public String unavailable(String what) {
        if (slots.isEmpty()) return what + " needs the app plugin; no plugin configured (agent argument plugins=…)";
        List<String> states = new ArrayList<String>();
        for (PluginSlot s : slots) states.add(s.name() + " is " + s.stateName() + " (" + s.detail() + ")");
        return what + " needs the app plugin; " + String.join(", ", states);
    }

    private PluginParts appOrThrow(String what) {
        PluginParts p = app();
        if (p == null) throw new DriverException(Reason.CAPABILITY_GAP, unavailable(what));
        return p;
    }

    @Override public Verbs verbs() { return verbs; }
    @Override public void navigate(String target) { appOrThrow("navigate").host.navigate(target); }
    @Override public void closeActiveTab() { appOrThrow("close").host.closeActiveTab(); }
    @Override public void loginAs(String alias) { appOrThrow("loginAs").host.loginAs(alias); }

    @Override public PendingDialog openDialog() {
        PluginParts p = app();
        return p == null ? null : p.host.openDialog();
    }

    private UiInventory inventory() {
        PluginParts p = appOrThrow("describe_ui");
        if (p.inventory == null) throw new DriverException(Reason.CAPABILITY_GAP, "the app plugin offers no describe_ui inventory");
        return p.inventory;
    }

    @Override public String activeTabTitle() { return inventory().activeTabTitle(); }
    @Override public List<String> navTargets() { return inventory().navTargets(); }
    @Override public List<String> entryPointIds() { return inventory().entryPointIds(); }
    @Override public List<Map<String, Object>> toolbarActions() { return inventory().toolbarActions(); }
    @Override public List<String> menuPaths() { return inventory().menuPaths(); }
    @Override public Map<String, List<String>> navEntryPoints() { return inventory().navEntryPoints(); }
    @Override public Map<String, List<String>> viewTabs() { return inventory().viewTabs(); }
    @Override public List<Map<String, Object>> viewSections() { return inventory().viewSections(); }
    @Override public List<String> viewSectionProblems() { return inventory().viewSectionProblems(); }
}
