package io.github.kormcrete.swingui.driver;

import io.github.kormcrete.swingui.driver.dnd.DropInjector;
import io.github.kormcrete.swingui.driver.focus.Activation;
import io.github.kormcrete.swingui.driver.focus.FocusEmulator;
import io.github.kormcrete.swingui.driver.input.Poster;
import io.github.kormcrete.swingui.driver.model.Names;
import io.github.kormcrete.swingui.driver.model.Recorder;
import io.github.kormcrete.swingui.driver.model.Refs;
import io.github.kormcrete.swingui.driver.model.Resolver;
import io.github.kormcrete.swingui.driver.model.PartSource;
import io.github.kormcrete.swingui.driver.model.Parts;
import io.github.kormcrete.swingui.driver.model.SectionSource;
import io.github.kormcrete.swingui.driver.model.Sections;
import io.github.kormcrete.swingui.driver.model.Snapshot;
import io.github.kormcrete.swingui.driver.model.Visibility;
import io.github.kormcrete.swingui.driver.platform.CapabilityReport;
import io.github.kormcrete.swingui.driver.platform.Seam;
import io.github.kormcrete.swingui.driver.platform.Seams;
import io.github.kormcrete.swingui.driver.platform.SelfCheck;
import io.github.kormcrete.swingui.driver.platform.Watch;
import io.github.kormcrete.swingui.driver.settle.BusySignal;
import io.github.kormcrete.swingui.driver.settle.EdtErrors;
import io.github.kormcrete.swingui.driver.settle.ErrorDialogClassifier;
import io.github.kormcrete.swingui.driver.settle.GlassPaneBusy;
import io.github.kormcrete.swingui.driver.settle.Settle;
import io.github.kormcrete.swingui.driver.verbs.LiveVerbs;
import io.github.kormcrete.swingui.driver.window.AppWindows;

import java.awt.Component;
import java.awt.Window;
import java.util.function.Supplier;

/**
 * The facade the agent, plugins and {@link LiveVerbs} use. One per process: {@link #install} selects
 * the seam, runs the self-check (refusing to start without a required capability) and starts the watch.
 */
public final class Driver {

    private static volatile Driver current;

    private final DriverSettings settings;
    private final Seam seam;
    private final CapabilityReport capabilities;
    private final FocusEmulator focus;
    private final Activation activation;
    private final Poster poster;
    private final Settle<Window> settle;
    private final DropInjector drops;
    private final Watch watch;
    private final EdtErrors edtErrors;
    private final AppWindows windows = new AppWindows();
    private final Refs refs = new Refs(Component::isDisplayable);
    private final Names names = new Names(Visibility.SHOWING);
    // Declared before `snapshot` and `resolver`: field initializers run in declaration order, and those
    // constructors must not receive a still-null Sections or Parts.
    private final Sections sections = new Sections();
    private final Parts parts = new Parts();
    private final Snapshot snapshot = new Snapshot(Visibility.SHOWING, names, refs, windows, sections, parts);
    private final Resolver resolver = new Resolver(Visibility.SHOWING, names, refs, windows, sections, parts);
    private final Recorder recorder = new Recorder(resolver, windows);
    private final LiveVerbs verbs = new LiveVerbs(this);
    private volatile ErrorDialogClassifier errorDialogs = ErrorDialogClassifier.NONE;

    private Driver(DriverSettings settings, Seam seam, CapabilityReport capabilities) {
        this.settings = settings;
        this.seam = seam;
        this.capabilities = capabilities;
        this.focus = new FocusEmulator(seam);
        this.activation = new Activation(seam, focus);
        this.poster = Poster.forThisJvm();
        this.settle = Settle.live();
        this.drops = new DropInjector(seam);
        this.watch = new Watch(seam);
        this.edtErrors = EdtErrors.live();
    }

    /** Idempotent. Call after {@link LaunchMode#apply}: the seam starts the toolkit. */
    public static synchronized Driver install(DriverSettings settings) {
        if (current != null) return current;
        Seam seam = Seams.forThisJvm();
        CapabilityReport report = SelfCheck.run(seam);
        Driver driver = new Driver(settings, seam, report);
        driver.settle.register(new GlassPaneBusy());
        driver.watch.start();
        driver.edtErrors.ensureInstalled();
        current = driver;
        System.out.println("UI driver installed, mode " + settings.mode + ": " + report.lines());
        return driver;
    }

    /** The installed driver, or null in a process that never installed one. */
    public static Driver current() {
        return current;
    }

    public DriverSettings settings() { return settings; }
    public Seam seam() { return seam; }
    public CapabilityReport capabilities() { return capabilities; }
    public FocusEmulator focus() { return focus; }
    public Activation activation() { return activation; }
    public Poster poster() { return poster; }
    public Settle<Window> settle() { return settle; }
    public DropInjector drops() { return drops; }
    public Watch watch() { return watch; }
    public EdtErrors edtErrors() { return edtErrors; }
    public AppWindows windows() { return windows; }
    public ErrorDialogClassifier errorDialogs() { return errorDialogs; }
    public Names names() { return names; }
    public Refs refs() { return refs; }
    public Snapshot snapshot() { return snapshot; }
    public Resolver resolver() { return resolver; }
    public Recorder recorder() { return recorder; }
    public LiveVerbs verbs() { return verbs; }
    public Sections sections() { return sections; }

    public void registerBusySignal(BusySignal signal) {
        settle.register(signal);
    }

    public void registerSections(SectionSource source) {
        sections.register(source);
    }

    /** What the app paints inside one component that a user nonetheless picks on its own, a switch's segment. */
    public void registerParts(PartSource source) {
        parts.register(source);
    }

    public void setErrorDialogClassifier(ErrorDialogClassifier classifier) {
        errorDialogs = classifier == null ? ErrorDialogClassifier.NONE : classifier;
    }

    public void setMainWindow(Supplier<Window> main) {
        windows.setMain(main);
    }
}
