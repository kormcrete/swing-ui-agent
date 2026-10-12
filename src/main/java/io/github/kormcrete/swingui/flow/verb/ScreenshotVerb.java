package io.github.kormcrete.swingui.flow.verb;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.Shot;
import io.github.kormcrete.swingui.driver.VerbOutcome;
import io.github.kormcrete.swingui.driver.Verbs;
import io.github.kormcrete.swingui.driver.model.Locator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * A flow step that writes a PNG of a window, a scope or one element to a file, so a replay can
 * capture screens. The MCP {@code screenshot} tool is the read-only counterpart that returns the image
 * instead of writing it and is never recorded; this step is recorded like any other.
 *
 * <p>A relative {@code path} lands under the screenshot directory: {@code -Dswingui.screenshotDir}, else
 * {@code target/flow-screenshots} of the app plugin's module folder ({@link #moduleDir}) when that exists below
 * the working directory, else of the working directory.
 */
@Doc("Writes a PNG of a window, a scope or one element to 'path', painting the app's own components (no screen "
        + "grab, no focus taken). Give 'window' (main | dialog | dialog:<title> | window:<title>) for a whole "
        + "window, or 'target' for a tab, section or element; neither means the current window. A relative "
        + "path lands under -Dswingui.screenshotDir, else target/flow-screenshots of the app plugin's module "
        + "folder, else of the working directory. Recorded as a flow step, so a replay captures it too.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScreenshotVerb extends VerbAction {

    public static final String DIR_PROP = "swingui.screenshotDir";
    static final String DEFAULT_DIR = "target/flow-screenshots";

    /** The app plugin's module folder (its export defaults), set by the agent's assembly; null: the working directory. */
    public static volatile java.util.function.Supplier<String> moduleDir = () -> null;

    @Required @Doc("Where to write the PNG, e.g. 'orders/ORD-1640.png': absolute, or relative to the "
            + "screenshot directory.")
    public String path;
    @Doc("A whole window: main | dialog | dialog:<title> | window:<title> (a title ending in * matches a prefix).")
    public String window;
    @Doc("An element instead of a whole window: a tab (its content), a section, a table, a panel. A locator, "
            + "not a ref - refs do not replay.")
    public Locator target;
    @Doc("Longest side of the image, default 1200; it is scaled down to fit.")
    public Integer maxSize;

    @Override public String describe() {
        return "screenshot(" + (target != null ? target : window != null ? window : "current window") + " -> " + path + ")";
    }

    @Override public VerbOutcome run(Verbs verbs) {
        if (path == null || path.trim().isEmpty()) throw bad("screenshot needs a 'path' to write the PNG to");
        if (target != null && window != null) throw bad("screenshot takes a 'window' or a 'target', not both");
        if (target != null && target.ref != null) {
            throw bad("a screenshot step needs a locator, not a ref: refs do not replay");
        }
        Locator what = target;
        if (window != null) {
            what = new Locator();
            what.window = window;
        }
        Shot shot = verbs.screenshot(what, null, maxSize);
        Path file = resolve(path.trim());
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.write(file, shot.png);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write the screenshot to " + file, e);
        }
        VerbOutcome outcome = new VerbOutcome();
        outcome.note = "wrote " + file.toAbsolutePath().normalize() + " (" + shot.width + "x" + shot.height + ")";
        return outcome;
    }

    @Override public Map<String, Locator> targets() {
        return targetMap(target);
    }

    /** {@code path} as written: absolute as given, relative under the screenshot directory. */
    static Path resolve(String path) {
        Path p = Paths.get(path);
        if (p.isAbsolute()) return p;
        return screenshotDir(Paths.get("")).resolve(p);
    }

    static Path screenshotDir(Path workingDir) {
        String configured = System.getProperty(DIR_PROP);
        if (configured != null && !configured.trim().isEmpty()) return Paths.get(configured.trim());
        String moduleName = moduleDir.get();
        Path module = moduleName == null ? null : workingDir.resolve(moduleName);
        return module != null && Files.isDirectory(module) ? module.resolve(DEFAULT_DIR) : workingDir.resolve(DEFAULT_DIR);
    }

    private static DriverException bad(String message) {
        return new DriverException(Reason.BAD_INPUT, message);
    }
}
