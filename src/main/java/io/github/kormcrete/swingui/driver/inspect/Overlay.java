package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.widgets.Screenshots;

import javax.swing.AbstractButton;
import javax.swing.CellRendererPane;
import javax.swing.Icon;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JList;
import javax.swing.JMenuBar;
import javax.swing.JPopupMenu;
import javax.swing.JRootPane;
import javax.swing.JScrollBar;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.JTableHeader;
import javax.swing.text.JTextComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Everything the inspector shows in one window, all of it INSIDE that window's layered pane so it stays
 * visible when the app fills the screen: boxes (drag layer, never the glass pane, never a border on the app's own
 * component), check-mark badges, the bar (palette or question, draggable) and - while the person picks or checks - a
 * catcher just under the popup layer, so the app gets no click but a menu the inspector opens still does. The box layer
 * takes no mouse input; the rest is added and removed, never hidden. EDT only.
 */
final class Overlay extends JComponent {

    static final String PACKAGE = Overlay.class.getPackage().getName() + ".";
    private static final Integer BOXES = JLayeredPane.DRAG_LAYER;
    private static final Integer CONTROLS = JLayeredPane.DRAG_LAYER + 1;
    private static final Integer CATCHER = JLayeredPane.POPUP_LAYER - 1;
    private static final List<Rectangle> NONE = Collections.emptyList();
    /** How far apart the bar's spots along the window's edges are. */
    private static final int STEP = 24;

    static final Color ASK = new Color(0xE8, 0x6A, 0x10);
    static final Color HOVER = new Color(0x1F, 0x6F, 0xEB);
    static final Color YES = new Color(0x1E, 0x9E, 0x4A);
    static final Color NO = new Color(0xC0, 0x36, 0x36);
    static final Color NAVY = new Color(0x14, 0x22, 0x3D);
    static final Color CHECK = new Color(0x6B, 0x4F, 0xBB);

    /** One box: a component, one tab, one table cell, or one whole table column (view indices). */
    static final class Mark {
        final Component component;
        final Integer tab;
        final int row;
        final int column;
        String label;
        Color color;
        final boolean dashed;
        Badge yes;
        /** A result painted on the box's top right corner: true a green check, false a red cross, null none. */
        Boolean result;

        Mark(Component component, Integer tab, int row, int column, String label, Color color, boolean dashed) {
            this.component = component;
            this.tab = tab;
            this.row = row;
            this.column = column;
            this.label = label;
            this.color = color;
            this.dashed = dashed;
        }

        static Mark of(Target t, String label, Color color, boolean dashed) {
            return new Mark(t.component, t.tab, t.row, t.column, label, color, dashed);
        }
    }

    final JRootPane root;
    private final JLayeredPane layers;
    private final List<Mark> marks = new ArrayList<Mark>();
    private Mark hover;
    /** The person's palette. */
    private Bar bar;
    /** The agent's question, beside the palette. */
    private Bar ask;
    private JComponent panel;
    /** The palette and panel are out while a test run holds the agent. */
    private boolean hidden;
    /** The palette, panel and question are out until the agent's step ends: no spot in the window clears its target. */
    private boolean yielding;
    /**
     * What the agent acts on in its current step (layered-pane coordinates; a drag has two targets): the bar and its
     * panel stay off all of it while the step runs, unless the person drags meanwhile. Emptied when the step ends
     * ({@link #endYield}), so the bar goes back to its spot.
     */
    private final List<Rectangle> avoid = new ArrayList<Rectangle>();
    /** Where the bar stepped aside to ({@link #placePalette}), null while it is on its spot. */
    private Rectangle aside;
    /** The bar's {@link Bar#moved} when {@link #avoid} was filled: a drag replaces it. */
    private Point movedAtAvoid;
    private Catcher catcher;
    /** Where the person dragged a bar in this window while this overlay showed it; null: no drag here yet. */
    private Point drag;
    /** Where a bar goes in this window before the person drags one here (null: where it goes by itself); set by the inspector. */
    Function<Bar, Point> spotFor;
    /**
     * The app's small components in this window as last looked at ({@link #smallIn}): the bar's default spot is picked
     * clear of them, and a bar stepping aside or the agent's question avoids them where it can. Looked at again when a
     * bar comes in, when the agent acts, and on the palette's timer ({@link #recheck}); null until then.
     */
    private List<Rectangle> small;
    /**
     * The bar's default spot here, as shares of the room it can move in across and down (0 left or top, 1 right or
     * bottom), so it follows the window's corners as it is resized; null until picked. Picked once, the first time a
     * bar shows here, and never again while the app runs (the inspector keeps it when this overlay goes,
     * {@link #picked}); a drag by the person wins over it. Never saved: the next start picks again.
     */
    double[] spot;
    /** Told the default spot when it is picked (the inspector keeps it for this window); null: nobody. */
    Consumer<double[]> picked;
    /** Follows layout changes, scrolling and resizes while anything shows; stopped while nothing does. */
    private final Timer follow = new Timer(120, e -> relayout());
    /** What the boxes looked like when last painted: a tick repaints the window only when that changed. */
    private List<Object> drawn = new ArrayList<Object>();

