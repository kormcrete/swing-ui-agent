package io.github.kormcrete.swingui.driver.input;

import io.github.kormcrete.swingui.driver.DriverException;
import io.github.kormcrete.swingui.driver.Reason;
import io.github.kormcrete.swingui.driver.focus.FocusEmulator;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.function.Function;

/**
 * Before posting: the target shows, is enabled where required, the point is in its visible
 * part, and a press there would reach it. Mirrors AWT's mouse-target rule: the topmost visible
 * component under the point that has mouse listeners. A component's private event mask cannot be
 * read, so one that enabled mouse events without adding a listener counts as transparent - except for the one
 * Swing case that is known: {@code setComponentPopupMenu} enables mouse events, so a component with its own
 * popup menu takes mouse input even with no listener (a label or panel that only has a context menu).
 */
public final class HitCheck {

    private HitCheck() {}

    /** Something the agent itself draws over the app - the person's palette - that can step off a point it covers. EDT. */
    public interface Cover {
        /** {@code cover} lies over {@code p} in {@code target}: true when it moved off. */
        boolean stepOff(Component cover, Component target, Point p);
    }

    private static final Cover NEVER = (cover, target, p) -> false;
    private static volatile Cover cover = NEVER;

    /** What the agent draws over the app; null: nothing that steps off. */
    public static void cover(Cover c) {
        cover = c == null ? NEVER : c;
    }

    /** EDT. */
    public static void check(Component target, Point p, boolean needsEnabled, Function<Component, String> namer) {
        String name = namer.apply(target);
        if (!target.isShowing()) throw notActionable("hidden: " + name);
        if (needsEnabled && !target.isEnabled()) throw notActionable("disabled: " + name);
        if (!visibleAt(target, p)) {
            throw notActionable("hidden: " + name + " is clipped or scrolled out of view at " + p.x + "," + p.y);
        }
        Window window = FocusEmulator.windowOf(target);
        Component hit = receiver(window, SwingUtilities.convertPoint(target, p, window), target, p);
        if (hit == null) throw notActionable("nothing at " + name + " takes mouse input");
        if (!reaches(hit, target)) throw notActionable("covered by " + namer.apply(hit));
    }

    /**
     * The component a press at {@code at} in {@code window} goes to, after the agent's own cover stepped off
     * {@code p} in {@code target} when it was in the way.
     */
    static Component receiver(Component window, Point at, Component target, Point p) {
        Component hit = mouseTarget(window, at.x, at.y);
        if (hit != null && !reaches(hit, target) && cover.stepOff(hit, target, p)) hit = mouseTarget(window, at.x, at.y);
        return hit;
    }

    /** A press on {@code hit} reaches {@code target}: the same component, or one inside the other. */
    private static boolean reaches(Component hit, Component target) {
        return hit == target || isAncestor(target, hit) || isAncestor(hit, target);
    }

    public static boolean visibleAt(Component target, Point p) {
        Rectangle visible = target instanceof JComponent
                ? ((JComponent) target).getVisibleRect()
                : new Rectangle(0, 0, target.getWidth(), target.getHeight());
        return !visible.isEmpty() && visible.contains(p);
    }

    /** The component a press at (x, y), in {@code c}'s coordinates, is delivered to; null for none. */
    public static Component mouseTarget(Component c, int x, int y) {
        if (!c.isVisible() || !c.contains(x, y)) return null;
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                Component hit = mouseTarget(child, x - child.getX(), y - child.getY());
                if (hit != null) return hit;
            }
        }
        return listensToMouse(c) ? c : null;
    }

    private static boolean listensToMouse(Component c) {
        return c.getMouseListeners().length > 0 || c.getMouseMotionListeners().length > 0
                || c.getMouseWheelListeners().length > 0 || hasOwnPopupMenu(c);
    }

    /** An inherited popup menu enables nothing on {@code c}: only one set on {@code c} itself counts. */
    private static boolean hasOwnPopupMenu(Component c) {
        if (!(c instanceof JComponent)) return false;
        JComponent j = (JComponent) c;
        if (j.getComponentPopupMenu() == null) return false;
        if (!j.getInheritsPopupMenu() || !(j.getParent() instanceof JComponent)) return true;
        return j.getComponentPopupMenu() != ((JComponent) j.getParent()).getComponentPopupMenu();
    }

    private static boolean isAncestor(Component ancestor, Component c) {
        return ancestor instanceof Container && ((Container) ancestor).isAncestorOf(c);
    }

    private static DriverException notActionable(String message) {
        return new DriverException(Reason.NOT_ACTIONABLE, message);
    }
}
