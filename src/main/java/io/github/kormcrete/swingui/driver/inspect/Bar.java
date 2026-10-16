package io.github.kormcrete.swingui.driver.inspect;

import io.github.kormcrete.swingui.driver.widgets.Screenshots;

import javax.swing.JComponent;
import java.awt.Color;
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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The inspector's one control strip, inside the app window: a grip to drag it, a line of text (the agent's
 * question, or what was recorded last) and buttons. Painted by hand, so the app's look and feel cannot restyle it.
 */
final class Bar extends JComponent {

    static final class Button {
        String label;
        final Color color;
        final Runnable action;
        boolean active;
        Rectangle at = new Rectangle();

        Button(String label, Color color, Runnable action) {
            this.label = label;
            this.color = color;
            this.action = action;
        }
    }

    private static final int GRIP = 14;

    private String title;
    private String text;
    /** A dot before the title, or null: red and pulsing while recording. */
    private Color dot;
    private boolean pulse;
    private final javax.swing.Timer pulseTimer = new javax.swing.Timer(50, e -> repaint());
    private final List<Button> buttons = new ArrayList<Button>();
    private Button over;
    /**
     * Where it goes: the person's drag in the window it shows in, set by that window's overlay ({@link Overlay#bar}),
     * or their drag of it there; null: where it goes by itself.
     */
    Point moved;
    /** Hears the person's drag: the window it shows in keeps it for every bar shown there ({@link Overlay#bar}). */
    Consumer<Point> dragged;
    private Point grab;