    Overlay(JRootPane root) {
        this.root = root;
        this.layers = root.getLayeredPane();
        setOpaque(false);
        layers.add(this, BOXES);
        relayout();
    }

    /** Whether the follow timer runs. */
    boolean following() {
        return follow.isRunning();
    }

    /** A component of the inspector's own, or one inside a menu the inspector opened. */
    static boolean ours(Component c) {
        for (Component k = c; k != null; k = k instanceof JPopupMenu ? ((JPopupMenu) k).getInvoker() : k.getParent()) {
            if (k.getClass().getName().startsWith(PACKAGE)) return true;
        }
        return false;
    }

    @Override
    public boolean contains(int x, int y) {
        return false;
    }

    void mark(Mark m, Runnable onYes) {
        marks.add(m);
        if (onYes != null) {
            m.yes = new Badge(onYes);
            layers.add(m.yes, CONTROLS);
        }
        relayout();
    }

    void hover(Mark m) {
        hover = m;
        repaintIfChanged();
        followWhileShowing();
    }

    /**
     * The person's palette; null removes it. Every bar shown here (the one Done, ● Record or a replay puts up too)
     * takes the person's latest drag in this window - of whichever bar they dragged here - else the one they left in
     * this window before ({@link #spotFor}), else the default spot, which covers none of the window's small
     * components. Never a spot the bar brings from another window or from an older drag.
     */
    void bar(Bar b) {
        if (b == bar) return;
        if (bar != null) layers.remove(bar);
        if (b != null) {
            b.moved = dragged();
            b.dragged = p -> drag = p;
            small = smallIn(layers);
        }
        bar = b;
        sync();
        relayout();
        layers.repaint();
    }

    /** The agent's question bar; null removes it. */
    void ask(Bar b) {
        if (ask != null) layers.remove(ask);
        ask = b;
        sync();
        relayout();
        layers.repaint();
    }

    /**
     * While a test run holds the agent, the person's palette and panel leave the window, so they can never cover what
     * the test clicks; they come back after. Removed and re-added, never hidden.
     */
    void hidePalette(boolean on) {
        if (on == hidden) return;
        hidden = on;
        sync();
        relayout();
        layers.repaint();
    }

    /**
     * The agent is about to act on {@code area} (layered-pane coordinates) and the palette covers it: the bar (with its
     * panel) and the question step aside to the first spot clear of it - and of what the agent acts on earlier in the
     * same step - until the step ends ({@link #endYield}) or the person drags the bar. With no such spot they leave the
     * window until then. True: nothing of the palette covers any of the step's areas any more.
     */
    boolean makeRoom(Rectangle area) {
        avoid.add(new Rectangle(area));
        movedAtAvoid = bar == null ? null : bar.moved;
        small = smallIn(layers);
        relayout();
        if (coversAvoided()) {
            yielding = true;
            sync();
            relayout();
            layers.repaint();
        }
        return true;
    }

    private boolean coversAvoided() {
        for (JComponent c : new JComponent[] {bar, ask, panel}) {
            if (c == null || c.getParent() != layers) continue;
            for (Rectangle r : avoid) if (c.getBounds().intersects(r)) return true;
        }
        return false;
    }

    /**
     * The agent's step ended: a palette that left the window for it comes back. When the step was not one of a run
     * ({@code runGoesOn} false), a bar that stepped aside for it also goes back to its spot (where the person dragged it,
     * else its default one) and the agent's next step avoids only what that step acts on; in a run (a batch, a replay, a
     * recorded test's run) it stays aside until the run ends, so it does not jump back and forth between the steps.
     */
    void endYield(boolean runGoesOn) {
        boolean back = yielding || !runGoesOn && !avoid.isEmpty();
        if (!runGoesOn) avoid.clear();
        if (!back) return;
        yielding = false;
        sync();
        relayout();
        layers.repaint();
    }

