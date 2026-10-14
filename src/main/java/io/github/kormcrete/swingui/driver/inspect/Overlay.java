package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.widgets.Screenshots;

import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JPopupMenu;
import javax.swing.JRootPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.JTableHeader;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

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
    /** The steps panel is out while a replay runs. */
    private boolean away;
    /** The palette and panel are out while a test run holds the agent. */
    private boolean hidden;
    /** The palette, panel and question are out until the agent's step ends: no spot in the window clears its target. */
    private boolean yielding;
    /**
     * What the agent acts on in its current step (layered-pane coordinates; a drag has two targets): the bar and its
     * panel stay off all of it until the person drags. Kept after the step, so the bar stays where it went; the agent's
     * next step starts a list of its own.
     */
    private final List<Rectangle> avoid = new ArrayList<Rectangle>();
    /** The agent's step that filled {@link #avoid} ended: the next {@link #makeRoom} starts afresh. */
    private boolean avoidDone;
    /** The bar's {@link Bar#moved} when {@link #avoid} was filled: a drag replaces it. */
    private Point movedAtAvoid;
    private Catcher catcher;
    /** Follows layout changes, scrolling and resizes while anything shows. */
    private final Timer follow = new Timer(120, e -> relayout());

    Overlay(JRootPane root) {
        this.root = root;
        this.layers = root.getLayeredPane();
        setOpaque(false);
        layers.add(this, BOXES);
        follow.start();
        relayout();
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
        repaint();
    }

    void bar(Bar b) {
        if (b == bar) return;
        if (bar != null) layers.remove(bar);
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
     * panel) and the question move to the first spot clear of it - and of what the agent acts on earlier in the same
     * step - and stay there until the person drags the bar. With no such spot they leave the window until the agent's
     * step ends ({@link #endYield}). True: nothing of the palette covers any of the step's areas any more.
     */
    boolean makeRoom(Rectangle area) {
        if (avoidDone) avoid.clear();
        avoidDone = false;
        avoid.add(new Rectangle(area));
        movedAtAvoid = bar == null ? null : bar.moved;
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
     * The agent's step ended: a palette that left the window for it comes back, and the agent's next step avoids only
     * what that step acts on (the palette keeps its spot until then).
     */
    void endYield() {
        avoidDone = true;
        if (!yielding) return;
        yielding = false;
        sync();
        relayout();
        layers.repaint();
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

    /** A panel beside the bar (the steps list); null removes it. */
    void panel(JComponent p) {
        if (panel != null) layers.remove(panel);
        panel = p;
        sync();
        relayout();
        layers.repaint();
    }

    /**
     * While a replay runs the steps panel leaves the window, so it never covers a step's target, and comes back after
     * (removed and re-added, never hidden). The bar stays: it is small, and it moves off any box.
     */
    void controlsAway(boolean on) {
        if (on == away) return;
        away = on;
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

    /** Whether the catcher is up here: the person picks or checks. */
    boolean catching() {
        return catcher != null;
    }

    void removeCatcher() {
        if (catcher != null) layers.remove(catcher);
        catcher = null;
        hover = null;
        layers.repaint();
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
        away = false;
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
        show(panel, panelIn());
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

    private boolean panelIn() {
        return barIn() && !away;
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
        taken.addAll(avoid);
        boolean withPanel = panel != null && panelIn();
        if (bar != null && barIn()) {
            place(bar, taken, withPanel ? panel.getPreferredSize() : null);
            taken.add(bar.getBounds());
        }
        if (ask != null && !yielding) place(ask, taken, null);
        if (withPanel && bar != null) {
            Rectangle want = panelBeside(bar.getBounds(), panel.getPreferredSize(), layers.getWidth(), layers.getHeight());
            if (!want.equals(panel.getBounds())) {
                panel.setBounds(want);
                panel.revalidate();
            }
        }
        repaint();
    }

    private void place(Bar b, List<Rectangle> taken, Dimension panel) {
        Dimension size = b.getPreferredSize();
        b.setBounds(placeBar(size.width, size.height, layers.getWidth(), layers.getHeight(), b.moved, taken, panel));
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
        int w = Math.min(prefW, Math.max(120, areaW - 16));
        int maxX = Math.max(0, areaW - w - 8);
        int maxY = Math.max(0, areaH - h - 8);
        if (moved != null) {
            Rectangle at = new Rectangle(Math.max(0, Math.min(moved.x, maxX)), Math.max(0, Math.min(moved.y, maxY)), w, h);
            if (free(at, panel, areaW, areaH, taken)) return at;
        }
        int[][] corners = {{maxX, maxY}, {8, maxY}, {maxX, 8}, {8, 8}, {maxX / 2, maxY}, {maxX / 2, 8}};
        for (int[] c : corners) {
            Rectangle at = new Rectangle(c[0], c[1], w, h);
            if (free(at, panel, areaW, areaH, taken)) return at;
        }
        return new Rectangle(maxX, maxY, w, h);
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

    /** The mark's box in layered-pane coordinates, or null when it is not showing. */
    Rectangle rectOf(Mark m) {
        Component c = m.component;
        if (c == null || !c.isShowing() || SwingUtilities.getRootPane(c) != root) return null;
        Rectangle r = new Rectangle(0, 0, c.getWidth(), c.getHeight());
        if (m.tab != null && c instanceof JTabbedPane && m.tab < ((JTabbedPane) c).getTabCount()) {
            Rectangle t = ((JTabbedPane) c).getBoundsAt(m.tab);
            if (t != null) r = t;
        } else if (c instanceof JTable && m.column >= 0 && m.column < ((JTable) c).getColumnCount()) {
            JTable table = (JTable) c;
            if (m.row >= 0 && m.row < table.getRowCount()) {
                r = table.getCellRect(m.row, m.column, true);
            } else {
                Rectangle cell = table.getCellRect(0, m.column, true);
                Rectangle seen = table.getVisibleRect();
                r = SwingUtilities.convertRectangle(table, new Rectangle(cell.x, seen.y, cell.width, seen.height), layers);
                JTableHeader header = table.getTableHeader();
                if (header != null && header.isShowing() && SwingUtilities.getRootPane(header) == root) {
                    r = r.union(SwingUtilities.convertRectangle(header, header.getHeaderRect(m.column), layers));
                }
                return r;
            }
        }
        return SwingUtilities.convertRectangle(c, r, layers);
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
