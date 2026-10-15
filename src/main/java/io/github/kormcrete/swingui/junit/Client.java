package io.github.kormcrete.swingui.junit;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.driver.model.Node;
import io.github.kormcrete.swingui.flow.UiUsers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/** A test's handle on one live Swing client. The only way a UI test reaches the UI. */
public final class Client {

    /**
     * Relative screenshot paths resolve here for a Client built directly. A {@link UiTest}'s Client resolves them in
     * its test module's target/ui-screenshots instead, whatever the runner's working directory.
     */
    static final Path SCREENSHOT_DIR = Paths.get("target", "ui-screenshots");

    private final Verbs verbs;
    private final ClientApp app;
    private final String runId;
    private final Long timeoutMillis;
    private final List<String> warnings;
    private final Path screenshotDir;

    public Client(Verbs verbs, ClientApp app, String runId) {
        this(verbs, app, runId, SCREENSHOT_DIR);
    }

    /** {@code screenshotDir}: where {@link #saveScreenshot} puts a relative path; null for {@link #SCREENSHOT_DIR}. */
    Client(Verbs verbs, ClientApp app, String runId, Path screenshotDir) {
        this(verbs, app, runId, null, new ArrayList<String>(), screenshotDir == null ? SCREENSHOT_DIR : screenshotDir);
    }

    private Client(Verbs verbs, ClientApp app, String runId, Long timeoutMillis, List<String> warnings, Path screenshotDir) {
        this.verbs = verbs;
        this.app = app;
        this.runId = runId;
        this.timeoutMillis = timeoutMillis;
        this.warnings = warnings;
        this.screenshotDir = screenshotDir;
    }

    /** Unique per test, e.g. {@code UIT-20260928-141503-a3f9}: name what the test creates with it. */
    public String runId() { return runId; }

    /** The same client with another wait for its actions and assertions, and those of the targets made from it. */
    public Client timeout(Duration d) {
        return new Client(verbs, app, runId, Target.millis(d), warnings, screenshotDir);
    }

    /** The app's typed hooks: an interface the app's plugin registers, e.g. {@code ui.hooks(MyAppHooks.class).openTabTitles()}. */
    public <T> T hooks(Class<T> type) {
        return app("hooks").hooks(type);
    }

    /** The app side of this client (recorded test runs); package-private for {@link RecordedTests}. */
    ClientApp app() {
        return app("run recorded tests");
    }

    private ClientApp app(String what) {
        if (app == null) throw new UnsupportedOperationException(what + " needs a UI session; this Client has none");
        return app;
    }

    /** Switches the logged-in user mid-test (the test's own @LoginAs is applied before it starts). */
    public void loginAs(String alias) {
        app("loginAs").loginAs(alias);
    }

    /**
     * Switches mid-test to an account no alias in .swing-ui-agent/ui-users.properties names, e.g. one the test itself
     * just created. A repeat for the same user is a no-op; {@link #relogin} re-uses these credentials, and the next
     * test's reset logs back in as its own alias. Valid credentials only: a wrong password fails this test once the app
     * plugin's login gives up.
     */
    public void loginAs(String user, String password) {
        app("loginAs").loginAs(user, password);
    }

    /** Logs out and back in as the current user: the state right after login, which a reset does not restore. */
    public void relogin() {
        app("relogin").relogin();
    }

    /**
     * The secret {@code name} of {@code alias}: the {@code <alias>.<name>} line of .swing-ui-agent/ui-users.properties,
     * e.g. {@code secret("default", "pin")}. Type it into the app; never print or assert on it. Fails when absent.
     */
    public String secret(String alias, String name) { return UiUsers.secret(alias, name); }

    public List<String> warnings() { return Collections.unmodifiableList(warnings); }

    Verbs verbs() { return verbs; }
    Long timeoutMillis() { return timeoutMillis; }
    void warn(String w) { warnings.add(w); }

    // ---- roots
    public Target main() { return root("main"); }
    public Target popup() { return root("popup"); }
    public Target dialog() { return root("dialog"); }
    public Target dialog(String title) { return root("dialog:" + title); }
    public Target window(String title) { return root("window:" + title); }
    /** A raw window token as the driver spells it: main, popup, dialog, dialog:&lt;title&gt;, window:&lt;title&gt;. */
    public Target inWindow(String windowToken) { return root(windowToken); }

    private Target root(String window) {
        Locator l = new Locator();
        l.window = window;
        return new Target(this, l, null, false);
    }