    /**
     * The palette's timer: looks at the window's small components again (the agent's question and a bar stepping
     * aside avoid them). The palette's spot stays as it is: picked once, it never moves because the window's controls
     * changed. Nothing changed, nothing happens: no repaint.
     */
    void recheck() {
        List<Rectangle> now = smallIn(layers);
        if (now.equals(small)) return;
        small = now;
        relayout();
    }

    /** {@code c} is the bar, the question or the panel, or inside one of them. */
    boolean holds(Component c) {
        for (JComponent k : new JComponent[] {bar, ask, panel}) {
            if (k != null && SwingUtilities.isDescendingFrom(c, k)) return true;
        }
        return false;
    }

    Bar bar() {
        return bar;
    }

    /** The person's drag in this window: their latest one here, else the one they left here before; null: none. */
    Point dragged() {
        return drag != null ? drag : spotFor != null ? spotFor.apply(bar) : null;
    }

    /** A panel beside the bar (the steps list); null removes it. */
    void panel(JComponent p) {
        if (panel != null) layers.remove(panel);
        panel = p;
        if (p != null) Bar.keepPopupsOpen(p);
        sync();
        relayout();
        layers.repaint();
    }

    /** One transparent sheet over the window that takes the mouse, so no click reaches the app. */
    void catcher(Consumer<MouseEvent> moved, Consumer<MouseEvent> clicked) {
        removeCatcher();
        catcher = new Catcher(moved, clicked);
        layers.add(catcher, CATCHER);
        relayout();
    }

    /** The hover box's label, or null when none shows. */
    String hoverLabel() {
        return hover == null ? null : hover.label;
    }

    /** The boxes' labels, in the order they were put up. */
    List<String> markLabels() {
        List<String> out = new ArrayList<String>();
        for (Mark m : marks) out.add(m.label);
        return out;
    }

    /** Whether the catcher is up here: the person picks or checks. */
    boolean catching() {
        return catcher != null;
    }

    void removeCatcher() {
        if (catcher != null) layers.remove(catcher);
        catcher = null;
        hover = null;
        layers.repaint();
        followWhileShowing();
    }

    boolean empty() {
        return marks.isEmpty() && hover == null && bar == null && ask == null && panel == null && catcher == null;
    }

    /** Drops every box but {@code keep}. */
    void keepOnly(Mark keep) {
        for (Mark m : new ArrayList<Mark>(marks)) {
            if (m == keep) continue;
            if (m.yes != null) layers.remove(m.yes);
            marks.remove(m);
        }
        relayout();
        layers.repaint();
    }

    void clearMarks() {
        for (Mark m : marks) if (m.yes != null) layers.remove(m.yes);
        marks.clear();
        relayout();
        layers.repaint();
    }

    void dispose() {
        clearMarks();
        hidden = false;
        yielding = false;
        panel(null);
        bar(null);
        ask(null);
        removeCatcher();
        follow.stop();
        layers.remove(this);
        layers.repaint();
    }

    /** The topmost app component under {@code p} (layered-pane coordinates), skipping the inspector's own. */
    Component appComponentAt(Point p) {
        for (Component c : layers.getComponents()) {
            if (ours(c) || !c.isVisible() || !c.getBounds().contains(p)) continue;
            Point q = SwingUtilities.convertPoint(layers, p, c);
            Component deepest = SwingUtilities.getDeepestComponentAt(c, q.x, q.y);
            if (deepest != null) return deepest;
        }
        return null;
    }

    JLayeredPane layers() {
        return layers;
    }

    /** Puts the bar, the panel and the question into the layered pane or takes them out, as the flags say. */
    private void sync() {
        show(bar, barIn());
        show(panel, barIn());
        show(ask, !yielding);
    }

    private void show(JComponent c, boolean in) {
        if (c == null) return;
        if (in && c.getParent() != layers) layers.add(c, CONTROLS);
        else if (!in && c.getParent() == layers) layers.remove(c);
    }

    private boolean barIn() {
        return !hidden && !yielding;
    }

    void relayout() {
        setBounds(0, 0, layers.getWidth(), layers.getHeight());
        if (catcher != null) catcher.setBounds(0, 0, layers.getWidth(), layers.getHeight());
        List<Rectangle> taken = new ArrayList<Rectangle>();
        for (Mark m : marks) {
            Rectangle r = rectOf(m);
            if (r != null) taken.add(r);
            if (m.yes == null) continue;
            m.yes.setVisible(r != null);
            if (r != null) {
                m.yes.setBounds(Math.min(r.x + r.width - 11, layers.getWidth() - 23), Math.max(r.y - 11, 0), 22, 22);
            }
        }
        // a drag by the person wins again: the bar forgets what the agent acted on
        if (bar != null && bar.moved != movedAtAvoid) avoid.clear();
        boolean withPanel = panel != null && barIn();
        if (bar != null && barIn()) placePalette(taken, withPanel ? panel.getPreferredSize() : null);
        taken.addAll(avoid);
        if (bar != null && barIn()) taken.add(bar.getBounds());
        if (ask != null && !yielding) place(ask, taken, null);
        if (withPanel && bar != null) {
            Rectangle want = panelBeside(bar.getBounds(), panel.getPreferredSize(), layers.getWidth(), layers.getHeight());
            if (!want.equals(panel.getBounds())) {
                panel.setBounds(want);
                panel.revalidate();
            }
        }
        repaintIfChanged();
        followWhileShowing();
    }

