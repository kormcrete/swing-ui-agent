package io.github.kormcrete.swingui.agent;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.PendingDialog;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.junit.ClientApp;
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
 * Every ready plugin serves plugin_call and adds its vocabulary, whose steps run as {@code do} steps
 * ({@link #runStep}); the others serve only that.
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
        List<String> plugins = new ArrayList<String>();
        List<List<DomainStep>> vocabularies = new ArrayList<List<DomainStep>>();
        for (PluginSlot s : slots) {
            if (s.parts() == null) continue;
            plugins.add(s.name());
            vocabularies.add(s.vocabulary()); // one read per slot: a slot may turn ready meanwhile
        }
        return merge(plugins, vocabularies);
    }

    /** Names the steps of {@code vocabularies} (one immutable list per plugin, in plugin order). */
    static List<VocabularyStep> merge(List<String> plugins, List<List<DomainStep>> vocabularies) {
        Map<String, Integer> defined = new HashMap<String, Integer>();
        for (List<DomainStep> v : vocabularies) {
            for (DomainStep d : v) {
                Integer n = defined.get(d.id());
                defined.put(d.id(), n == null ? 1 : n + 1);
            }
        }
        List<VocabularyStep> all = new ArrayList<VocabularyStep>();
        for (int i = 0; i < plugins.size(); i++) {
            String plugin = plugins.get(i);
            for (DomainStep d : vocabularies.get(i)) {
                String name = defined.get(d.id()) > 1 ? plugin + ":" + d.id() : d.id();
                all.add(new VocabularyStep(name, plugin, d));
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

    /**
     * Runs the vocabulary step {@code id} ({@code <plugin>:<id>} always works; a bare id two plugins offer must be
     * qualified) with {@code params}, checked first (BAD_INPUT, no body run): a Java body on an in-JVM
     * {@link io.github.kormcrete.swingui.junit.Client} over {@link #verbs()}, whose app steps and hooks are these
     * plugins' ({@link #clientApp}); declarative steps one by one. On the calling thread, not the EDT.
     */
    @Override public VerbOutcome runStep(String id, Map<String, String> params) {
        VocabularyStep found = find(id);
        MapParams values = MapParams.of(found.step, params);
        if (verbs == null) {
            throw new DriverException(Reason.CAPABILITY_GAP, "no UI driver is installed for the do step '" + id + "'");
        }
        return StepRunner.run(found.step, values, verbs, clientApp(found.plugin));
    }

    /** The vocabulary step {@code id} names; BAD_INPUT when none or two, CAPABILITY_GAP when there is no vocabulary. */
    private VocabularyStep find(String id) {
        if (id == null || id.trim().isEmpty()) {
            throw new DriverException(Reason.BAD_INPUT, "a do step needs 'step': the id of a plugin's domain step");
        }
        List<VocabularyStep> all = vocabulary();
        if (all.isEmpty()) {
            if (slots.isEmpty()) {
                throw new DriverException(Reason.CAPABILITY_GAP, "the do step '" + id + "' " + StepHost.NO_VOCABULARY
                        + " (agent argument plugins=…)");
            }
            List<String> states = new ArrayList<String>();
            for (PluginSlot s : slots) states.add(s.name() + " is " + s.stateName() + " (" + s.detail() + ")");
            throw new DriverException(Reason.CAPABILITY_GAP, "the do step '" + id + "' needs a plugin with a vocabulary; "
                    + "none offers one: " + String.join(", ", states));
        }
        int colon = id.lastIndexOf(':');
        List<VocabularyStep> matching = new ArrayList<VocabularyStep>();
        for (VocabularyStep v : all) {
            boolean match = colon < 0 ? v.step.id().equals(id)
                    : v.plugin.equals(id.substring(0, colon)) && v.step.id().equals(id.substring(colon + 1));
            if (match) matching.add(v);
        }
        if (matching.size() == 1) return matching.get(0);
        List<String> names = new ArrayList<String>();
        if (matching.isEmpty()) {
            for (VocabularyStep v : all) names.add(v.name);
            throw new DriverException(Reason.BAD_INPUT, "no domain step '" + id + "'; the steps are " + String.join(", ", names));
        }
        for (VocabularyStep v : matching) names.add(v.plugin + ":" + v.step.id());
        throw new DriverException(Reason.BAD_INPUT, "'" + id + "' is offered by more than one plugin; name one: "
                + String.join(", ", names));
    }

    /**
     * The app side of a domain step's Client: app steps through these plugins (the app plugin's step host), hooks from
     * the step's own plugin first, then any ready one. A login by user name and password, or again, has no step form:
     * refused, as a recorded test could not hold it.
     */
    private ClientApp clientApp(final String plugin) {
        return new ClientApp() {
            @Override public void navigate(String target) { Plugins.this.navigate(target); }
            @Override public void closeActiveTab() { Plugins.this.closeActiveTab(); }
            @Override public void loginAs(String alias) { Plugins.this.loginAs(alias); }
            @Override public void loginAs(String user, String password) {
                throw new DriverException(Reason.CAPABILITY_GAP, "a domain step logs in by alias only (loginAs(alias)): "
                        + "a login by user name and password has no step form");
            }
            @Override public void relogin() {
                throw new DriverException(Reason.CAPABILITY_GAP, "a domain step logs in by alias only (loginAs(alias)): "
                        + "logging in again has no step form");
            }
            @Override public <T> T hooks(Class<T> type) {
                List<PluginSlot> order = new ArrayList<PluginSlot>();
                for (PluginSlot s : slots) if (s.name().equals(plugin)) order.add(s);
                for (PluginSlot s : slots) if (!s.name().equals(plugin)) order.add(s);
                for (PluginSlot s : order) {
                    PluginParts p = s.parts();
                    if (p != null && p.hooks != null && type.isInstance(p.hooks)) return type.cast(p.hooks);
                }
                throw new DriverException(Reason.CAPABILITY_GAP, "no ready plugin offers hooks of type " + type.getName());
            }
        };
    }

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