    private Target any() { return new Target(this, new Locator(), null, false); }

    // ---- leaf shortcuts (the whole client)
    public Target button(String name) { return any().button(name); }
    public Target field(String name) { return any().field(name); }
    public Target password(String name) { return any().password(name); }
    public Target combo(String name) { return any().combo(name); }
    public Target checkbox(String name) { return any().checkbox(name); }
    public Target radio(String name) { return any().radio(name); }
    public Target toggle(String name) { return any().toggle(name); }
    public Target list(String name) { return any().list(name); }
    public Target tree(String name) { return any().tree(name); }
    public Target table(String name) { return any().table(name); }
    public Target table() { return any().table(); }
    public Target tab(String title) { return any().tab(title); }
    public Target section(String caption) { return any().section(caption); }
    public Target label(String name) { return any().label(name); }
    public Target clickable(String name) { return any().clickable(name); }
    public Target menuitem(String name) { return any().menuitem(name); }
    public Target role(String role, String name) { return any().role(role, name); }
    public Target byId(String id) { return any().byId(id); }

    // ---- client-level actions
    /** Opens {@code target} through the app plugin's navigate step. Fails as a UiActionFailed with no locator. */
    public void navigate(String target) {
        act("navigate " + target, null, () -> { app("navigate").navigate(target); return null; });
    }

    /** Closes the active tab, answering its "close anyway?" confirm. Fails as a UiActionFailed with no locator. */
    public void closeActiveTab() {
        act("closeActiveTab", null, () -> { app("closeActiveTab").closeActiveTab(); return null; });
    }

    public void menu(String... path) { act("menu " + Arrays.asList(path), null, () -> verbs.menu(Arrays.asList(path), timeoutMillis)); }

    /** The visible labels of the menu-bar menu {@code path} names (a read: nothing opens). */
    public List<String> menuItems(String... path) {
        try {
            return verbs.menuItems(Arrays.asList(path));
        } catch (DriverException e) {
            throw new UiActionFailed("menuItems " + Arrays.asList(path), null, e, Collections.<String>emptyList());
        }
    }

    public void pick(String... path) { act("pick " + Arrays.asList(path), null, () -> verbs.pick(Arrays.asList(path), timeoutMillis)); }
    public void answer(String button) { act("answer " + button, null, () -> verbs.answer(button, timeoutMillis)); }
    /** Chooses {@code path} in the showing file chooser and approves it. */
    public void chooseFile(String path) {
        act("chooseFile " + path, null, () -> verbs.chooseFile(path, null, timeoutMillis));
    }

    public void chooseFile(String path, boolean approve) {
        act("chooseFile " + path, null, () -> verbs.chooseFile(path, approve, timeoutMillis));
    }
    public void press(String keys) { act("press " + keys, null, () -> verbs.press(keys, null, timeoutMillis)); }

    /**
     * Puts {@code text} on the system clipboard, as a copy in another application would - e.g. a block
     * copied from a spreadsheet ({@code "7,2\t<0,5\n12\tn/a"}). Paste it with {@code press("Mod+V")} on the
     * focused element. Replaces what the user had on the clipboard.
     */
    public void setClipboard(String text) { act("setClipboard", null, () -> verbs.setClipboard(text, timeoutMillis)); }

    /** The clipboard's text, e.g. what a copy in the app left there; null when it holds none. */
    public String clipboardText() {
        try {
            return verbs.clipboardText();
        } catch (DriverException e) {
            throw new UiActionFailed("clipboardText", null, e, Collections.<String>emptyList());
        }
    }

    /**
     * The showing file chooser - its folder, file-name field and whether that field has focus - waiting for one
     * up to this client's timeout. Fails as a UiActionFailed when none shows.
     */
    public FileChooser fileChooser() {
        try {
            return FileChooser.of(verbs.fileChooser(timeoutMillis));
        } catch (DriverException e) {
            throw new UiActionFailed("fileChooser", null, e, Collections.<String>emptyList());
        }
    }

    /** The absolute path of the folder the showing file chooser is in ({@link #fileChooser()}). */
    public String fileChooserFolder() { return fileChooser().folder(); }

    /** The showing file chooser's file-name field text ({@link #fileChooser()}). */
    public String fileChooserName() { return fileChooser().name(); }

    /**
     * Puts focus in the showing file chooser's file-name field, where a chooser does not open with it: the next
     * target-less {@link #press} lands there. No locator reaches that field - it is the look and feel's own class.
     */
    public void focusFileChooserName() {
        act("focusFileChooserName", null, () -> verbs.focusFileChooserName(timeoutMillis));
    }