    /**
     * The box layer is as large as the window and not opaque: repainting it repaints the whole window, app included.
     * So only when a box, its label, colour or result, or the hover changed; moving the bar or the window repaints by
     * itself (setBounds).
     */
    private void repaintIfChanged() {
        List<Object> now = new ArrayList<Object>();
        List<Mark> all = new ArrayList<Mark>(marks);
        if (hover != null) all.add(hover);
        for (Mark m : all) {
            now.add(rectOf(m));
            now.add(m.label);
            now.add(m.color);
            now.add(m.dashed);
            now.add(m.result);
        }
        if (now.equals(drawn)) return;
        drawn = now;
        repaint();
    }

    /** The follow timer runs while anything shows here, so an overlay with nothing in it costs nothing. */
    private void followWhileShowing() {
        if (empty()) follow.stop();
        else if (!follow.isRunning()) follow.start();
    }

    private List<Rectangle> small() {
        if (small == null) small = smallIn(layers);
        return small;
    }

    /** The agent's question: off the boxes and the palette, and off small components where it can be. */
    private void place(Bar b, List<Rectangle> taken, Dimension panel) {
        Dimension size = b.getPreferredSize();
        b.setBounds(placeBar(size.width, size.height, layers.getWidth(), layers.getHeight(), b.moved, taken, panel, small(), null));
    }

    /**
     * The person's palette: on its spot - where they dragged it, else its default spot ({@link #spot}, picked once,
     * clear of the window's small components) - and it moves on its own for nothing else: not for the window's
     * controls coming and going, not for its own {@code panel} (that opens on whichever side has room). It steps aside
     * only while a box ({@code boxes}) lies under the bar, or while the agent's step acts on something under the bar
     * or its panel ({@link #avoid}); then it takes the best spot clear of them, and comes back when they are gone.
     */
    private void placePalette(List<Rectangle> boxes, Dimension panel) {
        Dimension size = bar.getPreferredSize();
        int areaW = layers.getWidth();
        int areaH = layers.getHeight();
        List<Rectangle> small = small();
        Rectangle home;
        if (spot != null) {
            home = at(spot, size.width, size.height, areaW, areaH);
        } else {
            home = paletteSpot(size.width, size.height, areaW, areaH, small);
            // a window not laid out to a size yet has nothing to pick from: it picks once it has one
            if (areaW > 0 && areaH > 0) {
                spot = share(home, areaW, areaH);
                if (picked != null) picked.accept(spot);
            }
        }
        Rectangle mine = placeBar(size.width, size.height, areaW, areaH, bar.moved != null ? bar.moved : home.getLocation(), NONE);
        List<Rectangle> taken = new ArrayList<Rectangle>(boxes);
        taken.addAll(avoid);
        if (free(mine, taken) && (panel == null || free(panelBeside(mine, panel, areaW, areaH), avoid))) {
            aside = null;
            bar.setBounds(mine);
            return;
        }
        // already aside: it stays there while that stays clear, so a spot earlier in the order that comes free (a box
        // of an earlier step gone) never pulls it from spot to spot within a run
        Rectangle now = bar.getBounds();
        if (now.equals(aside) && now.width == width(size.width, areaW) && now.height == size.height
                && now.x >= 0 && now.y >= 0 && now.x + now.width <= areaW && now.y + now.height <= areaH && free(now, taken)
                && (panel == null || avoid.isEmpty() || free(panelBeside(now, panel, areaW, areaH), taken))) {
            return;
        }
        aside = placeBar(size.width, size.height, areaW, areaH, null, taken, avoid.isEmpty() ? null : panel, small, null, true);
        bar.setBounds(aside);
    }

    /**
     * The palette's default spot in a {@code areaW} x {@code areaH} window: the bottom middle, else the best of the other
     * spots in {@link #placeBar}'s order, covering none of the window's {@code small} components where it can.
     */
    static Rectangle paletteSpot(int prefW, int h, int areaW, int areaH, List<Rectangle> small) {
        return placeBar(prefW, h, areaW, areaH, null, NONE, null, small, null, true);
    }