    Bar(String title, String text) {
        this.title = title;
        this.text = text;
        setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        MouseAdapter a = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                grab = buttonAt(e.getPoint()) == null ? e.getPoint() : null;
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (grab == null) return;
                dragTo(new Point(getX() + e.getX() - grab.x, getY() + e.getY() - grab.y));
            }
            @Override public void mouseReleased(MouseEvent e) {
                Button b = buttonAt(e.getPoint());
                if (grab == null && b != null) b.action.run();
                grab = null;
            }
            @Override public void mouseMoved(MouseEvent e) {
                Button b = buttonAt(e.getPoint());
                if (b != over) { over = b; repaint(); }
                setCursor(Cursor.getPredefinedCursor(b == null ? Cursor.MOVE_CURSOR : Cursor.HAND_CURSOR));
            }
            @Override public void mouseExited(MouseEvent e) { over = null; repaint(); }
        };
        addMouseListener(a);
        addMouseMotionListener(a);
    }

    /** The person dragged it to {@code p} (layered-pane coordinates). */
    void dragTo(Point p) {
        moved = p;
        setLocation(p);
        if (dragged != null) dragged.accept(p);
    }

    /** A dot before the title; {@code pulse} makes it breathe (only while the bar is showing). */
    Bar dot(Color color, boolean pulse) {
        this.dot = color;
        this.pulse = pulse;
        if (pulse && isDisplayable()) pulseTimer.start(); else pulseTimer.stop();
        repaint();
        return this;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (pulse) pulseTimer.start();
    }

    @Override
    public void removeNotify() {
        pulseTimer.stop();
        super.removeNotify();
    }

    Bar button(String label, Color color, Runnable action) {
        buttons.add(new Button(label, color, action));
        return this;
    }

    /** Puts a button first, right after the line of text it answers (a note's Undo); one with that label goes first. */
    void buttonFirst(String label, Color color, Runnable action) {
        drop(label);
        buttons.add(0, new Button(label, color, action));
        revalidate();
        repaint();
    }

    /** Takes the button labelled {@code label} away, if there is one. */
    void drop(String label) {
        for (int i = 0; i < buttons.size(); i++) {
            if (buttons.get(i).label.equals(label)) {
                Button b = buttons.remove(i);
                if (over == b) over = null;
                revalidate();
                repaint();
                return;
            }
        }
    }

    void active(String label) {
        for (Button b : buttons) b.active = b.label.equals(label);
        repaint();
    }

    /** The button labelled {@code from}, if there is one, reads {@code to} now. */
    void relabel(String from, String to) {
        for (Button b : buttons) {
            if (b.label.equals(from)) {
                b.label = to;
                revalidate();
                repaint();
                return;
            }
        }
    }

    void text(String text) {
        this.text = text;
        revalidate();
        repaint();
    }

    /** Its title: what the palette is doing ("Recording", "Loaded", …). */
    String title() {
        return title;
    }

    /** The line of text it shows now. */
    String text() {
        return text;
    }

    /** Its buttons' labels, left to right. */
    List<String> labels() {
        List<String> out = new ArrayList<String>();
        for (Button b : buttons) out.add(b.label);
        return out;
    }

    /** Runs the button labelled {@code label}, as if the person pressed it. */
    void press(String label) {
        for (Button b : buttons) {
            if (b.label.equals(label)) {
                b.action.run();
                return;
            }
        }
        throw new IllegalArgumentException("no button '" + label + "' on the bar");
    }

    private Button buttonAt(Point p) {
        for (Button b : buttons) if (b.at.contains(p)) return b;
        return null;
    }

    /** Its width without the line of text: grip, dot, title and buttons. */
    int minimumWidth() {
        FontMetrics fm = getFontMetrics(getFont());
        FontMetrics bold = getFontMetrics(getFont().deriveFont(Font.BOLD));
        int w = GRIP + 10 + (dot == null ? 0 : 16) + (title == null ? 0 : bold.stringWidth(title) + 10) + 12;
        for (Button b : buttons) w += fm.stringWidth(b.label) + 22;
        return w;
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        return new Dimension(minimumWidth() + Math.min(fm.stringWidth(text), 420), fm.getHeight() + 16);
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
        g2.setColor(new Color(0, 0, 0, 60));
        g2.fillRoundRect(2, 3, getWidth() - 2, getHeight() - 2, 12, 12);
        g2.setColor(Overlay.NAVY);
        g2.fillRoundRect(0, 0, getWidth() - 2, getHeight() - 3, 12, 12);
        g2.setFont(getFont());
        FontMetrics fm = g2.getFontMetrics();
        int base = (getHeight() - 3 - fm.getHeight()) / 2 + fm.getAscent();
        g2.setColor(new Color(0x8A, 0x96, 0xAD));
        for (int i = 0; i < 3; i++) for (int j = 0; j < 2; j++) g2.fillOval(7 + j * 4, getHeight() / 2 - 7 + i * 5, 2, 2);
        int x = GRIP + 6;
        if (dot != null) {
            int d = 10;
            int cy = (getHeight() - 3) / 2;
            float a = pulse ? (float) (0.55 + 0.45 * Math.sin(System.currentTimeMillis() / 180.0)) : 1f;
            if (pulse) {
                // a soft ring that swells and fades with the beat
                int ring = d + 2 + Math.round(6 * (1 - a));
                g2.setColor(new Color(dot.getRed(), dot.getGreen(), dot.getBlue(), Math.round(90 * a)));
                g2.fillOval(x + d / 2 - ring / 2, cy - ring / 2, ring, ring);
            }
            g2.setColor(new Color(dot.getRed(), dot.getGreen(), dot.getBlue(), Math.round(255 * Math.max(0.6f, a))));
            g2.fillOval(x, cy - d / 2, d, d);
            x += d + 6;
        }
        if (title != null) {
            g2.setFont(getFont().deriveFont(Font.BOLD));
            g2.setColor(Color.WHITE);
            g2.drawString(title, x, base);
            x += g2.getFontMetrics().stringWidth(title) + 10;
            g2.setFont(getFont());
        }
        int buttonsWidth = 0;
        for (Button b : buttons) buttonsWidth += fm.stringWidth(b.label) + 22;
        int textRoom = getWidth() - x - buttonsWidth - 8;
        g2.setColor(new Color(0xD8, 0xDE, 0xEA));
        g2.drawString(clip(text, fm, textRoom), x, base);
        int bx = getWidth() - buttonsWidth - 4;
        for (Button b : buttons) {
            int bw = fm.stringWidth(b.label) + 16;
            b.at = new Rectangle(bx, 5, bw, getHeight() - 13);
            Color fill = b.active ? b.color : b == over ? b.color.darker() : new Color(0x3A, 0x47, 0x63);
            g2.setColor(fill);
            g2.fillRoundRect(b.at.x, b.at.y, b.at.width, b.at.height, 8, 8);
            if (b.active) {
                g2.setColor(Color.WHITE);
                g2.drawRoundRect(b.at.x, b.at.y, b.at.width - 1, b.at.height - 1, 8, 8);
            }
            g2.setColor(Color.WHITE);
            g2.drawString(b.label, bx + 8, base);
            bx += bw + 6;
        }
        g2.dispose();
    }

    private static String clip(String s, FontMetrics fm, int room) {
        if (s == null) return "";
        if (fm.stringWidth(s) <= room) return s;
        String dots = "…";
        int end = s.length();
        while (end > 0 && fm.stringWidth(s.substring(0, end) + dots) > room) end--;
        return s.substring(0, end) + dots;
    }
}