    // ---- modal dialogs (one look, no wait)
    /** How many nodes {@link #describeModal()} reads: enough for a message pane's title and text. */
    private static final int DESCRIBE_MAX_NODES = 50;

    /**
     * Whether a modal dialog shows now. Read off the dialog window's own snapshot node: a locator never
     * resolves a window root itself, so no Target can answer this.
     */
    public boolean modalShowing() {
        try {
            return modalShowing(verbs);
        } catch (DriverException e) {
            throw new UiActionFailed("modalShowing", null, e, Collections.<String>emptyList());
        }
    }

    /**
     * The top modal dialog's title and its first label's text (its message), or "(no modal dialog)". The
     * text, not the label's name: a {@code JOptionPane} names every message label "OptionPane.label".
     */
    public String describeModal() {
        Node app;
        try {
            app = verbs.snapshot("dialog", null, DESCRIBE_MAX_NODES, 0);
        } catch (DriverException e) {
            throw new UiActionFailed("describeModal", null, e, Collections.<String>emptyList());
        }
        Node dialog = topModal(app);
        if (dialog == null) return "(no modal dialog)";
        String title = blankToNull(dialog.name);
        String text = firstLabel(dialog);
        if (title != null && text != null) return title + ": " + text;
        if (title != null) return title;
        return text != null ? text : "(untitled dialog)";
    }

    /** One window node, no wait: {@code window:"dialog"} is the top modal (else a lone dialog, not modal). */
    static boolean modalShowing(Verbs verbs) {
        return topModal(verbs.snapshot("dialog", null, 1, 0)) != null;
    }

    private static Node topModal(Node app) {
        if (app == null || app.children == null) return null;
        for (Node w : app.children) {
            if (Boolean.TRUE.equals(w.modal)) return w;
        }
        return null;
    }

    /** A label node carries its text as {@code value} when its name is something else (a component name). */
    private static String firstLabel(Node n) {
        if (n.children == null) return null;
        for (Node c : n.children) {
            if ("label".equals(c.role)) {
                String text = blankToNull(c.value) != null ? blankToNull(c.value) : blankToNull(c.name);
                if (text != null) return text;
            }
            String below = firstLabel(c);
            if (below != null) return below;
        }
        return null;
    }

    private static String blankToNull(String s) { return s == null || s.trim().isEmpty() ? null : s.trim(); }

    public byte[] screenshot() { return shoot(null); }

    /** A PNG of the key window at {@code scale} image pixels per screen pixel, 1 to 4: 2 is a HiDPI (2x) image. */
    public byte[] screenshot(double scale) { return shoot(scale); }

    private byte[] shoot(Double scale) {
        try {
            return verbs.screenshot(null, null, null, scale).png;
        } catch (DriverException e) {
            throw new UiActionFailed("screenshot", null, e, Collections.<String>emptyList());
        }
    }

    /** Saves a screenshot; a relative {@code path} lands under the test module's target/ui-screenshots. */
    public void saveScreenshot(String path) { write(path, screenshot()); }

    /** {@link #saveScreenshot(String)} at {@code scale} (1 to 4; 2 for images people will look at). */
    public void saveScreenshot(String path, double scale) { write(path, screenshot(scale)); }

    void write(String path, byte[] png) {
        Path p = Paths.get(path);
        Path file = p.isAbsolute() ? p : screenshotDir.resolve(p);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.write(file, png);
        } catch (IOException e) {
            throw new IllegalStateException("could not write screenshot " + file.toAbsolutePath(), e);
        }
    }

    /** Runs one verb; a DriverException becomes UiActionFailed (with candidates when a target was not found). */
    void act(String what, Locator target, Supplier<VerbOutcome> call) {
        VerbOutcome out;
        try {
            out = call.get();
        } catch (DriverException e) {
            throw new UiActionFailed(what, target, e, candidatesFor(target, e));
        }
        if (out != null && out.warning != null) warn(what + (target == null ? "" : " " + target) + ": " + out.warning);
    }

    List<String> candidatesFor(Locator target, DriverException e) {
        if (target == null) return Collections.emptyList();
        switch (e.reason()) {
            case NOT_FOUND:
            case AMBIGUOUS:
            case NOT_ACTIONABLE:
                try {
                    return verbs.candidates(target);
                } catch (RuntimeException ignored) {
                    return Collections.emptyList();
                }
            default:
                return Collections.emptyList();
        }
    }
}