    /** The bar's width in a {@code areaW} wide window. */
    private static int width(int prefW, int areaW) {
        return Math.min(prefW, Math.max(120, areaW - 16));
    }

    /** {@code at} as shares of the room the bar can move in ({@link #spot}). */
    private static double[] share(Rectangle at, int areaW, int areaH) {
        int maxX = Math.max(0, areaW - at.width - 8);
        int maxY = Math.max(0, areaH - at.height - 8);
        return new double[] {maxX > 8 ? (at.x - 8) / (double) (maxX - 8) : 1, maxY > 8 ? (at.y - 8) / (double) (maxY - 8) : 1};
    }

    /** The bar's rectangle at {@code share} ({@link #spot}) in a window of that size. */
    private static Rectangle at(double[] share, int prefW, int h, int areaW, int areaH) {
        int w = width(prefW, areaW);
        int maxX = Math.max(0, areaW - w - 8);
        int maxY = Math.max(0, areaH - h - 8);
        int x = maxX > 8 ? 8 + (int) Math.round(share[0] * (maxX - 8)) : maxX;
        int y = maxY > 8 ? 8 + (int) Math.round(share[1] * (maxY - 8)) : maxY;
        return new Rectangle(x, y, w, h);
    }

    /**
     * Where the bar goes in a {@code areaW} x {@code areaH} window: where the person dragged it ({@code moved}) unless
     * that covers a box; else the first corner that covers none, bottom right first, so a question never hides what it
     * asks about. Always inside the window.
     */
    static Rectangle placeBar(int prefW, int h, int areaW, int areaH, Point moved, List<Rectangle> taken) {
        return placeBar(prefW, h, areaW, areaH, moved, taken, null);
    }

    /**
     * {@link #placeBar(int, int, int, int, Point, List)} with a {@code panel} of that preferred size open beside the bar
     * ({@link #panelBeside}): a spot is free only when the bar and the panel both are.
     */
    static Rectangle placeBar(int prefW, int h, int areaW, int areaH, Point moved, List<Rectangle> taken, Dimension panel) {
        return placeBar(prefW, h, areaW, areaH, moved, taken, panel, NONE, null);
    }

    /**
     * Where the bar goes: where the person dragged it ({@code moved}), else at {@code preferred} (its default spot),
     * while that covers none of {@code taken} (boxes, what the agent acts on) with the bar or its {@code panel}. Else the
     * best of the corners, the edge middles and spots along the four edges ({@link #spots}), in that order: clear of
     * {@code taken}; then covering the fewest of the window's {@code small} components with the bar, then the least of
     * them; then with the panel the fewest. So the bar is never put on a small component to make room for its panel,
     * and with every spot taken it still lands where it hides the least. Always inside the window.
     */
    static Rectangle placeBar(int prefW, int h, int areaW, int areaH, Point moved, List<Rectangle> taken, Dimension panel,
                              List<Rectangle> small, Point preferred) {
        return placeBar(prefW, h, areaW, areaH, moved, taken, panel, small, preferred, false);
    }

    /** {@link #placeBar(int, int, int, int, Point, List, Dimension, List, Point)}; {@code palette}: bottom middle first ({@link #spots}). */
    private static Rectangle placeBar(int prefW, int h, int areaW, int areaH, Point moved, List<Rectangle> taken,
                                      Dimension panel, List<Rectangle> small, Point preferred, boolean palette) {
        int w = width(prefW, areaW);
        int maxX = Math.max(0, areaW - w - 8);
        int maxY = Math.max(0, areaH - h - 8);
        for (Point p : new Point[] {moved, preferred}) {
            if (p == null) continue;
            Rectangle at = new Rectangle(Math.max(0, Math.min(p.x, maxX)), Math.max(0, Math.min(p.y, maxY)), w, h);
            if (free(at, panel, areaW, areaH, taken)) return at;
        }
        Rectangle best = null;
        long[] bestScore = null;
        for (Point p : spots(maxX, maxY, palette)) {
            Rectangle at = new Rectangle(p.x, p.y, w, h);
            Rectangle beside = panel == null ? null : panelBeside(at, panel, areaW, areaH);
            long[] score = {free(at, panel, areaW, areaH, taken) ? 0 : 1, covered(at, small), coveredArea(at, small),
                    beside == null ? 0 : covered(beside, small)};
            if (bestScore == null || less(score, bestScore)) {
                best = at;
                bestScore = score;
                if (score[0] == 0 && score[1] == 0 && score[3] == 0) break;
            }
        }
        return best;
    }

    /**
     * The spots the bar may take in a window where it can go as far as {@code maxX}, {@code maxY}: the corners and the
     * edge middles (bottom right first), then every {@link #STEP} along the bottom, top, right and left edges. The
     * person's palette ({@code palette}) tries the bottom middle before all of them; the agent's question does not.
     */
    private static List<Point> spots(int maxX, int maxY, boolean palette) {
        List<Point> out = new ArrayList<Point>();
        // in a window hardly wider than the bar, as far left as the other spots go
        if (palette) out.add(new Point(Math.max(Math.min(8, maxX), maxX / 2), maxY));
        int[][] corners = {{maxX, maxY}, {8, maxY}, {maxX, 8}, {8, 8}, {maxX / 2, maxY}, {maxX / 2, 8}};
        for (int[] c : corners) out.add(new Point(c[0], c[1]));
        for (int y : new int[] {maxY, 8}) for (int x = maxX; x >= 8; x -= STEP) out.add(new Point(x, y));
        for (int x : new int[] {maxX, 8}) for (int y = maxY; y >= 8; y -= STEP) out.add(new Point(x, y));
        return out;
    }

    private static boolean less(long[] a, long[] b) {
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return a[i] < b[i];
        return false;
    }

    /** How many of {@code small} {@code at} covers. */
    private static int covered(Rectangle at, List<Rectangle> small) {
        int n = 0;
        for (Rectangle r : small) if (r.intersects(at)) n++;
        return n;
    }

    private static long coveredArea(Rectangle at, List<Rectangle> small) {
        long a = 0;
        for (Rectangle r : small) {
            Rectangle i = r.intersection(at);
            if (!i.isEmpty()) a += (long) i.width * i.height;
        }
        return a;
    }

    /**
     * The app's small components in this window, in layered-pane coordinates: what a person acts on or reads in it -
     * buttons (disabled ones too: a dialog's Next often is), check boxes and radio buttons, combo boxes, text fields,
     * spinners, sliders, a label's text, a tabbed pane's tab rectangles and the menu bar. Large views - tables, lists,
     * trees, text in a scroll pane - and plain containers are not: the bar covers only part of them. The inspector's own
     * components, popups (they come and go) and anything hidden or scrolled out of sight do not count. EDT.
     */
    static List<Rectangle> smallIn(JLayeredPane layers) {
        List<Rectangle> out = new ArrayList<Rectangle>();
        Rectangle all = new Rectangle(0, 0, layers.getWidth(), layers.getHeight());
        for (Component c : layers.getComponents()) {
            if (layers.getLayer(c) >= JLayeredPane.POPUP_LAYER) continue;
            smallIn(c, 0, 0, all, out);
        }
        return out;
    }

    /** {@code c} (its parent at {@code dx}, {@code dy} in the layered pane, seen only inside {@code clip}) and what it holds. */
    private static void smallIn(Component c, int dx, int dy, Rectangle clip, List<Rectangle> out) {
        // the parents were looked at on the way down: only this one's own class can be the inspector's
        if (!c.isVisible() || c.getWidth() <= 0 || c.getHeight() <= 0 || c.getClass().getName().startsWith(PACKAGE)) return;
        if (c instanceof JPopupMenu || c instanceof CellRendererPane || c instanceof JScrollBar) return;
        Rectangle at = new Rectangle(dx + c.getX(), dy + c.getY(), c.getWidth(), c.getHeight());
        if (c instanceof JLabel) {
            Rectangle text = labelText((JLabel) c);
            if (text != null) add(new Rectangle(at.x + text.x, at.y + text.y, text.width, text.height), clip, out);
            return;
        }
        if (c instanceof AbstractButton || c instanceof JComboBox || c instanceof JTextField || c instanceof JSpinner
                || c instanceof JSlider || c instanceof JMenuBar) {
            add(at, clip, out);
            return;
        }
        if (c instanceof JTable || c instanceof JList || c instanceof JTree) return;
        if (c instanceof JTextComponent) {
            // text in a scroll pane is a view; a bare one reads like a label
            if (!(c.getParent() instanceof JViewport)) add(at, clip, out);
            return;
        }
        if (c instanceof JTabbedPane) {
            JTabbedPane tabs = (JTabbedPane) c;
            for (int i = 0; i < tabs.getTabCount(); i++) {
                Rectangle tab = tabs.getBoundsAt(i);
                if (tab != null) add(new Rectangle(at.x + tab.x, at.y + tab.y, tab.width, tab.height), clip, out);
            }
        }
        if (!(c instanceof Container)) return;
        Rectangle inside = c instanceof JViewport ? clip.intersection(at) : clip;
        if (inside.isEmpty()) return;
        for (Component k : ((Container) c).getComponents()) smallIn(k, at.x, at.y, inside, out);
    }

    private static void add(Rectangle r, Rectangle clip, List<Rectangle> out) {
        Rectangle seen = r.intersection(clip);
        if (!seen.isEmpty()) out.add(seen);
    }

    /** Where a label paints its text and icon, in its own coordinates; null when it shows neither. */
    private static Rectangle labelText(JLabel l) {
        String text = l.getText();
        Icon icon = l.isEnabled() ? l.getIcon() : l.getDisabledIcon();
        boolean hasText = text != null && !text.isEmpty();
        if (!hasText && icon == null) return null;
        if (l.getFont() == null) return new Rectangle(0, 0, l.getWidth(), l.getHeight());
        Insets in = l.getInsets();
        Rectangle view = new Rectangle(in.left, in.top, l.getWidth() - in.left - in.right, l.getHeight() - in.top - in.bottom);
        Rectangle iconAt = new Rectangle();
        Rectangle textAt = new Rectangle();
        SwingUtilities.layoutCompoundLabel(l, l.getFontMetrics(l.getFont()), text, icon, l.getVerticalAlignment(),
                l.getHorizontalAlignment(), l.getVerticalTextPosition(), l.getHorizontalTextPosition(), view, iconAt, textAt,
                l.getIconTextGap());
        if (!hasText) return iconAt;
        return icon == null ? textAt : textAt.union(iconAt);
    }

    /** Where a panel of preferred size {@code pref} sits beside {@code bar}: above it, else below; inside the window. */
    static Rectangle panelBeside(Rectangle bar, Dimension pref, int areaW, int areaH) {
        int pw = Math.min(pref.width, areaW - 16);
        int ph = Math.min(pref.height, Math.max(80, areaH - bar.height - 30));
        int px = Math.max(8, Math.min(bar.x + bar.width - pw, areaW - pw - 8));
        int py = bar.y - ph - 6 >= 8 ? bar.y - ph - 6 : Math.min(bar.y + bar.height + 6, areaH - ph - 8);
        return new Rectangle(px, Math.max(8, py), pw, ph);
    }

    private static boolean free(Rectangle bar, Dimension panel, int areaW, int areaH, List<Rectangle> taken) {
        return free(bar, taken) && (panel == null || free(panelBeside(bar, panel, areaW, areaH), taken));
    }

    private static boolean free(Rectangle at, List<Rectangle> taken) {
        for (Rectangle r : taken) if (r.intersects(at)) return false;
        return true;
    }

    /**
     * The mark's box in layered-pane coordinates, or null when it is not showing. A box shows what the person sees: only
     * the part of its component (tab, cell, column) inside the scroll pane's view - a long table's rows scrolled out of
     * view are no part of it - and none for a cell scrolled out entirely.
     */
    Rectangle rectOf(Mark m) {
        Component c = m.component;
        if (c == null || !c.isShowing() || SwingUtilities.getRootPane(c) != root) return null;
        Rectangle r = new Rectangle(0, 0, c.getWidth(), c.getHeight());
        Rectangle seen = c instanceof JComponent ? ((JComponent) c).getVisibleRect() : r;
        if (m.tab != null && c instanceof JTabbedPane && m.tab < ((JTabbedPane) c).getTabCount()) {
            Rectangle t = ((JTabbedPane) c).getBoundsAt(m.tab);
            if (t != null) r = t;
        } else if (c instanceof JTable && m.column >= 0 && m.column < ((JTable) c).getColumnCount()) {
            JTable table = (JTable) c;
            if (m.row >= 0 && m.row < table.getRowCount()) {
                r = table.getCellRect(m.row, m.column, true);
            } else {
                Rectangle cell = table.getCellRect(0, m.column, true);
                Rectangle column = new Rectangle(cell.x, seen.y, cell.width, seen.height).intersection(seen);
                r = column.isEmpty() ? null : SwingUtilities.convertRectangle(table, column, layers);
                JTableHeader header = table.getTableHeader();
                if (header != null && header.isShowing() && SwingUtilities.getRootPane(header) == root) {
                    Rectangle head = header.getHeaderRect(m.column).intersection(header.getVisibleRect());
                    if (!head.isEmpty()) {
                        head = SwingUtilities.convertRectangle(header, head, layers);
                        r = r == null ? head : r.union(head);
                    }
                }
                return r;
            }
        }
        Rectangle shown = r.intersection(seen);
        return shown.isEmpty() ? null : SwingUtilities.convertRectangle(c, shown, layers);
    }

    /** Not in a screenshot: a capture leaves the palette out. */
    @Override
    public void paint(Graphics g) {
        if (!Screenshots.capturing()) super.paint(g);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        for (Mark m : marks) paint(g2, m);
        if (hover != null) paint(g2, hover);
        g2.dispose();
    }

    private void paint(Graphics2D g2, Mark m) {
        Rectangle r = rectOf(m);
        if (r == null) return;
        r.grow(2, 2);
        g2.setColor(new Color(m.color.getRed(), m.color.getGreen(), m.color.getBlue(), 28));
        g2.fillRect(r.x, r.y, r.width, r.height);
        g2.setColor(m.color);
        g2.setStroke(m.dashed
                ? new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {5f, 3f}, 0f)
                : new BasicStroke(2f));
        g2.drawRect(r.x, r.y, r.width, r.height);
        if (m.result != null) paintResult(g2, r, m.result);
        if (m.label == null) return;
        FontMetrics fm = g2.getFontMetrics();
        int w = fm.stringWidth(m.label) + 10;
        int h = fm.getHeight() + 2;
        int x = Math.max(0, Math.min(r.x, getWidth() - w));
        int y = r.y - h >= 0 ? r.y - h : r.y + r.height;
        g2.fillRoundRect(x, y, w, h, 6, 6);
        g2.setColor(Color.WHITE);
        g2.drawString(m.label, x + 5, y + 1 + fm.getAscent());
    }

    /** A check's outcome on the box's top right corner, as big as a badge: green check, red cross. */
    private void paintResult(Graphics2D g2, Rectangle r, boolean ok) {
        int d = 22;
        int x = Math.min(r.x + r.width - d / 2, getWidth() - d - 1);
        int y = Math.max(r.y - d / 2, 0);
        Graphics2D g = (Graphics2D) g2.create();
        g.setColor(ok ? YES : NO);
        g.fillOval(x, y, d, d);
        g.setColor(Color.WHITE);
        g.drawOval(x, y, d - 1, d - 1);
        g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D p = new Path2D.Float();
        if (ok) {
            p.moveTo(x + d * 0.28, y + d * 0.52);
            p.lineTo(x + d * 0.44, y + d * 0.68);
            p.lineTo(x + d * 0.73, y + d * 0.34);
        } else {
            p.moveTo(x + d * 0.32, y + d * 0.32);
            p.lineTo(x + d * 0.68, y + d * 0.68);
            p.moveTo(x + d * 0.68, y + d * 0.32);
            p.lineTo(x + d * 0.32, y + d * 0.68);
        }
        g.draw(p);
        g.dispose();
    }

    /** A round check mark on a box's top right corner: "yes, this one". */
    static final class Badge extends JComponent {
        private boolean over;

        Badge(Runnable onYes) {
            setToolTipText("Yes, this one");
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { onYes.run(); }
                @Override public void mouseEntered(MouseEvent e) { over = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { over = false; repaint(); }
            });
        }

        /** Not in a screenshot: a capture leaves the palette out. */
        @Override
        public void paint(Graphics g) {
            if (!Screenshots.capturing()) super.paint(g);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(over ? YES.darker() : YES);
            g2.fillOval(1, 1, getWidth() - 2, getHeight() - 2);
            g2.setColor(Color.WHITE);
            g2.drawOval(1, 1, getWidth() - 3, getHeight() - 3);
            g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D check = new Path2D.Float();
            check.moveTo(getWidth() * 0.28, getHeight() * 0.52);
            check.lineTo(getWidth() * 0.44, getHeight() * 0.68);
            check.lineTo(getWidth() * 0.73, getHeight() * 0.34);
            g2.draw(check);
            g2.dispose();
        }
    }

    /** Transparent; takes the mouse while the person picks or checks, so the click names a component instead of using it. */
    final class Catcher extends JComponent {
        Catcher(Consumer<MouseEvent> moved, Consumer<MouseEvent> clicked) {
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            MouseAdapter a = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) { moved.accept(e); }
                @Override public void mouseDragged(MouseEvent e) { moved.accept(e); }
                @Override public void mouseExited(MouseEvent e) { hover(null); }
                @Override public void mouseReleased(MouseEvent e) { clicked.accept(e); }
            };
            addMouseListener(a);
            addMouseMotionListener(a);
        }

        /** Not in a screenshot: a capture leaves the palette out. */
        @Override
        public void paint(Graphics g) {
            if (!Screenshots.capturing()) super.paint(g);
        }
    }
}
